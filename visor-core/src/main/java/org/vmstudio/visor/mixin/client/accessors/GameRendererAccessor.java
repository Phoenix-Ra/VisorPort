package org.vmstudio.visor.mixin.client.accessors;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * PORT-1.21.11: {@code FogParameters.NO_FOG} is gone. Fog is a uniform block now, and the only
 * source of a "no fog" one is the {@link FogRenderer} the game renderer keeps to itself.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    @Accessor("fogRenderer")
    FogRenderer visor$getFogRenderer();

    /**
     * PORT-1.21.11: GUI drawing records into a {@link GuiRenderState} and is submitted in one go
     * by the {@link GuiRenderer}. Visor renders GUIs off the vanilla schedule - once per overlay
     * texture, plus the desktop mirror - so it needs both halves.
     */
    // PORT-26.1: the GuiRenderState moved into GameRenderer.gameRenderState and is public via
    // getGameRenderState().guiRenderState, so no accessor is needed any more. It must not be a
    // default method here either: an interface mixin is only an accessor mixin while every
    // method is @Accessor/@Invoker - one plain method turns it into an interface mixin, which
    // Mixin refuses to apply to a class ("@Mixin target type mismatch ... is not an interface").

    @Accessor("guiRenderer")
    GuiRenderer visor$getGuiRenderer();
}
