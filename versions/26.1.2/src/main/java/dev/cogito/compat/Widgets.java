package dev.cogito.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.function.Function;

/** Minecraft 26.1: the screen widgets whose builders differ between the supported versions. */
public final class Widgets {
    private Widgets() {}

    public static <T> CycleButton.Builder<T> cycle(Function<T, Component> text, T initial) {
        return CycleButton.builder(text, initial);
    }

    /** What is typed shows as stars. */
    public static void mask(EditBox box) {
        box.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
    }

    /** A layout that scrolls its content when it is taller than the room it has; the game's own scroll area. */
    public static final class Scroller extends ScrollableLayout {
        public Scroller(Minecraft minecraft, Layout content, int maxHeight) {
            super(minecraft, content, maxHeight);
        }

        /** Lays the content out again for this much room. */
        public void fit(int maxHeight) {
            arrangeElements();
            setMaxHeight(maxHeight);
        }

        /** The game's scroll area takes the wheel itself, so the screen need not pass it on. */
        public boolean scrolled(double mouseX, double mouseY, double amount) {
            return false;
        }
    }
}
