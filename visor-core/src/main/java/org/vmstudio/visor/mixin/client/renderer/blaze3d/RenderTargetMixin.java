package org.vmstudio.visor.mixin.client.renderer.blaze3d;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.vmstudio.visor.compatibility.ShadersHelper;
import org.vmstudio.visor.extensions.client.render.RenderTargetExtension;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.TextureFormat;
import org.vmstudio.visor.core.client.render.target.VRExternalGlTexture;
import java.util.function.Supplier;
import com.mojang.blaze3d.opengl.GlStateManager;


@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin implements RenderTargetExtension {
    @Shadow
    public int width;
    @Shadow
    public int height;
    @Shadow
    protected GpuTexture colorTexture;


    @Unique
    private int visor$textureId = -1;
    @Unique
    private boolean visor$useLinearFilter;
    @Unique
    private boolean visor$useStencil = false;

    /* ************************* *\
  //--------TEXTURE CREATION--------\\
    \* ************************* */

    /**
     * 1.21.9 rewrote {@code createBuffers} to allocate through {@code GpuDevice.createTexture}
     * with a hardcoded RGBA8/DEPTH32 format and no raw GL, which retired the three separate
     * injections Visor used to use (stencil format, external texture id, filter constant).
     * All three now hang off this one redirect.
     */
    @Redirect(method = "createBuffers", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"))
    private GpuTexture visor$createTexture(GpuDevice device, Supplier<String> label, int usage,
                                           TextureFormat format, int width, int height,
                                           int depth, int mipLevels) {

        // colour attachment backed by a texture we were handed (an OpenXR swapchain image)
        if (format == TextureFormat.RGBA8 && this.visor$textureId != -1) {
            return new VRExternalGlTexture(usage, label.get(), format,
                    width, height, depth, mipLevels, this.visor$textureId, false);
        }

        // TextureFormat has no combined depth+stencil, so allocate one ourselves and wrap it
        if (format == TextureFormat.DEPTH32 && this.visor$useStencil) {
            int glId = GlStateManager._genTexture();
            GlStateManager._bindTexture(glId);
            GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8,
                    width, height, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, null);
            return new VRExternalGlTexture(usage, label.get(), format,
                    width, height, depth, mipLevels, glId, true);
        }

        return device.createTexture(label, usage, format, width, height, depth, mipLevels);
    }

    @Override
    public String toString() {
        return "\n" +
                "Size:   " + this.width + " x " + this.height + "\n" +
                "Tex ID: " + this.visor$getColorTextureId() + "\n";
    }


    /* ************************ *\
  //--------PUBLIC METHODS--------\\
    \* ************************ */

    @Override
    @Unique
    public void visor$setUseStencil(boolean useStencil) {
        this.visor$useStencil = useStencil;
    }

    @Override
    @Unique
    public boolean visor$isUsingStencil() {
        return visor$useStencil;
    }

    @Override
    @Unique
    public void visor$setTextureId(int texid) {
        this.visor$textureId = texid;
    }

    @Override
    @Unique
    public void visor$isLinearFilter(boolean linearFilter) {
        this.visor$useLinearFilter = linearFilter;
    }

    @Override
    @Unique
    public int visor$getColorTextureId() {
        return this.colorTexture instanceof GlTexture glTexture ? glTexture.glId() : -1;
    }


}
