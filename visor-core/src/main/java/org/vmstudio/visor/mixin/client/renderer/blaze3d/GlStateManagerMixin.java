package org.vmstudio.visor.mixin.client.renderer.blaze3d;

import com.mojang.blaze3d.opengl.GlStateManager;
import org.vmstudio.visor.core.client.render.helpers.ShaderTextureHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.opengl.GL11;

@Mixin(GlStateManager.class)
public class GlStateManagerMixin {

    //Change the limit of textures to 32
    @ModifyArg(at = @At(value = "INVOKE", target = "Ljava/util/stream/IntStream;range(II)Ljava/util/stream/IntStream;"), index = 1, method = "<clinit>")
    private static int visor$moreTextures(int i) {
        return 32;
    }

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

    @Inject(method = "_deleteTexture", at = @At("RETURN"), remap = false)
    private static void visor$forgetDeletedTexture(int texture, CallbackInfo ci) {
        ShaderTextureHelper.onTextureDeleted(texture);
    }

    @Inject(method = "_deleteTextures", at = @At("RETURN"), remap = false)
    private static void visor$forgetDeletedTextures(int[] textures, CallbackInfo ci) {
        for (int texture : textures) {
            ShaderTextureHelper.onTextureDeleted(texture);
        }
    }

    @Inject(method = "_genTexture", at = @At("RETURN"), remap = false)
    private static void visor$trackCreatedTexture(CallbackInfoReturnable<Integer> cir) {
        ShaderTextureHelper.onTextureCreated(cir.getReturnValue());
    }

    @Inject(method = "_genTextures", at = @At("RETURN"), remap = false)
    private static void visor$trackCreatedTextures(int[] textures, CallbackInfo ci) {
        for (int texture : textures) {
            ShaderTextureHelper.onTextureCreated(texture);
        }
    }
}
