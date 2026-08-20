package org.vmstudio.visor.core.client.render.target;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.TextureFormat;

/**
 * A {@link GlTexture} wrapped around a GL texture Visor does not own.
 * <p>
 * 1.21.9 replaced {@code RenderTarget}'s raw {@code colorTextureId} with a {@code GpuTexture},
 * so the only way to make Minecraft render straight into an OpenXR swapchain image - or into a
 * depth buffer allocated with a stencil channel, which {@link TextureFormat} cannot express - is
 * to hand blaze3d a texture object that already points at our GL name.
 * <p>
 * The runtime owns swapchain images, so {@link #close()} deliberately does not delete them.
 */
public class VRExternalGlTexture extends GlTexture {

    private final boolean ownsGlTexture;

    /**
     * @param ownsGlTexture whether closing this texture should free the underlying GL object;
     *                      false for OpenXR swapchain images, true for buffers Visor allocated
     */
    public VRExternalGlTexture(int usage, String label, TextureFormat format,
                               int width, int height, int depth, int mipLevels,
                               int glId, boolean ownsGlTexture) {
        super(usage, label, format, width, height, depth, mipLevels, glId);
        this.ownsGlTexture = ownsGlTexture;
    }

    @Override
    public void close() {
        if (this.ownsGlTexture) {
            super.close();
        }
        // otherwise the texture belongs to the XR runtime; releasing it here would pull the
        // swapchain image out from under the compositor
    }
}
