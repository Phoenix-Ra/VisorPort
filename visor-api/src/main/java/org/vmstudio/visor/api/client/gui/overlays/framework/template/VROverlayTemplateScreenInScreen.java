package org.vmstudio.visor.api.client.gui.overlays.framework.template;

import lombok.Getter;
import org.vmstudio.visor.api.client.gui.overlays.framework.VROverlayScreen;
import org.vmstudio.visor.api.common.addon.VisorAddon;
import org.vmstudio.visor.api.common.addon.component.ComponentPriority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.NotNull;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;


/**
 * Abstract class for {@link VROverlayScreen} templates,
 * that render specified {@link Screen}.
 */
public abstract class VROverlayTemplateScreenInScreen<T extends Screen> extends VROverlayTemplateScreen{

    @Getter
    protected T screen;

    public VROverlayTemplateScreenInScreen(@NotNull VisorAddon owner, @NotNull String id) {
        super(owner, id);
    }

    public VROverlayTemplateScreenInScreen(@NotNull VisorAddon owner, @NotNull String id, @NotNull ComponentPriority priority, float overlayScale) {
        super(owner, id, priority, overlayScale);
    }



    @Override
    protected void init() {
        if(screen!=null){
            screen.init(width,
                    height
            );
        }
    }

    @Override
    protected void onRender(GuiGraphicsExtractor guiGraphics,
                            int mouseX, int mouseY,
                            float partialTicks) {

        if(screen!=null) {
            screen.extractRenderStateWithTooltipAndSubtitles(guiGraphics, mouseX, mouseY, partialTicks);
        }

    }


    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int buttonType = event.button();
        if(screen==null) return true;
        return screen.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        double mouseX = event.x();
        double mouseY = event.y();
        int buttonType = event.button();
        if(screen==null) return true;
        return screen.mouseReleased(event);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if(screen==null) return;
        screen.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = event.x();
        double mouseY = event.y();
        int buttonType = event.button();
        if(screen==null) return true;
        return screen.mouseDragged(event, dragX, dragY);
    }


    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollDelta) {
        if(screen==null) return true;
        return screen.mouseScrolled(mouseX, mouseY, scrollX, scrollDelta);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        int keyScan = event.scancode();
        if(screen==null) return true;
        return screen.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        int keyCode = event.key();
        int keyScan = event.scancode();
        if(screen==null) return true;
        return screen.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        char chr = (char) event.codepoint();
        if(screen==null) return true;
        return screen.charTyped(event);
    }

    @Override
    protected void onTick() {
        if(screen != null && isVisible()){
            screen.tick();
        }
    }


}
