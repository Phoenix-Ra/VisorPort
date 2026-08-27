package org.vmstudio.visor.mixin.client.renderer.blaze3d;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.vmstudio.visor.compatibility.ShadersHelper;
import org.vmstudio.visor.core.client.render.helpers.VREffectsHelper;
import org.vmstudio.visor.extensions.client.render.RenderTargetExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.GpuDevice;
import org.vmstudio.visor.core.client.render.target.VRExternalGlTexture;
import java.util.function.Supplier;


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
            target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"))
    private GpuTexture visor$createTexture(GpuDevice device, Supplier<String> label, int usage,
                                           GpuFormat format, int width, int height,
                                           int depth, int mipLevels) {

        // colour attachment backed by a texture we were handed (an OpenXR swapchain image)
        if (format == GpuFormat.RGBA8_UNORM && this.visor$textureId != -1) {
            return new VRExternalGlTexture(usage, label.get(), format,
                    width, height, depth, mipLevels, this.visor$textureId, false);
        }

        // PORT-26.2: GpuFormat can express packed depth+stencil now (D32_FLOAT_S8_UINT), so the
        // hand-rolled GL allocation - which reported D32_FLOAT while being DEPTH24_STENCIL8,
        // dropped to 24-bit fixed depth under a reverse-Z renderer built for float depth, and
        // skipped GlDevice.createTexture's parameter setup - is gone. Honoured only while the
        // stencil is actually drawn: with it off the depth stays vanilla's exact D32_FLOAT, which
        // keeps copyDepthFrom (Fabulous framegraph) format-compatible.
        if (format == GpuFormat.D32_FLOAT && this.visor$useStencil
                && VREffectsHelper.STENCIL_SUPPORTED) {
            return device.createTexture(label, usage, GpuFormat.D32_FLOAT_S8_UINT,
                    width, height, depth, mipLevels);
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
