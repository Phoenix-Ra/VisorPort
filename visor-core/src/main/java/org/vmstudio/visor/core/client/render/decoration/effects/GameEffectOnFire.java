package org.vmstudio.visor.core.client.render.decoration.effects;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import org.vmstudio.visor.api.client.player.pose.VRPlayerPoseClient;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.client.render.VRRenderPass;
import org.vmstudio.visor.api.client.render.decoration.VRDecorator;
import org.vmstudio.visor.api.client.render.decoration.annotations.RegisterVRGameEffect;
import org.vmstudio.visor.api.client.render.decoration.effects.VRGameEffect;
import org.vmstudio.visor.api.common.addon.VisorAddon;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.render.helpers.VRMeshDrawer;
import org.vmstudio.visor.core.client.render.helpers.VRTesselator;
import org.vmstudio.visor.extensions.client.render.GameRendererExtension;
import org.vmstudio.visor.core.client.render.helpers.RenderPoseHelper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.vmstudio.visor.core.client.render.VisorPipelines;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;


@RegisterVRGameEffect
public class GameEffectOnFire extends VRGameEffect {

    public static final String ID = "on_fire";

    private static final float  FIRE_HALF_WIDTH  = 0.3f;
    private static final float  FIRE_ALPHA       = 0.9f;


    public GameEffectOnFire(@NotNull VisorAddon owner) {
        super(owner);
    }

    @Override
    public void render(@NotNull VRRenderPass renderPass,
                       @NotNull PoseStack stack,
                       float partialTicks) {
        // --- Prepare variables ---
        VRPlayerPoseClient renderPose = ClientContext.localPlayer.getPoseData(PlayerPoseType.RENDER);
        float fireHeight = (float)(renderPose.getHeadPivot().y()
                - ((GameRendererExtension)MC.gameRenderer)
                .visor$getCameraEntityCache()
                .getY());

        // Material no longer resolves its own sprite; the atlas manager is the lookup now.
        TextureAtlasSprite sprite = MC.getAtlasManager().get(ModelBakery.FIRE_1);
        Identifier atlas = sprite.atlasLocation();
        // PORT-1.21.11: TextureAtlasSprite#uvShrinkRatio is gone. The sprite now folds its atlas
        // padding straight into u0/u1/v0/v1, so vanilla's own fire quads sample the raw sprite
        // bounds; keeping the old inset-toward-the-middle lerp would crop the flame twice.
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();

        // In third person the fire is part of the scene and tests depth; in first person it is
        // a face overlay that has to draw over everything. GL_ALWAYS has no equivalent on a
        // pipeline, so the first-person variant drops the depth test entirely - which also drops
        // its depth write, harmless for something nothing else is meant to occlude.
        RenderType type = renderPass == VRRenderPass.THIRD_PERSON
                ? VisorPipelines.positionTexColor(atlas)
                : VisorPipelines.positionTexColorNoDepth(atlas);

        // --- Pose setup ---
        stack.pushPose();
        stack.setIdentity();
        RenderPoseHelper.applyCameraPose(renderPass, stack);

        // --- Render ---
        // One buffer for all four faces: they only differ by the matrix baked into the vertices,
        // so splitting them would open four render passes for no reason.
        BufferBuilder buf = VRTesselator.begin(PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int i = 0; i < 4; i++) {
            stack.pushPose();
            // spin quad around player
            stack.mulPose(Axis.YP.rotation(
                    i * (float)Math.PI/2 - renderPose.getBodyYaw()
            ));
            stack.translate(0, -fireHeight, 0);

            Matrix4f mat = stack.last().pose();
            buf.addVertex(mat, -FIRE_HALF_WIDTH,0, -FIRE_HALF_WIDTH)
                    .setUv(u1, v1).setColor(1,1,1,FIRE_ALPHA);
            buf.addVertex(mat,  FIRE_HALF_WIDTH,0, -FIRE_HALF_WIDTH)
                    .setUv(u0, v1).setColor(1,1,1,FIRE_ALPHA);
            buf.addVertex(mat,  FIRE_HALF_WIDTH, fireHeight,  -FIRE_HALF_WIDTH)
                    .setUv(u0, v0).setColor(1,1,1,FIRE_ALPHA);
            buf.addVertex(mat, -FIRE_HALF_WIDTH, fireHeight,  -FIRE_HALF_WIDTH)
                    .setUv(u1, v0).setColor(1,1,1,FIRE_ALPHA);

            stack.popPose();
        }
        VRMeshDrawer.draw(type, buf.buildOrThrow());

        // --- Restore pose ---
        stack.popPose();
    }

    @Override
    public boolean isVisible(@NotNull VRDecorator currentDecorator) {
        return ((GameRendererExtension) MC.gameRenderer).visor$isOnFire();
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }
}
