package org.vmstudio.visor.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.vmstudio.visor.api.server.VRServerSettings;
import org.vmstudio.visor.core.client.player.VRClientPlayers;
import org.vmstudio.visor.core.client.render.context.PreRenderContext;
import org.vmstudio.visor.core.client.render.context.RenderContext;
import org.vmstudio.visor.api.client.input.HandAction;
import org.vmstudio.visor.core.client.gui.overlays.builtin.VROverlayGameScreen;
import org.vmstudio.visor.core.client.tasks.types.TaskRoomConsume;
import org.vmstudio.visor.core.client.tasks.types.movement.vehicle.TaskVehicle;
import org.vmstudio.visor.extensions.client.MinecraftExtension;
import org.vmstudio.visor.extensions.client.entity.LocalPlayerExtension;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.core.client.settings.VROptionWidgetType;
import java.util.function.BooleanSupplier;
import net.minecraft.client.*;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.vmstudio.visor.extensions.client.render.GameRendererExtension;
import org.vmstudio.visor.core.client.tasks.types.movement.TaskTeleport;
import com.mojang.blaze3d.platform.Window;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.vmstudio.visor.core.client.VisorState;

import org.vmstudio.visor.core.client.ClientContext;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin implements MinecraftExtension {


    @Final
    @Shadow
    public Gui gui;


    @Shadow
    public Screen screen;



    @Final
    @Shadow
    private DeltaTracker.Timer deltaTracker;

    @Final
    @Shadow
    public GameRenderer gameRenderer;

    @Shadow
    public ClientLevel level;

    @Shadow
    public RenderTarget mainRenderTarget;

    @Shadow
    public LocalPlayer player;

    @Shadow
    public abstract Entity getCameraEntity();

    @Shadow
    public abstract void tick();




     /* *************************** *\
   //--------VR INITIALIZATION--------\\
     \* *************************** */

    /**
     * Instantiates RenderStageManager with
     * a vanilla main render target.
     * <br>
     * We need it early created
     * and separately from Visor initialization
     *
     * @param overlay s
     * @return s
     */
    @ModifyArg(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setOverlay(Lnet/minecraft/client/gui/screens/Overlay;)V"), method = "<init>", index = 0)
    public Overlay visor$initRenderStageManager(Overlay overlay) {
        VRRenderState.initVanillaTarget((MainTarget) this.mainRenderTarget);

        return overlay;
    }

    @Inject(method = "onGameLoadFinished", at = @At("TAIL"))
    public void visor$onGameLoadFinish(CallbackInfo ci) {
        VisorState.setMinecraftLoaded(true);

    }



     /* ***************** *\
   //--------TICKING--------\\
     \* ***************** */

    /**
     * Pre Ticks Visor right before mc tick() is called
     *
     * @param ci s
     */
    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V"), method = "runTick")
    public void visor$preTick(CallbackInfo ci) {
        if(ClientContext.visor != null) {
            ClientContext.visor.preTickVR();
        }
    }

    /**
     * Ticks Visor (before mc tick methods called)
     *
     * @param info s
     */
    @Inject(at = @At("HEAD"), method = "tick()V")
    public void visor$tick(CallbackInfo info) {
        if(ClientContext.visor != null) {
            ClientContext.visor.tickVR();
        }
    }

    /**
     * Post Ticks Visor right after mc tick() is called
     *
     * @param ci s
     */
    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V", shift = Shift.AFTER), method = "runTick")
    public void visor$postTick(CallbackInfo ci) {
        if(ClientContext.visor != null) {
            ClientContext.visor.postTickVR();
        }
    }



     /* ******************* *\
   //--------RENDERING--------\\
     \* ******************* */

    /**
     * Calls pre render task at the beginning of a frame
     *
     * @param tick     s
     * @param callback s
     */
    @Inject(at = @At("HEAD"), method = "runTick(Z)V")
    public void visor$runVR(boolean tick, CallbackInfo callback) {
        visor$startGameLoop();
        visor$frameStartedByRunTick = true;
    }

    /**
     * Set by {@link #visor$runVR} and consumed by the next {@code renderFrame}: whether the VR
     * frame for that render was already started at the top of the game loop.
     */
    @Unique
    private boolean visor$frameStartedByRunTick;

    @Unique
    private void visor$startGameLoop() {
        VisorState.updateState();
        if(ClientContext.visor != null) {
            ClientContext.visor
                    .onGameLoopStart();
        }
    }

    /**
     * PORT-26.1: runTick() no longer renders itself; the whole frame (update / pick / extract /
     * render / present) moved into renderFrame(boolean). The "render" profiler section that
     * used to mark the start of rendering is gone with it, so this hooks the head of renderFrame.
     * <p>
     * PORT-26.1: renderFrame(false) is also called on its own, without runTick, by doWorldLoad's
     * "waitForServer" loop, by the disconnect wait loop and by setScreenAndShow. On 1.21.11 all
     * three went through runTick(false), so {@link #visor$runVR} started an OpenXR frame for
     * every frame drawn. Without it, nothing begins a frame for the loading screen: the runtime
     * gets no frames for the whole world load, xrWaitFrame stops pacing the loop, XrRenderer
     * skips the scene because no frame is in flight, and the model-view push made by
     * onGameRenderStart was never popped - "max stack size of 16 reached" after sixteen loading
     * frames. So the game-loop start runs here whenever runTick did not already run it for this
     * frame. It stays on runTick for the normal loop so input and poses are still polled before
     * the ticks, as before.
     */
    @Inject(method = "renderFrame", at = @At("HEAD"))
    public void visor$preRenderVR(boolean tick, CallbackInfo callback) {
        if (!visor$frameStartedByRunTick) {
            visor$startGameLoop();
        }
        visor$frameStartedByRunTick = false;

        if(ClientContext.visor != null) {
            ClientContext.visor
                    .preRenderVR(
                            new PreRenderContext(
                                    Profiler.get(), tick,
                                    visor$getPartialTicks()
                            )
                    );
        }
    }

    /**
     * PORT-26.1: the frame is three GameRenderer calls now - update(), extract(), render() - each
     * taking the same advanceGameTime flag that render(DeltaTracker, boolean) took alone. The VR
     * GUI phase starts right before the first of them and turns the flag off for all three, so
     * the vanilla frame only produces the GUI (into Visor's GUI target); the eye/mirror passes
     * render the level themselves from visor$renderVR.
     */
    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;Z)V"), method = "renderFrame")
    private void visor$startVRGuiPhase(boolean advanceGameTime, CallbackInfo ci) {
        if (VisorState.get().isActive()) {
            ClientContext.renderer.onGameRenderStart(advanceGameTime);
        }
    }

    @ModifyArg(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;Z)V"), method = "renderFrame", index = 1)
    private boolean visor$guiPhaseUpdate(boolean advanceGameTime) {
        return visor$vanillaFrameRendersLevel(advanceGameTime);
    }

    @ModifyArg(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"), method = "renderFrame", index = 1)
    private boolean visor$guiPhaseExtract(boolean advanceGameTime) {
        return visor$vanillaFrameRendersLevel(advanceGameTime);
    }

    @ModifyArg(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"), method = "renderFrame", index = 1)
    private boolean visor$guiPhaseRender(boolean advanceGameTime) {
        return visor$vanillaFrameRendersLevel(advanceGameTime);
    }

    @Unique
    private boolean visor$vanillaFrameRendersLevel(boolean advanceGameTime) {
        if (VisorState.get().isActive() && VRRenderState.getPhase().isVRGui()) {
            return false;
        }
        return advanceGameTime;
    }

    /**
     * PORT-26.1: the VR passes run after the vanilla frame finished rendering the GUI and before
     * the result is presented; "blit" became the "present" section of renderFrame. The first
     * long local is still the frame start timestamp. Minecraft.noRender no longer exists.
     */
    @Inject(at = @At(value = "CONSTANT", args = "stringValue=present"), method = "renderFrame")
    public void visor$renderVR(boolean renderLevel, CallbackInfo ci, @Local(ordinal = 0) long nanoTime) {
        if (ClientContext.visor != null) {
            ClientContext.visor
                    .renderVR(
                            new RenderContext(
                                    Profiler.get(),
                                    renderLevel,
                                    nanoTime,
                                    visor$getPartialTicks()
                            )
                    );
        }
    }


    /**
     * Blits the render target Visor actually finished the frame on, not the one vanilla saw at
     * the top of it.
     * <p>
     * PORT-1.21.11: {@code runTick} used to read {@code this.mainRenderTarget} again for the
     * final blit, which is what put Visor's mirror on the desktop window - {@code renderVR} runs
     * at the "blit" profiler constant just above, and the mirror phase leaves the mirror target
     * in that field. 1.21.11 hoists the read: {@code getMainRenderTarget()} is now called once
     * before {@code gameRenderer.render} and stashed in a local, and that local is what gets
     * blitted. Two things broke at once. The mirror stopped reaching the screen, and - because
     * {@code createTargets()} runs inside the frame, destroying every VR target and building new
     * ones - the stale local could point at a {@code RenderTarget} whose buffers had since been
     * destroyed, which is the {@code "Can't blit to screen, color texture doesn't exist yet"}
     * crash on the first frame after a target reinit.
     * <p>
     * One call site in {@code runTick}, so no {@code ordinal}.
     * <p>
     * PORT-26.1: the blit lives in {@code renderFrame}'s "present" section now and reads
     * {@code this.mainRenderTarget} at blit time again; the redirect stays for the
     * destroyed-target guard below.
     */
    @Redirect(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen()V"))
    private void visor$blitLiveTarget(RenderTarget capturedAtFrameStart) {
        RenderTarget live = this.mainRenderTarget != null
                ? this.mainRenderTarget
                : capturedAtFrameStart;
        // A target still mid-reinit has no colour attachment; skipping one frame of desktop
        // mirror beats taking down the game.
        if (live.getColorTexture() == null) {
            return;
        }
        live.blitToScreen();
    }


    /**
     * PORT-26.1: resizeDisplay() became resizeGui(); the render target resize it also used to do
     * moved into GameRenderer.render() (see GameRendererMixin#visor$noVanillaResizeInVR).
     */
    @Inject(at = @At("HEAD"), method = "resizeGui")
    void visor$ensurePhaseOnResize(CallbackInfo ci) {
        if (VisorState.get().isInitialized()) {
            if (VisorState.get().isActive()) {
                VRRenderState.startVRGuiPhase();
            } else {
                VRRenderState.startVanillaPhase();
            }
        }
    }

    /**
     * Disables Thread.sleep()
     * call in vanilla when waiting for world to finish loading.
     * <p>
     * FPS has to be handled only by VR related features
     */
    /*
     * PORT-1.21.11: the Thread.sleep(16L) that paced the "waitForServer" loop is gone. doWorldLoad
     * budgets a frame explicitly now -
     *     long l = TimeUnit.SECONDS.toNanos(1L) / 60L;
     *     ...
     *     this.managedBlock(() -> Util.getNanos() > m);
     * - so the 60 FPS cap moved from a sleep into a managedBlock that parks until the budget
     * elapses. Skipping that call is exactly what skipping the sleep used to do; runAllTasks()
     * runs immediately before it, so nothing is left undrained.
     *
     * The old hook carried expect = 0, which is why it did not fail when Thread.sleep disappeared -
     * it silently matched nothing and the FPS cap quietly came back. That is removed on purpose:
     * this injector should be loud the next time vanilla reshapes the loop.
     */
    @WrapOperation(method = "doWorldLoad",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;managedBlock(Ljava/util/function/BooleanSupplier;)V"))
    private void visor$noFPSLimitOnWorldLoad(Minecraft instance, BooleanSupplier until,
                                             Operation<Void> original) {
        if (VisorState.get().isActive()) {
            return;
        }
        original.call(instance, until);
    }


    /**
     * Release data that won't be updating
     * during world load (like input and mb something else)
     */
    @Inject(method = "doWorldLoad", at = @At("HEAD"))
    private void visor$onWorldLoad(CallbackInfo ci) {
        if (VisorState.get().isNotActive()) {
            return;
        }
        try {
            var activeSet = ClientContext.inputManager.getActiveSet();
            if (activeSet != null) {
                activeSet.clear();
            }
        } catch (Throwable ignored) {
            // Don't block world load
        }
    }


     /* ******************* *\
   //--------VR OVERLAYS--------\\
     \* ******************* */

    /**
     * Handles screen changes
     *
     * @param pGuiScreen s
     * @param info       s
     */
    @Inject(at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, target = "Lnet/minecraft/client/Minecraft;screen:Lnet/minecraft/client/gui/screens/Screen;", shift = Shift.BEFORE, ordinal = 0), method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V")
    public void visor$onOpenScreen(Screen pGuiScreen, CallbackInfo info) {
        if (VisorState.get().isNotActive()) return;

        ClientContext.overlayManager
                .getOverlay(VROverlayGameScreen.ID, VROverlayGameScreen.class)
                .onScreenChanged(this.screen, pGuiScreen, true);
    }

    /**
     * Handles overlay changes
     *
     * @param overlay s
     * @param ci      s
     */
    @Inject(at = @At("TAIL"), method = "setOverlay")
    public void visor$onOverlaySet(Overlay overlay, CallbackInfo ci) {
        if (VisorState.get().isNotActive()) return;

        ClientContext.overlayManager
                .getOverlay(VROverlayGameScreen.ID, VROverlayGameScreen.class)
                .onScreenChanged(this.screen, this.screen, true);
    }

    /**
     * Ticks VR overlays right after mc ticked screen
     *
     * @param ci s
     */
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;tick(Z)V"))
    private void visor$tickVrOverlays(CallbackInfo ci) {
        if (VisorState.get().isNotActive()) return;

        if (ClientContext.overlayManager == null) return;
        ClientContext.overlayManager.tick();
    }

      /* *************** *\
    //--------INPUT--------\\
      \* *************** */

    /**
     * Overrides an action performed when
     * pressed "keyTogglePerspective" button
     * <br>
     * So, instead this button changes mirror camera type
     *
     * @param instance   s
     * @param cameraType s
     */
    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;setCameraType(Lnet/minecraft/client/CameraType;)V"), method = "handleKeybinds")
    public void visor$toggleMirrorButton(Options instance, CameraType cameraType) {
        if (VisorState.get().isActive()) {
            ClientContext.settingsManager.nextOptionValue(
                    VROptionWidgetType.MIRROR_MODE.getKey()
            );
        } else {
            instance.setCameraType(cameraType);
        }
    }

    /**
     * Disables last method that can be called when
     * pressed "keyTogglePerspective" button
     *
     * @param instance s
     * @param entity   s
     */
    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;checkEntityPostEffect(Lnet/minecraft/world/entity/Entity;)V"), method = "handleKeybinds")
    public void visor$noTogglePerspectiveAction(GameRenderer instance, Entity entity) {
        if (VisorState.get().isNotActive()) {
            instance.checkEntityPostEffect(entity);
        }
    }




     /* ****************** *\
   //--------VR MOUSE--------\\
     \* ****************** */

    /**
     * Makes mouse always grabbed,
     * since it should not be disabled in VR mode
     *
     * @param instance s
     * @return s
     */
    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"), method = "handleKeybinds")
    public boolean visor$mouseAlwaysGrabbed(MouseHandler instance) {
        return VisorState.get().isActive() || instance.isMouseGrabbed();
    }





     /* **************** *\
   //--------EVENTS--------\\
     \* **************** */

    /**
     * Resets room origin when world changed
     * <p>
     * PORT-1.21.11: setLevel is back to a single ClientLevel parameter - the
     * LevelLoadingScreen.Reason that 1.21.1 added is gone again. An @Inject handler must mirror
     * the target's parameters exactly, so the stale Reason argument was an apply-time crash.
     *
     * @param pLevelClient s
     * @param info         s
     */
    @Inject(at = @At("HEAD"), method = "setLevel")
    public void visor$onLevelChange(ClientLevel pLevelClient, CallbackInfo info) {
        if (VisorState.get().isActive()) {
            ClientContext.localPlayer.setOrigin(
                    0.0f, 0.0f, 0.0f, true
            );
        }
    }

    /* ***************************************** *\
  //--------TWO HANDED VR (OFFHAND SUPPORT)--------\\
    \* ***************************************** */

    @WrapOperation(method = {"continueAttack", "startAttack"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;)V"))
    private void visor$swingArmAttack(LocalPlayer instance, InteractionHand hand, Operation<Void> original) {
        if (VisorState.get().isActive()) {
            ClientContext.handRenderer.setSwingType(HandAction.ATTACK);
            original.call(instance,
                    ClientContext.localPlayer.getActiveHand()
                            .asInteractionHand()
            );
            return;
        }
        original.call(instance, hand);
    }

    @Unique
    private boolean visor$attackKeyDown;

    @Inject(method = "handleKeybinds", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;startAttack()Z"))
    private void visor$markAttackKeyDown(CallbackInfo ci) {
        visor$attackKeyDown = true;
    }

    @WrapWithCondition(method = "continueAttack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;stopDestroyBlock()V"))
    private boolean visor$keepSwingMining(MultiPlayerGameMode instance) {
        boolean allowStop = VisorState.get().isNotActive() || visor$attackKeyDown;
        visor$attackKeyDown = false;
        return allowStop;
    }


    @WrapOperation(
            method = "startAttack",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;"
            )
    )
    private ItemStack visor$getItemInHand(LocalPlayer instance,
                                        InteractionHand hand,
                                        Operation<ItemStack> original) {
        if (VisorState.get().isActive()) {
            return original.call(instance,
                    ClientContext.localPlayer.getActiveHand()
                            .asInteractionHand()
            );
        }
        return original.call(instance, hand);
    }

    @WrapOperation(
            method = "startUseItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/InteractionHand;values()[Lnet/minecraft/world/InteractionHand;"
            )
    )
    private InteractionHand[] visor$useItemOnlyActive(Operation<InteractionHand[]> original) {
        if (VisorState.get().isActive() && VRServerSettings.isTwoHandedVR()) {
            return new InteractionHand[] {
                    ClientContext.localPlayer.getActiveHand().asInteractionHand()
            };
        }
        return original.call();
    }

    @WrapOperation(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;)V"))
    private void visor$swingArmUse(LocalPlayer instance, InteractionHand hand, Operation<Void> original) {
        if (VisorState.get().isActive()) {
            ClientContext.handRenderer.setSwingType(HandAction.USE);
        }
        original.call(instance, hand);
    }

    /* ********************** *\
  //--------ROOM CONSUME--------\\
    \* ********************** */


    @WrapOperation(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;releaseUsingItem(Lnet/minecraft/world/entity/player/Player;)V"))
    private void visor$keepConsume(MultiPlayerGameMode instance, Player player, Operation<Void> original) {
        if (VisorState.get().isActive()
                && TaskRoomConsume.getInstance() != null
                && TaskRoomConsume.getInstance().isGestureConsuming()) {
            return;
        }
        original.call(instance, player);
    }

    /* ************** *\
  //--------MISC--------\\
    \* ************** */

    @Inject(method = "stop", at = @At("HEAD"))
    private void visor$markVrShutdown(CallbackInfo ci) {
        try {
            if (ClientContext.visor == null) {
                return;
            }
            ClientContext.visor.getVrProvider().prepareDestroy();
        } catch (Throwable ignored) {
            // Don't block
        }
    }
    @Inject(method = "close", at = @At("HEAD"))
    private void visor$destroyVrOnClose(CallbackInfo ci) {
        try {
            if (VisorState.get().isInitialized()) {
                VisorState.destroyVR();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }


    @Inject(method = "setCameraEntity", at = @At("HEAD"), cancellable = true)
    private void visor$rideEntity(Entity entity, CallbackInfo ci) {
        var state = VisorState.get();
        if (!state.isInitialized() || entity == null) {
            return;
        }

        if (state.isActive()
                && this.player != null
                && this.player.isSpectator()
                && entity != this.player) {
            ci.cancel(); //cancel spectate entity in VR
            return;
        }

        if (entity != this.getCameraEntity()) {
            // snap to entity, if it changed
            ClientContext.localPlayer.recenterOrigin(entity, true);
        }
        if (entity != this.player) {
            // ride the new camera entity
            TaskVehicle.getInstance().onStartRiding(entity);
        } else {
            TaskVehicle.getInstance().onStopRiding();
        }
    }

    /**
     * PORT-26.1: GameRenderer.pick(F) is now the private Minecraft.pick(F). Vanilla calls it once
     * per tick (with 1.0) and once per frame from renderFrame. In VR the tick-time raycast is
     * skipped as before, and the frame-time one is replaced by the hand-aware pick that used to
     * wrap GameRenderer.pick.
     */
    @WrapOperation(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;pick(F)V"), method = "tick")
    private void visor$noVanillaHitResult(Minecraft instance, float partialTick, Operation<Void> original) {
        if (VisorState.get().isNotActive()) {
            original.call(instance, partialTick);
        }
    }

    @WrapMethod(method = "pick(F)V")
    private void visor$vrPick(float partialTick, Operation<Void> original) {
        if (VisorState.get().isNotActive()) {
            original.call(partialTick);
            return;
        }
        ((GameRendererExtension) this.gameRenderer).visor$pick(
                partialTick,
                tick -> original.call(tick)
        );
        if (this.screen == null) {
            TaskTeleport.updateTeleportDestination(this.player);
        }
    }

    /**
     * PORT-26.1: the pause-on-focus-loss check moved from GameRenderer.render() into
     * Minecraft.pauseIfInactive() and reads Window.isFocused() instead of isWindowActive().
     */
    @Redirect(at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;isFocused()Z"), method = "pauseIfInactive")
    private boolean visor$noPauseGameIfWindowNotFocused(Window instance) {
        return VisorState.get().isActive() || instance.isFocused();
    }

    @Override
    public float visor$getPartialTicks() {
        // 1.21.1: DeltaTracker.Timer handles the pause freeze internally;
        // (false) = pause-respecting partial tick, same as the old
        // "pause ? pausePartialTick : timer.partialTick"
        return this.deltaTracker.getGameTimeDeltaPartialTick(false);
    }
}
