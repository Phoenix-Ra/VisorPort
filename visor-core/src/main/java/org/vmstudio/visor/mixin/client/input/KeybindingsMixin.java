package org.vmstudio.visor.mixin.client.input;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.screens.ChatScreen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.api.client.VRPlayMode;
import org.vmstudio.visor.api.client.input.InputHelper;
import org.vmstudio.visor.api.client.render.VRSceneType;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.VisorState;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;


import java.io.File;
import java.util.function.Consumer;


@Mixin(KeyboardHandler.class)
public class KeybindingsMixin {

    /*
     * PORT-1.21.11: KeyboardHandler#keyPress(long, int, int, int, int) became
     * keyPress(long window, int action, KeyEvent event) - key, scancode and modifiers are packed
     * into the KeyEvent record, and the surviving int is the GLFW action. The @At is unchanged:
     * debugCrashKeyTime is still read first thing inside keyPress, so ordinal 0 still lands
     * before vanilla dispatches the key to the screen or the keybinds.
     */
    @Inject(method = "keyPress", at = @At(value = "FIELD", target = "Lnet/minecraft/client/KeyboardHandler;debugCrashKeyTime:J", ordinal = 0), cancellable = true)
    private void visor$handleVRHotKeys(long windowPointer,
                                    int action,
                                    KeyEvent event,
                                    CallbackInfo ci) {
        if (action == GLFW.GLFW_PRESS) {
            if (InputHelper.isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL)) {
                if (event.key() == GLFW.GLFW_KEY_F7
                        && VisorAPI.clientState().sceneType() == VRSceneType.MAIN_MENU) {
                    VRPlayMode mode = VisorAPI.clientState().playMode().next();
                    VisorState.setVrPlayMode(mode);
                    ClientContext.settingsManager.saveOptions();
                    ci.cancel();
                }
            }
        }
    }
    // PORT-26.2: the screenshot hotkey no longer runs in KeyboardHandler.keyPress - it moved to
    // Minecraft.handleGlobalKeyPress, and the overload it calls is grab(Minecraft, boolean).
    // The redirect moved to MinecraftMixin with it.
}
