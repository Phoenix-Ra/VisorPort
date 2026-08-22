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
    @Accessor("guiRenderState")
    GuiRenderState visor$getGuiRenderState();

    @Accessor("guiRenderer")
    GuiRenderer visor$getGuiRenderer();
}
