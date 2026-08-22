package org.vmstudio.visor.extensions.client;

/**
 * Marker for the Gui mixin. The only method it ever declared (visor$getShowPlayerList) had no
 * implementation and no callers - Mixin's interface check reported it as unimplemented on
 * net.minecraft.client.gui.Gui - so it was removed.
 */
public interface GuiExtension {
}
