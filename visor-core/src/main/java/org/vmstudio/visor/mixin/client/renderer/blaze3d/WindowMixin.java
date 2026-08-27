package org.vmstudio.visor.mixin.client.renderer.blaze3d;

import com.mojang.blaze3d.platform.Window;
import org.vmstudio.visor.api.client.gui.overlays.framework.VROverlayScreen;
import org.vmstudio.visor.core.client.VisorState;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.extensions.client.WindowExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.vmstudio.visor.core.client.ClientContext;
import static org.vmstudio.visor.core.client.VisorClientImpl.MC;

@Mixin(Window.class)
public abstract class WindowMixin implements WindowExtension {

    @Shadow
    private int framebufferWidth;

    @Shadow
    private int framebufferHeight;


    /* ********************************** *\
  //--------REPLACING VANILLA VALUES--------\\
    \* ********************************** */

    /**
     * The overlay whose texture is being replayed right now, if any.
     * <p>
     * PORT-1.21.11: the GUI replay derives its ortho projection and gui scale from this Window
     * ({@code GuiRenderer.draw} reads {@code getWidth()/getGuiScale()}), so while an overlay
     * with its own resolution is being textured, the Window has to speak that overlay's
     * resolution or the replay lands on the main GUI's grid - squashed for every overlay whose
     * size differs from it. Guarded on the scale factor: an overlay that has not computed its
     * scale yet falls back to the shared GUI values rather than dividing by zero.
     */
    @Unique
    private static VROverlayScreen visor$texturingOverlay() {
        var overlayManager = ClientContext.overlayManager;
        if (overlayManager == null) {
            return null;
        }
        VROverlayScreen overlay = overlayManager.getTexturingOverlay();
        return overlay != null && overlay.getGuiScaleFactor() > 0 ? overlay : null;
    }

    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    void visor$vrWidth(CallbackInfoReturnable<Integer> cir) {
        if(VisorState.get().isActive()) {
            VROverlayScreen overlay = visor$texturingOverlay();
            if (overlay != null) {
                cir.setReturnValue(overlay.getRequestedWidth());
                return;
            }
            var phase = VRRenderState.getPhase();
            if (phase.isVanilla() || phase.isVRGui()) {
                cir.setReturnValue(
                        ClientContext.guiManager.getGuiWidth()
                );
            } else {
                cir.setReturnValue(
                        MC.gameRenderer.mainRenderTarget.width
                );
            }
        }
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    void visor$vrHeight(CallbackInfoReturnable<Integer> cir) {
        if(VisorState.get().isActive()) {
            VROverlayScreen overlay = visor$texturingOverlay();
            if (overlay != null) {
                cir.setReturnValue(overlay.getRequestedHeight());
                return;
            }
            var phase = VRRenderState.getPhase();
            if (phase.isVanilla() || phase.isVRGui()) {
                cir.setReturnValue(
                        ClientContext.guiManager.getGuiHeight()
                );
            } else {
                cir.setReturnValue(
                        MC.gameRenderer.mainRenderTarget.height
                );
            }
        }
    }


    @Inject(method = "getScreenWidth", at = @At("HEAD"), cancellable = true)
    void visor$vrScreenWidth(CallbackInfoReturnable<Integer> cir) {
        if (VisorState.get().isActive()) {
            cir.setReturnValue(
                    ClientContext
                            .guiManager
                            .getGuiWidth()
            );
        }
    }

    @Inject(method = "getScreenHeight", at = @At("HEAD"), cancellable = true)
    void visor$vrScreenHeight(CallbackInfoReturnable<Integer> cir) {
        if (VisorState.get().isActive()) {
            cir.setReturnValue(
                    ClientContext
                            .guiManager
                            .getGuiHeight()
            );
        }
    }


    @Inject(method = "getGuiScaledWidth", at = @At("HEAD"), cancellable = true)
    void visor$vrGuiScaledWidth(CallbackInfoReturnable<Integer> cir) {
        if (VisorState.get().isActive()) {
            VROverlayScreen overlay = visor$texturingOverlay();
            if (overlay != null) {
                cir.setReturnValue(overlay.getRequestedWidthScaled());
                return;
            }
            cir.setReturnValue(
                    ClientContext
                            .guiManager
                            .getGuiScaledWidth()
            );
        }
    }

    @Inject(method = "getGuiScaledHeight", at = @At("HEAD"), cancellable = true)
    void visor$vrGuiScaledHeight(CallbackInfoReturnable<Integer> cir) {
        if (VisorState.get().isActive()) {
            VROverlayScreen overlay = visor$texturingOverlay();
            if (overlay != null) {
                cir.setReturnValue(overlay.getRequestedHeightScaled());
                return;
            }
            cir.setReturnValue(
                    ClientContext
                            .guiManager
                            .getGuiScaledHeight()
            );
        }
    }


    /**
     * PORT-1.21.11: {@code getGuiScale} returns {@code int} now, not {@code double}. The generic
     * on {@code CallbackInfoReturnable} is erased by the time Mixin derives the handler
     * descriptor, so neither javac nor the apply-time descriptor check sees a stale one - the
     * mismatch only surfaces when the injector's generated {@code cir.getReturnValueI()} casts
     * the boxed {@code Double} this used to store and throws {@code ClassCastException}.
     */
    @Inject(method = "getGuiScale", at = @At("HEAD"), cancellable = true)
    void visor$vrScaleFactor(CallbackInfoReturnable<Integer> cir) {
        if (VisorState.get().isActive()) {
            VROverlayScreen overlay = visor$texturingOverlay();
            if (overlay != null) {
                cir.setReturnValue(overlay.getGuiScaleFactor());
                return;
            }
            cir.setReturnValue(
                    ClientContext
                            .guiManager
                            .getScaleFactor()
            );
        }
    }


    /* ************** *\
  //--------MISC--------\\
    \* ************** */
    @Inject(method = "onResize", at = @At("HEAD"))
    private void visor$onResize(long l, int i, int j, CallbackInfo ci) {
        if (VisorState.get().isActive()) {
            ClientContext.renderer.prepareResize(
                    "Main Window Resized"
            );
        }
    }

    /**
     * PORT-26.2: everything size-related runs off the framebuffer callback now - it is what
     * reconfigures the swapchain, and a DPI change can fire it without any window resize. The
     * mirror target has to follow it, or the present crops/offsets (26.2's blit is
     * crop-and-anchor against the swapchain, which is sized from the raw framebuffer size).
     */
    @Inject(method = "onFramebufferResize", at = @At("HEAD"))
    private void visor$onFramebufferResize(long l, int i, int j, CallbackInfo ci) {
        if (VisorState.get().isActive()) {
            ClientContext.renderer.prepareResize(
                    "Main Framebuffer Resized"
            );
        }
    }

    // PORT-26.2: Window.updateVsync is gone - vsync is a swapchain present mode now, chosen in
    // Minecraft.renderFrame from options.enableVsync(). "No vsync in VR" moved to MinecraftMixin,
    // which suppresses that flag where the present mode is picked.


    /* ************************ *\
  //--------PUBLIC METHODS--------\\
    \* ************************ */
    // PORT-26.2: these size the mirror/desktop-facing targets, and the desktop present is a
    // crop-and-anchor blit against a swapchain configured from the raw framebuffer size - so
    // "actual" means framebuffer pixels, not the screen-coordinate width/height (they differ on
    // any scaled display).
    @Override
    @Unique
    public int visor$getActualScreenHeight() {
        return framebufferHeight;
    }

    @Override
    @Unique
    public int visor$getActualScreenWidth() {
        return framebufferWidth;
    }
}
