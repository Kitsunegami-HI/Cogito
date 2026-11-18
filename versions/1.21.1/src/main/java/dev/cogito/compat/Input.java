package dev.cogito.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.cogito.mixin.KeyboardHandlerInvoker;
import dev.cogito.mixin.MouseHandlerInvoker;
import net.minecraft.client.Minecraft;

/** Minecraft 1.21.1: the game's own input handlers, which take the raw GLFW callback arguments. */
public final class Input {
    private Input() {}

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    public static long windowHandle() {
        return mc().getWindow().getWindow();
    }

    public static void keyPress(int key, int scancode, int modifiers, int action) {
        ((KeyboardHandlerInvoker) mc().keyboardHandler).cogito$keyPress(windowHandle(), key, scancode, action, modifiers);
    }

    public static void charTyped(int codepoint) {
        ((KeyboardHandlerInvoker) mc().keyboardHandler).cogito$charTyped(windowHandle(), codepoint, 0);
    }

    public static void mouseButton(int button, int modifiers, int action) {
        ((MouseHandlerInvoker) mc().mouseHandler).cogito$onPress(windowHandle(), button, action, modifiers);
    }

    public static void mouseMove(double x, double y) {
        ((MouseHandlerInvoker) mc().mouseHandler).cogito$onMove(windowHandle(), x, y);
    }

    public static void scroll(double dx, double dy) {
        ((MouseHandlerInvoker) mc().mouseHandler).cogito$onScroll(windowHandle(), dx, dy);
    }

    /** Sets the operating system cursor mode (GLFW_CURSOR_NORMAL or _DISABLED) and puts it at a window position. */
    public static void grabOrReleaseMouse(int mode, double x, double y) {
        InputConstants.grabOrReleaseMouse(windowHandle(), mode, x, y);
    }

    /** The cursor in GUI-scaled coordinates. */
    public static double guiMouseX() {
        Window window = mc().getWindow();
        return mc().mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
    }

    public static double guiMouseY() {
        Window window = mc().getWindow();
        return mc().mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();
    }
}
