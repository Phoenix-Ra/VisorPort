package org.vmstudio.visor.mixin.client.renderer;

import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vmstudio.visor.core.client.render.VRRenderState;

/**
 * PORT-1.21.11: the CloudInfo UBO is one ring segment rewritten in place on every
 * {@code render} call. Nothing rotates it - vanilla's {@code LevelRenderer.endFrame()} is dead
 * code, no caller - and a write map goes through {@code GL_MAP_UNSYNCHRONIZED_BIT}, so the CPU
 * write is not ordered against pending draws that read the segment. Vanilla survives because it
 * writes once per frame with values that barely move between frames. Visor renders clouds once
 * per pass: the second eye's write races the first eye's still-pending cloud draw, and the first
 * eye intermittently reads the second eye's cell offsets. The offsets are relative to a
 * 12-block cloud cell, so while both eyes share a cell the error is the ~6cm eye distance -
 * invisible - but at head poses where the eyes straddle a cell boundary it is a whole cell:
 * clouds visibly jitter in whichever eye renders first.
 * <p>
 * Rotating the ring at the head of each VR render gives every pass its own segment, and
 * correctness follows from the ring's own contract: {@code rotate()} fences the outgoing
 * segment, {@code currentBuffer()} awaits that fence before the segment is written again.
 * Three segments and two-to-four rotations per frame keeps every rewrite behind a signaled
 * fence. The vanilla phase is left alone - single writer, vanilla-identical behaviour.
 */
@Mixin(CloudRenderer.class)
public class CloudRendererMixin {

    @Shadow
    @Final
    private MappableRingBuffer ubo;

    @Inject(method = "render", at = @At("HEAD"))
    private void visor$perPassCloudInfo(CallbackInfo ci) {
        if (VRRenderState.getPhase().isNotVanilla()) {
            this.ubo.rotate();
        }
    }
}
