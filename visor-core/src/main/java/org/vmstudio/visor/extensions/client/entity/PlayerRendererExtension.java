package org.vmstudio.visor.extensions.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

public interface PlayerRendererExtension {

    /**
     * Runs LivingEntityRenderer#render on the player renderer, bypassing AvatarRenderer's own
     * dispatch. Our AvatarRenderer subclasses must use this instead of plain super.render(...);
     * see PlayerRenderMixins.PlayerRendererMixin for why.
     */
    void visor$renderVanilla(AvatarRenderState renderState, PoseStack poseStack,
                             MultiBufferSource buffer, int packedLight);

}
