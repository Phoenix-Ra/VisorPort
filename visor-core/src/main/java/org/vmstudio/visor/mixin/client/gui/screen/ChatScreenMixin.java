package org.vmstudio.visor.mixin.client.gui.screen;



import org.vmstudio.visor.core.client.VisorState;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {
    @Shadow protected EditBox input;

    protected ChatScreenMixin(Component component) {
        super(component);
    }
    /*
     * PORT-1.21.11: Screen#keyPressed(int,int,int) became keyPressed(KeyEvent) - the whole
     * GuiEventListener family takes event records now. The @At is unchanged and still resolves:
     * ChatScreen#keyPressed calls minecraft.setScreen(null) exactly once, in the
     * event.isConfirmation() branch, after handleChatInput has already sent the message.
     * Injecting in front of that call and returning true keeps the message sent, clears the box
     * and leaves the screen open, which is what VR wants - Visor owns the overlay's lifetime.
     *
     * Do NOT also inject into onClose() to "restore" the Escape case. In 1.21.4 this same @At
     * matched TWO setScreen calls in keyPressed, but the Escape one was unreachable dead code:
     * Screen#keyPressed tests `keyCode == 256 && shouldCloseOnEsc()` first and returns true, and
     * ChatScreen never overrides shouldCloseOnEsc(), so super.keyPressed() always won and the
     * `else if (keyCode == 256)` branch below it was never entered. Escape closed the chat screen
     * in 1.21.4 with this mixin active, and must keep doing so - cancelling onClose() would trap
     * the user in a chat screen Escape cannot dismiss. Going 2 matches -> 1 is safe: @Inject's
     * default require is 1.
     */
    @Inject(method = "keyPressed", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setScreenAndShow(Lnet/minecraft/client/gui/screens/Screen;)V"),cancellable = true)
    private void visor$clearInputOnClose(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if(VisorState.get().isNotActive()) return;
        input.setValue("");

        cir.setReturnValue(true);
    }
}
