package org.vmstudio.visor.mixin.client.renderer.blaze3d;

import net.minecraft.client.FramerateLimiter;
import org.vmstudio.visor.core.client.VisorState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PORT-1.21.11: two of the three injections here targeted methods that no longer exist.
 * {@code RenderSystem.defaultBlendFunc} was removed with the rest of the immediate-mode blend API
 * (blend now belongs to the pipeline; the GUI alpha rewrite moved to {@code GlStateManagerMixin},
 * which pipelines still route through), and {@code setShaderTexture} went with the global texture
 * slots, taking the stale-id guard with it.
 */
@Mixin(FramerateLimiter.class)
/**
 * PORT-26.1: RenderSystem.limitDisplayFPS(int) moved to net.minecraft.client.FramerateLimiter.
 */
public class RenderSystemMixin {

    @Inject(at = @At("HEAD"), method = "limitDisplayFPS",
            cancellable = true)
    private static void visor$noFPSlimit(CallbackInfo ci) {
        if (VisorState.get().isActive()) {
            ci.cancel();
        }
    }
}
