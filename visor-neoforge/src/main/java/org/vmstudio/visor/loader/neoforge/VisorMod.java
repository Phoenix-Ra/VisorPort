package org.vmstudio.visor.loader.neoforge;


import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.core.common.addon.AddonManagerImpl;

@Mod(VisorAPI.MOD_ID)
public class VisorMod {

    public VisorMod(final IEventBus modEventBus){

        modEventBus.addListener(NeoForgeModLoader::registerPayloads);
        // PORT-1.21.11: client payload handlers are registered through a separate client-only
        // event now - see NeoForgeModLoader.registerClientPayloads. Dist-gated so a dedicated
        // server never resolves the client event class. (FML 10 made the dist a getter.)
        if (FMLEnvironment.getDist().isClient()) {
            modEventBus.addListener(NeoForgeModLoader::registerClientPayloads);
        }
        modEventBus.addListener(this::onLoadComplete);
    }

    private void onLoadComplete(final FMLLoadCompleteEvent event){
        event.enqueueWork(AddonManagerImpl::register);
    }


}
