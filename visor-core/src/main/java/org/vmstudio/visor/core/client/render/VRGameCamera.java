package org.vmstudio.visor.core.client.render;


import org.vmstudio.visor.api.common.player.VRPose;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.client.render.VRRenderPass;
import org.vmstudio.visor.api.common.utils.VRMathUtils;
import org.vmstudio.visor.core.client.player.VRClientPlayers;
import org.vmstudio.visor.core.client.render.helpers.RenderPoseHelper;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import org.vmstudio.visor.core.client.ClientContext;


public class VRGameCamera extends Camera {

    // 1.21.11 narrowed Camera.setup's first parameter from BlockGetter back to Level
    @Override
    public void setup(@NotNull Level level,
                      @NotNull Entity entity,
                      boolean thirdPerson,
                      boolean thirdPersonReverse,
                      float partialTicks) {
        if (VRRenderState.getPhase().isVanilla()) {
            super.setup(level, entity, thirdPerson, thirdPersonReverse, partialTicks);
            if (VRRenderState.isSpectatedVRView(entity)) {
                setupSpectatedVR(entity);
            }
        } else {
            setupVR(level, entity);
        }
    }


    /**
     * PORT-1.21.11: the environment attribute probe has to be ticked in VR too.
     * <p>
     * 1.21.4's {@code Camera.tick()} was nothing but the eye-height interpolation, which VR has
     * no use for - the head height comes from the HMD - so skipping it outside the vanilla phase
     * was free. 1.21.11 added {@code attributeProbe.tick(level, position)} to the same method,
     * and {@code SkyRenderer.extractRenderState} now reads <em>every</em> sky value through
     * {@code camera.attributeProbe()}: sun angle, moon angle, star angle, sky colour, sunrise
     * colour. An unticked probe hands back defaults for all of them, which is a black sky.
     * <p>
     * The phase is still VR_MIRROR from the end of the previous frame when
     * {@code GameRenderer.tick()} runs, so the vanilla branch never covered this.
     * <p>
     * Only the probe is replayed - the eye-height interpolation stays skipped, as before.
     */
    @Override
    public void tick() {
        if (VRRenderState.getPhase().isVanilla()) {
            super.tick();
            return;
        }
        if (this.entity != null && this.level != null) {
            this.attributeProbe().tick(this.level, this.position());
        }
    }


    @Override
    public boolean isDetached() {
        if (VRRenderState.getPhase().isVanilla()) {
            return super.isDetached();
        }
        return VRRenderState.isSelfModelRenderCamera();
    }



    private void setupVR(Level level, Entity entity) {
        this.initialized = true;
        this.level = level;
        this.entity = entity;

        VRRenderPass renderPass = VRRenderState.getRenderPass();
        VRPose cameraElement = ClientContext.localPlayer
                .getPoseData(PlayerPoseType.RENDER)
                .getCameraPose(renderPass);

        // Position
        // 1.21.11's Vec3 takes a Vector3fc, so the old downcast to Vector3f is gone
        this.setPosition(new Vec3(
                RenderPoseHelper.getCameraPosition(
                        renderPass,
                        ClientContext.localPlayer.getPoseData(PlayerPoseType.RENDER)
                )
        ));

        // Orientation
        this.xRot = -cameraElement.getPitchDegrees();
        this.yRot =  cameraElement.getYawDegrees();

        // Look, Up, Left vectors
        // (VRMathUtils.LEFT_VECTOR is already -X, matching Camera's 1.21.1
        // LEFT basis — unlike the legacy +X constant older builds used)
        var dir = cameraElement.getDirection();
        var upVec = cameraElement.getCustomVector(VRMathUtils.UP_VECTOR);
        var leftVec = cameraElement.getCustomVector(VRMathUtils.LEFT_VECTOR);

        writeBasis(this.forwardVector(), dir.x(), dir.y(), dir.z());
        writeBasis(this.upVector(), upVec.x, upVec.y, upVec.z);
        writeBasis(this.leftVector(), leftVec.x, leftVec.y, leftVec.z);

        // 1.21.1 builds the world view matrix directly from rotation()
        // (and changed the camera basis/Euler convention), so copy the
        // exact tracked orientation instead of rebuilding yaw+pitch —
        // this also preserves headset roll.
        cameraElement.getRotation()
                .getNormalizedRotation(this.rotation());
    }

    private void setupSpectatedVR(Entity entity) {
        var vrPlayer = VRClientPlayers.getPlayer(entity.getUUID());
        if (vrPlayer == null) {
            return;
        }
        VRPose hmd = vrPlayer.getPoseData(PlayerPoseType.RENDER).getHmd();

        this.setPosition(new Vec3(hmd.getPosition()));

        // Orientation
        this.xRot = -hmd.getPitchDegrees();
        this.yRot =  hmd.getYawDegrees();

        var dir = hmd.getDirection();
        var upVec = hmd.getCustomVector(VRMathUtils.UP_VECTOR);
        var leftVec = hmd.getCustomVector(VRMathUtils.LEFT_VECTOR);

        writeBasis(this.forwardVector(), dir.x(), dir.y(), dir.z());
        writeBasis(this.upVector(), upVec.x, upVec.y, upVec.z);
        writeBasis(this.leftVector(), leftVec.x, leftVec.y, leftVec.z);

        hmd.getRotation()
                .getNormalizedRotation(this.rotation());
    }

    // PORT-1.21.11: getLookVector()/getUpVector()/getLeftVector() became
    // forwardVector()/upVector()/leftVector() and now hand out a read-only Vector3fc while the
    // backing fields stayed private. The getters still return the live Vector3f instances, so
    // writing through the cast is the same in-place basis update the 1.21.4 code did.
    private static void writeBasis(Vector3fc basis, float x, float y, float z) {
        ((Vector3f) basis).set(x, y, z);
    }

}
