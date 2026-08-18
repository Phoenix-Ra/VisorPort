package org.vmstudio.visor.api.client.gui.widgets;

import lombok.Getter;
import org.vmstudio.visor.api.client.gui.GuiTexture;
import org.vmstudio.visor.api.client.gui.helpers.GuiHelper;
import org.vmstudio.visor.api.client.gui.widgets.info.WidgetInfoButtonImaged;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import org.vmstudio.visor.api.client.gui.overlays.framework.VROverlayScreen;
import org.vmstudio.visor.api.compatibility.mcversion.McVersionUtilsClient;

import java.util.function.Consumer;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import com.mojang.blaze3d.platform.InputConstants;

public class ButtonImaged extends AbstractButton {

    @Getter
    private final WidgetInfoButtonImaged widgetInfo;

    private final Consumer<ButtonImaged> onPress;

    @Nullable
    private final Consumer<ButtonImaged> onRelease;

    @Getter
    private boolean selected;

    @Getter
    private boolean pressed;

    @Nullable
    private Tooltip tooltipOverride;

    public ButtonImaged(WidgetInfoButtonImaged widgetInfo,
                        Consumer<ButtonImaged> onPress) {
        this(widgetInfo, onPress, null);
    }

    public ButtonImaged(WidgetInfoButtonImaged widgetInfo,
                        Consumer<ButtonImaged> onPress,
                        @Nullable Consumer<ButtonImaged> onRelease) {
        super(widgetInfo.getX(), widgetInfo.getY(),
                widgetInfo.getWidth(), widgetInfo.getHeight(),
                widgetInfo.getText()
        );
        this.widgetInfo = widgetInfo;
        this.onPress = onPress;
        this.onRelease = onRelease;
    }


    // Tooltip is kept out of the vanilla WidgetTooltipHolder on purpose: the holder lost
    // custom-positioner support in 1.20.2, so it is submitted manually in renderWidget().
    @Override
    public void setTooltip(@Nullable Tooltip tooltip) {
        this.tooltipOverride = tooltip;
    }

    public @Nullable Tooltip getTooltip() {
        return tooltipOverride != null ? tooltipOverride : widgetInfo.getTooltip();
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
        this.active = !widgetInfo.isInactiveOnSelected()
                || !selected;
    }


    @Override
    public void onPress(InputWithModifiers input) {
        pressed = true;
        if (this.onPress != null) {
            this.onPress.accept(this);
        }
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (!pressed) return;
        pressed = false;
        if (this.onRelease != null) {
            this.onRelease.accept(this);
        }
    }

    public void forceRelease() {
        // synthetic release at the widget's own position; left button, no modifiers
        onRelease(new MouseButtonEvent(getX(), getY(),
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
    }

    /**
     * Mirrors WidgetTooltipHolder#refreshTooltipForNextRenderPass
     */
    private boolean visorShouldShowTooltip() {
        return this.isHovered()
                || (this.isFocused()
                        && Minecraft.getInstance().getLastInputType().isKeyboard());
    }
    @Override
    protected void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (visorShouldShowTooltip()) {
            var tooltip = getTooltip();
            if (tooltip != null) {
                // 1.21.9: tooltips are queued on the GuiGraphics being rendered rather than on
                // Minecraft's current screen, so an overlay's tooltip lands in the overlay itself.
                guiGraphics.setTooltipForNextFrame(
                        Minecraft.getInstance().font,
                        tooltip.toCharSequence(Minecraft.getInstance()),
                        ClampedTooltipPositioner.INSTANCE,
                        mouseX, mouseY,
                        this.isFocused()
                );
            }
        }

        GuiTexture texture;
        if(!active){

            texture = selected
                    ? widgetInfo.getTextureSelected()
                    : widgetInfo.getTextureInactive();
        }else {
            if (selected) {
                texture = widgetInfo.getTextureHoveredSelected();
                if (!isHovered || texture == null) {
                    texture = widgetInfo.getTextureSelected();
                }
            } else if (isHovered) {
                texture = widgetInfo.getTextureHovered();
            } else {
                texture = widgetInfo.getTexture();
            }
        }
        if(texture == null){
            texture = widgetInfo.getTexture();
        }

        widgetInfo
                .pos(getX(), getY())
                .size(getWidth(), getHeight());

        widgetInfo.drawFill(guiGraphics, isHovered);

        if(texture != null) {
            texture.blit(
                    guiGraphics,
                    this.getX(), this.getY(),
                    this.width, this.height
            );
        }

        widgetInfo.drawHighlight(guiGraphics, active, isHovered, selected);


        String text = getMessage().getString();
        int textX = getX() + widgetInfo.getTextPosOffset().x;
        int textY = getY() + widgetInfo.getTextPosOffset().y;
        int textW = getWidth() + widgetInfo.getTextSizeOffset().x;
        int textH = getHeight() + widgetInfo.getTextSizeOffset().y;

        if (!text.isEmpty()) {
            Font font = Minecraft.getInstance().font;
            int color = widgetInfo.getTextColor().asInt();

            if (widgetInfo.isDynamicTextScale()) {
                GuiHelper.renderScalableText(
                        guiGraphics, font, text, color,
                        textX, textY, textW, textH,
                        widgetInfo.getDynamicTextMaxScale(),
                        true
                );
                return;
            }

            GuiHelper.renderScrollableText(
                    guiGraphics, font, text, color,
                    textX, textY, textW, textH,
                    widgetInfo.getTextScale(),
                    true
            );
        }
    }

    //Use Only during rendering of this widget!!!!
    private static @Nullable Screen getTooltipScreen() {
        VROverlayScreen overlay = VROverlayScreen.getRenderingOverlay();
        if (overlay != null) {
            return overlay;
        }
        return Minecraft.getInstance().screen;
    }



    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        this.defaultButtonNarrationText(narrationElementOutput);
    }

}