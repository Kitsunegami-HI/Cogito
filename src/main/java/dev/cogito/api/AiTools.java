package dev.cogito.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Lets another mod add a tool to this mod's MCP server. */
public final class AiTools {
    public record Tool(String description, String schemaJson, Function<JsonObject, JsonElement> handler) {}

    private static final Map<String, Tool> TOOLS = new ConcurrentHashMap<>();

    private AiTools() {}

    public static void register(String name, String description, String schemaJson, Function<JsonObject, JsonElement> handler) {
        TOOLS.put(name, new Tool(description, schemaJson, handler));
    }

    public static Map<String, Tool> all() {
        return TOOLS;
    }
}
