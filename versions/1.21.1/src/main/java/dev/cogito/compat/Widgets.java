package dev.cogito.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.function.Consumer;
import java.util.function.Function;

/** Minecraft 1.21.1: the screen widgets whose builders differ between the supported versions. */
public final class Widgets {
    private Widgets() {}

    public static <T> CycleButton.Builder<T> cycle(Function<T, Component> text, T initial) {
        return CycleButton.builder(text).withInitialValue(initial);
    }

    /** What is typed shows as stars. */
    public static void mask(EditBox box) {
        box.setFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
    }

    /**
     * A layout that scrolls its content when it is taller than the room it has. This version has no scroll area for
     * layouts, so the content moves under the wheel and the rows that do not fit entirely are hidden.
     */
    public static final class Scroller implements Layout {
        private static final int STEP = 20;

        private final Layout content;
        private int maxHeight;
        private int x;
        private int y;
        private int scroll;

        public Scroller(Minecraft minecraft, Layout content, int maxHeight) {
            this.content = content;
            this.maxHeight = maxHeight;
        }

        /** Lays the content out again for this much room. */
        public void fit(int maxHeight) {
            this.maxHeight = maxHeight;
            arrangeElements();
        }

        /** Scrolls when the wheel turns over the content; true when it did. */
        public boolean scrolled(double mouseX, double mouseY, double amount) {
            if (mouseX < x || mouseX >= x + getWidth() || mouseY < y || mouseY >= y + getHeight()) {
                return false;
            }
            int before = scroll;
            scroll = clampScroll(scroll - (int) Math.round(amount * STEP));
            if (scroll == before) {
                return false;
            }
            place();
            return true;
        }

        private int clampScroll(int value) {
            return Math.max(0, Math.min(value, Math.max(0, content.getHeight() - maxHeight)));
        }

        private void place() {
            content.setPosition(x, y - scroll);
            int bottom = y + getHeight();
            content.visitWidgets(widget -> widget.visible = widget.getY() >= y && widget.getY() + widget.getHeight() <= bottom);
        }

        @Override
        public void arrangeElements() {
            content.arrangeElements();
            scroll = clampScroll(scroll);
            place();
        }

        @Override
        public void visitChildren(Consumer<LayoutElement> visitor) {
            visitor.accept(content);
        }

        @Override
        public void visitWidgets(Consumer<AbstractWidget> visitor) {
            content.visitWidgets(visitor);
        }

        @Override
        public void setX(int x) {
            this.x = x;
            place();
        }

        @Override
        public void setY(int y) {
            this.y = y;
            place();
        }

        @Override
        public int getX() {
            return x;
        }

        @Override
        public int getY() {
            return y;
        }

        @Override
        public int getWidth() {
            return content.getWidth();
        }

        @Override
        public int getHeight() {
            return Math.min(content.getHeight(), maxHeight);
        }
    }
}
