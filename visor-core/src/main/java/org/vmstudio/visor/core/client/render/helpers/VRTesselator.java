package org.vmstudio.visor.core.client.render.helpers;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * PORT-26.2: {@code com.mojang.blaze3d.vertex.Tesselator} is gone from 26.2 - the class that owned
 * the client's one shared scratch buffer and handed out {@link BufferBuilder}s for immediate-mode
 * meshes. Vanilla replaced every one of its own uses with the submit/extract pipeline and kept no
 * shared buffer at all: in 26.2 the only {@code ByteBufferBuilder} field left in the game is
 * {@code StagedVertexBuffer}'s private staging buffer.
 * <p>
 * Visor still builds small meshes by hand and draws them straight through its own pipelines, so
 * this restores exactly what Tesselator did, with the same numbers: one lazily created
 * {@link ByteBufferBuilder} of 786432 bytes (Tesselator's {@code MAX_BYTES}), and a {@code begin}
 * that is {@code new BufferBuilder(sharedBuffer, topology, format)} - which is verbatim what
 * {@code Tesselator.begin} compiled to in 26.1.2.
 * <p>
 * The one signature change is forced: {@code VertexFormat.Mode} was replaced by
 * {@link PrimitiveTopology}, which lives in {@code com.mojang.blaze3d} rather than
 * {@code com.mojang.blaze3d.vertex} (so a {@code blaze3d.vertex.*} wildcard import does not cover
 * it). The constants Visor uses - QUADS and TRIANGLES - carry over by name.
 * <p>
 * Ownership of the returned mesh is unchanged from the Tesselator days: whoever calls
 * {@link BufferBuilder#buildOrThrow()} owns the resulting {@code MeshData} and has to close it,
 * or the shared buffer never reclaims that region.
 */
public final class VRTesselator {

    /** Tesselator's own MAX_BYTES in 26.1.2, kept so buffer growth behaviour does not change. */
    private static final int MAX_BYTES = 786432;

    private static ByteBufferBuilder buffer;

    private VRTesselator() {
    }

    /**
     * Begins a mesh in the shared scratch buffer.
     *
     * @param topology primitive topology, was {@code VertexFormat.Mode} before 26.2
     * @param format   vertex format to build with
     * @return a builder writing into the shared buffer
     */
    public static BufferBuilder begin(PrimitiveTopology topology, VertexFormat format) {
        if (buffer == null) {
            buffer = new ByteBufferBuilder(MAX_BYTES);
        }
        return new BufferBuilder(buffer, topology, format);
    }
}
