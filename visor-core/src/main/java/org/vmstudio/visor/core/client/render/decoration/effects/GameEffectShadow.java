package org.vmstudio.visor.core.client.render.decoration.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import me.phoenixra.atumvr.api.misc.color.AtumColorImmutable;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.client.render.VRRenderPass;
import org.vmstudio.visor.api.client.render.decoration.VRDecorator;
import org.vmstudio.visor.api.client.render.decoration.annotations.RegisterVRGameEffect;
import org.vmstudio.visor.api.client.render.decoration.effects.VRGameEffect;
import org.vmstudio.visor.api.common.addon.VisorAddon;
import org.vmstudio.visor.api.common.utils.VRMathUtils;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.extensions.client.entity.LocalPlayerExtension;
import org.vmstudio.visor.extensions.client.render.GameRendererExtension;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.core.client.render.helpers.RenderHelper;
import org.vmstudio.visor.core.client.render.VisorPipelines;
import org.vmstudio.visor.core.client.render.helpers.RenderPoseHelper;
import org.vmstudio.visor.api.client.gui.helpers.TexturesHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;


@RegisterVRGameEffect
public class GameEffectShadow extends VRGameEffect {
    public static final String ID = "shadow";

    private static final AtumColorImmutable SHADOW_COLOR = new AtumColorImmutable(
            0,0,0,
            64
    );

    public GameEffectShadow(@NotNull VisorAddon owner) {
        super(owner);
    }

    @Override
    public void render(@NotNull VRRenderPass renderPass,
                       @NotNull PoseStack poseStack,
                       float partialTicks) {


        // --- Prepare variables ---
        AABB box = MC.player.getBoundingBox();
        float playerWidth  = (float) box.getXsize();
        float playerLength = (float) box.getZsize();

        Vec3 camPos = new Vec3((Vector3f) RenderPoseHelper.getCameraPosition(renderPass,
                ClientContext.localPlayer.getPoseData(PlayerPoseType.RENDER))
        );
        Vec3 worldPlayerPos = ((GameRendererExtension) MC.gameRenderer)
                .visor$getCameraEntityCache()
                .getInterpolatedPos(partialTicks);
        Vec3 shadowPos = worldPlayerPos
                .subtract(camPos)
                .add(0, 0.005, 0);

        // --- Pose setup ---
        poseStack.pushPose();

        poseStack.setIdentity();
        RenderPoseHelper.applyCameraOrientation(renderPass, poseStack);
        poseStack.translate(shadowPos.x, shadowPos.y, shadowPos.z);


        // --- Render ---
        // GL_ALWAYS with a depth write is not expressible on a pipeline. The shadow is a fake
        // drawn flat on the ground and never meant to occlude anything, so it gives up the write
        // rather than the "always draws" half.
        RenderHelper.renderFlatQuad(
                VisorPipelines.POSITION_COLOR_NORMAL_NO_DEPTH_TYPE,
                poseStack.last().pose(),
                VRMathUtils.ZERO_VECTOR,
                playerWidth,
                playerLength,
                0f,
                SHADOW_COLOR
        );

        poseStack.popPose();
    }



    @Override
    public boolean isVisible(@NotNull VRDecorator currentDecorator) {
        if(VRRenderState.getRenderPass() == VRRenderPass.THIRD_PERSON){
            return false;
        }
        if (!MC.player.isAlive()) {
            return false;
        }
        if (MC.player.getVehicle() != null) {
            return false;
        }
        if ((((LocalPlayerExtension) MC.player).visor$getRoomYOffset() < 0.0D)) {
            return false;
        }

        return true;
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }
}
