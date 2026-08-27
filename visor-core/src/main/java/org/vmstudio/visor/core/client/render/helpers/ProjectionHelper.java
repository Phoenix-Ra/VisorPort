package org.vmstudio.visor.core.client.render.helpers;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * PORT-26.2: 26.2 renders with a reversed depth buffer. Vanilla's Projection.getMatrix builds
 * every render projection with zNear/zFar swapped (and the device's clip-space convention:
 * GL runs glClipControl ZERO_TO_ONE where ARB_clip_control exists), clears depth to 0.0 and
 * depth-tests GREATER_THAN_OR_EQUAL. Everything Visor renders the world with has to follow the
 * same convention, so the projections it hands to vanilla are built here.
 * <p>
 * Culling is the one place vanilla stays on classic depth: Camera.createProjectionMatrixForCulling
 * passes zNear/zFar in classic order, because Frustum derives its view vector from the matrix's
 * z row, and that row flips direction on a reversed projection (offsetToFullyIncludeCameraCube
 * would then walk the wrong way). {@link #toCullProjection} converts a render projection back.
 */
public final class ProjectionHelper {

    private ProjectionHelper() {
        throw new UnsupportedOperationException("This is an utility class and cannot be instantiated");
    }

    public static boolean isZZeroToOne() {
        return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
    }

    /** Reverse-depth symmetric perspective - what vanilla's Projection.getMatrix builds. */
    public static Matrix4f perspective(float fovYRadians, float aspect, float zNear, float zFar) {
        return new Matrix4f().setPerspective(fovYRadians, aspect, zFar, zNear, isZZeroToOne());
    }

    /** Reverse-depth off-center perspective, from the XR runtime's per-eye half angles. */
    public static Matrix4f perspectiveOffCenterFov(float angleLeft, float angleRight,
                                                   float angleDown, float angleUp,
                                                   float zNear, float zFar) {
        return new Matrix4f().setPerspectiveOffCenterFov(
                angleLeft, angleRight, angleDown, angleUp,
                zFar, zNear, isZZeroToOne()
        );
    }

    /**
     * A classic-depth copy of {@code renderProjection} for building a {@code Frustum}. Only the
     * z rows differ between the conventions, so the off-center x/y terms carry over untouched;
     * the formulas are JOML's setPerspective ones for the device's clip-space convention.
     */
    public static Matrix4f toCullProjection(Matrix4fc renderProjection, float zNear, float zFar) {
        Matrix4f cull = new Matrix4f(renderProjection);
        if (isZZeroToOne()) {
            cull.m22(zFar / (zNear - zFar));
            cull.m32(zFar * zNear / (zNear - zFar));
        } else {
            cull.m22((zFar + zNear) / (zNear - zFar));
            cull.m32((zFar + zFar) * zNear / (zNear - zFar));
        }
        cull.m23(-1.0f);
        return cull;
    }
}
