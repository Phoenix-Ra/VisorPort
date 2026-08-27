package org.vmstudio.visor.mixin.client.renderer;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import me.phoenixra.atumvr.api.enums.EyeType;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.vmstudio.visor.api.ModLoader;
import org.vmstudio.visor.api.client.ClientFeature;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.client.render.VRRenderPass;
import org.vmstudio.visor.api.client.settings.VRClientSettings;
import org.vmstudio.visor.api.client.settings.enums.MirrorMode;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visor.api.common.player.VRPose;
import org.vmstudio.visor.api.server.VRServerSettings;
import org.vmstudio.visor.compatibility.immportals.ImmPortalsCompatHelper;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.VisorState;
import org.vmstudio.visor.core.client.render.VRCameraEntityCache;
import org.vmstudio.visor.core.client.render.VRGameCamera;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.core.client.render.helpers.ProjectionHelper;
import org.vmstudio.visor.core.client.render.helpers.RenderHelper;
import org.vmstudio.visor.core.client.render.helpers.RenderPoseHelper;
import org.vmstudio.visor.core.client.render.helpers.VREffectsHelper;
import org.vmstudio.visor.extensions.client.render.GameRendererExtension;
import org.vmstudio.visor.extensions.client.WindowExtension;

import java.nio.file.Path;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;

/**
 * PORT-26.1: GameRenderer.render() was split into update() / extract() / render(), and the
 * camera-related members (pick, getFov, tickFov, getProjectionMatrix, getDepthFar, the fov
 * modifiers, lastActiveTime) left this class: picking lives on Minecraft, fov/projection/far
 * plane on Camera, pause-on-focus-loss on Minecraft. The hooks that depended on them moved
 * with them (MinecraftMixin, VRGameCamera); what is left here is the per-pass state the rest
 * of Visor reads through {@link GameRendererExtension}, the projection upload for the VR
 * decoration passes, and the render()/extract() gating that keeps the GUI out of the eye
 * targets and the world out of the GUI target.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin
        implements AutoCloseable, GameRendererExtension {

    @Shadow
    @Final
    Minecraft minecraft;

    @Shadow
    private boolean effectActive;

    @Shadow
    @Final
    private Camera mainCamera;

    @Shadow
    private void renderItemInHand(CameraRenderState cameraState, float deltaPartialTick, Matrix4fc modelViewMatrix) {
        throw new AssertionError();
    }

    @Shadow
    public abstract GameRenderState gameRenderState();

    @Unique
    public Matrix4f visor$thirdPersonProjection = new Matrix4f();

    @Unique
    public float visor$nearClipPlane = 0.02F;

    @Unique
    private float visor$farClipPlane = 128.0F;

    @Unique
    private float visor$renderDistance;

    @Unique
    public Vec3 visor$crossVec;

    @Unique
    private HandType visor$pickingHand;

    @Unique
    private final HitResult[] visor$handHitResult = new HitResult[2];

    @Unique
    private final Vec3[] visor$handCrossVec = new Vec3[2];

    @Unique
    private final Entity[] visor$handPickEntity = new Entity[2];

    @Unique
    public boolean visor$onfire;

    @Unique
    public boolean visor$inBlock = false;

    @Unique
    public float visor$blockProximity = 0.0f;

    @Unique
    private static final int VISOR_PROJECTION_RING_SIZE = 32;

    @Unique
    private ProjectionMatrixBuffer[] visor$projectionRing;

    @Unique
    private int visor$projectionRingIndex;

    @Unique
    public VRCameraEntityCache visor$cameraEntityCache = new VRCameraEntityCache();

    @Unique
    private boolean visor$cameraEntityCached;

    @Unique
    private int visor$cameraEntityCacheDepth;

    @Unique
    private boolean visor$isVRGuiVisible;

    // ------------------------------------------------------------------
    // frame structure
    // ------------------------------------------------------------------

    /**
     * A VR world pass ends where the GUI would start: right after the world was drawn and the
     * fog frame closed. The GUI is rendered once per frame, into its own target, by the VR GUI
     * phase. In the main menu there is no world, so the menu decoration is drawn here instead.
     */
    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V", shift = Shift.AFTER), method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", cancellable = true)
    public void visor$onRenderGUI(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo info) {
        if (VRRenderState.getPhase().isNotVRWorld()) {
            return;
        }
        // render() pushed "render" on the profiler; balance it before leaving early
        Profiler.get().pop();
        // PORT-26.2: 26.2 recycles the shared StagedVertexBuffer pools in renderBuffers.endFrame()
        // at render()'s tail, which this cancel skips. Without it every VR pass allocates fresh
        // GPU buffers for its entities that only come back at the next vanilla-frame tail.
        ((GameRenderer) (Object) this).renderBuffers().endFrame();
        info.cancel();
        if (VRRenderState.getSceneType().isMainMenu()) {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            PoseStack poseStack = new PoseStack();
            ClientContext.decorationRenderer.renderMainMenu(
                    poseStack,
                    deltaTracker.getGameTimeDeltaPartialTick(false)
            );
        }
    }

    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"), method = "render(Lnet/minecraft/client/DeltaTracker;Z)V")
    public void visor$matrix(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo info) {
        if (VisorState.get().isNotActive()) return;
        RenderSystem.setProjectionMatrix(
                visor$uploadProjection(visor$getPassProjection()),
                ProjectionType.PERSPECTIVE
        );
        RenderSystem.getModelViewStack().identity();
    }

    @Override
    public boolean visor$isVRGuiVisible() {
        return visor$isVRGuiVisible;
    }

    @Override
    public void visor$setVRGuiVisible(boolean flag) {
        visor$isVRGuiVisible = flag;
    }

    /**
     * The VR GUI phase runs extract() with advanceGameTime=false so the level is not extracted
     * into the GUI target's frame, but the HUD still has to be extracted when there is a world
     * behind the GUI. 1.21.11 did the same by rewriting the renderLevel local before the GUI
     * extraction; 26.1 hands that flag to extractGui() as an argument.
     * PORT-26.2: GameRenderer.extractGui is gone - extract() calls Gui.extractRenderState with the
     * same (DeltaTracker, boolean, boolean) shape, so the same argument is modified one call down.
     */
    @ModifyArg(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V"), method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", index = 1)
    private boolean visor$extractGuiWithWorld(boolean shouldRenderLevel) {
        if (VRRenderState.getPhase().isVanilla()) {
            return shouldRenderLevel;
        }
        return visor$isVRGuiVisible();
    }

    // PORT-26.2: the "no GUI extraction in a world pass" cancel moved to GuiMixin - extractGui
    // is gone and Gui.extractRenderState is the method to stop now.

    /**
     * 26.1 resizes the main render target from render() whenever the window reports a resize.
     * In VR "the main render target" is whichever Visor target the current pass draws into, and
     * those are sized by Visor (WindowMixin#visor$onResize -> prepareResize), not by the window.
     */
    /**
     * PORT-26.2: 26.1 gated its resize on windowRenderState.isResized, which the 26.1 port
     * cleared to veto it. That flag is gone - render() now fires resize(II) whenever the
     * extracted window size disagrees with the live mainRenderTarget. In VR the two only agree
     * because WindowMixin reports the current pass target's size; any transient disagreement
     * would resize a Visor target to window dimensions and invalidate the section graph every
     * frame, so the call is vetoed explicitly while VR is active.
     */
    @WrapWithCondition(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;resize(II)V"))
    private boolean visor$noVanillaResizeInVRRender(GameRenderer instance, int width, int height) {
        return VisorState.get().isNotActive();
    }

    /**
     * PORT-26.2: the hide-GUI flag moved from OptionsRenderState (extracted every pass) to
     * GuiRenderState.isHudHidden, written only by Hud.extractRenderState - which the VR world
     * passes cancel, so the value the item-hand / screen-effect / crosshair gates read would go
     * stale. Refreshed here because extractOptions still runs once per pass.
     */
    @Inject(at = @At("TAIL"), method = "extractOptions")
    private void visor$refreshHudHiddenFlag(CallbackInfo ci) {
        if (VisorState.get().isNotActive()) {
            return;
        }
        this.gameRenderState().guiRenderState.isHudHidden = this.minecraft.gui.hud.isHidden;
    }

    @Inject(at = @At("TAIL"), method = "extractWindow")
    private void visor$noVanillaResizeInVR(CallbackInfo ci) {
        if (VisorState.get().isNotActive()) {
            return;
        }
        // PORT-26.2: WindowRenderState no longer carries an isResized flag, so there is nothing
        // to gate on or clear. The body below only resizes when the dimensions actually differ,
        // so running it on every extractWindow is equivalent.
        // keep the vanilla target at the real window size so it is right when VR is turned off
        RenderTarget vanillaTarget = VRRenderState.getVanillaTarget();
        var window = (WindowExtension) (Object) this.minecraft.getWindow();
        int width = Math.max(1, window.visor$getActualScreenWidth());
        int height = Math.max(1, window.visor$getActualScreenHeight());
        if (vanillaTarget != null
                && vanillaTarget != this.minecraft.gameRenderer.mainRenderTarget()
                && (vanillaTarget.width != width || vanillaTarget.height != height)) {
            vanillaTarget.resize(width, height);
        }
    }

    @Inject(at = @At("HEAD"), method = "shouldRenderBlockOutline", cancellable = true)
    public void visor$shouldDrawBlockOutline(CallbackInfoReturnable<Boolean> cir) {
        if (VRRenderState.getPhase().isVRWorld()) {
            cir.setReturnValue(
                    ClientContext.visor.isFeatureEnabled(ClientFeature.AIM_EFFECTS)
            );
        }
    }

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "net/minecraft/client/Camera"))
    public Camera visor$replaceCamera() {
        return new VRGameCamera();
    }

    @Redirect(at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/GameRenderer;effectActive:Z"), method = "render")
    public boolean visor$noPostEffectOnThirdPerson(GameRenderer instance) {
        return this.effectActive && VRRenderState.getRenderPass() != VRRenderPass.THIRD_PERSON;
    }

    @Inject(at = @At("HEAD"), method = "takeAutoScreenshot", cancellable = true)
    public void visor$noScreenshotInMenu(Path path, CallbackInfo ci) {
        if (VisorState.get().isActive() && VRRenderState.getSceneType().isMainMenu()) {
            ci.cancel();
        }
    }

    @Inject(at = @At("HEAD"), method = "bobHurt", cancellable = true)
    public void visor$noBobHurt(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
        if (VRRenderState.getPhase().isNotVanilla()) {
            ci.cancel();
        }
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    public void visor$noBobView(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
        if (VRRenderState.getPhase().isNotVanilla()) {
            ci.cancel();
        }
    }

    // ------------------------------------------------------------------
    // renderLevel
    // ------------------------------------------------------------------

    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V"), method = "renderLevel")
    public void visor$noVanillaHands(GameRenderer instance, CameraRenderState cameraState, float deltaPartialTick, Matrix4fc modelViewMatrix) {
        if (VRRenderState.isSpectatedVRView(minecraft.getCameraEntity())) {
            return;
        }
        if (VRRenderState.getPhase().isVanilla()) {
            this.renderItemInHand(cameraState, deltaPartialTick, modelViewMatrix);
        }
    }

    @Redirect(at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"),
            method = "renderLevel")
    public void visor$noVanillaHandDepthClear(CommandEncoder encoder, GpuTexture depthTexture,
                                              double depth) {
        if (VRRenderState.isSpectatedVRView(minecraft.getCameraEntity())) {
            return;
        }
        if (VRRenderState.getPhase().isVanilla()) {
            encoder.clearDepthTexture(depthTexture, depth);
        }
    }

    @WrapOperation(at = @At(value = "INVOKE",
            // PORT-26.2: renderScreenEffect -> submit, same descriptor.
            target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;submit(ZZFLnet/minecraft/client/renderer/SubmitNodeCollector;Z)V"),
            method = "renderLevel")
    public void visor$noVanillaScreenEffects(ScreenEffectRenderer instance, boolean firstPerson,
                                             boolean sleeping, float partialTick,
                                             SubmitNodeCollector collector, boolean hideGui,
                                             Operation<Void> original) {
        if (VRRenderState.isSpectatedVRView(minecraft.getCameraEntity())) {
            return;
        }
        if (VRRenderState.getPhase().isVanilla()) {
            original.call(instance, firstPerson, sleeping, partialTick, collector, hideGui);
        }
    }

    @Inject(at = @At("TAIL"), method = "renderLevel")
    public void visor$disableStencil(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (VRRenderState.getPhase().isNotVanilla()) {
            VREffectsHelper.disableStencilTest();
        }
    }

    // ------------------------------------------------------------------
    // projection
    // ------------------------------------------------------------------

    /**
     * The projection of the pass being rendered. 1.21.11 served this through an injection into
     * GameRenderer.getProjectionMatrix(fov); 26.1 builds the projection inside Camera, so
     * VRGameCamera asks for it here and writes it into the CameraRenderState itself.
     */
    @Override
    @Unique
    public Matrix4f visor$getPassProjection() {
        visor$setupClipPlanes();
        ClientContext.renderer.updateProjection();
        VRRenderPass renderPass = VRRenderState.getRenderPass();
        if (renderPass == VRRenderPass.EYE_LEFT) {
            return new Matrix4f(ClientContext.renderer.getEyeProjection(EyeType.LEFT));
        }
        if (renderPass == VRRenderPass.EYE_RIGHT) {
            return new Matrix4f(ClientContext.renderer.getEyeProjection(EyeType.RIGHT));
        }
        // PORT-26.2: every projection here is reverse-depth, like vanilla's Projection.getMatrix.
        if (renderPass == VRRenderPass.THIRD_PERSON) {
            Matrix4f projection;
            if (VRClientSettings.getMirrorMode() == MirrorMode.MIXED_REALITY) {
                projection = ProjectionHelper.perspective(
                        VRClientSettings.getMixedRealityFov() * Mth.DEG_TO_RAD,
                        VRClientSettings.getMixedRealityAspectRatio(),
                        this.visor$nearClipPlane,
                        this.visor$farClipPlane
                );
            } else {
                projection = ProjectionHelper.perspective(
                        VRClientSettings.getThirdPersonFov() * Mth.DEG_TO_RAD,
                        visor$screenAspect(),
                        this.visor$nearClipPlane,
                        this.visor$farClipPlane
                );
            }
            this.visor$thirdPersonProjection = new Matrix4f(projection);
            return projection;
        }
        return ProjectionHelper.perspective(
                this.mainCamera.getFov() * Mth.DEG_TO_RAD,
                visor$screenAspect(),
                this.visor$nearClipPlane,
                this.visor$farClipPlane
        );
    }

    @Unique
    private float visor$screenAspect() {
        return (float) this.minecraft.getWindow().getScreenWidth()
                / (float) Math.max(1, this.minecraft.getWindow().getScreenHeight());
    }

    @Override
    @Unique
    public void visor$resetProjectionMatrix(float partialTicks) {
        RenderSystem.setProjectionMatrix(
                visor$uploadProjection(visor$getPassProjection()),
                ProjectionType.PERSPECTIVE
        );
    }

    @Unique
    private GpuBufferSlice visor$uploadProjection(Matrix4f projection) {
        if (this.visor$projectionRing == null) {
            this.visor$projectionRing =
                    new ProjectionMatrixBuffer[VISOR_PROJECTION_RING_SIZE];
        }
        int slot = this.visor$projectionRingIndex;
        this.visor$projectionRingIndex = (slot + 1) % VISOR_PROJECTION_RING_SIZE;
        ProjectionMatrixBuffer buffer = this.visor$projectionRing[slot];
        if (buffer == null) {
            buffer = new ProjectionMatrixBuffer("visor vr projection " + slot);
            this.visor$projectionRing[slot] = buffer;
        }
        return buffer.getBuffer(projection);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void visor$closeProjectionBuffer(CallbackInfo ci) {
        if (this.visor$projectionRing == null) {
            return;
        }
        for (int i = 0; i < this.visor$projectionRing.length; i++) {
            if (this.visor$projectionRing[i] != null) {
                this.visor$projectionRing[i].close();
                this.visor$projectionRing[i] = null;
            }
        }
        this.visor$projectionRing = null;
        this.visor$projectionRingIndex = 0;
    }

    @Override
    @Unique
    public void visor$setupClipPlanes() {
        this.visor$renderDistance = (float) (this.minecraft.options.getEffectiveRenderDistance() * 16);
        float depthFar = Math.max(
                this.visor$renderDistance * 4.0F,
                this.minecraft.options.cloudRange().get() * 16
        );
        this.visor$farClipPlane = Math.max(
                this.visor$renderDistance + 1024.0F,
                depthFar
        );
    }

    @Override
    @Unique
    public float visor$getNearClipPlane() {
        return this.visor$nearClipPlane;
    }

    @Override
    @Unique
    public float visor$getFarClipPlane() {
        return this.visor$farClipPlane;
    }

    @Override
    @Unique
    public Matrix4f visor$getThirdPersonProjection() {
        return visor$thirdPersonProjection;
    }

    // ------------------------------------------------------------------
    // picking
    // ------------------------------------------------------------------

    /**
     * Hand-aware replacement of the vanilla raycast; called once per frame from
     * MinecraftMixin around Minecraft.pick(F).
     */
    @Override
    @Unique
    public void visor$pick(float partialTick, VanillaPick original) {
        if (this.minecraft.gui.screen() != null && this.minecraft.hitResult != null) {
            return;
        } else if (this.minecraft.getCameraEntity() == null) {
            if (this.minecraft.player != null) {
                this.minecraft.hitResult = BlockHitResult.miss(this.minecraft.player.position(),
                        this.minecraft.player.getDirection(), this.minecraft.player.blockPosition());
            } else {
                this.minecraft.hitResult = BlockHitResult.miss(Vec3.ZERO, Direction.UP, BlockPos.ZERO);
            }
            return;
        }
        HandType activeHand = ClientContext.localPlayer.getActiveHand();
        visor$pickWithHand(activeHand, partialTick, original);
        HandType otherHand = activeHand.opposite();
        if (VRServerSettings.isTwoHandedVR()
                && ClientContext.rawPoseHandler.getControllerData(otherHand).isTracking()) {
            HitResult activeHit = this.minecraft.hitResult;
            Entity activePickEntity = this.minecraft.crosshairPickEntity;
            visor$pickWithHand(otherHand, partialTick, original);
            this.minecraft.hitResult = activeHit;
            this.minecraft.crosshairPickEntity = activePickEntity;
            this.visor$crossVec = visor$handCrossVec[activeHand.ordinal()];
        } else {
            visor$handHitResult[otherHand.ordinal()] = null;
            visor$handCrossVec[otherHand.ordinal()] = null;
            visor$handPickEntity[otherHand.ordinal()] = null;
        }
    }

    @Unique
    private void visor$pickWithHand(HandType hand, float partialTick, VanillaPick original) {
        visor$pickingHand = hand;
        VRPose handPose = ClientContext.localPlayer
                .getPoseData(PlayerPoseType.RENDER)
                .getHand(hand);
        AABB originalBB = this.minecraft.getCameraEntity().getBoundingBox();
        this.visor$cacheCameraEntity(this.minecraft.getCameraEntity());
        this.visor$setupCameraEntity(handPose);
        this.minecraft.getCameraEntity().setBoundingBox(originalBB.move(
                this.minecraft.getCameraEntity().getX() - visor$cameraEntityCache.getX(),
                this.minecraft.getCameraEntity().getY() - visor$cameraEntityCache.getY(),
                this.minecraft.getCameraEntity().getZ() - visor$cameraEntityCache.getZ()));
        original.pick(partialTick);
        this.visor$restoreCameraEntity(this.minecraft.getCameraEntity());
        this.minecraft.getCameraEntity().setBoundingBox(originalBB);
        visor$applyPortalAwareBlockRay(handPose);
        HitResult hitResult = this.minecraft.hitResult;
        if (hitResult != null && hitResult.getType() != HitResult.Type.MISS) {
            this.visor$crossVec = hitResult.getLocation();
        } else if (this.minecraft.player != null) {
            this.visor$crossVec = visor$aimedPointAtDistance(
                    handPose,
                    this.minecraft.player.blockInteractionRange()
            );
        }
        visor$handHitResult[hand.ordinal()] = hitResult;
        visor$handCrossVec[hand.ordinal()] = this.visor$crossVec;
        visor$handPickEntity[hand.ordinal()] = this.minecraft.crosshairPickEntity;
        visor$pickingHand = null;
    }

    @Unique
    private void visor$applyPortalAwareBlockRay(VRPose handPose) {
        if (!ImmPortalsCompatHelper.isLoaded()
                || this.minecraft.player == null
                || MC.level == null) {
            return;
        }
        HitResult current = this.minecraft.hitResult;
        if (current != null && current.getType() == HitResult.Type.ENTITY) {
            return;
        }
        HitResult blockHit = visor$pickBlock(
                handPose,
                this.minecraft.player.blockInteractionRange(),
                false
        );
        if (blockHit == null) {
            return;
        }
        this.minecraft.hitResult = blockHit;
        this.minecraft.crosshairPickEntity = null;
    }

    @Override
    @Unique
    public Vec3 visor$getCrossVec() {
        return visor$crossVec;
    }

    @Override
    @Unique
    public Vec3 visor$getCrossVec(HandType hand) {
        return visor$handCrossVec[hand.ordinal()];
    }

    @Override
    @Unique
    public HitResult visor$getHandHitResult(HandType hand) {
        return visor$handHitResult[hand.ordinal()];
    }

    @Override
    @Unique
    public void visor$applyHandPick(HandType hand) {
        HitResult hitResult = visor$handHitResult[hand.ordinal()];
        Vec3 crossVec = visor$handCrossVec[hand.ordinal()];
        if (hitResult == null || crossVec == null) {
            this.minecraft.pick(1.0f);
            return;
        }
        this.minecraft.hitResult = hitResult;
        this.minecraft.crosshairPickEntity = visor$handPickEntity[hand.ordinal()];
        this.visor$crossVec = crossVec;
    }

    @Unique
    public Vec3 visor$aimedPointAtDistance(VRPose vrPose,
                                           double distance) {
        var dir = vrPose.getDirection();
        return new Vec3(vrPose
                .getPosition().add(
                        dir.x() * (float) distance,
                        dir.y() * (float) distance,
                        dir.z() * (float) distance,
                        new Vector3f()
                )
        );
    }

    @Unique
    public HitResult visor$pickBlock(VRPose vrPose,
                                     double blockReachDistance,
                                     boolean fluid
    ) {
        return ImmPortalsCompatHelper.pickBlock(MC.level, vrPose, blockReachDistance, fluid, MC.player);
    }

    // ------------------------------------------------------------------
    // world pass bracket (camera entity relocation + per pass overlay status)
    // ------------------------------------------------------------------

    /**
     * 1.21.11 did this from the pick() call at the top of renderLevel and undid it at its tail.
     * 26.1 extracts the level before renderLevel runs, so the camera entity has to sit at the
     * VR camera for the whole update/extract/render sequence of a pass; VisorScene brackets it.
     */
    @Override
    @Unique
    public void visor$beginWorldPass(float partialTicks) {
        this.visor$cacheCameraEntity(this.minecraft.getCameraEntity());
        this.visor$setupCameraEntityAsVRCamera();
        this.visor$setupOverlayStatus(partialTicks);
    }

    @Override
    @Unique
    public void visor$endWorldPass() {
        this.visor$restoreCameraEntity(this.minecraft.getCameraEntity());
    }

    @Override
    @Unique
    public void visor$setupCameraEntity(VRPose vrPose) {
        if (this.visor$cameraEntityCached) {
            var position = vrPose.getPosition();
            LivingEntity cameraEntity = (LivingEntity) this.minecraft.getCameraEntity();
            cameraEntity.setPosRaw(position.x(), position.y(), position.z());
            cameraEntity.xOld = position.x();
            cameraEntity.yOld = position.y();
            cameraEntity.zOld = position.z();
            cameraEntity.xo = position.x();
            cameraEntity.yo = position.y();
            cameraEntity.zo = position.z();
            cameraEntity.setXRot(-vrPose.getPitchDegrees());
            cameraEntity.xRotO = cameraEntity.getXRot();
            cameraEntity.setYRot(vrPose.getYawDegrees());
            cameraEntity.yRotO = cameraEntity.getYRot();
            cameraEntity.yHeadRot = cameraEntity.getYRot();
            cameraEntity.yHeadRotO = cameraEntity.getYRot();
            cameraEntity.eyeHeight = 0.0001F;
        }
    }

    @Override
    @Unique
    public void visor$cacheCameraEntity(Entity cameraEntity) {
        if (this.minecraft.getCameraEntity() != null) {
            this.visor$cameraEntityCacheDepth++;
            if (!this.visor$cameraEntityCached) {
                LivingEntity livingEntity = cameraEntity instanceof LivingEntity ent ? ent : null;
                visor$cameraEntityCache = new VRCameraEntityCache(
                        cameraEntity.getX(), cameraEntity.getY(),
                        cameraEntity.getZ(),
                        cameraEntity.xOld, cameraEntity.yOld,
                        cameraEntity.zOld,
                        cameraEntity.xo, cameraEntity.yo,
                        cameraEntity.zo,
                        livingEntity != null ? livingEntity.yHeadRot : cameraEntity.getYRot(),
                        cameraEntity.getXRot(),
                        livingEntity != null ? livingEntity.yHeadRotO : cameraEntity.yRotO,
                        cameraEntity.xRotO,
                        cameraEntity.getEyeHeight()
                );
                this.visor$cameraEntityCached = true;
            }
        }
    }

    @Override
    @Unique
    public void visor$restoreCameraEntity(Entity cameraEntity) {
        if (this.visor$cameraEntityCacheDepth > 0) {
            this.visor$cameraEntityCacheDepth--;
        }
        if (cameraEntity != null
                && this.visor$cameraEntityCached
                && this.visor$cameraEntityCacheDepth == 0) {
            visor$cameraEntityCache.apply(cameraEntity);
            this.visor$cameraEntityCached = false;
        }
    }

    @Override
    @Unique
    public void visor$applyCachedCameraEntityPosition(Entity cameraEntity) {
        if (cameraEntity != null && this.visor$cameraEntityCached) {
            this.visor$cameraEntityCache.apply(cameraEntity);
        }
    }

    @Override
    public VRCameraEntityCache visor$getCameraEntityCache() {
        return visor$cameraEntityCache;
    }

    @Override
    @Unique
    public boolean visor$isOnFire() {
        return visor$onfire;
    }

    @Override
    @Unique
    public boolean visor$isInBlock() {
        return visor$inBlock;
    }

    @Override
    @Unique
    public float visor$getBlockProximity() {
        return visor$blockProximity;
    }

    @Unique
    private void visor$setupOverlayStatus(float partialTicks) {
        this.visor$inBlock = false;
        this.visor$blockProximity = 0.0f;
        this.visor$onfire = false;
        if (minecraft.player == null
                || minecraft.player.isSpectator()
                || !minecraft.player.isAlive()
                || VRRenderState.getSceneType().isMainMenu()) {
            return;
        }
        if (this.minecraft.level != this.minecraft.player.level()) {
            return;
        }
        VRRenderPass renderPass = VRRenderState.getRenderPass();
        if (renderPass == null) {
            return;
        }
        var cameraPos = RenderPoseHelper.getCameraPosition(
                renderPass,
                ClientContext.localPlayer.getPoseData(PlayerPoseType.RENDER)
        );
        float inBlockEffectStart = 0.3f;
        float distance = RenderHelper.distanceToNearestSolidBlockSurface(
                new Vec3((Vector3f) cameraPos),
                inBlockEffectStart
        );
        this.visor$blockProximity = Math.max(
                0.0f,
                1.0f - distance / inBlockEffectStart
        );
        this.visor$inBlock = distance < visor$nearClipPlane * 2.0f;
        this.visor$onfire = VRRenderState.getRenderPass() != VRRenderPass.THIRD_PERSON
                && this.minecraft.player.isOnFire()
                && !ModLoader.get().renderFireOverlay(
                this.minecraft.player, new PoseStack()
        );
    }
}
