package dev.cogito.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cogito.bot.Api;
import dev.cogito.bot.Bot;
import dev.cogito.usage.UsageRegistry;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The public side of the usage declaration protocol, for other mods: how an item or block is used, written once by
 * its maker, read by the AI like a skill and run by it like a tool.
 *
 * <p>A mod writes the manual as {@code assets/<modid>/ai_usage/<name>.md}. Simple actions are steps in that file.
 * When an action needs real code, there are two ways, and neither needs this mod to be installed:
 *
 * <ol>
 * <li>Name a class in the frontmatter, {@code handler: com.example.mymod.compat.CogitoActions}, with one public
 * static method whose signature uses only Gson and the JDK:
 * <pre>{@code
 * public static JsonElement invoke(String action, JsonObject args, BiFunction<String, JsonObject, JsonObject> game)
 * }</pre>
 * {@code game.apply("goto", args)} runs any function of the AI's api (the README lists them) and answers with
 * {@code ok}, {@code result} or {@code error}, {@code message}, {@code hint}. Return a JSON result, or an object with
 * {@code error}, {@code message} and optional {@code hint} to fail. The class is loaded only when this mod is present
 * and the action is used, so the mod compiles without this one. Leave the third parameter out if no game functions
 * are needed.
 * <li>Or, compiling against {@code cogito-api.jar} ({@code gradlew apiJar}) behind a {@code ModList.isLoaded}
 * check, register the handler here:
 * <pre>{@code
 * AiUsage.handler("mymod:flux_core", "charge", args -> {
 *     // Runs on a worker thread; use onGameThread for game access, call for the AI's functions.
 *     return myResultAsJson;
 * });
 * }</pre>
 * </ol>
 *
 * <p>Either way the actions run off the game thread, on the thread that plays, so they may block and wait; use
 * {@link #onGameThread} (or {@code Minecraft.getInstance().submit}) to touch the world.
 */
public final class AiUsage {
    private AiUsage() {}

    /** Code for an action of a declaration; the action's name must still appear in the declaration's actions block. */
    public static void handler(String declarationId, String action, Function<JsonObject, JsonElement> handler) {
        UsageRegistry.registerHandler(declarationId, action, handler);
    }

    /**
     * Runs one of the AI's functions by name, such as {@code goto}, {@code useOn} or {@code inventory}, as the model
     * would. The answer has {@code ok} and {@code result}, or {@code error}, {@code message} and {@code hint}.
     */
    public static JsonObject call(String fn, JsonObject args) {
        return Api.call(fn, args == null ? new JsonObject() : args);
    }

    /** Runs code on the game thread and waits for its answer, as the AI's own functions do. */
    public static <T> T onGameThread(Supplier<T> task) {
        return Bot.call(task);
    }
}
