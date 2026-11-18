package dev.cogito.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Minecraft 26.1: what the overlay draws with, over the frame's GUI graphics. */
public record Canvas(GuiGraphicsExtractor graphics) {
    public void fill(int x0, int y0, int x1, int y1, int color) {
        graphics.fill(x0, y0, x1, y1, color);
    }

    /** Text with the usual drop shadow. */
    public void text(Font font, String text, int x, int y, int color) {
        graphics.text(font, text, x, y, color);
    }
}
