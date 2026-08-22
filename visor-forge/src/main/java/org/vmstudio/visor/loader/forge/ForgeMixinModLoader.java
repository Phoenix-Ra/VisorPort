package org.vmstudio.visor.loader.forge;

import net.minecraftforge.fml.loading.LoadingModList;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visor.MixinModLoader;

public class ForgeMixinModLoader implements MixinModLoader {

    @Override
    public boolean isModLoaded(@NotNull String id) {
        // PORT-26.1 (Forge 64): FMLLoader.getLoadingModList() is gone; LoadingModList is a static API
        return LoadingModList.getModFileById(id) != null;
    }

    @Override
    public @NotNull LoaderType getType() {
        return LoaderType.FORGE;
    }
}
