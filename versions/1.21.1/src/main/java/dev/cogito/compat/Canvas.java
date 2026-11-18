package dev.cogito.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Minecraft 1.21.1: what the overlay draws with, over the frame's GUI graphics. */
public record Canvas(GuiGraphics graphics) {
    public void fill(int x0, int y0, int x1, int y1, int color) {
        graphics.fill(x0, y0, x1, y1, color);
    }

    /** Text with the usual drop shadow. */
    public void text(Font font, String text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color);
    }
}
