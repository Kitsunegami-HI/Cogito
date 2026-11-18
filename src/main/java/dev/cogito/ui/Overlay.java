package dev.cogito.ui;

import dev.cogito.CogitoMod;
import dev.cogito.agent.AgentLoop;
import dev.cogito.control.Control;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import dev.cogito.compat.Canvas;
import dev.cogito.compat.Input;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Shows a person watching what the AI is doing, and where its virtual cursor is. */
public final class Overlay {
    private static final int BACKGROUND = 0xB0000000;
    private static final int ACCENT = 0xFFFFD040;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int QUIET = 0xFFC0C0C0;

    /** Whoever needs the frames to be the game alone right now, such as a device-level model reading them. */
    private static final Set<String> quietReasons = ConcurrentHashMap.newKeySet();

    private Overlay() {}

    public static void setQuiet(String reason, boolean on) {
        if (on) {
            quietReasons.add(reason);
        } else {
            quietReasons.remove(reason);
        }
    }

    public static void draw(Canvas graphics, boolean screenOpen) {
        Minecraft mc = Minecraft.getInstance();
        if (!quietReasons.isEmpty()) {
            // What a model trains on must match what it sees later, whoever is driving: no banner, the same cursor.
            if (screenOpen) {
                drawCursor(graphics, mc);
            }
            return;
        }
        if (!Control.isAi()) {
            return;
        }
        Font font = mc.font;
        String key = CogitoMod.TAKEOVER_KEY.getTranslatedKeyMessage().getString();
        // A window's own title and tabs sit up here, and the AI reads this same picture: stay small over them.
        List<String> lines = screenOpen ? List.of() : AgentLoop.get().hudLines();
        int width = font.width("AI 控制中 · 按 " + key + " 接管") + 8;
        for (String line : lines) {
            width = Math.max(width, font.width(line) + 8);
        }
        int maxWidth = mc.getWindow().getGuiScaledWidth() - 8;
        width = Math.min(width, maxWidth);
        int height = 12 + lines.size() * 10 + 2;
        graphics.fill(4, 4, 4 + width, 4 + height, BACKGROUND);
        graphics.text(font, "AI 控制中 · 按 " + key + " 接管", 8, 7, ACCENT);
        int y = 18;
        boolean first = true;
        for (String line : lines) {
            graphics.text(font, font.plainSubstrByWidth(line, width - 8), 8, y, first ? TEXT : QUIET);
            first = false;
            y += 10;
        }
        if (screenOpen) {
            drawCursor(graphics, mc);
        }
    }

    /** With a screen open the OS cursor is free, so the AI's cursor is drawn by hand. */
    private static void drawCursor(Canvas graphics, Minecraft mc) {
        int x = (int) Input.guiMouseX();
        int y = (int) Input.guiMouseY();
        for (int row = 0; row < 9; row++) {
            int span = Math.min(row + 1, 6);
            graphics.fill(x - 1, y + row - 1, x + span + 1, y + row + 2, 0xFF000000);
        }
        for (int row = 0; row < 9; row++) {
            int span = Math.min(row + 1, 6);
            graphics.fill(x, y + row, x + span, y + row + 1, ACCENT);
        }
    }
}
