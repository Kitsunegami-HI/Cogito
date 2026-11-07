package dev.cogito.api;

import com.google.gson.JsonObject;
import dev.cogito.CogitoMod;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Lets another mod watch what the agent does, without this mod knowing who is watching. With no listener this costs
 * one empty-list check. Listeners run on the thread doing the work, so they must return quickly.
 *
 * <p>Kinds and their fields:
 * <ul>
 * <li>goal_start: id, text, horizon, source. goal_end: id, text, result.
 * <li>plan: goal_id, summary, complete, thinking, model, steps [{instruction, done_when}].
 * <li>step_start: goal_id, index, total, instruction, done_when. step_end: goal_id, index, status, summary, lesson.
 * <li>turn (a model reply): thinking, text, stop, input_tokens, output_tokens, model, calls [{tool, input}].
 * <li>picture (an image sent to the model): width, height.
 * <li>call_start: id, fn, args, read. call_end: id, fn, ok, error, message, result. Every game function and screen action.
 * </ul>
 */
public final class AgentEvents {
    @FunctionalInterface
    public interface Listener {
        void on(String kind, JsonObject data);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private AgentEvents() {}

    public static void listen(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void emit(String kind, Supplier<JsonObject> data) {
        if (LISTENERS.isEmpty()) {
            return;
        }
        JsonObject row = data.get();
        for (Listener listener : LISTENERS) {
            try {
                listener.on(kind, row);
            } catch (RuntimeException e) {
                // Someone watching must never break the thing being watched.
                CogitoMod.LOG.warn("An agent event listener failed on {}: {}", kind, e.toString());
            }
        }
    }
}
