package org.vmstudio.visor.mixin.client.renderer;

import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.render.VRRenderState;


@Mixin(WeatherEffectRenderer.class)
public class WeatherEffectRendererMixin {

    /**
     * PORT-1.21.11: weather was split into extract and render like the rest of the level. The
     * private {@code collectColumnInstances} this used to intercept is gone - the rain and snow
     * columns are now laid out around the camera in
     * {@code extractRenderState(Level, int, float, Vec3, WeatherRenderState)}, so the camera
     * position is a parameter of that method rather than an argument to an inner call. Same seam,
     * one level up: the columns still get centred on the headset instead of the vanilla camera,
     * which is what stops rain sitting off to one side in VR.
     */
    @ModifyVariable(method = "extractRenderState", at = @At("HEAD"), argsOnly = true)
    private Vec3 visor$rainAndSnowCentre(Vec3 cameraPosition) {
        if (VRRenderState.getRenderPass().isEye()) {
            var hmd = ClientContext.localPlayer.getPoseData(PlayerPoseType.RENDER)
                    .getHmd().getPosition();
            return new Vec3(hmd.x(), hmd.y(), hmd.z());
        }
        return cameraPosition;
    }
}
