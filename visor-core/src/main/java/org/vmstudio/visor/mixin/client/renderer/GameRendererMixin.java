package org.vmstudio.visor.mixin.client.renderer;


import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.DeltaTracker;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.vertex.PoseStack;
import me.phoenixra.atumvr.api.enums.EyeType;
import org.vmstudio.visor.api.ModLoader;
import org.vmstudio.visor.api.client.ClientFeature;
import org.vmstudio.visor.api.common.player.VRPose;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.client.render.VRRenderPass;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visor.api.server.VRServerSettings;
import org.vmstudio.visor.compatibility.immportals.ImmPortalsCompatHelper;
import org.vmstudio.visor.core.client.VisorState;
import org.vmstudio.visor.core.client.tasks.types.movement.TaskTeleport;
import org.vmstudio.visor.extensions.client.render.GameRendererExtension;
import org.vmstudio.visor.core.client.render.VRCameraEntityCache;
import org.vmstudio.visor.core.client.render.VRGameCamera;
import org.vmstudio.visor.core.client.render.helpers.RenderHelper;
import org.vmstudio.visor.core.client.render.helpers.RenderPoseHelper;
import org.vmstudio.visor.core.client.render.helpers.VREffectsHelper;
import org.vmstudio.visor.core.client.render.VRRenderState;

import org.vmstudio.visor.api.client.settings.VRClientSettings;
import org.vmstudio.visor.api.client.settings.enums.MirrorMode;
import net.minecraft.util.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;

import org.vmstudio.visor.core.client.ClientContext;
import static org.vmstudio.visor.core.client.VisorClientImpl.MC;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin
        // PORT: ResourceManagerReloadListener dropped - GameRenderer never implemented it
        // (neither 1.21.4 nor 1.21.11) and it has no onResourceManagerReload, so merging the
        // interface made the target claim a contract it cannot honour.
        implements AutoCloseable, GameRendererExtension {
    @Shadow
    @Final
    Minecraft minecraft;

    @Shadow private boolean effectActive;

    @Shadow
    private float renderDistance;
    // PORT-1.21.11: GameRenderer.zoom/zoomX/zoomY are gone - vanilla dropped the zoom
    // transform from getProjectionMatrix, so there is nothing left to mirror into the VR one.
    @Shadow
    private float fovModifier;

    @Shadow
    private float oldFovModifier;

    // PORT-1.21.11: GameRenderer.renderHand is gone - the first person hand render is no longer
    // gated by a field, renderLevel just calls renderItemInHand unconditionally, so the hook that
    // used to suppress vanilla hands in VR has to redirect that call instead.
    @Shadow
    private void renderItemInHand(float partialTick, boolean sleeping, Matrix4f cameraMatrix) {
        throw new AssertionError();
    }

    @Shadow
    public abstract Matrix4f getProjectionMatrix(float fov);

    @Shadow
    public abstract float getDepthFar();

    @Shadow
    private float getFov(Camera mainCamera2, float partialTicks, boolean b) {
        throw new AssertionError();
    }

    @Shadow
    public abstract void pick(float f);

    @Shadow
    private long lastActiveTime;

    @Shadow
    @Final
    private Camera mainCamera;



    @Unique
    public Matrix4f visor$thirdPersonProjection = new Matrix4f();
    @Unique
    public float visor$nearClipPlane = 0.02F;
    @Unique
    private float visor$farClipPlane = 128.0F;
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

    /** Slots in the VR projection ring - see {@link #visor$uploadProjection}. */
    @Unique
    private static final int VISOR_PROJECTION_RING_SIZE = 32;

    @Unique
    private PerspectiveProjectionMatrixBuffer[] visor$projectionRing;

    @Unique
    private int visor$projectionRingIndex;

    @Unique
    public VRCameraEntityCache visor$cameraEntityCache = new VRCameraEntityCache();
    @Unique
    private boolean visor$cameraEntityCached;
    @Unique
    private int visor$cameraEntityCacheDepth;



    /* ******************* *\
  //--------RENDERING--------\\
    \* ******************* */

    /**
     * Cancels GUI rendering for VRWorld stage and render VR main menu room.
     * <p>
     * PORT-1.21.11: the old anchor was "getWindow() ordinal 6", the {@code Window window =
     * getWindow()} load in front of the GUI ortho setup. render() only calls getWindow() six
     * times now (ordinals 0-5) and there is no ortho setup left, so the world/GUI boundary is
     * anchored on {@code fogRenderer.endFrame()} instead - the single unconditional call that
     * closes the world section. Cancelling right after it skips the depth clear, the
     * GuiRenderState reset and the whole GUI extraction/replay, exactly as cancelling in front
     * of {@code RenderSystem.clear(256)} used to.
     */
    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V", shift = Shift.AFTER), method = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V", cancellable = true)
    public void visor$onRenderGUI(DeltaTracker deltaTracker, boolean renderWorldIn, CallbackInfo info) {

        if (VRRenderState.getPhase().isNotVRWorld()) {
            // Proceed rendering GUI for Vanilla and VRGui stage
            return;
        }

        info.cancel();


        // Render Main Menu View
        if (VRRenderState.getSceneType().isMainMenu()) {

            GL11.glDisable(GL11.GL_STENCIL_TEST);

            PoseStack poseStack = new PoseStack();
            //render VR main menu
            ClientContext.decorationRenderer.renderMainMenu(
                    poseStack,
                    deltaTracker.getGameTimeDeltaPartialTick(false)
            );
        }
    }

    @Unique
    private boolean visor$isVRGuiVisible;

    @Override
    public boolean visor$isVRGuiVisible(){
        return visor$isVRGuiVisible;
    }

    @Override
    public void visor$setVRGuiVisible(boolean flag){
        visor$isVRGuiVisible = flag;
    }

    /**
     * Draw GUI only after first level render
     * <p>
     * PORT-1.21.11: same argument, new anchor. {@code renderWorldIn} still gates both the world
     * block and the {@code gui.render(...)} extraction; {@code guiRenderState.reset()} is the
     * first instruction of the GUI half, so overwriting the argument there still lets the level
     * render and still gates the GUI.
     */
    @ModifyVariable(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/state/GuiRenderState;reset()V"), method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", ordinal = 0, argsOnly = true)
    private boolean visor$renderGui(boolean doRender) {
        if (VRRenderState.getPhase().isVanilla()) {
            return doRender;
        }
        return visor$isVRGuiVisible();
    }

    /**
     * If no crosshair rendered,
     * don't render block outline as well
     * @param cir
     */
    @Inject(at = @At("HEAD"), method = "shouldRenderBlockOutline", cancellable = true)
    public void visor$shouldDrawBlockOutline(CallbackInfoReturnable<Boolean> cir) {
        if (VRRenderState.getPhase().isVRWorld()) {
            cir.setReturnValue(
                    ClientContext.visor.isFeatureEnabled(ClientFeature.AIM_EFFECTS)
            );
        }
    }



    /* **************** *\
      //--------CAMERA--------\\
        \* **************** */
    @Redirect(method = "<init>", at = @At(value = "NEW", target = "net/minecraft/client/Camera"))
    public Camera visor$replaceCamera() {
        return new VRGameCamera();
    }

    @Inject(at = @At("HEAD"), method = "getFov(Lnet/minecraft/client/Camera;FZ)F", cancellable = true)
    public void visor$fov(Camera camera, float f, boolean bl, CallbackInfoReturnable<Float> info) {
        if (VisorState.get().isActive() && VRRenderState.getSceneType().isMainMenu()) {
            info.setReturnValue(Float.valueOf(this.minecraft.options.fov().get()));
        }
    }

    @Inject(at = @At("HEAD"), method = "getProjectionMatrix(F)Lorg/joml/Matrix4f;", cancellable = true)
    public void visor$projection(float d, CallbackInfoReturnable<Matrix4f> info) {
        if (VisorState.get().isNotActive()) {
            return;
        }
        PoseStack posestack = new PoseStack();
        visor$setupClipPlanes();
        ClientContext.renderer.updateProjection();

        VRRenderPass renderPass = VRRenderState.getRenderPass();
        if(renderPass == VRRenderPass.EYE_LEFT){
            posestack.mulPose(
                    ClientContext.renderer.getEyeProjection(EyeType.LEFT)
            );
            info.setReturnValue(
                    posestack.last().pose()
            );
            return;
        }
        if (renderPass == VRRenderPass.EYE_RIGHT) {
            posestack.mulPose(
                    ClientContext.renderer.getEyeProjection(EyeType.RIGHT)
            );
            info.setReturnValue(posestack.last().pose());
            return;
        }
        if (renderPass == VRRenderPass.THIRD_PERSON) {
            if (VRClientSettings.getMirrorMode() == MirrorMode.MIXED_REALITY) {
                posestack.mulPose(
                        new Matrix4f().setPerspective(
                                VRClientSettings.getMixedRealityFov() * 0.01745329238474369F,
                                VRClientSettings.getMixedRealityAspectRatio(), this.visor$nearClipPlane,
                                this.visor$farClipPlane
                        )
                );
            }else {
                posestack.mulPose(
                        new Matrix4f().setPerspective(
                                VRClientSettings.getThirdPersonFov() * 0.01745329238474369F,
                                (float) this.minecraft.getWindow().getScreenWidth()
                                        / (float) this.minecraft.getWindow().getScreenHeight(),
                                this.visor$nearClipPlane, this.visor$farClipPlane
                        )
                );
            }
            this.visor$thirdPersonProjection = new Matrix4f(posestack.last().pose());
            info.setReturnValue(posestack.last().pose());
            return;
        }

        posestack.mulPose(
                new Matrix4f()
                        .setPerspective(
                                (float) d * Mth.DEG_TO_RAD,
                                (float) this.minecraft.getWindow().getScreenWidth()
                                        / (float) this.minecraft.getWindow().getScreenHeight(),
                                this.visor$nearClipPlane,
                                this.visor$farClipPlane
                        )
        );

        info.setReturnValue(posestack.last().pose());
    }

    /**
     * Rebinds the VR projection once the world section of the frame is over.
     * <p>
     * PORT-1.21.11: {@code RenderSystem.viewport} does not exist any more - RenderSystem has no
     * viewport member at all - so the old anchor located zero callbacks. The rebind is anchored
     * in front of {@code fogRenderer.endFrame()}, i.e. still at the end of the world section and
     * still ahead of {@link #visor$onRenderGUI}, which draws the VR main menu room with whatever
     * projection is bound here. It is needed more than before: renderLevel now ends by pushing
     * its own {@code hud3dProjectionMatrixBuffer} perspective (built straight from the window
     * size, bypassing getProjectionMatrix), so without this the VR passes would be left holding
     * a flat-screen projection.
     */
    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"), method = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V")
    public void visor$matrix(DeltaTracker deltaTracker, boolean renderWorldIn, CallbackInfo info) {
        if(VisorState.get().isNotActive()) return;
        RenderSystem.setProjectionMatrix(
                visor$uploadProjection(
                        this.getProjectionMatrix(
                                minecraft.options.fov().get()
                        )
                ),
                ProjectionType.PERSPECTIVE
        );
        RenderSystem.getModelViewStack().identity();
    }


    @WrapMethod(method = "pick(F)V")
    private void visor$vrPick(float partialTick, Operation<Void> original) {
        if(VisorState.get().isNotActive()){
            original.call(partialTick);
            return;
        }
        // don't update the hitresult when chat is open
        if (this.minecraft.screen != null && this.minecraft.hitResult != null) {
            return;
        }
        // skip when data not available yet
        else if (this.minecraft.getCameraEntity() == null)
        {
            // some mods don't like it when the hitresult is null, so set it to a miss
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
    private void visor$pickWithHand(HandType hand, float partialTick, Operation<Void> original) {
        visor$pickingHand = hand;

        VRPose handPose = ClientContext.localPlayer
                .getPoseData(PlayerPoseType.RENDER)
                .getHand(hand);

        AABB originalBB = this.minecraft.getCameraEntity().getBoundingBox();
        // set the entity position and view to the controller
        this.visor$cacheCameraEntity(this.minecraft.getCameraEntity());
        this.visor$setupCameraEntity(handPose);
        // move the bounding box as well, this is used for entity hits
        this.minecraft.getCameraEntity().setBoundingBox(originalBB.move(
                this.minecraft.getCameraEntity().getX() - visor$cameraEntityCache.getX(),
                this.minecraft.getCameraEntity().getY() - visor$cameraEntityCache.getY(),
                this.minecraft.getCameraEntity().getZ() - visor$cameraEntityCache.getZ()));

        // call the vanilla method
        original.call(partialTick);

        // restore entity
        this.visor$restoreCameraEntity(this.minecraft.getCameraEntity());
        this.minecraft.getCameraEntity().setBoundingBox(originalBB);

        visor$applyPortalAwareBlockRay(handPose);

        HitResult hitResult = this.minecraft.hitResult;
        if (hitResult != null && hitResult.getType() != HitResult.Type.MISS) {
            // includes entity hits found by the vanilla trace
            this.visor$crossVec = hitResult.getLocation();
        } else if (this.minecraft.player != null) {
            // the ray missed: aim the crosshair at the far end of the reach instead
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

    /**
     * Re-runs the block leg of the pick through {@link ImmPortalsCompatHelper} so a controller
     * aimed through a portal hits what is on the far side.
     * <p>
     * PORT-1.21.11: the ray trace itself moved out of GameRenderer entirely - {@code pick(F)V}
     * now just calls {@code LocalPlayer.raycastHitResult}, and the eye position / view vector /
     * {@code Entity.pick} call that Visor used to override all live in the private static
     * {@code LocalPlayer.pick(Entity,DDF)}. Nothing inside GameRenderer is left to inject into,
     * so the substitution happens here, on the finished result: the entity leg keeps whatever
     * vanilla found (its ray already starts at the hand, because
     * {@link #visor$setupCameraEntity} posed the camera entity), and only a non-entity result is
     * replaced. Without Immersive Portals the helper would only repeat the same
     * {@code level.clip(OUTLINE, NONE)} vanilla already ran from the same origin, so the whole
     * thing is skipped there rather than paying for a second trace per hand per frame.
     */
    @Unique
    private void visor$applyPortalAwareBlockRay(VRPose handPose) {
        if (!ImmPortalsCompatHelper.isLoaded()
                || this.minecraft.player == null
                || MC.level == null) {
            return;
        }
        HitResult current = this.minecraft.hitResult;
        if (current != null && current.getType() == HitResult.Type.ENTITY) {
            // vanilla already rejected every block closer than this entity
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

    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;pick(F)V"), method = "renderLevel")
    public void visor$pickAndSetupCamera(GameRenderer g, float pPartialTicks) {
        if (VRRenderState.getPhase().isVanilla()) {
            g.pick(pPartialTicks);
            return;
        }
        if (VRRenderState.getRenderPass() == VRRenderPass.worldUpdater()) {
            this.pick(pPartialTicks);

            if(MC.screen == null){
                TaskTeleport.updateTeleportDestination(MC.player);
            }
        }

        this.visor$cacheCameraEntity(this.minecraft.getCameraEntity());
        this.visor$setupCameraEntityAsVRCamera();
        this.visor$setupOverlayStatus(pPartialTicks);
    }

    @Inject(at = @At(value = "TAIL"), method = "renderLevel")
    public void visor$restoreCamera(DeltaTracker deltaTracker, CallbackInfo i) {
        if(VRRenderState.getPhase().isNotVanilla()) {
            this.visor$restoreCameraEntity(
                    this.minecraft.getCameraEntity()
            );
        }
    }


    /* ********************* *\
  //--------RAY TRACING--------\\
    \* ********************* */
    // PORT-1.21.11: visor$pickPos / visor$pickDirection / visor$pickBlockWithHand used to sit
    // inside GameRenderer#pick(Entity,DDF)HitResult, overriding the ray origin, the ray
    // direction and the block trace. That overload is gone from GameRenderer: pick(F)V delegates
    // to LocalPlayer#raycastHitResult, which runs the trace in the private static
    // LocalPlayer#pick(Entity,DDF). Leaving the injectors would have thrown
    // InvalidInjectionException at start-up. Origin and direction are now exact because
    // visor$setupCameraEntity poses the camera entity the trace reads from (including yRotO, so
    // the partial-tick lerp no longer drags the ray towards the body yaw); the portal-aware
    // block trace and the crosshair fallback moved into visor$pickWithHand /
    // visor$applyPortalAwareBlockRay.


    /* ******************************* *\
      //--------DISABLE VANILLA STUFF--------\\
        \* ******************************* */
    @Redirect(at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/GameRenderer;effectActive:Z"), method = "render")
    public boolean visor$noPostEffectOnThirdPerson(GameRenderer instance) {
        return this.effectActive && VRRenderState.getRenderPass() != VRRenderPass.THIRD_PERSON;
    }

    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isWindowActive()Z"), method = "render")
    public boolean visor$noPauseGameIfWindowNotFocused(Minecraft instance) {
        return VisorState.get().isActive() || instance.isWindowActive();
    }


    @Inject(at = @At("HEAD"), method = "tickFov", cancellable = true)
    public void visor$noFOVchangeInVR(CallbackInfo ci) {
        if(VRRenderState.getPhase().isNotVanilla()) {
            this.oldFovModifier = this.fovModifier = 1.0f;
            ci.cancel();
        }
    }

    @Inject(at = @At("HEAD"), method = "takeAutoScreenshot", cancellable = true)
    public void visor$noScreenshotInMenu(Path path, CallbackInfo ci) {
        if (VisorState.get().isActive() && VRRenderState.getSceneType().isMainMenu()) {
            ci.cancel();
        }
    }

    @Inject(at = @At("HEAD"), method = "bobHurt", cancellable = true)
    public void visor$noBobHurt(PoseStack poseStack,
                                float f,
                                CallbackInfo ci) {
        if(VRRenderState.getPhase().isNotVanilla()) {
            ci.cancel();
        }
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    public void visor$noBobView(PoseStack matrixStack,
                                float f,
                                CallbackInfo ci) {
        if(VRRenderState.getPhase().isNotVanilla()) {
            ci.cancel();
        }
    }



    /**
     * PORT-1.21.11: {@code renderLevel} used to guard the first person hand render with
     * {@code if (this.renderHand)}, which this hook redirected. The field is gone and the call is
     * unconditional now, so the call itself is redirected instead. Vanilla's own suppression
     * (panoramic screenshots) moved inside {@code renderItemInHand}, which returns early on
     * {@code isPanoramicMode()} before doing anything else, so nothing is lost by dropping the
     * old {@code && renderHand}.
     * <p>
     * PORT-1.21.11: this covers only HALF of what the old hook did. 1.21.4 was
     * {@code if (this.renderHand) { RenderSystem.clear(256); this.renderItemInHand(...); }} - the
     * depth clear sat inside the guarded block, so redirecting the field suppressed it too. In
     * 1.21.11 the clear moved out in front of the call and is unconditional, so it needs its own
     * hook: {@link #visor$noVanillaHandDepthClear}.
     */
    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(FZLorg/joml/Matrix4f;)V"), method = "renderLevel")
    public void visor$noVanillaHands(GameRenderer instance, float partialTick, boolean sleeping, Matrix4f cameraMatrix) {
        if (VRRenderState.isSpectatedVRView(minecraft.getCameraEntity())) {
            return;
        }
        if (VRRenderState.getPhase().isVanilla()) {
            this.renderItemInHand(partialTick, sleeping, cameraMatrix);
        }
    }

    /**
     * The other half of the old {@code renderHand} redirect. 1.21.4 ran
     * {@code RenderSystem.clear(256)} inside {@code if (this.renderHand)}; 1.21.11 runs
     * {@code RenderSystem.getDevice().createCommandEncoder()
     *   .clearDepthTexture(getMainRenderTarget().getDepthTexture(), 1.0)} unconditionally, one
     * instruction ahead of the hand render. Left alone it wipes the world depth of every VR eye
     * pass right after {@code levelRenderer.renderLevel}.
     * <p>
     * No {@code ordinal}: {@code clearDepthTexture} occurs exactly once inside {@code renderLevel}
     * (the other one in this class is in {@code render}, which {@code method = "renderLevel"}
     * excludes). An ordinal that later goes stale is how these hooks rot silently.
     */
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

    /**
     * The third piece of what the old {@code renderHand} redirect suppressed. In 1.21.4 the
     * screen-space overlays (fire, underwater, the inside-a-block texture) were drawn from
     * {@code renderItemInHand}, so cutting the hand render cut them too. In 1.21.11 the call
     * moved out into {@code renderLevel}, after the hand render and under the same flat hud3d
     * projection - left alone it stamps those overlays across each VR eye as a screen quad.
     * Visor draws its own versions (GameEffectOnFire, the in-block vignette), so the vanilla
     * call runs only for the flat-screen phases, exactly as before. The item activation
     * animation that also lives in renderScreenEffect is driven by Visor separately, through
     * {@code GameEffectVanilla}.
     */
    @Redirect(at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;renderScreenEffect(ZFLnet/minecraft/client/renderer/SubmitNodeCollector;)V"),
            method = "renderLevel")
    public void visor$noVanillaScreenEffects(ScreenEffectRenderer instance, boolean sleeping,
                                             float partialTick, SubmitNodeCollector collector) {
        if (VRRenderState.isSpectatedVRView(minecraft.getCameraEntity())) {
            return;
        }
        if (VRRenderState.getPhase().isVanilla()) {
            instance.renderScreenEffect(sleeping, partialTick, collector);
        }
    }

    @Inject(at = @At("TAIL"), method = "renderLevel")
    public void visor$disableStencil(DeltaTracker deltaTracker, CallbackInfo ci) {
        if(VRRenderState.getPhase().isNotVanilla()) {
            VREffectsHelper.disableStencilTest();
        }
    }


    /* ************** *\
  //--------MISC--------\\
    \* ************** */

    //ITEM ACTIVATION ANIMATION
    // PORT-1.21.11: the animation is no longer GameRenderer's. It lives on
    // ScreenEffectRenderer#renderItemActivationAnimation(PoseStack,float,SubmitNodeCollector),
    // together with itemActivationTicks/Item/OffX/OffY, and nothing in vanilla calls it any more
    // - the method is dead code in the jar. So all three hooks that used to sit here are gone:
    //   * visor$noItemActivationAnimInGUI suppressed vanilla's GUI-space call from render(); that
    //     call site no longer exists, so there is nothing left to suppress.
    //   * visor$noScaleItem / visor$noItemTranslate re-anchored the animation onto the VR camera
    //     by rewriting the PoseStack.translate/scale calls inside the method body. The body still
    //     performs that GUI-space translate/scale, but it belongs to another class now, so those
    //     redirects have to be re-declared in a ScreenEffectRenderer mixin - see the report.
    // Visor drives the animation itself from GameEffectVanilla, through the two widened members
    // in visor.accesswidener.
    //--

    /**
     * Only process this when rendering vanilla
     * or VR camera that is a worldUpdater
     */
    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;pauseGame(Z)V"), method = "render")
    public void visor$pauseOncePerFrame(Minecraft instance, boolean bl) {
        if (VisorState.get().isNotActive() || VRRenderState.getRenderPass() == VRRenderPass.worldUpdater()) {
            instance.pauseGame(bl);
        }
    }

    /**
     * Only process this when rendering vanilla
     * or VR camera that is a worldUpdater
     */
    // 1.21.11: Util moved to net.minecraft.util, so the redirect descriptor moved with it
    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMillis()J"), method = "render")
    public long visor$useActiveTimeOncePerFrame() {
        if (VisorState.get().isNotActive() || VRRenderState.getRenderPass() == VRRenderPass.worldUpdater()) {
            return Util.getMillis();
        } else {
            return this.lastActiveTime;
        }
    }





    /* ************************ *\
  //--------PUBLIC METHODS--------\\
    \* ************************ */
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
            // PORT-1.21.11: yRotO has to follow too. Entity#getViewVector lerps yRotO -> yRot,
            // and the hand ray trace reads that view vector directly now that Visor no longer
            // overrides the direction local inside the (moved) pick overload - leaving the stale
            // body yaw here would swing the ray by the partial tick.
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
    @Unique
    public void visor$setupClipPlanes() {
        this.renderDistance = (float) (this.minecraft.options.getEffectiveRenderDistance() * 16);
        // honor vanilla's depthFar (and far-distance mods extending it)
        this.visor$farClipPlane = Math.max(
                this.renderDistance + 1024.0F,
                this.getDepthFar()
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


    @Override
    @Unique
    public void visor$resetProjectionMatrix(float partialTicks) {
        RenderSystem.setProjectionMatrix(
                visor$uploadProjection(
                        this.getProjectionMatrix(this.getFov(this.mainCamera, partialTicks, true))
                ),
                ProjectionType.PERSPECTIVE
        );
    }

    /**
     * Writes {@code projection} into a fresh slot of the VR projection ring and hands back its
     * slice.
     * <p>
     * PORT-1.21.11: a projection is a pointer now, not a value, and this cannot be one buffer.
     * {@code RenderSystem.setProjectionMatrix} took a {@code Matrix4f} in 1.21.4 and copied it
     * into a CPU field, so overwriting the projection as often as VR likes cost nothing. It
     * takes a {@code GpuBufferSlice} in 1.21.11, and a {@code PerspectiveProjectionMatrixBuffer}
     * owns exactly ONE slot: {@code getBuffer} rewrites that slot in place and returns the same
     * final slice every call. So one instance can only ever hold one projection at a time, and
     * every write invalidates the value that previously-handed-out slices resolve to -
     * {@code RenderSystem.backupProjectionMatrix}/{@code restoreProjectionMatrix} included, since
     * those now save the pointer rather than the matrix.
     * <p>
     * That collides head-on with 1.21.11 drawing being deferred. Visor writes a projection from
     * four call sites (both overlay passes, both hand passes) several times per eye pass, while
     * the geometry that reads it is sitting in the SubmitNodeStorage waiting for
     * {@code renderAllFeatures()}. With a single slot, whichever projection was written last wins
     * for every pending draw - so geometry rendered correctly when its flush happened to follow
     * its own write, and was drawn through another pass's projection otherwise.
     * <p>
     * A ring gives every write its own slot, so a slice stays valid until the GPU has consumed
     * the draws that reference it. 64 bytes each; the ring is sized well past the number of
     * writes that can be in flight across the eye, mirror and GUI passes of one frame.
     */
    @Unique
    private GpuBufferSlice visor$uploadProjection(Matrix4f projection) {
        if (this.visor$projectionRing == null) {
            this.visor$projectionRing =
                    new PerspectiveProjectionMatrixBuffer[VISOR_PROJECTION_RING_SIZE];
        }
        int slot = this.visor$projectionRingIndex;
        this.visor$projectionRingIndex = (slot + 1) % VISOR_PROJECTION_RING_SIZE;
        PerspectiveProjectionMatrixBuffer buffer = this.visor$projectionRing[slot];
        if (buffer == null) {
            buffer = new PerspectiveProjectionMatrixBuffer("visor vr projection " + slot);
            this.visor$projectionRing[slot] = buffer;
        }
        return buffer.getBuffer(projection);
    }

    /**
     * Releases the VR projection UBO alongside vanilla's own {@code levelProjectionMatrixBuffer},
     * which {@code GameRenderer#close} disposes of on the same line.
     */
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
            this.pick(1.0f);
            return;
        }
        this.minecraft.hitResult = hitResult;
        this.minecraft.crosshairPickEntity = visor$handPickEntity[hand.ordinal()];
        this.visor$crossVec = crossVec;
    }

    @Override
    public VRCameraEntityCache visor$getCameraEntityCache() {
        return visor$cameraEntityCache;
    }

    @Override
    @Unique
    public Matrix4f visor$getThirdPersonProjection() {
        return visor$thirdPersonProjection;
    }


    /* ************************* *\
      //--------UTILITY METHODS--------\\
        \* ************************* */
    @Unique
    private void visor$setupOverlayStatus(float partialTicks) {
        //@TODO add post process for these effects
        this.visor$inBlock = false;
        this.visor$blockProximity = 0.0f;

        this.visor$onfire = false;

        if(minecraft.player.isSpectator()
                || !minecraft.player.isAlive()
                || VRRenderState.getSceneType().isMainMenu()){
            return;
        }
        // fix for immersive portals issue
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

}
