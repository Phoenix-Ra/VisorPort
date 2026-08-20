package org.vmstudio.visor.extensions.client.entity;

import org.joml.Quaternionf;

public interface EntityRenderDispatcherExtension {

    /**
     * Billboard orientation that looks from the headset (from the camera in third person)
     * at the given world point, so a billboard anchored there faces the eye instead of
     * being parallel to the flat camera plane.
     *
     * <p>PORT-1.21.11: used to take the scale/offset of the entity that
     * {@code LevelRendererExtension#visor$getRenderedEntity()} was reporting. The extract/submit
     * split means there is no "entity being rendered" during submit any more, so the caller
     * resolves the anchor itself - the render state carries the interpolated position and the
     * bounding box.</p>
     */
    Quaternionf visor$getVRBillboardOrientation(double targetX, double targetY, double targetZ);

}
