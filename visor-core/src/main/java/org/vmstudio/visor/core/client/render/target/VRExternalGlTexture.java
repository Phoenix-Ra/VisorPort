package org.vmstudio.visor.core.client.render.target;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;

/**
 * A {@link GlTexture} wrapped around a GL texture Visor does not own.
 * <p>
 * 1.21.9 replaced {@code RenderTarget}'s raw {@code colorTextureId} with a {@code GpuTexture},
 * so the only way to make Minecraft render straight into an OpenXR swapchain image - or into a
 * depth buffer allocated with a stencil channel, which {@link GpuFormat} cannot express - is
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
    public VRExternalGlTexture(int usage, String label, GpuFormat format,
                               int width, int height, int depth, int mipLevels,
                               int glId, boolean ownsGlTexture) {
        // PORT-26.2: GlTexture takes the device's FrameBufferCache now - it is where the
        // per-attachment FBOs live since GlTexture.getFbo went away.
        super(usage, label, format, width, height, depth, mipLevels, glId,
                ((GlDevice) RenderSystem.getDevice().backend).frameBufferCache());
        this.ownsGlTexture = ownsGlTexture;
    }

    @Override
    public void close() {
        if (this.ownsGlTexture) {
            super.close();
            return;
        }
        // The texture belongs to the XR runtime; releasing it here would pull the swapchain
        // image out from under the compositor. `closed` deliberately stays false too -
        // removeViews() would otherwise reach destroyImmediately() and glDeleteTexture the
        // runtime's image. But the FBOs this texture accumulated in the device-wide
        // FrameBufferCache are Visor's to free: left behind, they leak, and a recycled GL
        // texture name would be handed a stale FBO whose attachment is long gone.
        // destroyFbo unregisters the key from its attachments, hence the drain-by-last loop
        // (same pattern as GlTexture.destroyImmediately).
        while (!this.fboKeys.isEmpty()) {
            this.frameBufferCache.destroyFbo(this.fboKeys.getLast());
        }
    }
}
