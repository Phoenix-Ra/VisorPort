package org.vmstudio.visor.mixin.client.renderer.blockentity;

import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.core.client.render.VRShaders;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.blockentity.TheEndGatewayRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public class EndPortalRendererMixins {


    /**
     * Gateways get their own 16-layer type; this override wins over the base one below.
     */
    @Mixin(TheEndGatewayRenderer.class)
    public static class EndGateway {

        @Inject(method = "renderType", at = @At("HEAD"), cancellable = true)
        private void visor$overrideShader(CallbackInfoReturnable<RenderType> cir) {
            if (VRRenderState.getPhase().isNotVanilla()) {
                cir.setReturnValue(VRShaders.getEndPortal().getGatewayRenderType());
            }
        }
    }


    /**
     * PORT-1.21.11: this used to target TheEndPortalRenderer, which does not declare
     * renderType() - it inherits it - so the injection never resolved. The method lives on
     * AbstractEndPortalRenderer, which is where it belongs.
     * <p>
     * Portals and gateways also used to share one render type; they now get the 15- and
     * 16-layer variants vanilla distinguishes between.
     */
    @Mixin(AbstractEndPortalRenderer.class)
    public static class EndPortal {

        @Inject(method = "renderType", at = @At("HEAD"), cancellable = true)
        private void visor$overrideShader(CallbackInfoReturnable<RenderType> cir) {
            if (VRRenderState.getPhase().isNotVanilla()) {
                cir.setReturnValue(VRShaders.getEndPortal().getPortalRenderType());
            }
        }
    }
}
