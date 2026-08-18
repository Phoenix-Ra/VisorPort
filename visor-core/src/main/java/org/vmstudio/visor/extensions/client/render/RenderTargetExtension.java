package org.vmstudio.visor.extensions.client.render;


public interface RenderTargetExtension {



    void visor$setTextureId(int texid);

    void visor$setUseStencil(boolean useStencil);

    boolean visor$isUsingStencil();

    void visor$isLinearFilter(boolean linearFilter);

    /**
     * GL name of the colour attachment.
     * <p>
     * 1.21.9 dropped {@code RenderTarget#colorTextureId} in favour of a {@code GpuTexture};
     * the VR path still needs the raw handle to hand textures to OpenXR and to blit between
     * framebuffers, so it is unwrapped here.
     *
     * @return the GL texture name, or -1 when the backend is not OpenGL
     */
    int visor$getColorTextureId();

}
