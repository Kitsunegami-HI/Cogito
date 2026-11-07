package dev.cogito.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cogito.CogitoMod;
import dev.cogito.Config;
import dev.cogito.api.AgentEvents;
import dev.cogito.bot.Api;
import dev.cogito.bot.ApiError;
import dev.cogito.bot.Bot;
import dev.cogito.bot.J;
import dev.cogito.bot.ModText;
import dev.cogito.control.Control;
import dev.cogito.llm.Llm;
import dev.cogito.llm.LlmException;
import dev.cogito.ui.LlmSettingsScreen;
import dev.cogito.memory.MemoryStore;
import dev.cogito.usage.UsageRegistry;
import dev.cogito.vision.FrameGrabber;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs goals one at a time: plan, run each step, verify, replan on failure. Goals are kept per world, so they
 * outlast a restart, and a goal can be set aside for a more urgent one and picked up again afterwards.
 * Personality and long-term relationships are not here; an external AI, or the person, supplies goals.
 */
public final class AgentLoop {
    private static final String GOALS_FILE = "goals.json";
    private static final String NOTHING_DONE = "its done_when was already true before it ran, so nothing was done";
    private static final AgentLoop INSTANCE = new AgentLoop();

    /** Waiting goals in the order they will run. */
    private final List<Goal> queue = new ArrayList<>();
    private volatile Goal current;
    /** The world whose goals are loaded; only the client thread changes it. */
    private volatile String world;
    private volatile Plan plan;
    private volatile int stepIndex;
    private volatile String phase = "idle";
    private volatile String lastAction = "";
    private volatile String lastResult = "";
    private volatile double goalCostStart;
    private volatile Thread worker;
    /** Set when the person takes over: the running step stops and is retried when control comes back. */
    private volatile boolean pausedByPerson;
    /** The running goal should stop now, whether it was dropped or only set aside. */
    private volatile boolean stopRequested;

    private AgentLoop() {}

    public static AgentLoop get() {
        return INSTANCE;
    }

    public void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(this::runForever, "Cogito-Agent");
        worker.setDaemon(true);
        worker.start();
    }

    // ---- inputs ----

    /** mode: replace drops everything else, queue waits its turn, interrupt runs now and resumes the rest after. */
    synchronized void setGoal(Goal goal, String mode) {
        Goal running = current;
        switch (mode) {
            case "queue" -> queue.add(goal);
            case "interrupt" -> {
                queue.add(0, goal);
                if (running != null) {
                    if (!queue.contains(running)) {
                        queue.add(1, running);
                    }
                    running.note("Set aside for a more urgent goal: " + goal.text);
                    stopRunning();
                }
            }
            case "replace" -> {
                for (Goal waiting : queue) {
                    if (waiting != running) {
                        waiting.ended = true;
                        GameEvents.add("goal_end", "Replaced while waiting: " + waiting.text);
                    }
                }
                queue.clear();
                if (running != null) {
                    running.cancelled = true;
                    running.ended = true;
                    stopRunning();
                }
                queue.add(goal);
            }
            default -> throw new ApiError("bad_argument", "mode must be replace, queue or interrupt");
        }
        saveGoals();
        notifyAll();
        GameEvents.add("goal", "New " + goal.horizon() + " goal from " + goal.source + " (" + mode + "): " + goal.text);
    }

    /** Cancels everything. */
    public synchronized void cancel() {
        Goal active = current;
        for (Goal goal : queue) {
            if (goal != active) {
                goal.ended = true;
                GameEvents.add("goal_end", "Cancelled while waiting: " + goal.text);
            }
        }
        queue.clear();
        Goal running = current;
        if (running != null) {
            running.cancelled = true;
            running.ended = true;
            stopRunning();
        }
        saveGoals();
    }

    /** Cancels one goal; the others carry on. */
    synchronized void cancel(String id) {
        Goal running = current;
        for (Goal goal : queue) {
            if (goal.id.equals(id) && goal != running) {
                goal.ended = true;
                queue.remove(goal);
                saveGoals();
                GameEvents.add("goal_end", "Cancelled while waiting: " + goal.text);
                return;
            }
        }
        if (running != null && running.id.equals(id)) {
            queue.remove(running);
            running.cancelled = true;
            running.ended = true;
            stopRunning();
            saveGoals();
            return;
        }
        throw new ApiError("not_found", "no goal with id " + id, "get_state lists the goals");
    }

    private void stopRunning() {
        stopRequested = true;
        worker.interrupt();
    }

    /** The person typed "!ai ...": it sets a goal and hands control to the AI. "long " makes it long-term. */
    public void onPersonCommand(String text) {
        if (text.isEmpty() || text.equals("stop")) {
            cancel();
            return;
        }
        String problem = setupProblem();
        if (problem != null) {
            // The person keeps control, and gets the settings screen with what is missing.
            GameEvents.add("guard", problem);
            LlmSettingsScreen.openSoon(problem);
            return;
        }
        boolean longTerm = text.startsWith("long ");
        setGoal(new Goal(longTerm ? text.substring(5).trim() : text, "person", longTerm, null, null), "replace");
        Minecraft.getInstance().execute(() -> Control.handOverByPerson("!ai command"));
    }

    /** What keeps a goal from reaching a model, or null: the planner always needs one, the executor when it is the LLM. */
    private static String setupProblem() {
        String problem = Llm.problem(Llm.Role.PLANNER);
        if (problem == null && Config.get().executor.equals("llm")) {
            problem = Llm.problem(Llm.Role.EXECUTOR);
        }
        return problem;
    }

    /** Client thread, from the takeover key. */
    public void onHumanTookOver() {
        pausedByPerson = true;
        if (current != null && worker != null) {
            worker.interrupt();
        }
    }

    public void takeControlFromExternal() {
        Config config = Config.get();
        if (!config.allowExternalTakeover) {
            throw new ApiError("not_allowed", "external takeover is off", "the person presses the takeover key, or sets allowExternalTakeover in config.json");
        }
        if (Control.isHumanLocked()) {
            throw new ApiError("human_in_control", "the person took control back; only they can hand it over again");
        }
        Bot.run(() -> Control.takeForAi("external request"));
    }

    // ---- worlds ----

    /** Client thread, once per tick. Each world keeps its own goals. */
    public void tick() {
        String key = MemoryStore.currentWorldKey();
        if (!Objects.equals(key, world)) {
            onWorldChanged(key);
        }
    }

    private synchronized void onWorldChanged(String key) {
        if (world != null) {
            // What is running stays in the file of the world it belongs to; it is neither finished nor failed.
            saveGoals();
            queue.clear();
            Goal running = current;
            if (running != null) {
                running.detached = true;
                running.note("The game left this world while this goal was running.");
                stopRunning();
            }
        }
        world = key;
        if (key != null) {
            List<Goal> stored = loadGoals(key);
            queue.addAll(stored);
            saveGoals();
            if (!stored.isEmpty()) {
                GameEvents.add("goal", "Resuming " + stored.size() + " goal(s) kept for this world, starting with: " + stored.get(0).text);
            }
            notifyAll();
        }
    }

    private List<Goal> loadGoals(String key) {
        List<Goal> out = new ArrayList<>();
        try {
            String text = MemoryStore.readWorldFile(key, GOALS_FILE);
            if (text != null) {
                for (JsonElement element : JsonParser.parseString(text).getAsJsonArray()) {
                    Goal goal = Goal.fromJson(element.getAsJsonObject());
                    goal.note("The game was restarted or the world was re-entered here; check the state before assuming earlier steps still hold.");
                    out.add(goal);
                }
            }
        } catch (RuntimeException e) {
            CogitoMod.LOG.warn("Could not read the saved goals of {}: {}", key, e.toString());
        }
        return out;
    }

    /** Written on every change, so a crash loses at most the step in progress. */
    private synchronized void saveGoals() {
        String key = world;
        if (key == null) {
            return;
        }
        JsonArray out = new JsonArray();
        Goal running = current;
        if (running != null && !running.ended && !running.detached && !queue.contains(running)) {
            out.add(running.toJson());
        }
        for (Goal goal : queue) {
            if (!goal.detached) {
                out.add(goal.toJson());
            }
        }
        MemoryStore.writeWorldFile(key, GOALS_FILE, out.toString());
    }

    // ---- notes from the running layers ----

    public void noteAction(String action) {
        lastAction = action;
    }

    /** What a skill is up to: it runs for minutes with no model, so this is the only account of it. */
    public void noteSkill(String text) {
        GameEvents.add("skill", text);
        AgentTrace.note("skill", text);
    }

    /** Direct calls from an external AI wait their turn: one body, one driver. */
    public void requireNotBusyFor(Thread thread) {
        if (current != null && thread != worker) {
            throw new ApiError("agent_busy", "the agent is working on a goal", "cancel it first, give it a new goal, or use chat, which is always allowed");
        }
    }

    private double spentOn(Goal goal) {
        return goal.spentUsd + Llm.estimatedCostUsd() - goalCostStart;
    }

    private double limitFor(Goal goal) {
        return goal.longTerm ? Config.get().maxLongGoalCostUsd : Config.get().maxGoalCostUsd;
    }

    private void updateSpend(Goal goal) {
        double now = Llm.estimatedCostUsd();
        goal.spentUsd += now - goalCostStart;
        goalCostStart = now;
    }

    /** Called between model turns inside a step. */
    void checkBudget() {
        Goal goal = current;
        if (goal != null && spentOn(goal) > limitFor(goal)) {
            throw new ApiError("budget", "this goal has spent about $" + Math.round(spentOn(goal) * 100) / 100.0 + ", over its limit");
        }
    }

    // ---- the loop ----

    private void runForever() {
        while (true) {
            Goal goal = null;
            try {
                goal = nextGoal();
                goalCostStart = Llm.estimatedCostUsd();
                AgentTrace.start(goal.text);
                Goal started = goal;
                AgentEvents.emit("goal_start", () -> J.obj("id", started.id, "text", started.text, "horizon", started.horizon(), "source", started.source));
                runGoal(goal);
            } catch (InterruptedException e) {
                // A new goal, a cancel, or leaving the world.
                Thread.interrupted();
                if (goal != null && goal.cancelled) {
                    finish(goal, "Cancelled: " + goal.text);
                }
            } catch (RuntimeException | LinkageError e) {
                // A broken goal must not take the agent thread down with it.
                CogitoMod.LOG.error("Agent error", e);
                if (goal != null) {
                    finish(goal, "error: " + e);
                }
            } finally {
                if (goal != null) {
                    updateSpend(goal);
                }
                synchronized (this) {
                    current = null;
                    plan = null;
                    phase = "idle";
                    saveGoals();
                }
            }
        }
    }

    private synchronized Goal nextGoal() throws InterruptedException {
        while (queue.isEmpty()) {
            wait();
        }
        Goal goal = queue.remove(0);
        current = goal;
        stopRequested = false;
        return goal;
    }

    private void finish(Goal goal, String result) {
        Thread.interrupted();
        goal.ended = true;
        synchronized (this) {
            queue.remove(goal);
        }
        lastResult = result;
        GameEvents.add("goal_end", result);
        AgentEvents.emit("goal_end", () -> J.obj("id", goal.id, "text", goal.text, "result", result));
        AgentTrace.note("goal_end", result + " | cost about $" + Math.round(spentOn(goal) * 10000) / 10000.0);
        try {
            MemoryStore.journal("goal_end", result);
        } catch (RuntimeException e) {
            CogitoMod.LOG.warn("Could not write the journal: {}", e.toString());
        }
    }

    private void runGoal(Goal goal) throws InterruptedException {
        Executor executor = chooseExecutor(goal);
        boolean fresh = goal.history().isEmpty();
        plan = goal.givenPlan;
        while (true) {
            if (plan == null) {
                if (spentOn(goal) > limitFor(goal)) {
                    finish(goal, "Stopped at the spending limit: " + goal.text);
                    return;
                }
                Planner.Output output = planWithPauses(goal);
                if (output.complete() || output.plan().steps().isEmpty()) {
                    finish(goal, (fresh ? "Goal already achieved: " : "Done: ") + goal.text + " - " + output.plan().summary());
                    return;
                }
                plan = output.plan();
            }
            if (fresh) {
                MemoryStore.journal("goal", goal.text);
                fresh = false;
            }
            boolean failed = false;
            boolean progressed = false;
            for (stepIndex = goal.givenPlan != null ? goal.nextStep : 0; stepIndex < plan.steps().size(); stepIndex++) {
                if (spentOn(goal) > limitFor(goal)) {
                    finish(goal, "Stopped at the spending limit: " + goal.text);
                    return;
                }
                if (yieldToShortGoal(goal)) {
                    return;
                }
                Plan.Step step = plan.steps().get(stepIndex);
                phase = "executing";
                lastAction = "";
                int index = stepIndex;
                int total = plan.steps().size();
                AgentEvents.emit("step_start", () -> {
                    JsonArray conditions = new JsonArray();
                    step.doneWhen().forEach(conditions::add);
                    return J.obj("goal_id", goal.id, "index", index, "total", total, "instruction", step.instruction(), "done_when", conditions);
                });
                Executor.StepResult result = runStepWithPauses(goal, step, executor);
                AgentEvents.emit("step_end", () -> J.obj("goal_id", goal.id, "index", index, "status", result.status().name().toLowerCase(),
                        "summary", result.summary(), "lesson", result.lesson()));
                String line = "Step \"" + step.instruction() + "\": " + result.status() + " - " + result.summary()
                        + (result.lesson() == null || result.lesson().isBlank() ? "" : " Lesson: " + result.lesson());
                goal.note(line);
                AgentTrace.note("step", line);
                MemoryStore.journal("step", line);
                GameEvents.add("step", line);
                if (result.lesson() != null && !result.lesson().isBlank()) {
                    MemoryStore.journal("lesson", result.lesson());
                    MemoryStore.addLesson(result.lesson(), goal.text + " " + step.instruction(), result.status() == Executor.Status.DONE);
                }
                goal.nextStep = stepIndex + 1;
                updateSpend(goal);
                saveGoals();
                if (result.status() != Executor.Status.DONE) {
                    failed = true;
                    break;
                }
                progressed |= !NOTHING_DONE.equals(result.summary());
            }
            if (goal.givenPlan != null) {
                finish(goal, (failed ? "Stopped: " : "Done: ") + goal.text);
                return;
            }
            // A long goal is ended by failing over and over with nothing to show for it, not by how many plans it has taken
            // or by dying on the way to somewhere it did get to. Its spending limit still ends a run that goes in circles.
            goal.replans = goal.longTerm && (!failed || progressed) ? 0 : goal.replans + 1;
            if (goal.replans > Config.get().maxReplans) {
                finish(goal, "Gave up after " + Config.get().maxReplans + " replans: " + goal.text);
                return;
            }
            plan = null;
        }
    }

    /** A long goal runs behind short ones: at a step boundary it lets any waiting short goal go first. */
    private synchronized boolean yieldToShortGoal(Goal goal) {
        if (!goal.longTerm || queue.stream().allMatch(g -> g.longTerm)) {
            return false;
        }
        int after = 0;
        while (after < queue.size() && !queue.get(after).longTerm) {
            after++;
        }
        queue.add(after, goal);
        goal.note("Paused for a short goal.");
        saveGoals();
        return true;
    }

    private Planner.Output planWithPauses(Goal goal) throws InterruptedException {
        int failures = 0;
        while (true) {
            waitForWorldAndControl();
            pausedByPerson = false;
            phase = "planning";
            try {
                Planner.Output output = Planner.plan(goal, stateText(), planningImage(goal));
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                GameEvents.add("plan", output.plan().summary());
                AgentTrace.plan(output.plan(), output.complete(), output.thinking());
                updateSpend(goal);
                return output;
            } catch (InterruptedException | RuntimeException e) {
                // The SDK reports an interrupted request as a runtime error, so both land here.
                Thread.interrupted();
                if (stopRequested) {
                    throw new InterruptedException();
                }
                if (pausedByPerson || !Control.isAi()) {
                    continue;
                }
                if (e instanceof InterruptedException) {
                    continue;
                }
                if (e instanceof LlmException llm && llm.setup) {
                    // No key or no model: asking again will not help until the person changes the settings.
                    throw llm;
                }
                CogitoMod.LOG.warn("Planning failed", e);
                if (++failures >= 3) {
                    throw (RuntimeException) e;
                }
                Thread.sleep(5000L * failures);
            }
        }
    }

    private FrameGrabber.Frame planningImage(Goal goal) {
        if (!Config.get().planWithScreenshot) {
            return null;
        }
        try {
            return FrameGrabber.grab(Config.get().autoScreenshotEdge, null);
        } catch (ApiError e) {
            return null;
        }
    }

    private Executor chooseExecutor(Goal goal) {
        String kind = goal.executor != null ? goal.executor : Config.get().executor;
        return switch (kind) {
            case "vla", "scripted" -> VlaExecutor.fromConfig(kind);
            default -> new LlmExecutor();
        };
    }

    /**
     * Carries a step out with code alone. Returns what happened in words if that did not finish it, or null if it did.
     * A device-level executor plays the game itself, so it never gets here.
     */
    private String runSkills(Plan.Step step, Vec3 start) throws InterruptedException {
        phase = "running skills";
        List<String> report = new ArrayList<>();
        boolean allOk = true;
        for (JsonObject call : step.run()) {
            JsonObject args = call.has("args") && call.get("args").isJsonObject() ? call.getAsJsonObject("args") : new JsonObject();
            JsonObject result = Api.call(J.str(call, "fn", ""), args);
            boolean ok = result.get("ok").getAsBoolean();
            AgentTrace.tool("skill", call, result.toString(), false, !ok);
            String kind = J.str(result, "error", "");
            // Stopped from outside: the same handling as for a model step, so the loop waits or moves on.
            if (Thread.interrupted() || kind.equals("interrupted") || kind.equals("human_in_control")) {
                throw new InterruptedException();
            }
            report.add(call.get("fn") + ": " + (ok ? result.get("result") : kind + ": " + J.str(result, "message", "")));
            if (!ok) {
                allOk = false;
                break;
            }
        }
        Verifier.Result verified = Bot.call(() -> Verifier.check(step.doneWhen(), start));
        boolean done = step.doneWhen().isEmpty() ? allOk : verified.met();
        return done ? null : String.join("; ", report) + (step.doneWhen().isEmpty() ? "" : "; check: " + verified.detail());
    }

    /** Runs a step; if the person takes over, waits for them to hand back and starts the step again. */
    private Executor.StepResult runStepWithPauses(Goal goal, Plan.Step step, Executor executor) throws InterruptedException {
        while (true) {
            waitForWorldAndControl();
            pausedByPerson = false;
            try {
                Vec3 start = Bot.call(() -> Bot.player().position());
                if (!step.doneWhen().isEmpty() && Bot.call(() -> Verifier.check(step.doneWhen(), start)).met()) {
                    // Said plainly, so a replan does not take a condition that was always true for work that got done.
                    return new Executor.StepResult(Executor.Status.DONE,
                            NOTHING_DONE, null);
                }
                String attempt = null;
                if (!step.run().isEmpty() && !(executor instanceof VlaExecutor)) {
                    attempt = runSkills(step, start);
                    if (attempt == null) {
                        return new Executor.StepResult(Executor.Status.DONE, "done by code, with no model turns", null);
                    }
                    GameEvents.add("skill", "Code did not finish this step, so the executor takes over: " + attempt);
                    phase = "executing";
                }
                String state = stateText() + (attempt == null ? "" : "\n\nCode already tried this step before you, and this is what it reported: " + attempt);
                Executor.Context context = new Executor.Context(goal.text, step, start,
                        () -> Bot.call(() -> Verifier.check(step.doneWhen(), start)), state);
                return executor.run(context);
            } catch (InterruptedException | ApiError e) {
                Thread.interrupted();
                if (stopRequested) {
                    throw new InterruptedException();
                }
                if (pausedByPerson || !Control.isAi()) {
                    phase = "paused: person in control";
                    continue;
                }
                if (e instanceof ApiError api) {
                    return new Executor.StepResult(Executor.Status.FAILED, api.kind + ": " + api.getMessage(), null);
                }
                throw (InterruptedException) e;
            } catch (RuntimeException e) {
                // A transport interruption from the SDK looks like this when the person takes over mid-request.
                Thread.interrupted();
                if (stopRequested) {
                    throw new InterruptedException();
                }
                if (pausedByPerson || !Control.isAi()) {
                    phase = "paused: person in control";
                    continue;
                }
                CogitoMod.LOG.warn("Executor failed", e);
                return new Executor.StepResult(Executor.Status.FAILED, e.toString(), null);
            }
        }
    }

    private void waitForWorldAndControl() throws InterruptedException {
        while (!Control.isAi() || Minecraft.getInstance().player == null) {
            phase = Control.isAi() ? "waiting for a world" : "waiting for control";
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                if (stopRequested) {
                    throw e;
                }
            }
        }
    }

    /** What the planner and the executor start from. */
    String stateText() {
        JsonObject state = new JsonObject();
        state.add("status", Api.call("status", new JsonObject()).get("result"));
        state.add("inventory", Api.call("inventory", new JsonObject()).get("result"));
        JsonElement entities = Api.call("entities", J.obj("radius", 16)).get("result");
        state.add("nearby", entities);
        JsonElement chests = Api.call("chestsKnown", J.obj("radius", 48, "max", 8)).get("result");
        if (chests != null && chests.getAsJsonObject().get("known").getAsInt() > 0) {
            state.add("chests_known", chests);
        }
        Goal goal = current;
        if (Minecraft.getInstance().player != null) {
            JsonObject notes = Bot.call(() -> ModText.notesInPlay(goal == null ? null : goal.text));
            if (!notes.isEmpty()) {
                state.add("mod_notes", notes);
            }
            // What mods wrote for the things carried or near, and for whatever the goal names.
            JsonArray usage = UsageRegistry.relevant(goal == null ? null : goal.text, 3);
            if (!usage.isEmpty()) {
                state.add("usage_declarations", usage);
            }
        }
        JsonArray events = GameEvents.since(Math.max(0, GameEvents.lastSeq() - 8), 8);
        if (!events.isEmpty()) {
            state.add("recent_events", events);
        }
        return state.toString();
    }

    // ---- what the HUD, MCP and recordings read ----

    /** The running goal, or null. */
    public String goalText() {
        Goal goal = current;
        return goal == null ? null : goal.text;
    }

    /** The running goal's id, or null. */
    public String goalId() {
        Goal goal = current;
        return goal == null ? null : goal.id;
    }

    /** No goal and nothing of its own running; a function called from outside may still be. */
    public boolean isIdle() {
        return "idle".equals(phase);
    }

    /** The running step, or an empty string. */
    public String stepText() {
        Plan activePlan = plan;
        int index = stepIndex;
        return activePlan != null && index < activePlan.steps().size() ? activePlan.steps().get(index).instruction() : "";
    }

    public List<String> hudLines() {
        List<String> out = new ArrayList<>();
        Goal goal = current;
        Plan activePlan = plan;
        String reflex = Reflexes.doing();
        if (!reflex.isEmpty()) {
            out.add("反应：" + reflex);
        }
        if (goal == null) {
            out.add("没有目标（" + phase + "）");
            if (!lastResult.isEmpty()) {
                out.add(lastResult);
            }
            return out;
        }
        int waiting = queueSize();
        out.add((goal.longTerm ? "长期目标：" : "目标：") + goal.text + (waiting > 0 ? "（还有 " + waiting + " 个排队）" : ""));
        int index = stepIndex;
        if (activePlan != null && index < activePlan.steps().size()) {
            out.add("步骤 " + (index + 1) + "/" + activePlan.steps().size() + "：" + activePlan.steps().get(index).instruction());
        } else {
            out.add(phase);
        }
        if (!lastAction.isEmpty()) {
            out.add(lastAction);
        }
        return out;
    }

    public JsonObject stateJson() {
        Goal goal = current;
        Plan activePlan = plan;
        JsonObject out = J.obj("control", Control.isAi() ? "ai" : "person", "human_locked", Control.isHumanLocked(),
                "phase", phase, "goal", goal == null ? null : goal.text, "queued", queueSize(),
                "last_action", lastAction, "last_result", lastResult, "reflex", Reflexes.doing(),
                "usage", Llm.usageJson(), "trace", AgentTrace.path());
        out.add("goals", goalsJson());
        if (activePlan != null) {
            JsonArray steps = new JsonArray();
            for (int i = 0; i < activePlan.steps().size(); i++) {
                Plan.Step step = activePlan.steps().get(i);
                JsonArray conditions = new JsonArray();
                step.doneWhen().forEach(conditions::add);
                steps.add(J.obj("index", i, "instruction", step.instruction(), "done_when", conditions,
                        "current", i == stepIndex));
            }
            out.add("plan", J.obj("summary", activePlan.summary(), "steps", steps));
        }
        return out;
    }

    private synchronized JsonArray goalsJson() {
        JsonArray out = new JsonArray();
        Goal running = current;
        if (running != null) {
            out.add(goalRow(running, "running"));
        }
        for (Goal goal : queue) {
            if (goal != running) {
                out.add(goalRow(goal, "waiting"));
            }
        }
        return out;
    }

    private static JsonObject goalRow(Goal goal, String state) {
        return J.obj("id", goal.id, "text", goal.text, "horizon", goal.horizon(), "source", goal.source, "state", state,
                "spent_usd", Math.round(goal.spentUsd * 10000) / 10000.0, "replans", goal.replans);
    }

    private synchronized int queueSize() {
        return queue.size();
    }
}
