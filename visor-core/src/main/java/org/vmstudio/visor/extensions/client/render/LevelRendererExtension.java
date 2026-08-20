package org.vmstudio.visor.extensions.client.render;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

public interface LevelRendererExtension {
    // PORT-1.21.11: visor$getRenderedEntity() is gone. It reported the entity LevelRenderer was
    // drawing, which only existed while renderEntity ran; the extract/submit split leaves no such
    // window, and its only reader (the VR name tag orientation) now takes the anchor off the
    // render state instead.

    void visor$damageBlockProgress(@NotNull Player player,
                                   @NotNull BlockPos blockPos,
                                   int destroyStage);
}
