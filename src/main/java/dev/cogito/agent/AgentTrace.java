package dev.cogito.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cogito.CogitoMod;
import dev.cogito.Config;
import dev.cogito.api.AgentEvents;
import dev.cogito.bot.J;
import dev.cogito.llm.Chat;
import dev.cogito.llm.Llm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * One JSON line per thing that happened on a goal, in cogito/traces. When the agent fails, this is what
 * shows where it went wrong: what it planned, what it called, and what the game answered.
 */
final class AgentTrace {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int CLIP = 1500;

    private static Path file;

    private AgentTrace() {}

    static synchronized void start(String goal) {
        file = Config.home().resolve("traces").resolve(LocalDateTime.now().format(STAMP) + ".jsonl");
        write("goal", J.obj("text", goal));
    }

    static synchronized String path() {
        return file == null ? null : Config.home().relativize(file).toString().replace('\\', '/');
    }

    static void plan(Plan plan, boolean complete, String thinking) {
        JsonArray steps = new JsonArray();
        for (Plan.Step step : plan.steps()) {
            JsonArray conditions = new JsonArray();
            step.doneWhen().forEach(conditions::add);
            JsonArray run = new JsonArray();
            step.run().forEach(run::add);
            steps.add(J.obj("instruction", step.instruction(), "done_when", conditions, "run", run));
        }
        write("plan", J.obj("summary", plan.summary(), "complete", complete, "steps", steps));
        AgentEvents.emit("plan", () -> J.obj("goal_id", AgentLoop.get().goalId(), "summary", plan.summary(), "complete", complete,
                "thinking", thinking, "model", Llm.describe(Llm.Role.PLANNER), "steps", steps));
    }

    static void turn(Chat.Reply reply, String model) {
        JsonArray calls = new JsonArray();
        for (Chat.Call call : reply.calls()) {
            calls.add(J.obj("tool", call.name(), "input", call.input()));
        }
        String stop = reply.stop().name().toLowerCase();
        Chat.Usage usage = reply.usage();
        write("turn", J.obj("model", model, "stop", stop,
                "thinking", reply.thinking().isEmpty() ? null : clip(reply.thinking()),
                "text", clip(reply.text()),
                "input", usage.input(),
                "cache_read", usage.cacheRead(),
                "cache_write", usage.cacheWrite(),
                "output", usage.output()));
        AgentEvents.emit("turn", () -> J.obj("thinking", reply.thinking(), "text", reply.text(), "stop", stop, "model", model,
                "input_tokens", usage.input() + usage.cacheRead() + usage.cacheWrite(), "output_tokens", usage.output(), "calls", calls));
    }

    static void tool(String name, JsonObject input, String output, boolean image, boolean error) {
        write("tool", J.obj("name", name, "input", clip(input.toString()), "output", clip(output),
                "image", image ? true : null, "error", error ? true : null));
    }

    static void note(String kind, String text) {
        write(kind, J.obj("text", clip(text)));
    }

    private static String clip(String text) {
        return text.length() > CLIP ? text.substring(0, CLIP) + "…" : text;
    }

    private static synchronized void write(String kind, JsonObject row) {
        if (file == null) {
            return;
        }
        row.addProperty("kind", kind);
        row.addProperty("time", System.currentTimeMillis());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, row + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            CogitoMod.LOG.warn("Could not write the trace: {}", e.toString());
        }
    }
}
