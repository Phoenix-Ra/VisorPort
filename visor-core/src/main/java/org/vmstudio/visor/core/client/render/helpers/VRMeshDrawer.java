package org.vmstudio.visor.core.client.render.helpers;

import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * PORT-26.2: replaces {@code RenderType.draw(MeshData)}, which 26.2 removed.
 * <p>
 * Up to 26.1 a {@link RenderType} could draw a mesh by itself in one call: it wrote the dynamic
 * transforms from {@code RenderSystem}'s model-view, bound its textures and pipeline, uploaded the
 * mesh and issued the draw. 26.2 splits that in two. {@link RenderType#prepare()} does the state
 * half and hands back a {@code PreparedRenderType}; the geometry half goes through a
 * {@link StagedVertexBuffer}, which batches meshes into pooled GPU buffers and returns an
 * {@code ExecuteInfo} describing where a given draw landed.
 * <p>
 * The sequence below is vanilla's, taken from {@code GuiRenderer}: append a draw for the type's
 * format and topology, append the mesh into it, upload, then execute. Note that
 * {@code GuiRenderer} owns a {@code StagedVertexBuffer} of its own rather than sharing the one on
 * {@code RenderBuffers}, which is what this does too - Visor's draws then never interleave with
 * whatever vanilla has staged but not yet uploaded.
 * <p>
 * This uploads and executes per call, because that is the contract every call site was written
 * against: {@code type.draw(mesh)} drew <em>now</em>, into whatever target and pass state was
 * current. Batching several Visor meshes into one upload would be faster, but only if the draws
 * genuinely share a frame and a target - which across VR passes and eyes they mostly do not.
 * <p>
 * Like the vanilla call it replaces, this must not run while a render pass is open: it drives the
 * command encoder, and an open pass rejects encoder commands.
 */
public final class VRMeshDrawer {

    private static StagedVertexBuffer buffer;

    private VRMeshDrawer() {
    }

    /**
     * Draws one mesh with one render type, immediately.
     * <p>
     * Takes ownership of {@code mesh} and closes it, exactly as {@code RenderType.draw} did - the
     * call sites build their mesh inline with {@code builder.buildOrThrow()} and never close it
     * themselves, and the region has to go back to the shared scratch buffer.
     */
    public static void draw(RenderType type, MeshData mesh) {
        try (mesh) {
            StagedVertexBuffer staged = buffer();
            StagedVertexBuffer.Draw draw = staged.appendDraw(type.format(), type.primitiveTopology());
            draw.append(mesh);
            // NB: endDraw() is not "finish this draw" - it clears the draw list and drops the
            // GPU buffers, i.e. resets the batch. Before upload() that leaves nothing to upload
            // and getExecuteInfo throws "Cannot execute before upload". endFrame() calls it.
            staged.upload();
            type.prepare().drawFromBuffer(staged.getExecuteInfo(draw));
            staged.endFrame();
        }
    }

    private static StagedVertexBuffer buffer() {
        if (buffer == null) {
            buffer = new StagedVertexBuffer(() -> "Visor mesh drawer", 4096);
        }
        return buffer;
    }
}
