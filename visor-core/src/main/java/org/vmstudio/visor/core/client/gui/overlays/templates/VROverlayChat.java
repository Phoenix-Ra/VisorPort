package org.vmstudio.visor.core.client.gui.overlays.templates;



import net.minecraft.client.gui.screens.ChatScreen;
import org.vmstudio.visor.api.client.gui.overlays.options.types.OverlayOptionsVisibility;
import org.vmstudio.visor.api.client.player.pose.PoseAnchor;
import org.vmstudio.visor.api.client.gui.overlays.RegisterVROverlayTemplate;
import org.vmstudio.visor.api.client.gui.overlays.options.OverlayOptionGroup;
import org.vmstudio.visor.api.client.gui.overlays.options.types.OverlayOptionsMisc;
import org.vmstudio.visor.api.client.gui.overlays.options.types.OverlayOptionsPose;
import org.vmstudio.visor.api.client.gui.overlays.framework.template.VROverlayTemplateScreen;
import org.vmstudio.visor.api.common.addon.VisorAddon;
import org.vmstudio.visor.core.client.ClientContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@RegisterVROverlayTemplate(
        id = VROverlayChat.ID,
        name = VROverlayChat.NAME,
        description = VROverlayChat.DESCRIPTION,
        isCreateDefault = true
)
public class VROverlayChat extends VROverlayTemplateScreen {
    public static final String ID = "chat";
    public static final String NAME = "visor.overlay.template."+ID+".name";
    public static final String DESCRIPTION = "visor.overlay.template."+ID+".description";

    public VROverlayChat(@NotNull VisorAddon owner,
                         @NotNull String id) {
        super(owner, id);
        setEnabled(true);
    }


    @Override
    protected void onRender(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // 1.21.11: ChatComponent.render takes the Font explicitly and a trailing
        // "change cursor on insertions" flag. Vanilla's HUD path passes false for it
        // (only ChatScreen's own draw turns it on), which is the behaviour this overlay had.
        // PORT-26.1: render(...) became extractRenderState(...) and the "focused" flag turned
        // into ChatComponent.DisplayMode (FOREGROUND while the chat screen is open, BACKGROUND
        // for the HUD).
        minecraft.gui.hud.getChat().extractRenderState(
                guiGraphics,
                minecraft.font,
                minecraft.gui.hud.getGuiTicks(),0, 0,
                minecraft.gui.screen() instanceof ChatScreen
                        ? ChatComponent.DisplayMode.FOREGROUND
                        : ChatComponent.DisplayMode.BACKGROUND,
                false
        );
    }


    @Override
    public boolean updateVisibility() {
        if(minecraft.level == null) return false;
        if(minecraft.isPaused()
                || ClientContext.overlayManager.getKeyboardAccessor().isVisible()) return false;

        return !minecraft.gui.hud.getChat().trimmedMessages.isEmpty() &&
                minecraft.options.chatVisibility().get() != ChatVisiblity.HIDDEN;
    }


    @Override
    public boolean supportsCursor() {
        return false;
    }

    @Override
    public boolean isHudLayer() {
        return true;
    }

    @Override
    protected @NotNull List<OverlayOptionGroup<?>> createTemplateOptions() {
        return List.of(
                new OverlayOptionsVisibility(
                        this,
                        it -> it.setVisible(true)
                ),
                new OverlayOptionsMisc(
                        this,
                        it->{
                            it.setOptionsUpdaterType(OverlayOptionsMisc.OptionsUpdaterType.TICK);
                        }
                ),
                new OverlayOptionsPose(
                        this,
                        it->{
                            it.setTickPose(true);
                            it.setAimedRotation(false);

                            it.setPositionAnchor(PoseAnchor.HMD);
                            it.setPositionOffset(
                                    0.392f,
                                    0.214f,
                                    -1.706f
                            );
                            it.setRotationAnchor(PoseAnchor.HMD);
                            it.setRotationOffset(
                                    0,
                                    0,
                                    0
                            );
                            it.setScale(1.2f);
                        }

                )
        );
    }
}
