package org.vmstudio.visor.api.client.gui.overlays.framework.screen;

import lombok.Getter;
import org.vmstudio.visor.api.client.gui.overlays.framework.VROverlayScreen;

import org.vmstudio.visor.api.common.addon.component.ComponentPriority;
import org.vmstudio.visor.api.common.addon.VisorAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * The {@link VROverlayScreen} that renders other {@link Screen}
 */
@Getter
public abstract class VROverlayScreenInScreen<T extends Screen> extends VROverlayScreen {
    protected T screen;

    public VROverlayScreenInScreen(@NotNull VisorAddon owner,
                                   @NotNull String id,
                                   @Nullable T screen) {
        this(owner, id, ComponentPriority.NORMAL, 1.0f, screen);

    }

    public VROverlayScreenInScreen(@NotNull VisorAddon owner,
                                   @NotNull String id,
                                   @NotNull ComponentPriority priority,
                                   float overlayScale,
                                   @Nullable T screen) {
        super(owner, id, priority, overlayScale);
        this.screen = screen;

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
    protected void onRender(GuiGraphics guiGraphics,
                            int mouseX, int mouseY,
                            float partialTicks) {

        if(screen!=null) {
            screen.renderWithTooltipAndSubtitles(guiGraphics, mouseX, mouseY, partialTicks);
        }

    }


    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int buttonType = event.button();
        if (buttonType == 0 && isCursorOnResizeHandle(getRawMouseX(), getRawMouseY())) {
            startResizing();
            return true;
        }
        if (buttonType == 0 && isCursorOnDragHandle(getRawMouseX(), getRawMouseY())) {
            startDragging();
            return true;
        }
        if(screen==null) return true;
        return screen.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        double mouseX = event.x();
        double mouseY = event.y();
        int buttonType = event.button();
        if (buttonType == 0 && isBeingResized()) {
            stopResizing();
            return true;
        }
        if (buttonType == 0 && isBeingDragged()) {
            stopDragging();
            return true;
        }
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
        int modifiers = event.modifiers();
        if(screen==null) return true;
        return screen.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        int keyCode = event.key();
        int keyScan = event.scancode();
        int modifiers = event.modifiers();
        if(screen==null) return true;
        return screen.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        char chr = (char) event.codepoint();
        int modifiers = event.modifiers();
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
