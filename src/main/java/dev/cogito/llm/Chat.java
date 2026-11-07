package dev.cogito.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.cogito.vision.FrameGrabber;

import java.util.List;

/**
 * What a conversation with a model is made of, whoever serves the model. Each provider turns these into its own SDK's
 * types and keeps its own form of the history, since some hand back data that must return unchanged (thinking blocks,
 * thought signatures).
 */
public final class Chat {
    private Chat() {}

    /** Part of a message from the game: words, or a picture of the screen. */
    public sealed interface Part permits Text, Image {}

    public record Text(String text) implements Part {}

    public record Image(FrameGrabber.Frame frame) implements Part {}

    /** A tool the model may call; {@code schema} is a JSON schema object, written as text. */
    public record Tool(String name, String description, String schema) {
        /** A tool whose input is an object with these properties, written as JSON text, of which these are required. */
        public static Tool of(String name, String description, String properties, List<String> required) {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            schema.add("properties", JsonParser.parseString(properties));
            JsonArray names = new JsonArray();
            required.forEach(names::add);
            schema.add("required", names);
            return new Tool(name, description, schema.toString());
        }
    }

    /** A tool call the model made. {@code id} is the provider's, or one made up when it gives none. */
    public record Call(String id, String name, JsonObject input) {}

    /** What a tool call gave back. */
    public record Result(Call call, String text, FrameGrabber.Frame image, boolean isError) {}

    public enum Stop { END, TOOL_USE, MAX_TOKENS, REFUSAL }

    /** Tokens of one reply: input read fresh, input read from the cache, input written to the cache, and output. */
    public record Usage(long input, long cacheRead, long cacheWrite, long output) {
        public static final Usage NONE = new Usage(0, 0, 0, 0);
    }

    public record Reply(String text, String thinking, List<Call> calls, Stop stop, String detail, Usage usage) {}

    /**
     * Everything that stays the same through a conversation. {@code effort} is one of {@link LlmSettings#EFFORTS};
     * {@code jsonSchema}, when set, is what the final answer must be: a JSON object written as text.
     */
    public record Request(String model, String effort, String system, List<Tool> tools, long maxTokens, String jsonSchema) {}

    /**
     * The JSON object in a final answer. Most providers enforce the schema, but a server that only claims the
     * OpenAI API may ignore it, and then the object can come wrapped in a code fence or a sentence.
     */
    public static JsonObject jsonAnswer(String text) {
        String trimmed = text.strip();
        try {
            JsonElement whole = JsonParser.parseString(trimmed);
            if (whole.isJsonObject()) {
                return whole.getAsJsonObject();
            }
        } catch (JsonSyntaxException ignored) {
            // Look for the object inside.
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            JsonElement inner = JsonParser.parseString(trimmed.substring(start, end + 1));
            if (inner.isJsonObject()) {
                return inner.getAsJsonObject();
            }
        }
        throw new JsonSyntaxException("the answer holds no JSON object");
    }
}
