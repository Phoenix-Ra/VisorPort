package org.vmstudio.visor.mixin.client.multiplayer;

import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vmstudio.visor.api.common.utils.LoggerUtils;
import org.vmstudio.visor.api.server.VRServerSettings;
import org.vmstudio.visor.core.client.VisorState;
import org.vmstudio.visor.extensions.client.level.ClientLevelExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

/**
 * PORT-26.2: the better-swinging cluster, moved here wholesale from {@code LevelRendererMixin}.
 * <p>
 * 26.2 moved block destruction progress off the renderer and onto the level: {@code ClientLevel}
 * owns {@code destructionProgress}, {@code destroyingBlocks} and {@code removeProgress} now. Visor
 * rewrites those maps so a VR swing shows a destroy stage the server has not confirmed yet, so it
 * follows them here rather than reaching across from the renderer.
 * <p>
 * Two things fall out of the move. The state is per-level now, so the old "clear on setLevel" hook
 * is gone - a fresh {@code ClientLevel} starts empty. And the per-frame pruning stays on the
 * renderer (see {@code LevelRendererMixin}), which calls {@link #visor$pruneSwingDamage()} here;
 * anchoring it on the level's own tick would have changed how fast a stale stage clears.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin implements ClientLevelExtension {

    @Final
    @Shadow
    private Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress;

    @Final
    @Shadow
    private Int2ObjectMap<BlockDestructionProgress> destroyingBlocks;

    // Mixin does not reliably run a mixin class's field initialisers - the fields are merged but
    // the initialising bytecode lives in the mixin's own <init>, and these came out null on the
    // first frame after joining a world. This is why the original code (on LevelRendererMixin)
    // allocated them from an @Inject on <init> instead; keep doing that.
    @Unique
    private Map<Long, Long> visor$damagedBlocksVr;

    @Unique
    private Map<Long, BlockDestructionProgress> visor$damagedBlocksVrSave;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void visor$initSwingState(CallbackInfo ci) {
        visor$damagedBlocksVr = Collections.synchronizedMap(new HashMap<>());
        visor$damagedBlocksVrSave = Collections.synchronizedMap(new HashMap<>());
    }

    /**
     * Vanilla drops the whole entry for a block when the server retracts one progress record.
     * Visor keeps its own optimistic entry alive instead, so a VR swing does not flicker.
     */
    @Inject(at = @At("HEAD"), method = "removeProgress", cancellable = true)
    private void visor$removeProgress(BlockDestructionProgress progress, CallbackInfo ci) {
        //fix of crash bcz of vr swinging
        ci.cancel();
        long blockPos = progress.getPos().asLong();
        Set<BlockDestructionProgress> set = this.destructionProgress.get(blockPos);
        if (set == null) return; //here it is
        set.remove(progress);
        if (set.isEmpty()) {
            this.destructionProgress.remove(blockPos);
        }
    }

    @Override
    @Unique
    public void visor$pruneSwingDamage() {
        if (visor$damagedBlocksVr.isEmpty() && visor$damagedBlocksVrSave.isEmpty()) {
            return;
        }
        try {
            List<Long> toRemove = new ArrayList<>();
            destructionProgress.forEach((key, value) -> {
                int stage = value.last().getProgress();
                if (stage < 0 || stage >= ModelBakery.DESTROY_TYPES.size()) {
                    toRemove.add(key);
                }
            });
            toRemove.forEach(it -> {
                destructionProgress.remove(it.longValue());
                visor$damagedBlocksVr.remove(it);
                visor$damagedBlocksVrSave.remove(it);
            });
            toRemove.clear();
            for (Map.Entry<Long, Long> entry : visor$damagedBlocksVr.entrySet()) {
                SortedSet<BlockDestructionProgress> set = destructionProgress.get(entry.getKey());
                if (set == null) {
                    toRemove.add(entry.getKey());
                    continue;
                }
                BlockDestructionProgress d = visor$damagedBlocksVrSave.get(entry.getKey());
                if (d == null) {
                    toRemove.add(entry.getKey());
                    continue;
                }
                if (!set.contains(d) || set.size() > 1) {
                    toRemove.add(entry.getKey());
                    continue;
                }
                //if anything happened with packet from server
                if (entry.getValue() + (VRServerSettings.getSwingingRepairDelay() * 50)
                        < System.currentTimeMillis()) {
                    toRemove.add(entry.getKey());
                }
            }
            toRemove.forEach(it -> {
                destructionProgress.remove(it.longValue());
                visor$damagedBlocksVr.remove(it);
                visor$damagedBlocksVrSave.remove(it);
            });
        } catch (Throwable e) {
            LoggerUtils.printError(e);
        }
    }

    @Override
    @Unique
    public void visor$damageBlockProgress(@NotNull Player player,
                                          @NotNull BlockPos blockPos,
                                          int destroyStage) {
        if (!VRServerSettings.isBetterSwinging()
                || VisorState.get().isNotActive()) return;

        // PORT-26.2: a stage past the last crack texture drops the overlay outright, the way
        // vanilla's own destroyBlockProgress treats progress >= 10. It used to slip through to
        // rendering, where DESTROY_TYPES.get(10) is an IndexOutOfBoundsException - 26.2 extracts
        // the render state before the per-frame prune could sanitise the map.
        if (destroyStage == -1 || destroyStage >= ModelBakery.DESTROY_TYPES.size()) {
            visor$damagedBlocksVr.remove(blockPos.asLong());
            visor$damagedBlocksVrSave.remove(blockPos.asLong());
            destructionProgress.remove(blockPos.asLong());
            return;
        }

        if (destroyStage == -2) {
            visor$damagedBlocksVr.remove(blockPos.asLong());
            visor$damagedBlocksVrSave.remove(blockPos.asLong());
            return;
        }

        final List<Integer> toRemove = new ArrayList<>();

        destroyingBlocks.forEach((id, progress) -> {
            if (progress.getPos().asLong() == blockPos.asLong()) {
                toRemove.add(id);
                destructionProgress.remove(progress.getPos().asLong());
            }
        });

        toRemove.forEach(it -> destroyingBlocks.remove(it.intValue()));

        BlockDestructionProgress progress = new BlockDestructionProgress(
                player.getId(), blockPos
        );
        progress.setProgress(destroyStage);

        SortedSet<BlockDestructionProgress> set =
                destructionProgress.computeIfAbsent(
                        progress.getPos().asLong(), (p_234254_) -> {
                            return Sets.newTreeSet();
                        }
                );

        set.clear();
        set.add(progress);

        visor$damagedBlocksVr.put(blockPos.asLong(), System.currentTimeMillis());
        visor$damagedBlocksVrSave.put(blockPos.asLong(), progress);
    }
}
