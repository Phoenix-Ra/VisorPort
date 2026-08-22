package org.vmstudio.visor.api.client.gui.widgets;

import org.vmstudio.visor.api.client.gui.GuiTexture;
import org.vmstudio.visor.api.client.gui.widgets.info.WidgetInfoEditBox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;
import java.util.function.Predicate;


public class EditBoxImaged extends EditBox {
    private final GuiTexture texture;

    // PORT-26.1: EditBox.setFilter(Predicate) was removed in 26.1 - vanilla's insertText no
    // longer consults anything, and the replacement `addFormatter(TextFormatter)` only styles
    // text, it cannot reject it. Reimplemented here so the nine call sites are unchanged.
    private Predicate<String> filter;
    private Consumer<String> responder;
    private boolean suppressResponder;

    public EditBoxImaged(@NotNull WidgetInfoEditBox widgetInfo) {
        super(widgetInfo.getTextFont(),
                widgetInfo.getX(),
                widgetInfo.getY(),
                widgetInfo.getWidth(),
                widgetInfo.getHeight(),
                Component.empty()
        );
        this.texture = widgetInfo.getTexture();
        setTextColor(widgetInfo.getTextColor().asInt());
        // PORT-1.21.11: EditBox.setHint dereferences the component immediately now (it applies
        // the default grey hint style to unstyled hints), so a null hint went from "no hint" to
        // an instant NPE. Widgets without a hint simply skip the call.
        if (widgetInfo.getHint() != null) {
            setHint(widgetInfo.getHint());
        }
        setMaxLength(widgetInfo.getTextMaxLength());

        setFilter(widgetInfo.getFilter());

        setTooltip(widgetInfo.getTooltip());

        setBordered(true);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        if(visible) {
            if(texture != null) {
                texture.blit(
                        guiGraphics,
                        getX(), getY(),
                        getWidth(), getHeight()
                );
            }
        }

        // draw text, cursor, selection
        super.extractWidgetRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }



    //---------
    // PORT-26.1: setFilter reimplementation.
    //
    // Vanilla used to test the prospective value inside setValue/insertText and drop the edit
    // when it failed. Those are still the only two mutation funnels (deleteCharsToPos routes a
    // selection delete through insertText, and its no-selection path bypassed the filter in the
    // old version too), so guarding them reproduces the previous behaviour exactly.
    //
    // insertText has no pre-check hook any more, so the edit is applied and rolled back when the
    // result is rejected. The rollback goes through super.setValue with the responder muted, so
    // callers never observe the rejected value - matching the old "nothing happened" semantics.

    /**
     * Restricts what this box will accept. A value is only committed when the predicate accepts
     * the whole prospective text.
     *
     * @param filter predicate applied to the full prospective value
     */
    public void setFilter(@NotNull Predicate<String> filter) {
        this.filter = filter;
    }

    private boolean accepts(String text) {
        // null while the superclass constructor runs, before this class's field initialisers
        return filter == null || filter.test(text);
    }

    @Override
    public void setResponder(Consumer<String> responder) {
        this.responder = responder;
        super.setResponder(value -> {
            if (!suppressResponder && this.responder != null) {
                this.responder.accept(value);
            }
        });
    }

    @Override
    public void setValue(String text) {
        if (accepts(text)) {
            super.setValue(text);
        }
    }

    @Override
    public void insertText(String text) {
        String before = getValue();
        int cursorBefore = getCursorPosition();

        super.insertText(text);

        if (!accepts(getValue())) {
            suppressResponder = true;
            try {
                super.setValue(before);
                setCursorPosition(cursorBefore);
            } finally {
                suppressResponder = false;
            }
        }
    }

    //---------
    //silly way to make no border drawing, but have the small padding for text

    @Override
    public boolean isBordered() {
        return false;
    }

    public int getInnerWidth() {
        return this.width - 8;
    }
}
