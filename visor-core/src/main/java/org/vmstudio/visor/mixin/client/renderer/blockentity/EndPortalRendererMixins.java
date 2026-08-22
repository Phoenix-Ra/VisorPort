package org.vmstudio.visor.mixin.client.renderer.blockentity;

import net.minecraft.client.renderer.blockentity.TheEndGatewayRenderer;
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.core.client.render.VRShaders;

/**
 * PORT-26.1: AbstractEndPortalRenderer.renderType() (overridden by TheEndGatewayRenderer) is
 * gone; each submit() now asks RenderTypes.endPortal() / RenderTypes.endGateway() inline, so the
 * VR render types are swapped in at those call sites instead.
 */
public class EndPortalRendererMixins {

    @Mixin(TheEndGatewayRenderer.class)
    public static class EndGateway {
        @Redirect(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/EndGatewayRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
                at = @At(value = "INVOKE",
                target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;endGateway()Lnet/minecraft/client/renderer/rendertype/RenderType;"))
        private RenderType visor$overrideShader() {
            if (VRRenderState.getPhase().isNotVanilla()) {
                return VRShaders.getEndPortal().getGatewayRenderType();
            }
            return RenderTypes.endGateway();
        }
    }

    @Mixin(TheEndPortalRenderer.class)
    public static class EndPortal {
        @Redirect(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/EndPortalRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
                at = @At(value = "INVOKE",
                target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;endPortal()Lnet/minecraft/client/renderer/rendertype/RenderType;"))
        private RenderType visor$overrideShader() {
            if (VRRenderState.getPhase().isNotVanilla()) {
                return VRShaders.getEndPortal().getPortalRenderType();
            }
            return RenderTypes.endPortal();
        }
    }
}
