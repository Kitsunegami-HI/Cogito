package dev.cogito.control;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cogito.compat.Input;
import dev.cogito.CogitoMod;
import dev.cogito.Config;
import dev.cogito.agent.AgentLoop;
import dev.cogito.agent.GameEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;

/**
 * Who owns the client right now. The person always wins: pressing the takeover key locks the AI out
 * until the person gives control back, and nothing on the AI or MCP side can clear that lock.
 */
public final class Control {
    private static final int GLFW_CURSOR_NORMAL = 212993;
    private static final int GLFW_CURSOR_DISABLED = 212995;

    private static volatile boolean ai;
    private static volatile boolean humanLock;
    /** Client-thread only: true while VirtualInput is feeding an event into the game. */
    private static boolean injecting;

    private Control() {}

    public static boolean isAi() {
        return ai;
    }

    public static boolean isHumanLocked() {
        return humanLock;
    }

    public static boolean blocksRealInput() {
        return ai && !injecting;
    }

    /** While the AI drives, slash commands need the setting; private messages are the one exception. */
    public static boolean blocksCommand(String command) {
        if (!ai || Config.get().allowCommands || command.startsWith("msg ") || command.startsWith("tell ") || command.startsWith("w ")) {
            return false;
        }
        GameEvents.add("guard", "Blocked a slash command while the AI was in control: /" + command);
        return true;
    }

    static void setInjecting(boolean value) {
        injecting = value;
    }

    /** Handles the takeover key. Returns true when the event was consumed. */
    public static boolean onRealKey(int action, int key) {
        if (injecting || action != 1 || CogitoMod.TAKEOVER_KEY == null) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof KeyBindsScreen) {
            return false;
        }
        InputConstants.Key bound = CogitoMod.TAKEOVER_KEY.getKey();
        if (bound.getType() != InputConstants.Type.KEYSYM || bound.getValue() != key) {
            return false;
        }
        if (ai) {
            giveToHuman("takeover key");
        } else {
            handOverByPerson("takeover key");
        }
        return true;
    }

    /** Client thread. The person explicitly hands over, which also lifts their own lock. */
    public static void handOverByPerson(String reason) {
        humanLock = false;
        takeForAi(reason);
    }

    /** Client thread. Hands control to the AI. */
    public static void takeForAi(String reason) {
        if (ai) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // Release the OS cursor while the mixin still lets the call through.
        if (mc.mouseHandler.isMouseGrabbed()) {
            Input.grabOrReleaseMouse(GLFW_CURSOR_NORMAL,
                    mc.getWindow().getScreenWidth() / 2.0, mc.getWindow().getScreenHeight() / 2.0);
        }
        KeyMapping.releaseAll();
        VirtualInput.reset();
        // Handing over from the pause menu, or one that opened when the window lost focus, means the game should go on.
        if (mc.screen instanceof PauseScreen) {
            mc.setScreen(null);
        }
        ai = true;
        GameEvents.add("control", "AI took control (" + reason + ")");
        CogitoMod.LOG.info("AI took control: {}", reason);
    }

    /** Client thread. Hands control to the person and stops whatever the AI is doing. */
    public static void giveToHuman(String reason) {
        if (!ai) {
            return;
        }
        VirtualInput.releaseAll();
        VirtualInput.reset();
        humanLock = true;
        ai = false;
        Minecraft mc = Minecraft.getInstance();
        AgentLoop.get().onHumanTookOver();
        if (mc.mouseHandler.isMouseGrabbed()) {
            Input.grabOrReleaseMouse(GLFW_CURSOR_DISABLED,
                    mc.getWindow().getScreenWidth() / 2.0, mc.getWindow().getScreenHeight() / 2.0);
            mc.mouseHandler.setIgnoreFirstMove();
        }
        KeyMapping.setAll();
        GameEvents.add("control", "Person took control (" + reason + ")");
        CogitoMod.LOG.info("Person took control: {}", reason);
    }
}
