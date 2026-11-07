package dev.cogito.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cogito.CogitoMod;
import dev.cogito.Config;
import dev.cogito.bot.Api;
import dev.cogito.api.AiTools;
import dev.cogito.bot.ApiError;
import dev.cogito.bot.J;
import dev.cogito.bot.ScreenTool;
import dev.cogito.memory.MemoryStore;
import dev.cogito.usage.UsageRegistry;
import dev.cogito.vision.FrameGrabber;

import java.util.Base64;

/**
 * What an external AI sees over MCP. The main path is set_goal plus watching: the mod's own agent plans and
 * plays, while the external AI keeps the personality and decides what is worth doing. The lower-level tools
 * are the same ones the executor uses, for an AI that wants to play by hand.
 */
public final class ExternalTools {
    public static final String INSTRUCTIONS = """
            Plays Minecraft through a real client. Give goals with set_goal, short or long; the mod plans and carries them \
            out, and you watch with get_state, get_plan, get_events and get_screenshot. It keeps its own gameplay memory, so \
            personality and relationships stay on your side. The person at the computer can take control at any time; when \
            they do, actions fail with human_in_control until they hand it back.""";

    private ExternalTools() {}

    public static JsonArray list() {
        JsonArray tools = new JsonArray();
        tools.add(tool("set_goal", "Give the agent a goal: anything from \"mine 10 iron\" to \"beat the game\". It plans and carries it out. horizon: short (default) or long. A long goal keeps going across many plans, has a larger spending limit, and waits behind any short goal at step boundaries. mode: replace (default) drops everything else; queue runs after what is waiting; interrupt runs now and resumes what it set aside afterwards. Goals are kept per world and survive a restart. steps is optional: your own plan, each with instruction, done_when and optionally run, a list of {fn, args} calls that do the step with no model at all, which skips its planner. executor is optional: llm, vla or scripted.",
                """
                {"type":"object","required":["text"],"properties":{"text":{"type":"string"},"horizon":{"type":"string","enum":["short","long"]},
                 "mode":{"type":"string","enum":["replace","queue","interrupt"]},
                 "executor":{"type":"string"},"steps":{"type":"array","items":{"type":"object","required":["instruction"],"properties":{
                 "instruction":{"type":"string"},"done_when":{"type":"array","items":{"type":"string"}},
                 "run":{"type":"array","items":{"type":"object","required":["fn"],"properties":{"fn":{"type":"string"},"args":{"type":"object"}}}}}}}}}"""));
        tools.add(tool("get_state", "Who is in control, the goal, the plan and its current step, spending, and the player's status, inventory and surroundings.", "{\"type\":\"object\",\"properties\":{}}"));
        tools.add(tool("get_plan", "The current plan and which step is running.", "{\"type\":\"object\",\"properties\":{}}"));
        tools.add(tool("get_events", "What has happened since a sequence number: goals, steps, damage, death, chat. Chat is marked untrusted.", "{\"type\":\"object\",\"properties\":{\"since\":{\"type\":\"number\"}}}"));
        tools.add(tool("get_screenshot", "A picture of the game as it looks now, including the agent's overlay.", "{\"type\":\"object\",\"properties\":{\"max_edge\":{\"type\":\"number\"}}}"));
        tools.add(tool("cancel", "Cancel one goal by id, from get_state; with no id, cancel the running goal and everything waiting.", "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}"));
        tools.add(tool("take_control", "Ask to hand the client to the AI. Refused unless the person allowed it in the settings, and always refused after the person has taken control back.", "{\"type\":\"object\",\"properties\":{}}"));
        tools.add(tool("api", "Run game functions directly, in order, stopping at the first failure. Only while no goal is running. Function index: " + Api.index(),
                "{\"type\":\"object\",\"required\":[\"calls\"],\"properties\":{\"calls\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"required\":[\"fn\"],\"properties\":{\"fn\":{\"type\":\"string\"},\"args\":{\"type\":\"object\"}}}}}}"));
        tools.add(tool("screen", ScreenTool.DESCRIPTION, """
                {"type":"object","required":["action"],"properties":{"action":{"type":"string"},"coordinate":{"type":"array","items":{"type":"number"}},
                 "start":{"type":"array","items":{"type":"number"}},"region":{"type":"array","items":{"type":"number"}},"text":{"type":"string"},
                 "seconds":{"type":"number"},"scroll_y":{"type":"number"},"dx":{"type":"number"},"dy":{"type":"number"},"modifier":{"type":"string"},
                 "with_screenshot":{"type":"boolean"}}}"""));
        tools.add(tool("usage", "Item and block manuals written by mods. op: list, read (id), invoke (id, action, args).",
                "{\"type\":\"object\",\"required\":[\"op\"],\"properties\":{\"op\":{\"type\":\"string\"},\"id\":{\"type\":\"string\"},\"action\":{\"type\":\"string\"},\"scope\":{\"type\":\"string\"},\"args\":{\"type\":\"object\"}}}"));
        tools.add(tool("memory", "The agent's gameplay memory. op: write (scope experience|world, title, text, applies_to), search (query, scope experience|world|other_worlds|all), worlds (every save and server: how far it got, places, people), place (name, x, y, z, note).",
                "{\"type\":\"object\",\"required\":[\"op\"],\"properties\":{\"op\":{\"type\":\"string\"},\"scope\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"},\"applies_to\":{\"type\":\"string\"},\"query\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"note\":{\"type\":\"string\"}}}"));
        AiTools.all().forEach((name, extra) -> tools.add(tool(name, extra.description(), extra.schemaJson())));
        return tools;
    }

    private static JsonObject tool(String name, String description, String schema) {
        return J.obj("name", name, "description", description, "inputSchema", JsonParser.parseString(schema));
    }

    public static JsonObject call(String name, JsonObject args) {
        try {
            AgentLoop agent = AgentLoop.get();
            return switch (name) {
                case "set_goal" -> {
                    agent.setGoal(goal(args), J.str(args, "mode", "replace"));
                    yield text(agent.stateJson().toString());
                }
                case "get_state" -> {
                    JsonObject state = agent.stateJson();
                    if (net.minecraft.client.Minecraft.getInstance().player != null) {
                        state.add("game", JsonParser.parseString(agent.stateText()));
                    }
                    yield text(state.toString());
                }
                case "get_plan" -> text(agent.stateJson().toString());
                case "get_events" -> text(J.obj("events", GameEvents.since((long) J.number(args, "since", 0), 100), "last_seq", GameEvents.lastSeq()).toString());
                case "get_screenshot" -> image("{}", FrameGrabber.grab(J.integer(args, "max_edge", Config.get().screenshotMaxEdge), null));
                case "cancel" -> {
                    String id = J.str(args, "id", null);
                    if (id == null) {
                        agent.cancel();
                    } else {
                        agent.cancel(id);
                    }
                    yield text("cancelled");
                }
                case "take_control" -> {
                    agent.takeControlFromExternal();
                    yield text("the AI has control; the person can take it back at any time");
                }
                case "api" -> text(Api.batch(args.getAsJsonArray("calls")).toString());
                case "screen" -> {
                    ScreenTool.Result result = ScreenTool.run(args);
                    yield result.image() == null ? text(result.json().toString()) : image(result.json().toString(), result.image());
                }
                case "usage" -> text(UsageRegistry.handle(args).toString());
                case "memory" -> text(MemoryStore.handle(args).toString());
                default -> {
                    AiTools.Tool extra = AiTools.all().get(name);
                    yield extra == null ? failure("unknown tool " + name) : text(extra.handler().apply(args).toString());
                }
            };
        } catch (ApiError e) {
            return failure(J.obj("error", e.kind, "message", e.getMessage(), "hint", e.hint).toString());
        } catch (RuntimeException e) {
            CogitoMod.LOG.warn("MCP tool {} failed", name, e);
            return failure(e.toString());
        }
    }

    private static Goal goal(JsonObject args) {
        Plan plan = null;
        if (args.has("steps") && args.get("steps").isJsonArray() && !args.getAsJsonArray("steps").isEmpty()) {
            plan = new Plan("plan from the external AI", Goal.stepsFromJson(args.getAsJsonArray("steps")));
        }
        return new Goal(J.str(args, "text"), "external", "long".equals(J.str(args, "horizon", "short")), plan, J.str(args, "executor", null));
    }

    private static JsonObject text(String value) {
        JsonArray content = new JsonArray();
        content.add(J.obj("type", "text", "text", value));
        return J.obj("content", content);
    }

    private static JsonObject image(String caption, FrameGrabber.Frame frame) {
        JsonArray content = new JsonArray();
        content.add(J.obj("type", "text", "text", caption));
        content.add(J.obj("type", "image", "mimeType", "image/png", "data", Base64.getEncoder().encodeToString(frame.png())));
        return J.obj("content", content);
    }

    private static JsonObject failure(String message) {
        JsonObject out = text(message);
        out.addProperty("isError", true);
        return out;
    }
}
