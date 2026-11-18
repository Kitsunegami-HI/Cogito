package dev.cogito.compat;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cogito.mixin.KeyboardHandlerInvoker;
import dev.cogito.mixin.MouseHandlerInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** Minecraft 26.1: the game's own input handlers, which take event records. */
public final class Input {
    private Input() {}

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    public static long windowHandle() {
        return mc().getWindow().handle();
    }

    public static void keyPress(int key, int scancode, int modifiers, int action) {
        ((KeyboardHandlerInvoker) mc().keyboardHandler).cogito$keyPress(windowHandle(), action, new KeyEvent(key, scancode, modifiers));
    }

    public static void charTyped(int codepoint) {
        ((KeyboardHandlerInvoker) mc().keyboardHandler).cogito$charTyped(windowHandle(), new CharacterEvent(codepoint));
    }

    public static void mouseButton(int button, int modifiers, int action) {
        ((MouseHandlerInvoker) mc().mouseHandler).cogito$onButton(windowHandle(), new MouseButtonInfo(button, modifiers), action);
    }

    public static void mouseMove(double x, double y) {
        ((MouseHandlerInvoker) mc().mouseHandler).cogito$onMove(windowHandle(), x, y);
    }

    public static void scroll(double dx, double dy) {
        ((MouseHandlerInvoker) mc().mouseHandler).cogito$onScroll(windowHandle(), dx, dy);
    }

    /** Sets the operating system cursor mode (GLFW_CURSOR_NORMAL or _DISABLED) and puts it at a window position. */
    public static void grabOrReleaseMouse(int mode, double x, double y) {
        InputConstants.grabOrReleaseMouse(mc().getWindow(), mode, x, y);
    }

    /** The cursor in GUI-scaled coordinates. */
    public static double guiMouseX() {
        return mc().mouseHandler.getScaledXPos(mc().getWindow());
    }

    public static double guiMouseY() {
        return mc().mouseHandler.getScaledYPos(mc().getWindow());
    }
}
