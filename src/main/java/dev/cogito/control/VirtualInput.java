package dev.cogito.control;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.cogito.compat.Input;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;

/**
 * The single entry point for everything the AI does with keys and the mouse. Events go into the game's
 * own handler methods, so every screen, modded or not, sees exactly what a real device would send.
 * All methods run on the client thread except where noted.
 */
public final class VirtualInput {
    /** Degrees per second for look movement; fast but visibly a turn, not a teleport. */
    private static final float LOOK_SPEED = 360f;
    /** Pixels of window space per second for the visible cursor. */
    private static final double CURSOR_SPEED = 2600;

    private static final Set<Integer> keysDown = new HashSet<>();
    private static final Set<Integer> buttonsDown = new HashSet<>();

    private static boolean lookActive;
    private static float lookYaw;
    private static float lookPitch;
    private static boolean cursorActive;
    private static double cursorX;
    private static double cursorY;
    private static long lastFrameNanos;

    private VirtualInput() {}

    public static boolean isKeyDown(int key) {
        return keysDown.contains(key);
    }

    static void reset() {
        lookActive = false;
        cursorActive = false;
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static int modifiers() {
        int mods = 0;
        if (keysDown.contains(GLFW.GLFW_KEY_LEFT_SHIFT) || keysDown.contains(GLFW.GLFW_KEY_RIGHT_SHIFT)) {
            mods |= GLFW.GLFW_MOD_SHIFT;
        }
        if (keysDown.contains(GLFW.GLFW_KEY_LEFT_CONTROL) || keysDown.contains(GLFW.GLFW_KEY_RIGHT_CONTROL)) {
            mods |= GLFW.GLFW_MOD_CONTROL;
        }
        if (keysDown.contains(GLFW.GLFW_KEY_LEFT_ALT) || keysDown.contains(GLFW.GLFW_KEY_RIGHT_ALT)) {
            mods |= GLFW.GLFW_MOD_ALT;
        }
        return mods;
    }

    // ---- keyboard ----

    public static void keyDown(int key) {
        if (!Control.isAi()) {
            return;
        }
        keysDown.add(key);
        sendKey(key, GLFW.GLFW_PRESS);
    }

    public static void keyUp(int key) {
        if (!Control.isAi()) {
            return;
        }
        keysDown.remove(key);
        sendKey(key, GLFW.GLFW_RELEASE);
    }

    public static void keyTap(int key) {
        keyDown(key);
        keyUp(key);
    }

    private static void sendKey(int key, int action) {
        Control.setInjecting(true);
        try {
            Input.keyPress(key, GLFW.glfwGetKeyScancode(key), modifiers(), action);
        } finally {
            Control.setInjecting(false);
        }
    }

    public static void typeChar(int codepoint) {
        if (!Control.isAi()) {
            return;
        }
        Control.setInjecting(true);
        try {
            Input.charTyped(codepoint);
        } finally {
            Control.setInjecting(false);
        }
    }

    /** Presses or releases whatever device a KeyMapping is bound to, so rebinds keep working. */
    public static void setMapping(KeyMapping mapping, boolean down) {
        InputConstants.Key key = mapping.getKey();
        if (key.getType() == InputConstants.Type.MOUSE) {
            mouseButton(key.getValue(), down);
        } else if (down != keysDown.contains(key.getValue())) {
            if (down) {
                keyDown(key.getValue());
            } else {
                keyUp(key.getValue());
            }
        }
    }

    public static void tapMapping(KeyMapping mapping) {
        setMapping(mapping, true);
        setMapping(mapping, false);
    }

    // ---- mouse ----

    public static void mouseButton(int button, boolean down) {
        if (!Control.isAi() || down == buttonsDown.contains(button)) {
            return;
        }
        if (down) {
            buttonsDown.add(button);
        } else {
            buttonsDown.remove(button);
        }
        Control.setInjecting(true);
        try {
            Input.mouseButton(button, modifiers(), down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE);
        } finally {
            Control.setInjecting(false);
        }
    }

    /** Puts the cursor at a window position immediately. */
    public static void mouseTo(double x, double y) {
        if (!Control.isAi()) {
            return;
        }
        Control.setInjecting(true);
        try {
            Input.mouseMove(x, y);
        } finally {
            Control.setInjecting(false);
        }
    }

    /** A relative device movement, the form a VLA emits. Turns the player when the mouse is grabbed. */
    public static void mouseDelta(double dx, double dy) {
        mouseTo(mc().mouseHandler.xpos() + dx, mc().mouseHandler.ypos() + dy);
    }

    public static void scroll(double dx, double dy) {
        if (!Control.isAi()) {
            return;
        }
        Control.setInjecting(true);
        try {
            Input.scroll(dx, dy);
        } finally {
            Control.setInjecting(false);
        }
    }

    /** Starts a visible cursor glide to a window position. Finished when {@link #cursorIdle()} is true. */
    public static void glideCursorTo(double x, double y) {
        cursorX = x;
        cursorY = y;
        cursorActive = true;
    }

    public static boolean cursorIdle() {
        return !cursorActive;
    }

    /** Starts a smooth turn. Finished when {@link #lookIdle()} is true. */
    public static void turnTo(float yaw, float pitch) {
        lookYaw = yaw;
        lookPitch = Mth.clamp(pitch, -90f, 90f);
        lookActive = true;
    }

    public static boolean lookIdle() {
        return !lookActive;
    }

    /** Called once per rendered frame; advances the look and cursor animations. */
    public static void onFrame() {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0.016 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
        lastFrameNanos = now;
        if (!Control.isAi()) {
            return;
        }
        if (lookActive) {
            stepLook(dt);
        }
        if (cursorActive) {
            stepCursor(dt);
        }
    }

    private static void stepLook(double dt) {
        LocalPlayer player = mc().player;
        if (player == null) {
            lookActive = false;
            return;
        }
        float dyaw = Mth.wrapDegrees(lookYaw - player.getYRot());
        float dpitch = lookPitch - player.getXRot();
        float dist = Mth.sqrt(dyaw * dyaw + dpitch * dpitch);
        if (dist < 0.05f) {
            lookActive = false;
            return;
        }
        float step = (float) Math.min(1.0, LOOK_SPEED * dt / dist);
        // Near the target the final frame lands exactly on it.
        if (dist < 1.5f) {
            step = 1f;
        }
        // player.turn takes mouse-style units: yRot += x * 0.15, xRot += y * 0.15.
        player.turn(dyaw * step / 0.15, dpitch * step / 0.15);
    }

    private static void stepCursor(double dt) {
        double x = mc().mouseHandler.xpos();
        double y = mc().mouseHandler.ypos();
        double dx = cursorX - x;
        double dy = cursorY - y;
        double dist = Math.hypot(dx, dy);
        double step = CURSOR_SPEED * dt;
        if (dist <= step) {
            mouseTo(cursorX, cursorY);
            cursorActive = false;
        } else {
            mouseTo(x + dx / dist * step, y + dy / dist * step);
        }
    }

    public static void releaseAll() {
        for (int key : new HashSet<>(keysDown)) {
            keyUp(key);
        }
        for (int button : new HashSet<>(buttonsDown)) {
            mouseButton(button, false);
        }
        KeyMapping.releaseAll();
    }

    /** Applies one device frame: full key and button state plus a mouse movement. */
    public static void applyFrame(Set<Integer> keys, Set<Integer> buttons, double mouseDx, double mouseDy, double scrollY) {
        for (int key : new HashSet<>(keysDown)) {
            if (!keys.contains(key)) {
                keyUp(key);
            }
        }
        for (int key : keys) {
            if (!keysDown.contains(key)) {
                keyDown(key);
            }
        }
        for (int button : new HashSet<>(buttonsDown)) {
            if (!buttons.contains(button)) {
                mouseButton(button, false);
            }
        }
        for (int button : buttons) {
            mouseButton(button, true);
        }
        if (mouseDx != 0 || mouseDy != 0) {
            mouseDelta(mouseDx, mouseDy);
        }
        if (scrollY != 0) {
            scroll(0, scrollY);
        }
    }

    /** Window size used to map screenshot coordinates to the mouse's coordinate space. */
    public static double[] windowSize() {
        Window window = mc().getWindow();
        return new double[] {window.getScreenWidth(), window.getScreenHeight()};
    }
}
