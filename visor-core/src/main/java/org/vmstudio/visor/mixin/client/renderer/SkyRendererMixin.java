package org.vmstudio.visor.mixin.client.renderer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;

/**
 * PORT-26.2: {@code SkyRenderer} captures the render target it draws into.
 * <p>
 * 26.1's constructor was {@code SkyRenderer(TextureManager, AtlasManager)} - it owned no target and
 * every sky draw landed on whatever was bound, which is what made Visor's per-pass target swap work
 * without the renderer knowing about it. 26.2 added a third constructor parameter and a
 * {@code private final RenderTarget renderTarget} field, and each of the six public sky draws feeds
 * {@code renderTarget.getColorTextureView()}/{@code getDepthTextureView()} straight into
 * {@code createRenderPass}. The renderer is built once, so that field pins the desktop framebuffer
 * for the rest of the run: the sky is drawn into the vanilla target with the eye's projection while
 * the eye target keeps whatever was underneath, which is the giant skewed sky wedge over the world.
 * <p>
 * Rebinding it in front of every draw restores the 26.1 behaviour. The read is unconditional on
 * purpose - outside a VR pass {@code GameRenderer.mainRenderTarget} is the vanilla target anyway
 * (see {@code VRRenderState.startVanillaPhase}), and going through the live field is also what
 * picks up a target that was rebuilt by a renderer reinit.
 * <p>
 * Only three classes in 26.2 hold a {@code RenderTarget} field. Visor already tracks the other two:
 * {@code GameRenderer.mainRenderTarget} is the one it swaps, and
 * {@code LevelRenderer.entityOutlineTarget} is handled in {@code LevelRendererMixin}.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {

    /**
     * The vanilla sky disc is {@code SKY_DISC_RADIUS = 512}, sized for the symmetric window
     * projection. An eye projection is asymmetric and much wider, so the rim of the disc comes
     * inside the frustum and shows up as a flat band of clear colour along the horizon. The disc is
     * pushed out past the VR far plane instead; nothing else about the sky changes.
     */
    @Unique
    private static final float VISOR_SKY_DISC_RADIUS = 16384.0F;

    @Mutable
    @Shadow
    @Final
    private RenderTarget renderTarget;

    /**
     * Point the captured field at the target this pass is actually drawing into. Null only happens
     * while the renderer is mid-reinit, and one frame of stale sky beats writing a null the next
     * draw would dereference.
     */
    @Unique
    private void visor$bindActiveRenderTarget() {
        RenderTarget live = MC.gameRenderer.mainRenderTarget;
        if (live != null) {
            this.renderTarget = live;
        }
    }

    @ModifyConstant(method = "buildSkyDisc", constant = @Constant(floatValue = 512.0F))
    private float visor$expandSkyDisc(float vanillaRadius) {
        return VISOR_SKY_DISC_RADIUS;
    }

    @Inject(method = "renderSkyDisc", at = @At("HEAD"))
    private void visor$bindSkyDiscTarget(int color, CallbackInfo ci) {
        visor$bindActiveRenderTarget();
    }

    @Inject(method = "renderDarkDisc", at = @At("HEAD"))
    private void visor$bindDarkDiscTarget(CallbackInfo ci) {
        visor$bindActiveRenderTarget();
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"))
    private void visor$bindCelestialTarget(PoseStack poseStack, float sunAngle,
                                           float sunAlpha, float moonAlpha,
                                           MoonPhase moonPhase, float starAlpha,
                                           float rainLevel, CallbackInfo ci) {
        visor$bindActiveRenderTarget();
    }

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"))
    private void visor$bindSunriseTarget(PoseStack poseStack, float sunAngle,
                                         int color, CallbackInfo ci) {
        visor$bindActiveRenderTarget();
    }

    @Inject(method = "renderEndSky", at = @At("HEAD"))
    private void visor$bindEndSkyTarget(CallbackInfo ci) {
        visor$bindActiveRenderTarget();
    }

    @Inject(method = "renderEndFlash", at = @At("HEAD"))
    private void visor$bindEndFlashTarget(PoseStack poseStack, float alpha,
                                          float pitch, float yaw, CallbackInfo ci) {
        visor$bindActiveRenderTarget();
    }
}
