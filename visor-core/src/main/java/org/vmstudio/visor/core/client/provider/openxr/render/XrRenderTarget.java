package org.vmstudio.visor.core.client.provider.openxr.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import org.vmstudio.visor.extensions.client.render.RenderTargetExtension;

/**
 * Render target backed by an OpenXR swapchain image.
 * <p>
 * Before 1.21.9 this hand-rolled its own GL framebuffer and attached the runtime's texture to
 * it, because {@code RenderTarget} exposed {@code frameBufferId}/{@code colorTextureId}. Those
 * are gone, and the framebuffer is now managed inside blaze3d, so instead the swapchain texture
 * is handed to {@link RenderTargetExtension#visor$setTextureId(int)} and Visor's
 * {@code RenderTargetMixin} substitutes it when the colour attachment gets allocated.
 */
public class XrRenderTarget extends RenderTarget {

    public XrRenderTarget(int width, int height, int colorId, int index) {
        super("Visor XR Eye " + index, false, GpuFormat.RGBA8_UNORM);
        RenderSystem.assertOnRenderThread();

        // must be set before resize(), which is what triggers createBuffers()
        ((RenderTargetExtension) this).visor$setTextureId(colorId);

        this.resize(width, height);
    }
}
