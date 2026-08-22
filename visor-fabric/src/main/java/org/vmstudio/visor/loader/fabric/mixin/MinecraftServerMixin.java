package org.vmstudio.visor.loader.fabric.mixin;

import com.mojang.datafixers.DataFixer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vmstudio.visor.api.ModLoader;
import org.vmstudio.visor.core.common.addon.AddonManagerImpl;
import org.vmstudio.visor.core.server.VisorServerImpl;

import java.io.IOException;
import java.net.Proxy;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    /**
     * PORT-26.1: the constructor gained an Optional<GameRules> after WorldStem and a trailing
     * boolean (propagatesCrashes). An @Inject into <init> must mirror the descriptor exactly or
     * the injector fails at startup, so the handler only names CallbackInfo and stays valid
     * across signature changes.
     */
    @Inject(at = @At("TAIL"), method = "<init>")
    public void visor$registerAddons(CallbackInfo callbackInfo){
        if(ModLoader.get().isDedicatedServer()){
            AddonManagerImpl.register();
        }
    }


}
