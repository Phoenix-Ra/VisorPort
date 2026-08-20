package org.vmstudio.visor.extensions.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

public interface PlayerRendererExtension {

    /**
     * Runs LivingEntityRenderer#submit on the player renderer, bypassing AvatarRenderer's own
     * dispatch. Our AvatarRenderer subclasses must use this instead of plain super.submit(...);
     * see PlayerRenderMixins.PlayerRendererMixin for why.
     * <p>
     * PORT-1.21.11: was {@code render(state, poseStack, MultiBufferSource, int packedLight)}.
     * Entity rendering is submit-then-dispatch now, and the packed light travels on the render
     * state as {@code lightCoords} rather than as an argument.
     */
    void visor$renderVanilla(AvatarRenderState renderState, PoseStack poseStack,
                             SubmitNodeCollector collector, CameraRenderState cameraState);

}
