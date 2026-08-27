package org.vmstudio.visor.extensions.client.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * PORT-26.2: was {@code LevelRendererExtension}. Block destruction progress moved off
 * {@code LevelRenderer} onto {@code ClientLevel} - both {@code destructionProgress} and
 * {@code destroyingBlocks} live there now, along with {@code removeProgress} - so Visor's
 * better-swinging state follows the data it manipulates.
 * <p>
 * One consequence is a hook that simply disappeared: the state used to be cleared by hand when
 * {@code LevelRenderer.setLevel} swapped worlds, and it is per-level now, so a new
 * {@code ClientLevel} starts empty on its own.
 */
public interface ClientLevelExtension {

    /**
     * Applies (or clears) a VR-swing destruction stage for a block.
     *
     * @param destroyStage 0..9 to set a stage, -1 to clear the block entirely, -2 to forget only
     *                     Visor's own bookkeeping for it
     */
    void visor$damageBlockProgress(@NotNull Player player,
                                   @NotNull BlockPos blockPos,
                                   int destroyStage);

    /**
     * Drops VR-swing entries the server has since contradicted or that have aged out.
     * <p>
     * Called once per frame from the level renderer, which is where it ran before the split - a
     * per-tick anchor on the level itself would change how quickly a stale stage disappears.
     */
    void visor$pruneSwingDamage();
}
