package dev.cogito.bot;

import dev.cogito.compat.Input;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import dev.cogito.Config;
import dev.cogito.agent.AgentLoop;
import dev.cogito.agent.Reflexes;
import dev.cogito.api.AgentEvents;
import dev.cogito.control.VirtualInput;
import dev.cogito.vision.FrameGrabber;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Computer use, scoped to this game window: screenshot, then cursor and keyboard, with the action names
 * models already know. Events go through the game's own handlers, so the system mouse is untouched and
 * nothing outside the window can be clicked.
 */
public final class ScreenTool {
    public record Result(JsonObject json, FrameGrabber.Frame image) {}

    public static final String DESCRIPTION = """
            Control this game window like a person with a screen, mouse and keyboard. Use it for anything the api has no \
            function for: an unknown machine, a modded GUI, a button, a tab, a text box. Actions: \
            describe (structured view: every widget and item slot with its coordinates; try this first, it costs far \
            less than a picture), screenshot, zoom (region:[x0,y0,x1,y1] for fine detail), mouse_move, left_click, \
            right_click, middle_click, double_click, left_mouse_down, left_mouse_up, left_click_drag (start and \
            coordinate), scroll (coordinate, scroll_y: positive is up), key (text like "Escape", "e", "ctrl+a"), \
            hold_key (text, seconds), type (text), wait (seconds), mouse_delta (dx, dy: turn the view while in the world). \
            coordinate is [x,y] in the pixels of the latest screenshot; every action can add with_screenshot:true to get a \
            picture of the result. modifier:"shift" holds Shift during a click.""";

    /** Negative, so these call ids can never collide with the ones from Api. */
    private static final AtomicLong CALL_IDS = new AtomicLong();

    private ScreenTool() {}

    public static boolean isRead(String action) {
        return switch (action) {
            case "screenshot", "describe", "zoom", "wait" -> true;
            default -> false;
        };
    }

    public static Result run(JsonObject args) {
        String action = J.str(args, "action");
        if (isRead(action)) {
            return perform(action, args);
        }
        Bot.requireControl();
        AgentLoop.get().requireNotBusyFor(Thread.currentThread());
        Long outerAction = Reflexes.beginAction();
        long callId = CALL_IDS.decrementAndGet();
        AgentEvents.emit("call_start", () -> J.obj("id", callId, "fn", "screen." + action, "args", args, "read", false));
        boolean ok = false;
        String error = null;
        try {
            guard();
            AgentLoop.get().noteAction("screen " + action);
            Result result = perform(action, args);
            ok = true;
            return result;
        } catch (ApiError e) {
            error = e.kind;
            throw e;
        } finally {
            Reflexes.endAction(outerAction);
            boolean finalOk = ok;
            String finalError = error;
            AgentEvents.emit("call_end", () -> J.obj("id", callId, "fn", "screen." + action, "ok", finalOk, "error", finalError));
        }
    }

    private static Result perform(String action, JsonObject args) {
        JsonObject out = new JsonObject();
        out.addProperty("action", action);
        FrameGrabber.Frame image = null;
        switch (action) {
            case "describe" -> out = Bot.call(ScreenTool::describe);
            case "screenshot" -> {
                image = FrameGrabber.grab(Config.get().screenshotMaxEdge, null);
                out.add("size", J.nums(image.width(), image.height()));
            }
            case "zoom" -> {
                image = zoom(args);
                out.add("size", J.nums(image.width(), image.height()));
                out.addProperty("note", "coordinates stay in full screenshot pixels, not in this zoomed image");
            }
            case "wait" -> Bot.pause((long) (J.number(args, "seconds", 1) * 1000));
            case "mouse_move" -> moveTo(args);
            case "left_click" -> click(args, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            case "right_click" -> click(args, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            case "middle_click" -> click(args, GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
            case "double_click" -> {
                click(args, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                Bot.pause(60);
                pressRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT, null);
            }
            case "left_mouse_down" -> Bot.run(() -> VirtualInput.mouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, true));
            case "left_mouse_up" -> Bot.run(() -> VirtualInput.mouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, false));
            case "left_click_drag" -> drag(args);
            case "scroll" -> {
                if (args.has("coordinate")) {
                    moveTo(args);
                }
                double amount = J.number(args, "scroll_y", 1);
                Bot.run(() -> VirtualInput.scroll(0, amount));
            }
            case "key" -> key(J.str(args, "text"));
            case "hold_key" -> holdKey(J.str(args, "text"), J.number(args, "seconds", 1));
            case "type" -> type(J.str(args, "text"));
            case "mouse_delta" -> {
                double dx = J.number(args, "dx", 0);
                double dy = J.number(args, "dy", 0);
                Bot.run(() -> VirtualInput.mouseDelta(dx, dy));
            }
            default -> throw new ApiError("bad_argument", "unknown screen action '" + action + "'");
        }
        if (J.bool(args, "with_screenshot", false) && image == null) {
            // Let the screen react before the picture is taken.
            Bot.waitTicks(3);
            image = FrameGrabber.grab(Config.get().screenshotMaxEdge, null);
        }
        if (!out.has("window")) {
            out.addProperty("window", Bot.call(() -> Bot.mc().screen == null ? "none" : Bot.mc().screen.getClass().getSimpleName()));
        }
        return new Result(out, image);
    }

    /** Menus outside a world can delete worlds and change the servers list, so they are opt-in. */
    private static void guard() {
        Bot.run(() -> {
            Minecraft mc = Bot.mc();
            boolean menu = mc.level == null;
            if (menu && !Config.get().allowMenus) {
                throw new ApiError("not_allowed", "screen control outside a world is turned off",
                        "the person can join a world, or set allowMenus in config.json");
            }
            if (mc.screen instanceof EditWorldScreen) {
                throw new ApiError("not_allowed", "the world editing screen is protected: it can delete worlds");
            }
        });
    }

    // ---- coordinates ----

    private static double[] windowPoint(JsonObject args, String key) {
        JsonArray point = args.getAsJsonArray(key);
        if (point == null || point.size() != 2) {
            throw new ApiError("bad_argument", key + " must be [x,y]");
        }
        int[] shot = FrameGrabber.shotSize(Config.get().screenshotMaxEdge);
        double[] size = VirtualInput.windowSize();
        return new double[] {point.get(0).getAsDouble() * size[0] / shot[0], point.get(1).getAsDouble() * size[1] / shot[1]};
    }

    private static void moveTo(JsonObject args) {
        double[] target = Bot.call(() -> {
            if (Bot.mc().mouseHandler.isMouseGrabbed() && Bot.mc().screen == null) {
                throw new ApiError("no_cursor", "in the world the mouse turns the view; there is no cursor to move",
                        "use look, lookAt or mouse_delta; open a window first to get a cursor");
            }
            if (Bot.mc().getWindow().getScreenWidth() <= 0) {
                throw new ApiError("window_minimized", "the game window is minimized, so a click here would land nowhere",
                        "the api's functions for item windows still work; anything that needs the cursor waits until the window is back");
            }
            return windowPoint(args, "coordinate");
        });
        glide(target);
    }

    private static void glide(double[] target) {
        Bot.run(() -> VirtualInput.glideCursorTo(target[0], target[1]));
        if (!Bot.waitUntil(VirtualInput::cursorIdle, 2500)) {
            throw new ApiError("timeout", "the cursor did not arrive; is the window minimized?");
        }
        Bot.waitTicks(1);
    }

    private static void click(JsonObject args, int button) {
        if (args.has("coordinate")) {
            moveTo(args);
        }
        pressRelease(button, J.str(args, "modifier", null));
    }

    private static void pressRelease(int button, String modifier) {
        int mod = modifier == null ? -1 : keyCode(modifier);
        Bot.run(() -> {
            if (mod >= 0) {
                VirtualInput.keyDown(mod);
            }
            VirtualInput.mouseButton(button, true);
        });
        Bot.pause(40);
        Bot.run(() -> {
            VirtualInput.mouseButton(button, false);
            if (mod >= 0) {
                VirtualInput.keyUp(mod);
            }
        });
        Bot.pause(60);
    }

    private static void drag(JsonObject args) {
        double[] start = Bot.call(() -> windowPoint(args, "start"));
        double[] end = Bot.call(() -> windowPoint(args, "coordinate"));
        glide(start);
        Bot.run(() -> VirtualInput.mouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, true));
        Bot.pause(60);
        glide(end);
        Bot.pause(60);
        Bot.run(() -> VirtualInput.mouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, false));
        Bot.pause(60);
    }

    private static FrameGrabber.Frame zoom(JsonObject args) {
        JsonArray region = args.getAsJsonArray("region");
        if (region == null || region.size() != 4) {
            throw new ApiError("bad_argument", "region must be [x0,y0,x1,y1]");
        }
        int[] shot = Bot.call(() -> FrameGrabber.shotSize(Config.get().screenshotMaxEdge));
        return FrameGrabber.grab(Config.get().screenshotMaxEdge, new double[] {
                region.get(0).getAsDouble() / shot[0], region.get(1).getAsDouble() / shot[1],
                region.get(2).getAsDouble() / shot[0], region.get(3).getAsDouble() / shot[1]});
    }

    // ---- keyboard ----

    private static void key(String combo) {
        String[] parts = combo.split("\\+");
        Bot.run(() -> {
            for (int i = 0; i < parts.length - 1; i++) {
                VirtualInput.keyDown(keyCode(parts[i]));
            }
            VirtualInput.keyTap(keyCode(parts[parts.length - 1]));
            for (int i = parts.length - 2; i >= 0; i--) {
                VirtualInput.keyUp(keyCode(parts[i]));
            }
        });
        Bot.waitTicks(2);
    }

    private static void holdKey(String name, double seconds) {
        int code = keyCode(name);
        Bot.run(() -> VirtualInput.keyDown(code));
        try {
            long end = System.currentTimeMillis() + (long) (seconds * 1000);
            while (System.currentTimeMillis() < end) {
                Bot.requireControl();
                Bot.pause(50);
            }
        } finally {
            Bot.run(() -> VirtualInput.keyUp(code));
        }
    }

    static void type(String text) {
        text.codePoints().forEach(codepoint -> {
            Bot.requireControl();
            Bot.run(() -> {
                if (codepoint == '\n') {
                    VirtualInput.keyTap(GLFW.GLFW_KEY_ENTER);
                } else {
                    VirtualInput.typeChar(codepoint);
                }
            });
            Bot.pause(8);
        });
    }

    static int keyCode(String raw) {
        String name = raw.trim().toLowerCase(Locale.ROOT);
        if (name.length() == 1) {
            char c = name.charAt(0);
            if (c >= 'a' && c <= 'z') {
                return GLFW.GLFW_KEY_A + (c - 'a');
            }
            if (c >= '0' && c <= '9') {
                return GLFW.GLFW_KEY_0 + (c - '0');
            }
        }
        if (name.matches("f([1-9]|1[0-2])")) {
            return GLFW.GLFW_KEY_F1 + Integer.parseInt(name.substring(1)) - 1;
        }
        return switch (name) {
            case "esc", "escape" -> GLFW.GLFW_KEY_ESCAPE;
            case "enter", "return" -> GLFW.GLFW_KEY_ENTER;
            case "tab" -> GLFW.GLFW_KEY_TAB;
            case "space" -> GLFW.GLFW_KEY_SPACE;
            case "backspace" -> GLFW.GLFW_KEY_BACKSPACE;
            case "delete" -> GLFW.GLFW_KEY_DELETE;
            case "up" -> GLFW.GLFW_KEY_UP;
            case "down" -> GLFW.GLFW_KEY_DOWN;
            case "left" -> GLFW.GLFW_KEY_LEFT;
            case "right" -> GLFW.GLFW_KEY_RIGHT;
            case "home" -> GLFW.GLFW_KEY_HOME;
            case "end" -> GLFW.GLFW_KEY_END;
            case "pageup" -> GLFW.GLFW_KEY_PAGE_UP;
            case "pagedown" -> GLFW.GLFW_KEY_PAGE_DOWN;
            case "shift" -> GLFW.GLFW_KEY_LEFT_SHIFT;
            case "ctrl", "control" -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case "alt" -> GLFW.GLFW_KEY_LEFT_ALT;
            default -> throw new ApiError("bad_argument", "unknown key '" + raw + "'");
        };
    }

    // ---- structured view ----

    private static int[] guiToShot(double guiX, double guiY) {
        Window window = Bot.mc().getWindow();
        int[] shot = FrameGrabber.shotSize(Config.get().screenshotMaxEdge);
        return new int[] {(int) Math.round(guiX * shot[0] / window.getGuiScaledWidth()),
                (int) Math.round(guiY * shot[1] / window.getGuiScaledHeight())};
    }

    private static JsonObject describe() {
        Minecraft mc = Bot.mc();
        Screen screen = mc.screen;
        int[] shot = FrameGrabber.shotSize(Config.get().screenshotMaxEdge);
        JsonObject out = J.obj("screen", screen == null ? "none (in the world)" : screen.getClass().getName(),
                "shot_size", J.nums(shot[0], shot[1]));
        if (screen == null) {
            if (mc.level != null) {
                out.addProperty("crosshair", Bot.cursorText());
            }
            return out;
        }
        out.addProperty("title", screen.getTitle().getString());
        Window window = mc.getWindow();
        int[] cursor = guiToShot(Input.guiMouseX(), Input.guiMouseY());
        out.add("cursor", J.nums(cursor[0], cursor[1]));
        JsonArray widgets = new JsonArray();
        collectWidgets(screen.children(), widgets, 0);
        out.add("widgets", widgets);
        if (screen instanceof AbstractContainerScreen<?> container) {
            out.add("slots", slots(container));
            ItemStack carried = container.getMenu().getCarried();
            if (!carried.isEmpty()) {
                out.add("cursor_item", Bot.stackJson(carried));
            }
        }
        return out;
    }

    private static void collectWidgets(List<? extends GuiEventListener> children, JsonArray out, int depth) {
        for (GuiEventListener child : children) {
            if (out.size() >= 120) {
                return;
            }
            if (child instanceof AbstractWidget widget && widget.visible) {
                int[] center = guiToShot(widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0);
                JsonObject row = J.obj("kind", widget.getClass().getSimpleName(), "text", widget.getMessage().getString(),
                        "center", J.nums(center[0], center[1]));
                if (!widget.active) {
                    row.addProperty("disabled", true);
                }
                out.add(row);
            }
            if (child instanceof ContainerEventHandler nested && depth < 4) {
                collectWidgets(nested.children(), out, depth + 1);
            }
        }
    }

    private static JsonArray slots(AbstractContainerScreen<?> screen) {
        JsonArray out = new JsonArray();
        var menu = screen.getMenu();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.isActive()) {
                continue;
            }
            int[] center = guiToShot(((dev.cogito.mixin.ContainerScreenAccessor) screen).cogito$leftPos() + slot.x + 8.0, ((dev.cogito.mixin.ContainerScreenAccessor) screen).cogito$topPos() + slot.y + 8.0);
            int[] shot = FrameGrabber.shotSize(Config.get().screenshotMaxEdge);
            JsonObject row = J.obj("slot", i, "side", slot.container instanceof Inventory ? "you" : "window");
            // Screens that lay themselves out can leave a wrong position on a slot. A point off the picture is no use
            // to anyone: go by the picture, or use the clickWindow function with the slot number.
            if (center[0] >= 0 && center[1] >= 0 && center[0] < shot[0] && center[1] < shot[1]) {
                row.add("center", J.nums(center[0], center[1]));
            } else {
                row.addProperty("off_picture", true);
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                row.add("item", Bot.stackJson(stack));
                // Modded items often explain themselves in their tooltip.
                if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("minecraft")) {
                    JsonArray tooltip = new JsonArray();
                    stack.getTooltipLines(Item.TooltipContext.of(Bot.level()), Bot.player(), TooltipFlag.NORMAL)
                            .forEach(line -> tooltip.add(line.getString()));
                    row.add("tooltip", tooltip);
                }
            }
            out.add(row);
        }
        return out;
    }
}
