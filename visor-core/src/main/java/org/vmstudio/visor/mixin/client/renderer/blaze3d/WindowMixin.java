package org.vmstudio.visor.mixin.client.renderer.blaze3d;

import com.mojang.blaze3d.platform.Window;
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
    private int width;

    @Shadow
    private int height;


    /* ********************************** *\
  //--------REPLACING VANILLA VALUES--------\\
    \* ********************************** */

    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    void visor$vrWidth(CallbackInfoReturnable<Integer> cir) {
        if(VisorState.get().isActive()) {
            var phase = VRRenderState.getPhase();
            if (phase.isVanilla() || phase.isVRGui()) {
                cir.setReturnValue(
                        ClientContext.guiManager.getGuiWidth()
                );
            } else {
                cir.setReturnValue(
                        MC.mainRenderTarget.width
                );
            }
        }
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    void visor$vrHeight(CallbackInfoReturnable<Integer> cir) {
        if(VisorState.get().isActive()) {
            var phase = VRRenderState.getPhase();
            if (phase.isVanilla() || phase.isVRGui()) {
                cir.setReturnValue(
                        ClientContext.guiManager.getGuiHeight()
                );
            } else {
                cir.setReturnValue(
                        MC.mainRenderTarget.height
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
     * No Vsync in VR
     * @param v s
     * @return s
     */
    @ModifyVariable(method = "updateVsync", ordinal = 0, at = @At("HEAD"), argsOnly = true)
    boolean visor$noVsync(boolean v) {
        if (VisorState.get().isActive()) {
            return false;
        }
        return v;
    }


    /* ************************ *\
  //--------PUBLIC METHODS--------\\
    \* ************************ */
    @Override
    @Unique
    public int visor$getActualScreenHeight() {
        return height;
    }

    @Override
    @Unique
    public int visor$getActualScreenWidth() {
        return width;
    }
}
