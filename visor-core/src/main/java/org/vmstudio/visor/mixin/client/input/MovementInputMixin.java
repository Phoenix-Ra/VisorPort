package org.vmstudio.visor.mixin.client.input;


import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.VisorState;
import org.vmstudio.visor.core.client.tasks.types.movement.TaskRoomSneak;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;



@Mixin(KeyboardInput.class)
public class MovementInputMixin extends ClientInput {


    /* ****************** *\
  //--------MOVEMENT--------\\
    \* ****************** */


    @Inject(method = "tick", at = @At("TAIL"))
    public void visor$movement(CallbackInfo ci,
                               @Share("climbing") LocalBooleanRef climbing) {
        if (VisorState.get().isNotActive()) {
            return;
        }

        boolean noScreen = Minecraft.getInstance().screen == null;

        boolean jumping = this.keyPresses.jump()
                && noScreen
                && !climbing.get();

        boolean shiftKeyDown = noScreen
                && (TaskRoomSneak.getInstance().getSneakTimer() > 0
                || TaskRoomSneak.getInstance().isSneaking()
                || this.keyPresses.shift());

        // the record is immutable, so the whole thing is rebuilt
        this.keyPresses = new Input(
                this.keyPresses.forward(),
                this.keyPresses.backward(),
                this.keyPresses.left(),
                this.keyPresses.right(),
                jumping,
                shiftKeyDown,
                this.keyPresses.sprint()
        );

        if (ClientContext.localPlayer.isMoving()) {
            var movement = ClientContext.localPlayer.getMovement();
            // the separate leftImpulse/forwardImpulse fields were folded into one Vec2(left, forward).
            // it is written raw, not normalized() like KeyboardInput does for the digital keys -
            // the VR stick is analog and normalizing would pin every nudge to full speed.
            this.moveVector = new Vec2(-movement.x, movement.y);
        }
    }
}
