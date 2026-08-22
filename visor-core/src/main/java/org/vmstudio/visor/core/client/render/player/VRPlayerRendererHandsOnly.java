package org.vmstudio.visor.core.client.render.player;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import me.phoenixra.atumvr.api.enums.ControllerType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.VisorState;
import org.vmstudio.visor.core.client.player.VRClientPlayers;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.core.client.render.player.model.CenteredArmsPlayerMesh;
import org.vmstudio.visor.core.client.render.player.model.simple.VRPlayerModelSimple;
import org.vmstudio.visor.core.client.utils.ScaleHelper;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.vmstudio.visor.extensions.client.entity.EntityRenderStateExtension;
import org.vmstudio.visor.extensions.client.entity.PlayerRendererExtension;

public class VRPlayerRendererHandsOnly extends AvatarRenderer<AbstractClientPlayer> {
    private static LayerDefinition VR_LAYER_DEFAULT;
    private static LayerDefinition VR_LAYER_SLIM;
    static {
        createLayers();
    }

    public static void createLayers() {
        VR_LAYER_DEFAULT = LayerDefinition.create(
                CenteredArmsPlayerMesh.create(CubeDeformation.NONE, false), 64, 64);
        VR_LAYER_SLIM = LayerDefinition.create(
                CenteredArmsPlayerMesh.create(CubeDeformation.NONE, true), 64, 64);
    }


    public VRPlayerRendererHandsOnly(EntityRendererProvider.Context context, boolean slim) {
        super(context, slim);
        this.model = new VRPlayerModelSimple(
                slim ? VR_LAYER_SLIM.bakeRoot()
                        : VR_LAYER_DEFAULT.bakeRoot(),
                slim
        );
    }


    @Override
    public void extractRenderState(AbstractClientPlayer player,
                                   AvatarRenderState renderState,
                                   float partialTick) {
        super.extractRenderState(player, renderState, partialTick);

        EntityRenderStateExtension ext = (EntityRenderStateExtension) renderState;
        ext.visor$setVRPlayer(VRClientPlayers.getPlayer(player.getUUID()));
        ext.visor$setSelfModelRender(VRRenderState.isSelfModelRender(player));
        ext.visor$setSelfModelPlayer(VRRenderState.isSelfModelPlayer(player));
        ext.visor$setSelfModelHandsRender(VRRenderState.isSelfModelHandsRender(player));
    }

    @Override
    public void submit(AvatarRenderState renderState, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState cameraState)
    {

        poseStack.pushPose();

        var vrPlayer = ((EntityRenderStateExtension) renderState).visor$getVRPlayer();

        if (vrPlayer != null) {
            var pose = vrPlayer.getPoseData(PlayerPoseType.RENDER);
            AbstractClientPlayer player = (AbstractClientPlayer) vrPlayer.getMcPlayer();
            float partialTick = ClientContext.visor != null
                    ? ClientContext.visor.getPartialTicks()
                    : 1.0F;

            float scale = vrPlayer.getFullHeightScale();
            if ((VisorState.get().isActive()
                    && player == Minecraft.getInstance().player))
            {
                scale *= pose.getWorldScale() / ScaleHelper.getEntityEyeHeightScale(player, partialTick);
            }

            if (renderState.isAutoSpinAttack && !VRRenderState.getPhase().isVRGui()) {
                float offset = renderState.xRot / 90F * 0.2F;
                poseStack.translate(0, pose.getHmd().getPosition().y() + offset, 0);
            }

            poseStack.scale(scale, scale, scale);
        }

        // Not super.submit(...): on Forge/NeoForge that binds to a synthetic bridge in
        // AvatarRenderer and recurses back into this method. See PlayerRenderMixins.
        ((PlayerRendererExtension) this).visor$renderVanilla(renderState, poseStack, collector, cameraState);

        poseStack.popPose();

        if (vrPlayer != null && VRRenderState.isSpectatedVRView(vrPlayer.getMcPlayer())) {
           ClientContext.handRenderer.renderSpectatedHands(
                    this, renderState, (AbstractClientPlayer) vrPlayer.getMcPlayer(), vrPlayer, poseStack,
                    collector, renderState.lightCoords,
                    ClientContext.visor != null ? ClientContext.visor.getPartialTicks() : 1.0F);
        }
    }


    @Override
    protected void setupRotations(
            AvatarRenderState renderState, PoseStack poseStack, float bodyRot, float scale)
    {
        if (VRRenderState.getPhase().isVRGui()) {
            if (renderState.isFallFlying || renderState.isVisuallySwimming || renderState.isAutoSpinAttack) {
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - bodyRot));
                return;
            }
            super.setupRotations(renderState, poseStack, bodyRot, scale);
            return;
        }

        var vrPlayer = ((EntityRenderStateExtension) renderState).visor$getVRPlayer();
        if (vrPlayer != null) {
            bodyRot = vrPlayer.getPoseData(PlayerPoseType.RENDER).getBodyYaw() * Mth.RAD_TO_DEG;
        }

        // vanilla below here
        super.setupRotations(renderState, poseStack, bodyRot, scale);
    }




    @Override
    public void renderRightHand(
            PoseStack poseStack, SubmitNodeCollector collector, int combinedLight, Identifier skin,
            boolean isSleeveVisible)
    {
        this.renderHand(ControllerType.RIGHT, poseStack, collector, combinedLight, skin, isSleeveVisible,
                this.model.rightArm, this.model.rightSleeve);
    }

    @Override
    public void renderLeftHand(
            PoseStack poseStack, SubmitNodeCollector collector, int combinedLight, Identifier skin,
            boolean isSleeveVisible)
    {
        this.renderHand(ControllerType.LEFT, poseStack, collector, combinedLight, skin, isSleeveVisible,
                this.model.leftArm, this.model.leftSleeve);
    }


    private void renderHand(
            ControllerType side, PoseStack poseStack, SubmitNodeCollector collector, int combinedLight,
            Identifier playerSkin, boolean isSleeveVisible,
            ModelPart rendererArm, ModelPart rendererArmwear)
    {
        // PORT-1.21.11: the blend/cull sandwich is gone - entityTranslucent's pipeline carries
        // that state, and a GlStateManager poke here would just be overwritten when the pass binds.
        boolean slim = this.getModel().slim;
        boolean left = side == ControllerType.LEFT;
        rendererArm.setPos(CenteredArmsPlayerMesh.armPivotX(slim, left),
                CenteredArmsPlayerMesh.armPivotY(slim), 0F);
        rendererArm.setRotation(0F, 0F, 0F);
        rendererArm.xScale = rendererArm.yScale = rendererArm.zScale = 1F;
        rendererArm.visible = true;


        rendererArmwear.resetPose();
        rendererArmwear.visible = isSleeveVisible;

        // render hand (and, as its child, the sleeve)
        collector.submitModelPart(rendererArm, poseStack,
                RenderTypes.entityTranslucent(playerSkin), combinedLight,
                OverlayTexture.NO_OVERLAY, null);
    }
}