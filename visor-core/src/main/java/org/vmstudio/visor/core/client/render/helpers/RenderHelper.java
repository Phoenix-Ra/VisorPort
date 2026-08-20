package org.vmstudio.visor.core.client.render.helpers;


import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.*;
import me.phoenixra.atumvr.api.misc.color.AtumColor;
import org.vmstudio.visor.api.common.utils.VRMathUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Vector4f;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Optional;
import java.util.function.Supplier;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;

public class RenderHelper {
    private RenderHelper() {
        throw new UnsupportedOperationException("This is an utility class and cannot be instantiated");
    }

    public static boolean isInSolidBlock(Vector3fc in) {
        if (MC.level == null) {
            return false;
        } else {
            BlockPos blockpos = BlockPos.containing(new Vec3((Vector3f) in));
            return MC.level.getBlockState(blockpos).isSolidRender();
        }
    }

    public static void renderCuboid(RenderType type,
                                    Matrix4f poseMatrix,
                                    Vector3fc start,
                                    Vector3fc end,
                                    float innerWidth, float outerWidth,
                                    float innerHeight, float outerHeight,
                                    AtumColor color) {

        // --- Prepare variables ---
        var forward = end.sub(start, new Vector3f()).normalize();
        var right = forward.cross(VRMathUtils.UP_VECTOR, new Vector3f()).normalize();
        var up = right.cross(forward, new Vector3f()).normalize();

        var r0 = right.mul(innerWidth, new Vector3f());
        var r1 = right.mul(outerWidth, new Vector3f());
        var u0 = up.mul(innerHeight, new Vector3f());
        var u1 = up.mul(outerHeight, new Vector3f());

        var corners = new Vector3fc[][] {
                { start, r0, u0 }, { start, r1, u0 }, { start, r1, u1 }, { start, r0, u1 },
                { end,   r0, u0 }, { end,   r1, u0 }, { end,   r1, u1 }, { end,   r0, u1 }
        };

        var faceIndices = new int[][] {
                // back face (start)
                {0, 3, 2, 1},
                // front face (end)
                {4, 5, 6, 7},
                // right face
                {1, 2, 6, 5},
                // left face
                {0, 4, 7, 3},
                // top face
                {3, 7, 6, 2},
                // bottom face
                {0, 1, 5, 4}
        };
        var faceNormals = new Vector3f[] {
                forward, forward.mul(-1, new Vector3f()).normalize(),
                right, right.mul(-1, new Vector3f()).normalize(),
                up, up.mul(-1, new Vector3f()).normalize()
        };


        // --- Render ---
        BufferBuilder bufferBuilder = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_COLOR_NORMAL
        );
        for (int f = 0; f < faceIndices.length; f++) {
            Vector3f normal = faceNormals[f];
            for (int idx : faceIndices[f]) {
                var base = corners[idx][0];
                var xOff = corners[idx][1];
                var yOff = corners[idx][2];
                var pos = base.add(xOff, new Vector3f()).add(yOff);
                addVertex(bufferBuilder, poseMatrix, pos, color, normal);
            }
        }
        type.draw(bufferBuilder.buildOrThrow());
    }


    public static void renderFlatQuad(RenderType type,
                                      Matrix4f poseMatrix,
                                      Vector3fc pos,
                                      float width,
                                      float height,
                                      float yaw,
                                      AtumColor color) {
        // --- Prepare variables ---
        float halfW = width  * 0.5f;
        float halfH = height * 0.5f;
        Vector3f off = new Vector3f(halfW, 0, halfH)
                .rotateY((float)Math.toRadians(-yaw));
        Vector3fc normal = VRMathUtils.UP_VECTOR;
        float xOff = off.x, zOff = off.z;
        float r = color.getRed(), g = color.getGreen(),
                b = color.getBlue(), a = color.getAlpha();


        float[][] vertices = {
                { pos.x() + xOff, pos.y(), pos.z() + zOff },
                { pos.x() + xOff, pos.y(), pos.z() - zOff },
                { pos.x() - xOff, pos.y(), pos.z() - zOff },
                { pos.x() - xOff, pos.y(), pos.z() + zOff }
        };


        // --- Render ---
        BufferBuilder bufferBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_COLOR_NORMAL);
        for (float[] vertex : vertices) {
            bufferBuilder.addVertex(poseMatrix, vertex[0], vertex[1], vertex[2])
                    .setColor(r, g, b, a)
                    .setNormal(normal.x(), normal.y(), normal.z())
            ;
        }
        type.draw(bufferBuilder.buildOrThrow());

    }


    /**
     * Draws a VR overlay panel: a camera-facing quad textured with {@code source}'s colour
     * attachment.
     * <p>
     * The texture comes from a {@link RenderTarget}, not from a registered {@code Identifier}, so
     * this cannot go through a {@code RenderType} - a {@code RenderSetup} can only name textures by
     * id. It draws through {@link RenderShaderHelper#drawMesh} instead, which also lets the tint
     * ride along as a real {@code ColorModulator}; {@code RenderType.draw} hardcodes that to white.
     */
    public static void renderDisplayQuad(RenderPipeline pipeline,
                                         Matrix4f poseMatrix,
                                         RenderTarget source,
                                         AtumColor color,
                                         float displayWidth,
                                         float displayHeight,
                                         float size) {
        // --- Prepare variables ---
        float aspect = displayHeight / displayWidth;
        float halfSize = size * 0.5f;
        float halfHeight = halfSize * aspect;
        float u0 = 0f, u1 = 1f, v0 = 0f, v1 = 1f;

        float[][] vertices = {
                { -halfSize, -halfHeight, 0f,   u0, v0 },
                {  halfSize, -halfHeight, 0f,   u1, v0 },
                {  halfSize,  halfHeight, 0f,   u1, v1 },
                { -halfSize,  halfHeight, 0f,   u0, v1 }
        };

        // --- Render ---
        BufferBuilder buf;
        buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX);

        for (float[] vertex : vertices) {
            buf.addVertex(poseMatrix, vertex[0], vertex[1], vertex[2])
                    .setUv(vertex[3], vertex[4])
            ;
        }

        RenderShaderHelper.drawMesh(() -> "visor overlay quad", pipeline, buf.buildOrThrow(),
                new Vector4f(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha()),
                pass -> RenderShaderHelper.bindColor(pass, "Sampler0", source));
    }


    /**
     * The lit variant of {@link #renderDisplayQuad}: same quad, sampling the lightmap so the panel
     * dims with the block light around it.
     * <p>
     * 1.21.4 got flat lighting by overriding both shader light directions to point along the
     * quad's own normal. That is no longer possible - the light directions live in a UBO slice
     * rather than in two settable vectors - so the flatness is declared on the pipeline instead,
     * via {@code NO_CARDINAL_LIGHTING}. Same result, see {@code VisorPipelines}.
     */
    public static void renderDisplayQuadWithLight(RenderPipeline pipeline,
                                                  Matrix4f poseMatrix,
                                                  RenderTarget source,
                                                  AtumColor color,
                                                  float displayWidth,
                                                  float displayHeight,
                                                  float size,
                                                  int light,
                                                  boolean flipY) {
        // --- Prepare variables ---
        float red = color.getRed();
        float green = color.getGreen();
        float blue = color.getBlue();
        float alpha = color.getAlpha();

        float aspect = displayHeight / displayWidth;
        float halfSize = size * 0.5f;
        float halfHeight = halfSize * aspect;
        float uMin = 0f;
        float uMax = 1f;
        float vMin = flipY ? 1f : 0f;
        float vMax = flipY ? 0f : 1f;

        float[][] pos = {
                { -halfSize, -halfHeight },
                {  halfSize, -halfHeight },
                {  halfSize,  halfHeight },
                { -halfSize,  halfHeight }
        };
        float[][] uv = {
                { uMin, vMin },
                { uMax, vMin },
                { uMax, vMax },
                { uMin, vMax }
        };

        // --- Render ---
        BufferBuilder buf;
        buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);

        for (int i = 0; i < 4; i++) {
            float x = pos[i][0], y = pos[i][1];
            float u = uv[i][0], v = uv[i][1];
            buf.addVertex(poseMatrix, x, y, 0f)
                    .setColor(red, green, blue, alpha)
                    .setUv(u, v)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light)
                    .setNormal(0f, 0f, 1f)
            ;
        }

        RenderShaderHelper.drawMesh(() -> "visor overlay quad (lit)", pipeline, buf.buildOrThrow(),
                RenderShaderHelper.NO_TINT,
                pass -> {
                    RenderShaderHelper.bindColor(pass, "Sampler0", source);
                    pass.bindTexture("Sampler1", MC.gameRenderer.overlayTexture().getTextureView(),
                            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    pass.bindTexture("Sampler2", MC.gameRenderer.lightTexture().getTextureView(),
                            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                });
    }


    public static float distanceToNearestSolidBlockSurface(Vec3 origin, double maxRadius) {
        ClientLevel level = MC.level;
        if (level == null) {
            return (float) maxRadius;
        }

        int minX = (int) Math.floor(origin.x - maxRadius);
        int minY = (int) Math.floor(origin.y - maxRadius);
        int minZ = (int) Math.floor(origin.z - maxRadius);
        int maxX = (int) Math.floor(origin.x + maxRadius);
        int maxY = (int) Math.floor(origin.y + maxRadius);
        int maxZ = (int) Math.floor(origin.z + maxRadius);

        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
        double minDistSq = maxRadius * maxRadius;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mPos.set(x, y, z);
                    if (!level.getBlockState(mPos).isSolidRender()) {
                        continue;
                    }
                    double dx = Math.max(0.0, Math.max(x - origin.x, origin.x - (x + 1.0)));
                    double dy = Math.max(0.0, Math.max(y - origin.y, origin.y - (y + 1.0)));
                    double dz = Math.max(0.0, Math.max(z - origin.z, origin.z - (z + 1.0)));
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq < minDistSq) {
                        minDistSq = distSq;
                        if (minDistSq <= 0.0) {
                            return 0.0f;
                        }
                    }
                }
            }
        }

        return (float) Math.sqrt(minDistSq);
    }


    /**
     * Searches within a sphere of radius {@code radius} around {@code origin}
     * for any block whose {@code isSolidRender} is true.
     *
     * @param origin the center of the search in world coordinates
     * @param radius the search radius
     * @return an Optional containing found opaque block info, or empty if none found
     */
    public static Optional<VREffectsHelper.NearestOpaqueBlock> findAnySolidBlock(Vec3 origin, double radius) {
        ClientLevel level = MC.level;
        if (level == null) {
            return Optional.empty();
        }

        AABB box = new AABB(
                origin.subtract(radius, radius, radius),
                origin.add(radius, radius, radius)
        );

        return BlockPos
                .betweenClosedStream(box)
                .filter(pos -> level.getBlockState(pos).isSolidRender())
                .findFirst()
                .map(pos -> new VREffectsHelper.NearestOpaqueBlock(
                        1.0F,
                        level.getBlockState(pos),
                        pos
                ));
    }




    private static void addVertex(BufferBuilder buff,
                                  Matrix4f mat,
                                  Vector3fc pos,
                                  AtumColor color,
                                  Vector3fc normal) {
        buff.addVertex(mat, pos.x(), pos.y(), pos.z())
                .setColor(color.getRedInt(), color.getGreenInt(), color.getBlueInt(), (byte) color.getAlphaInt())
                .setNormal(normal.x(), normal.y(), normal.z())
        ;
    }
}
