package dev.cogito.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cogito.bot.J;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One thing the agent was asked to do, with what must survive being set aside or a restart of the game.
 * A long-term goal differs in three ways: short goals go ahead of it at step boundaries, only failures in a row
 * count against it, and it has its own spending limit.
 */
final class Goal {
    private static final int MAX_HISTORY = 6000;
    private static final AtomicInteger COUNTER = new AtomicInteger();

    final String id;
    final String text;
    final String source;
    final boolean longTerm;
    /** Steps supplied by the external AI, which skip the planner. */
    final Plan givenPlan;
    final String executor;

    private final StringBuilder history = new StringBuilder();
    /** Next step of a given plan. The planner's own plans are rewritten on resume, since the world moved on. */
    volatile int nextStep;
    volatile int replans;
    volatile double spentUsd;
    /** Finished or cancelled: not to run or be saved again. */
    volatile boolean ended;
    volatile boolean cancelled;
    /** Left behind in another world's file: this session no longer owns it. */
    volatile boolean detached;

    Goal(String text, String source, boolean longTerm, Plan givenPlan, String executor) {
        this("g" + Long.toString(System.currentTimeMillis(), 36) + COUNTER.incrementAndGet(), text, source, longTerm, givenPlan, executor);
    }

    private Goal(String id, String text, String source, boolean longTerm, Plan givenPlan, String executor) {
        this.id = id;
        this.text = text;
        this.source = source;
        this.longTerm = longTerm;
        this.givenPlan = givenPlan;
        this.executor = executor;
    }

    synchronized void note(String line) {
        history.append(line).append('\n');
        if (history.length() > MAX_HISTORY) {
            int cut = history.indexOf("\n", history.length() - MAX_HISTORY);
            history.delete(0, cut < 0 ? history.length() - MAX_HISTORY : cut + 1);
        }
    }

    synchronized String history() {
        return history.toString();
    }

    String horizon() {
        return longTerm ? "long" : "short";
    }

    synchronized JsonObject toJson() {
        JsonObject out = J.obj("id", id, "text", text, "source", source, "horizon", horizon(),
                "next_step", nextStep, "replans", replans, "spent_usd", spentUsd, "history", history.toString());
        if (executor != null) {
            out.addProperty("executor", executor);
        }
        if (givenPlan != null) {
            out.add("steps", stepsJson(givenPlan));
        }
        return out;
    }

    static Goal fromJson(JsonObject json) {
        Plan plan = json.has("steps") ? new Plan("plan from the external AI", stepsFromJson(json.getAsJsonArray("steps"))) : null;
        Goal goal = new Goal(J.str(json, "id"), J.str(json, "text"), J.str(json, "source", "saved"),
                "long".equals(J.str(json, "horizon", "short")), plan, J.str(json, "executor", null));
        goal.nextStep = J.integer(json, "next_step", 0);
        goal.replans = J.integer(json, "replans", 0);
        goal.spentUsd = J.number(json, "spent_usd", 0);
        goal.history.append(J.str(json, "history", ""));
        return goal;
    }

    static JsonArray stepsJson(Plan plan) {
        JsonArray steps = new JsonArray();
        for (Plan.Step step : plan.steps()) {
            JsonArray conditions = new JsonArray();
            step.doneWhen().forEach(conditions::add);
            JsonObject row = new JsonObject();
            row.addProperty("instruction", step.instruction());
            row.add("done_when", conditions);
            if (!step.run().isEmpty()) {
                JsonArray run = new JsonArray();
                step.run().forEach(run::add);
                row.add("run", run);
            }
            steps.add(row);
        }
        return steps;
    }

    static List<Plan.Step> stepsFromJson(JsonArray array) {
        List<Plan.Step> steps = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject step = element.getAsJsonObject();
            List<String> conditions = new ArrayList<>();
            if (step.has("done_when") && step.get("done_when").isJsonArray()) {
                step.getAsJsonArray("done_when").forEach(c -> conditions.add(c.getAsString()));
            }
            List<JsonObject> run = new ArrayList<>();
            if (step.has("run") && step.get("run").isJsonArray()) {
                for (JsonElement call : step.getAsJsonArray("run")) {
                    if (call.isJsonObject()) {
                        run.add(call.getAsJsonObject());
                    }
                }
            }
            steps.add(new Plan.Step(J.str(step, "instruction"), conditions, run));
        }
        return steps;
    }
}
