package org.vmstudio.visor.core.client.render.helpers;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.world.level.dimension.DimensionType;
import org.lwjgl.system.MemoryStack;
import org.vmstudio.visor.api.client.player.pose.VRPlayerPoseClient;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.client.render.VRRenderPass;
import org.vmstudio.visor.core.client.player.pose.LocalPlayerPose;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.api.client.settings.VRClientSettings;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import org.vmstudio.visor.core.client.ClientContext;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;

public class RenderPoseHelper {

    private RenderPoseHelper() {
        throw new UnsupportedOperationException("This is an utility class and cannot be instantiated");
    }



    public static void applyCameraPose(VRRenderPass renderPass,
                                       PoseStack poseStack){
        applyCameraOrientation(renderPass, poseStack);
        applyCameraTranslation(renderPass, poseStack);
    }

    public static void applyCameraOrientation(VRRenderPass renderPass,
                                              PoseStack poseStack) {
        Matrix4f rotationMatrix = getViewRotation(renderPass);

        // apply to both blockPos & normal
        poseStack.last().pose().mul(rotationMatrix);
        poseStack.last().normal().mul(new Matrix3f(rotationMatrix));
    }


    public static Matrix4f getViewRotation(VRRenderPass renderPass) {
        float mirrorSmooth = VRClientSettings.getMirrorSmooth();

        boolean smooth = renderPass == VRRenderPass.CENTER && mirrorSmooth > 0f;
        if (smooth) {
            // average rotation over history
            return new Matrix4f()
                    .rotation(
                            ClientContext.rawPoseHandler
                                    .getHmdData()
                                    .getRotationHistory()
                                    .averageRotation(mirrorSmooth)
                    );
        }
        // direct VR eye/head rotation
        return ClientContext.localPlayer.getPoseData(PlayerPoseType.RENDER)
                .getCameraPose(renderPass)
                .getRotation()
                .transpose(new Matrix4f());
    }

   private static final Vector3fc LEVEL_LIGHT_0 = new Vector3f(0.2f, 1.0f, -0.7f).normalize();
    private static final Vector3fc LEVEL_LIGHT_1 = new Vector3f(-0.2f, 1.0f, 0.7f).normalize();
    private static final Vector3fc NETHER_LEVEL_LIGHT_1 = new Vector3f(-0.2f, -1.0f, 0.7f).normalize();


    /**
     * The uniform block {@link #setupEyeSpaceLevelLights} writes its two directions into.
     * <p>
     * PORT-1.21.11: {@code RenderSystem.setShaderLights(Vector3f, Vector3f)} is gone - the two
     * diffuse directions are a std140 uniform block now and {@code setShaderLights} takes a slice
     * of one. Vanilla's {@link Lighting} keeps one block per {@link Lighting.Entry} and only
     * rewrites it when the dimension changes, so Visor cannot borrow the LEVEL entry: its
     * directions change per eye and per decoration stage, and stomping LEVEL would leak the eye
     * rotation into everything vanilla draws afterwards. Hence a buffer of Visor's own.
     * <p>
     * Allocated once and rewritten in place - this runs several times per eye per frame, where a
     * per-call GPU allocation is a stall.
     */
    private static GpuBuffer eyeSpaceLights;

    public static void setupEyeSpaceLevelLights(VRRenderPass renderPass) {
        // PORT-1.21.11: ClientLevel.effects() is gone along with DimensionSpecialEffects; the
        // "one constant ambient direction" flag is DimensionType.cardinalLightType() == NETHER now.
        boolean constantAmbient = MC.level != null
                && MC.level.dimensionType().cardinalLightType()
                == DimensionType.CardinalLightType.NETHER;
        Vector3fc light1 = constantAmbient ? NETHER_LEVEL_LIGHT_1 : LEVEL_LIGHT_1;

        Matrix4f view = getViewRotation(renderPass);
        RenderSystem.setShaderLights(
                writeEyeSpaceLights(
                        view.transformDirection(LEVEL_LIGHT_0, new Vector3f()),
                        view.transformDirection(light1, new Vector3f())
                )
        );
    }

    private static GpuBufferSlice writeEyeSpaceLights(Vector3fc light0, Vector3fc light1) {
        if (eyeSpaceLights == null) {
            eyeSpaceLights = RenderSystem.getDevice().createBuffer(
                    () -> "visor eye-space level lights",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    Lighting.UBO_SIZE
            );
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(
                    eyeSpaceLights.slice(),
                    Std140Builder.onStack(stack, Lighting.UBO_SIZE)
                            .putVec3(light0)
                            .putVec3(light1)
                            .get()
            );
        }
        return eyeSpaceLights.slice();
    }

    /**
     * Releases {@link #eyeSpaceLights}; the next setup call rebuilds it. Called from
     * {@code VRRendererBase.destroy()}, which covers both a target reinit and VR shutdown -
     * without that the buffer would outlive the device it was allocated on.
     */
    public static void close() {
        if (eyeSpaceLights != null) {
            eyeSpaceLights.close();
            eyeSpaceLights = null;
        }
    }


    public static void restoreLevelLights() {
        // PORT-1.21.11: Lighting.setupLevel()/setupNetherLevel() are gone. The nether/overworld
        // split moved into the instance method Lighting.updateLevel(CardinalLightType), which
        // vanilla runs when the dimension changes - so the LEVEL entry already holds the correct
        // pair and restoring is just pointing setShaderLights back at it.
        MC.gameRenderer.getLighting().setupFor(Lighting.Entry.LEVEL);
    }

    public static void applyCameraTranslation(VRRenderPass renderPass,
                                              PoseStack poseStack) {
        if (!renderPass.isEye()) {
            return;
        }
        LocalPlayerPose renderPose = ClientContext.localPlayer.getPoseData(PlayerPoseType.RENDER);
        var eyePos = renderPose.getCameraPose(renderPass).getPosition();
        var hmdOrigin = renderPose.getHmd().getPosition();
        var offset = eyePos.sub(hmdOrigin, new Vector3f());

        poseStack.translate(-offset.x, -offset.y, -offset.z);
    }



    public static void applyHandPose(HandType hand,
                                     PoseStack poseStack) {
        LocalPlayerPose renderPose = ClientContext.localPlayer.getPoseData(PlayerPoseType.RENDER);
        Vector3fc cameraPos = getCameraPosition(VRRenderState.getRenderPass(), renderPose);
        applyHandPose(renderPose, hand, cameraPos, poseStack);
    }

    public static void applyHandPose(VRPlayerPoseClient renderPose,
                                     HandType hand,
                                     Vector3fc referencePos,
                                     PoseStack poseStack) {
        var handPose = renderPose.getBody().getHand(hand).getPose();
        // move origin to hand position relative to the reference origin
        var handPos = handPose.getPosition();

        var relative = handPos.sub(referencePos, new Vector3f());
        poseStack.translate(relative.x, relative.y, relative.z);

        // apply hand’s inverse rotation
        Matrix4f invRot = handPose
                .getRotation()
                .invert(new Matrix4f())
                .transpose(new Matrix4f());
        poseStack.last().pose().mul(invRot);

        // scale to world scale
        float s = renderPose.getWorldScale();
        poseStack.scale(s, s, s);
    }

    public static Vector3fc getCameraPosition(VRRenderPass renderPass,
                                              VRPlayerPoseClient vrPose) {
        float mirrorSmooth = VRClientSettings.getMirrorSmooth();

        boolean smooth = renderPass == VRRenderPass.CENTER && mirrorSmooth > 0f;
        if (smooth) {
            var avg = ClientContext.rawPoseHandler
                    .getHmdData()
                    .getPositionHistory()
                    .averagePosition(mirrorSmooth);

            return avg
                    .mul(vrPose.getWorldScale())
                    .rotateY(vrPose.getRotationY())
                    .add(vrPose.getOrigin());
        }

        return vrPose.getCameraPose(renderPass).getPosition();
    }




    public static Vector3fc getHandPosition(HandType hand) {
        return ClientContext
                .localPlayer
                .getPoseData(PlayerPoseType.RENDER)
                .getHand(hand)
                .getPosition();
    }





}
