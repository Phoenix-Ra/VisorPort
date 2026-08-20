package org.vmstudio.visor.mixin.common;

import org.vmstudio.visor.core.server.VisorServerImpl;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Shadow
    protected abstract boolean initServer() throws IOException;

    @Inject(at = @At("HEAD"), method = "runServer")
    public void visor$initServer(CallbackInfo ci){
        VisorServerImpl.create();

    }
    @Inject(at = @At("TAIL"), method = "stopServer")
    public void visor$stopServer(CallbackInfo callbackInfo){
        VisorServerImpl.INSTANCE.onServerStop();
    }

    @Inject(at = @At("HEAD"), method = "tickServer")
    public void visor$tickVR(BooleanSupplier booleanSupplier,
                            CallbackInfo callbackInfo){
        VisorServerImpl.INSTANCE.tickVR();
    }


}
