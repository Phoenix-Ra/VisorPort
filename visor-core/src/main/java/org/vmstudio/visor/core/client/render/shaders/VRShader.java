package org.vmstudio.visor.core.client.render.shaders;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.jetbrains.annotations.NotNull;

/**
 * A Visor-owned GPU program.
 * <p>
 * Before 1.21.9 this wrapped a {@code CompiledShaderProgram} that had to be linked through
 * {@code ShaderManager} after every resource reload. Pipelines are static data instead: they
 * are declared once as constants and compiled lazily by the device the first time a
 * {@code RenderPass} binds them, and a resource reload only clears the device pipeline cache,
 * so nothing here needs re-linking.
 * <p>
 * What <em>is</em> owned per-instance are GPU buffers (uniform ring buffers). Those are
 * allocated in {@link #init()} and released in {@link #close()} - an obligation the linked-program
 * model never had, and one that matters because
 * {@link org.vmstudio.visor.core.client.render.VRShaders#setup()} runs on every target reinit.
 */
public interface VRShader extends AutoCloseable {

    /** The pipeline this shader draws with. Constant, and valid before {@link #init()}. */
    @NotNull
    RenderPipeline getPipeline();

    /** Allocates GPU-side resources. Called from {@code VRShaders.setup()}. */
    default void init() throws Exception {
    }

    /** Releases everything {@link #init()} allocated. Must be safe to call twice. */
    @Override
    default void close() {
    }

    /**
     * Rotates any ring buffer this shader owns. Must run exactly once per frame - skipping it
     * means mapping a buffer the GPU is still reading, which stalls the CPU.
     */
    default void endFrame() {
    }
}
