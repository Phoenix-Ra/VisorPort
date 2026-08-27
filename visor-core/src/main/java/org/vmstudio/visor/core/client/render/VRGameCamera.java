package org.vmstudio.visor.core.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.client.render.VRRenderPass;
import org.vmstudio.visor.api.common.player.VRPose;
import org.vmstudio.visor.api.common.utils.VRMathUtils;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.VisorState;
import org.vmstudio.visor.core.client.player.VRClientPlayers;
import org.vmstudio.visor.core.client.render.helpers.CullFrustumHelper;
import org.vmstudio.visor.core.client.render.helpers.ProjectionHelper;
import org.vmstudio.visor.core.client.render.helpers.RenderPoseHelper;
import org.vmstudio.visor.extensions.client.render.GameRendererExtension;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;

/**
 * PORT-26.1: {@code Camera.setup(level, entity, thirdPerson, mirrored, partialTicks)} is gone.
 * {@code Camera.update(DeltaTracker)} now owns everything that used to be spread over
 * GameRenderer: entity alignment, fov, far plane, cull frustum and the projection, and
 * {@code extractRenderState} copies it all into the {@link CameraRenderState} the renderer
 * consumes. Visor drives {@code update}/{@code extract} once per VR pass, and this camera
 * answers every one of those with the pass pose and the pass projection.
 */
public class VRGameCamera extends Camera {

    private static final float HUD_FOV = 70.0F;

    @Override
    public void update(DeltaTracker deltaTracker) {
        if (VRRenderState.getPhase().isVanilla() || VisorState.get().isNotActive()) {
            super.update(deltaTracker);
            if (VRRenderState.isSpectatedVRView(this.entity)) {
                setupSpectatedVR(this.entity);
                // the vanilla frustum was built from the entity pose, rebuild it from the HMD pose
                this.prepareCullFrustum(
                        this.getViewRotationMatrix(new Matrix4f()),
                        this.createProjectionMatrixForCulling(),
                        this.position()
                );
            }
            return;
        }
        updateVR(deltaTracker);
    }

    @Override
    public void tick() {
        if (VRRenderState.getPhase().isVanilla() || VisorState.get().isNotActive()) {
            super.tick();
            return;
        }
        if (this.entity != null && this.level != null) {
            this.attributeProbe().tick(this.level, this.position());
        }
    }

    @Override
    public boolean isDetached() {
        if (VRRenderState.getPhase().isVanilla()) {
            return super.isDetached();
        }
        return VRRenderState.isSelfModelRenderCamera();
    }

    @Override
    public void extractRenderState(CameraRenderState cameraState, float cameraEntityPartialTicks) {
        super.extractRenderState(cameraState, cameraEntityPartialTicks);
        if (VRRenderState.getPhase().isVanilla() || VisorState.get().isNotActive()) {
            return;
        }
        cameraState.projectionMatrix.set(visor$passProjection());
        cameraState.depthFar = this.depthFar;
    }

    @Override
    public Matrix4f getViewRotationProjectionMatrix(Matrix4f dest) {
        if (VRRenderState.getPhase().isVanilla() || VisorState.get().isNotActive()) {
            return super.getViewRotationProjectionMatrix(dest);
        }
        dest.set(visor$passProjection());
        return dest.mul(this.getViewRotationMatrix(new Matrix4f()));
    }

    private Matrix4fc visor$passProjection() {
        return ((GameRendererExtension) MC.gameRenderer).visor$getPassProjection();
    }

    private void updateVR(DeltaTracker deltaTracker) {
        LocalPlayer player = MC.player;
        if (player == null || this.level == null) {
            return;
        }
        if (this.entity == null) {
            this.setEntity(player);
        }
        float partialTicks = this.getCameraEntityPartialTicks(deltaTracker);
        setupVR(this.level, this.entity);

        GameRendererExtension gameRenderer = (GameRendererExtension) MC.gameRenderer;
        gameRenderer.visor$setupClipPlanes();
        this.depthFar = gameRenderer.visor$getFarClipPlane();
        this.fov = calculateVRFov(partialTicks);
        this.hudFov = modifyFovBasedOnDeathOrFluid(partialTicks, HUD_FOV);

        // PORT-26.2: the pass projection is reverse-depth now; Frustum wants classic depth
        // (vanilla's createProjectionMatrixForCulling stays classic for the same reason - the
        // view vector it derives from the z row flips direction on a reversed matrix).
        Matrix4f cullProjection = ProjectionHelper.toCullProjection(
                visor$passProjection(),
                gameRenderer.visor$getNearClipPlane(),
                this.depthFar
        );
        this.prepareCullFrustum(
                this.getViewRotationMatrix(new Matrix4f()),
                CullFrustumHelper.widenCullProjection(cullProjection),
                this.position()
        );
        float width = Math.max(1, MC.getWindow().getWidth());
        float height = Math.max(1, MC.getWindow().getHeight());
        this.setupPerspective(
                gameRenderer.visor$getNearClipPlane(),
                this.depthFar,
                this.fov,
                width,
                height
        );
        this.initialized = true;
    }

    /**
     * 1.21.11 answered {@code getFov} with the plain option value while VR showed the main menu
     * and with the vanilla death/fluid-modified value otherwise; the sprint/flying fov modifier
     * never applied because {@code tickFov} was skipped in VR.
     */
    private float calculateVRFov(float partialTicks) {
        float fov = MC.options.fov().get().intValue();
        if (VRRenderState.getSceneType().isMainMenu()) {
            return fov;
        }
        return modifyFovBasedOnDeathOrFluid(partialTicks, fov);
    }

    private float modifyFovBasedOnDeathOrFluid(float partialTicks, float fov) {
        if (this.entity instanceof LivingEntity cameraEntity && cameraEntity.isDeadOrDying()) {
            float duration = Math.min(cameraEntity.deathTime + partialTicks, 20.0F);
            fov /= (1.0F - 500.0F / (duration + 500.0F)) * 2.0F + 1.0F;
        }
        FogType state = this.getFluidInCamera();
        if (state == FogType.LAVA || state == FogType.WATER) {
            float effectScale = MC.options.fovEffectScale().get().floatValue();
            fov *= Mth.lerp(effectScale, 1.0F, 0.85714287F);
        }
        return fov;
    }

    private void setupVR(net.minecraft.world.level.Level level, Entity entity) {
        this.initialized = true;
        this.level = level;
        this.entity = entity;

        VRRenderPass renderPass = VRRenderState.getRenderPass();
        var renderPose = ClientContext.localPlayer.getPoseData(PlayerPoseType.RENDER);
        VRPose cameraElement = renderPose.getCameraPose(renderPass);

        this.setPosition(new Vec3(
                RenderPoseHelper.getCameraPosition(renderPass, renderPose)
        ));
        applyPose(cameraElement);
    }

    private void setupSpectatedVR(Entity entity) {
        var vrPlayer = VRClientPlayers.getPlayer(entity.getUUID());
        if (vrPlayer == null) {
            return;
        }
        VRPose hmd = vrPlayer.getPoseData(PlayerPoseType.RENDER).getHmd();
        this.setPosition(new Vec3(hmd.getPosition()));
        applyPose(hmd);
    }

    private void applyPose(VRPose pose) {
        this.xRot = -pose.getPitchDegrees();
        this.yRot = pose.getYawDegrees();

        var dir = pose.getDirection();
        var upVec = pose.getCustomVector(VRMathUtils.UP_VECTOR);
        var leftVec = pose.getCustomVector(VRMathUtils.LEFT_VECTOR);

        writeBasis(this.forwardVector(), dir.x(), dir.y(), dir.z());
        writeBasis(this.upVector(), upVec.x, upVec.y, upVec.z);
        writeBasis(this.leftVector(), leftVec.x, leftVec.y, leftVec.z);

        pose.getRotation().getNormalizedRotation(this.rotation());
        // the cached view matrices are keyed on a dirty mask that only setRotation() touches
        this.visor$markRotationDirty();
    }

    private static void writeBasis(Vector3fc basis, float x, float y, float z) {
        ((Vector3f) basis).set(x, y, z);
    }

    /**
     * 26.1 caches the view-rotation matrices and only rebuilds them when {@code setRotation}
     * flips a dirty bit; writing the quaternion directly has to flip it too.
     */
    private void visor$markRotationDirty() {
        this.matrixPropertiesDirty |= 3;
    }
}
