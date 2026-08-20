package org.vmstudio.visor.mixin.client.renderer.blaze3d;

import com.mojang.blaze3d.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.lwjgl.opengl.GL11;

/**
 * PORT-1.21.11: this used to do three things, and two of them no longer have anything to hook.
 * <p>
 * The texture-slot limit was raised from 12 to 32 by rewriting the {@code IntStream.range} in the
 * class initialiser, for the benefit of {@code RenderSystem}'s global {@code shaderTextures} array.
 * That array is gone - textures are owned by {@code GpuTexture} and bound per render pass - so the
 * limit is meaningless and the tracking of created and deleted texture names that fed
 * {@code ShaderTextureHelper} has no reader left. Both were removed along with the helper.
 * <p>
 * The blend rewrite below stays: pipelines apply their blend function through this same method, so
 * it still sees every blend the frame sets.
 */
@Mixin(GlStateManager.class)
public class GlStateManagerMixin {

    // dstAlpha first, because that is the variable we are changing
    @ModifyVariable(method = "_blendFuncSeparate", at = @At("HEAD"), remap = false, index = 3, argsOnly = true)
    private static int visor$guiAlphaBlending(int dstAlpha, int srcRgb, int dstRgb, int srcAlpha) {
        if (srcRgb == GL11.GL_SRC_ALPHA &&
                dstRgb == GL11.GL_ONE_MINUS_SRC_ALPHA &&
                srcAlpha == GL11.GL_ONE &&
                dstAlpha == GL11.GL_ZERO)
        {
            return GL11.GL_ONE_MINUS_SRC_ALPHA;
        } else {
            return dstAlpha;
        }
    }
}
