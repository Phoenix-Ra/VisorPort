package org.vmstudio.visor.mixin.client.renderer;

import org.vmstudio.visor.core.client.render.VRRenderState;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Light updates are a once-per-frame job.
 *
 * PORT-26.1: vanilla moved {@code pollLightUpdates}/{@code runLightUpdates} out of
 * {@code LevelRenderer#renderLevel} (which Visor runs once per VR pass) into
 * {@code ClientLevel#update}, called exactly once per frame from {@code Minecraft#renderFrame}
 * - i.e. during Visor's VR_GUI phase, before any world pass. The old rule "only on the
 * worldUpdater pass" therefore cancelled the single vanilla call on every frame: queued chunk
 * light data (and with it {@code LevelRenderer#onChunkReadyToRender}) was never applied, so no
 * section ever became visible and the world rendered empty.
 *
 * The gate now only de-duplicates calls made from within a secondary VR world pass (mods that
 * still drive the light queue from the render path); the frame-level vanilla call is untouched.
 * {@code LevelLightEngine#runLightUpdates} is deliberately not hooked any more: the 1.21.11 hook
 * was a no-op, and the method is shared with the integrated server's light engine, which runs it
 * on worker threads where the client render phase means nothing.
 *
 * Nothing in here may reference the enclosing class at runtime: it sits in the mixin package,
 * which Mixin refuses to load directly.
 */
public class VRLightsMixins {

    @Mixin(ClientLevel.class)
    public static class ClientLevelMixin {

        @Inject(at = @At("HEAD"), method = "pollLightUpdates", cancellable = true)
        public void visor$noUpdateOncePerFrame(CallbackInfo info){
            if (VRRenderState.isSecondaryWorldPass()) {
                info.cancel();
            }
        }
    }

}
