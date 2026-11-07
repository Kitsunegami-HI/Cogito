package dev.cogito.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cogito.CogitoMod;
import dev.cogito.agent.AgentLoop;
import dev.cogito.agent.Reflexes;
import dev.cogito.api.AgentEvents;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Mineflayer-style function table. The model sees one line per function; a batch of calls runs in order
 * and stops at the first failure, so several steps cost one model turn.
 */
public final class Api {
    @FunctionalInterface
    public interface Handler {
        JsonElement call(JsonObject args);
    }

    /**
     * talk: it uses no part of the body, so it may run alongside a goal and through a reflex.
     * skill: a whole task that runs to the end without a model, and picks up again after a reflex.
     */
    public record Fn(String name, String signature, String summary, boolean read, boolean talk, boolean skill, Handler handler) {}

    private static final Map<String, Fn> FUNCTIONS = new LinkedHashMap<>();
    private static final AtomicLong CALL_IDS = new AtomicLong();

    static {
        Skills.register();
        Blueprints.register();
        Containers.register();
        Brewing.register();
        UsageFns.register();
        WorldFns.register();
        ActionFns.register();
        WindowFns.register();
        ExtraFns.register();
    }

    private Api() {}

    /** A function that only reads the game. */
    static void read(String signature, String summary, Handler handler) {
        add(signature, summary, true, false, false, handler);
    }

    /** A function that presses keys or moves the view. */
    static void act(String signature, String summary, Handler handler) {
        add(signature, summary, false, false, false, handler);
    }

    /** A function that speaks. An external AI can use it while the agent works, so its persona keeps talking. */
    static void talk(String signature, String summary, Handler handler) {
        add(signature, summary, false, true, false, handler);
    }

    /** A whole task made of many actions, which needs no model between them. */
    static void skill(String signature, String summary, Handler handler) {
        add(signature, summary, false, false, true, args -> Skills.resilient(handler, args));
    }

    private static void add(String signature, String summary, boolean read, boolean talk, boolean skill, Handler handler) {
        int paren = signature.indexOf('(');
        String name = paren < 0 ? signature : signature.substring(0, paren);
        FUNCTIONS.put(name, new Fn(name, signature, summary, read, talk, skill, handler));
    }

    /** One line per function, skills first; a stable order so it stays in the prompt cache. */
    public static String index() {
        StringBuilder out = new StringBuilder("Skills: whole tasks that run to the end with no model turn per action. Use these first.\n");
        for (Fn fn : FUNCTIONS.values()) {
            if (fn.skill()) {
                out.append(fn.signature()).append(" - ").append(fn.summary()).append('\n');
            }
        }
        out.append("\nSingle actions and reads, for what no skill covers:\n");
        for (Fn fn : FUNCTIONS.values()) {
            if (!fn.skill()) {
                out.append(fn.signature()).append(" - ").append(fn.summary()).append('\n');
            }
        }
        return out.toString();
    }

    public static int size() {
        return FUNCTIONS.size();
    }

    public static boolean isRead(String name) {
        Fn fn = FUNCTIONS.get(name);
        return fn != null && fn.read();
    }

    private static final java.util.concurrent.atomic.AtomicInteger BODY_CALLS = new java.util.concurrent.atomic.AtomicInteger();

    /** Whether any function that moves the body is running right now, from whoever called it. */
    public static boolean bodyBusy() {
        return BODY_CALLS.get() > 0;
    }

    /** Whether Baritone is walking the player somewhere. Client thread. */
    public static boolean walking() {
        return Pathing.hasBaritone() && BaritoneBridge.pathing();
    }

    /** Runs one call and never throws for a game-level failure: the model reads why it failed. */
    public static JsonObject call(String name, JsonObject args) {
        JsonObject out = new JsonObject();
        out.addProperty("fn", name);
        Fn fn = FUNCTIONS.get(name);
        if (fn == null) {
            // The model's other tools look like functions from inside a list of calls.
            boolean tool = name.equals("usage") || name.equals("memory") || name.equals("screen");
            return failure(out, new ApiError("unknown_function", "no function named '" + name + "'",
                    tool ? name + " is a tool of its own: call it directly, not through api" + (name.equals("usage") ? "; invokeUsage runs a declared action from here" : "") : null));
        }
        boolean usesBody = !fn.read() && !fn.talk();
        Long outerAction = null;
        boolean marked = false;
        long callId = CALL_IDS.incrementAndGet();
        // A copy, because a skill keeps its own notes in its arguments and a listener may hold on to these.
        AgentEvents.emit("call_start", () -> J.obj("id", callId, "fn", name, "args", args.deepCopy(), "read", fn.read()));
        try {
            if (!fn.read()) {
                Bot.requireControl();
            }
            if (usesBody) {
                AgentLoop.get().requireNotBusyFor(Thread.currentThread());
                outerAction = Reflexes.beginAction();
                marked = true;
                BODY_CALLS.incrementAndGet();
                AgentLoop.get().noteAction(name + " " + brief(args));
            }
            JsonElement result = fn.handler().call(args);
            out.addProperty("ok", true);
            if (result != null) {
                out.add("result", result);
            }
            return out;
        } catch (ApiError e) {
            return failure(out, e);
        } catch (RuntimeException e) {
            CogitoMod.LOG.warn("API call {} failed", name, e);
            return failure(out, new ApiError("game_error", e.toString()));
        } finally {
            if (marked) {
                BODY_CALLS.decrementAndGet();
                Reflexes.endAction(outerAction);
            }
            AgentEvents.emit("call_end", () -> {
                JsonObject row = J.obj("id", callId, "fn", name, "ok", out.has("ok") && out.get("ok").getAsBoolean());
                row.add("error", out.get("error"));
                row.add("message", out.get("message"));
                row.add("result", out.get("result"));
                return row;
            });
        }
    }

    private static JsonObject failure(JsonObject out, ApiError e) {
        out.addProperty("ok", false);
        out.addProperty("error", e.kind);
        out.addProperty("message", e.getMessage());
        if (e.hint != null) {
            out.addProperty("hint", e.hint);
        }
        return out;
    }

    /** Runs calls in order; stops at the first failure and says which ones did not run. */
    public static JsonObject batch(JsonArray calls) {
        JsonArray results = new JsonArray();
        int done = 0;
        boolean failed = false;
        for (JsonElement element : calls) {
            JsonObject call = element.getAsJsonObject();
            String name = J.str(call, "fn");
            JsonObject args = call.has("args") && call.get("args").isJsonObject()
                    ? call.getAsJsonObject("args") : new JsonObject();
            JsonObject result = call(name, args);
            results.add(result);
            done++;
            if (!result.get("ok").getAsBoolean()) {
                failed = true;
                break;
            }
        }
        JsonObject out = new JsonObject();
        out.add("results", results);
        if (failed && done < calls.size()) {
            out.addProperty("not_run", calls.size() - done);
        }
        return out;
    }

    private static String brief(JsonObject args) {
        String text = args.toString();
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }
}
