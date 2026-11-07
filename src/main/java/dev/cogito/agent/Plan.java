package dev.cogito.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

/**
 * The planner's output, and what an external AI may hand in directly. Each step says in done_when how the
 * game itself will confirm it, so completion never rests on what the executor reports about itself.
 */
public record Plan(String summary, List<Step> steps) {
    /**
     * run, when not empty, is the function calls that carry the step out with no model: each {"fn":..,"args":{..}}.
     * If they do not get it done, the executor takes over from there.
     */
    public record Step(String instruction, List<String> doneWhen, List<JsonObject> run) {}

    /** A run entry written as text by a model. Null if it is not a call: the executor then does that step. */
    static JsonObject parseCall(String text) {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("fn")) {
                return parsed.getAsJsonObject();
            }
        } catch (RuntimeException e) {
            // Falls through: a bad entry is the model's slip, and the executor can still do the step.
        }
        return null;
    }
}
