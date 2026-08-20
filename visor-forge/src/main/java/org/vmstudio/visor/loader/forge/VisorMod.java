package org.vmstudio.visor.loader.forge;


import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.core.common.addon.AddonManagerImpl;

@Mod(VisorAPI.MOD_ID)
public class VisorMod {

    /*
     * PORT-1.21.11: Forge 61 moved to EventBus 7, which replaced the one IEventBus per mod with a
     * BusGroup and a per-event-type EventBus. FMLJavaModLoadingContext#getModEventBus() is gone;
     * the group comes from getModBusGroup() and each event class hands out its own bus through a
     * static getBus(BusGroup). The mod constructor still receives the context, so the entrypoint
     * contract is unchanged.
     */
    public VisorMod(final FMLJavaModLoadingContext context){
        FMLLoadCompleteEvent.getBus(context.getModBusGroup())
                .addListener(this::onLoadComplete);
    }

    private void onLoadComplete(final FMLLoadCompleteEvent event){
        event.enqueueWork(AddonManagerImpl::register);
    }


}
