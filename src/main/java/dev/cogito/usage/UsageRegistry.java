package dev.cogito.usage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cogito.CogitoMod;
import dev.cogito.Config;
import dev.cogito.bot.Api;
import dev.cogito.bot.ApiError;
import dev.cogito.bot.Bot;
import dev.cogito.bot.J;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Usage declarations: a mod's own explanation of how an item or block is used, read the way Skills are.
 * The model sees one line per declaration, reads the manual when it needs it, then invokes its actions.
 *
 * <p>A declaration is {@code assets/<modid>/ai_usage/<name>.md}: frontmatter ({@code id}, {@code targets},
 * {@code summary}, optional {@code handler}), the manual as the body, and the actions as a fenced {@code actions}
 * JSON block. An action is either steps made of this mod's functions, or code: a handler registered through
 * {@link dev.cogito.api.AiUsage}, or a static {@code invoke} method on the class the frontmatter names, which needs
 * no compile-time dependency on this mod at all (see {@link #invoker}).
 */
public final class UsageRegistry {
    /** One parameter of an action: its name and what to pass. */
    public record Param(String name, String description) {}

    /**
     * One action: declarative steps from the manual, unless code is registered under its name. A {@code read} action
     * only looks and may run without control of the body, so planning can call it.
     */
    public record Action(String summary, List<Param> params, JsonArray steps, boolean read) {}

    public record Declaration(String id, List<String> targets, String summary, String manual, Map<String, Action> actions,
                              String source, String handlerClass) {}

    /** Code behind an action: the handler a mod registered, or the static method of the class a declaration names. */
    @FunctionalInterface
    private interface Invoker {
        JsonElement invoke(String action, JsonObject args);
    }

    private static final long RELOAD_MILLIS = 60_000;
    private static final int SEARCH_RESULTS = 8;
    private static final int NEAR_RADIUS = 32;
    private static final int NEAR_HEIGHT = 16;
    private static final Map<String, Function<JsonObject, JsonElement>> HANDLERS = new ConcurrentHashMap<>();
    private static final Map<String, Invoker> CLASS_INVOKERS = new ConcurrentHashMap<>();
    /** What a declaration's class calls to play: any function of the api, by name, with the usual ok/error answer. */
    private static final BiFunction<String, JsonObject, JsonObject> GAME = Api::call;
    private static volatile Map<String, Declaration> cache = Map.of();
    private static volatile long loadedAt;

    private UsageRegistry() {}

    public static void registerHandler(String id, String action, Function<JsonObject, JsonElement> handler) {
        HANDLERS.put(id + "#" + action, handler);
    }

    static {
        registerHandler("minecraft:clock", "read", args -> Bot.call(() -> {
            long time = Bot.level().getOverworldClockTime() % 24000;
            return J.obj("tick_of_day", time, "phase", time < 12000 ? "day" : time < 13000 ? "dusk" : time < 23000 ? "night" : "dawn");
        }));
    }

    private static Map<String, Declaration> all() {
        if (System.currentTimeMillis() - loadedAt > RELOAD_MILLIS) {
            cache = Bot.call(UsageRegistry::load);
            loadedAt = System.currentTimeMillis();
        }
        return cache;
    }

    private static Map<String, Declaration> load() {
        Map<String, Declaration> out = new LinkedHashMap<>();
        Map<Identifier, Resource> found = Minecraft.getInstance().getResourceManager()
                .listResources("ai_usage", id -> id.getPath().endsWith(".md"));
        found.forEach((id, resource) -> {
            try (BufferedReader reader = resource.openAsReader()) {
                Declaration declaration = parse(String.join("\n", reader.lines().toList()), id.toString());
                out.put(declaration.id(), declaration);
            } catch (IOException | RuntimeException e) {
                CogitoMod.LOG.warn("Bad usage declaration {}: {}", id, e.toString());
            }
        });
        // Files the person writes take priority over what mods ship.
        Path dir = Config.home().resolve("usage");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md")).forEach(path -> {
                    try {
                        Declaration declaration = parse(Files.readString(path), path.getFileName().toString());
                        out.put(declaration.id(), declaration);
                    } catch (IOException | RuntimeException e) {
                        CogitoMod.LOG.warn("Bad usage declaration {}: {}", path, e.toString());
                    }
                });
            } catch (IOException e) {
                CogitoMod.LOG.warn("Could not list {}", dir);
            }
        }
        return out;
    }

    /** Frontmatter of id, targets, summary and handler; the manual as the body; actions as a fenced JSON block. */
    static Declaration parse(String text, String source) {
        if (!text.startsWith("---")) {
            throw new IllegalArgumentException("must start with --- frontmatter");
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            throw new IllegalArgumentException("frontmatter is not closed");
        }
        Map<String, String> front = new LinkedHashMap<>();
        for (String line : text.substring(3, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                front.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        String id = front.get("id");
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("missing id");
        }
        List<String> targets = new ArrayList<>();
        for (String target : front.getOrDefault("targets", "").split(",")) {
            if (!target.isBlank()) {
                targets.add(target.trim());
            }
        }
        String body = text.substring(end + 4).strip();
        Map<String, Action> actions = new LinkedHashMap<>();
        int fence = body.indexOf("```actions");
        if (fence >= 0) {
            int close = body.indexOf("```", fence + 10);
            if (close < 0) {
                throw new IllegalArgumentException("actions block is not closed");
            }
            JsonObject parsed = JsonParser.parseString(body.substring(fence + 10, close)).getAsJsonObject();
            parsed.entrySet().forEach(entry -> {
                JsonObject action = entry.getValue().getAsJsonObject();
                actions.put(entry.getKey(), new Action(J.str(action, "summary", ""), params(action.get("params")),
                        action.has("steps") ? action.getAsJsonArray("steps") : new JsonArray(), J.bool(action, "read", false)));
            });
            body = (body.substring(0, fence) + body.substring(close + 3)).strip();
        }
        String handler = front.getOrDefault("handler", "").trim();
        return new Declaration(id, targets, front.getOrDefault("summary", ""), body, actions, source, handler.isEmpty() ? null : handler);
    }

    /** Params as a list of names, each optionally "name: what to pass", or as an object of name to description. */
    private static List<Param> params(JsonElement raw) {
        List<Param> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw.isJsonArray()) {
            raw.getAsJsonArray().forEach(p -> {
                String text = p.getAsString();
                int colon = text.indexOf(':');
                out.add(colon < 0 ? new Param(text.trim(), "") : new Param(text.substring(0, colon).trim(), text.substring(colon + 1).trim()));
            });
        } else if (raw.isJsonObject()) {
            raw.getAsJsonObject().entrySet().forEach(e -> out.add(new Param(e.getKey(), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "")));
        }
        return out;
    }

    // ---- what the model calls ----

    public static JsonElement handle(JsonObject args) {
        String op = J.str(args, "op");
        return switch (op) {
            case "list" -> list(J.str(args, "scope", "relevant"));
            case "search" -> search(J.str(args, "query"), SEARCH_RESULTS);
            case "read" -> read(J.str(args, "id"));
            case "invoke" -> invoke(J.str(args, "id"), J.str(args, "action"),
                    args.has("args") && args.get("args").isJsonObject() ? args.getAsJsonObject("args") : new JsonObject());
            default -> throw new ApiError("bad_argument", "op must be list, search, read or invoke");
        };
    }

    /** Short lines for the system prompt or an external AI; the manual stays out until it is asked for. */
    private static JsonElement list(String scope) {
        Set<String> around = scope.equals("all") ? null : Bot.call(UsageRegistry::targetsAround);
        JsonArray out = new JsonArray();
        for (Declaration declaration : all().values()) {
            if (around == null || declaration.targets().stream().anyMatch(around::contains)) {
                out.add(brief(declaration));
            }
        }
        return J.obj("declarations", out, "note", scope.equals("all") ? null
                : "only what you carry or stand near; scope:\"all\" lists everything, search finds one by words");
    }

    /** Declarations relevant right now: carried or nearby targets, plus the best matches for the goal's words. */
    public static JsonArray relevant(String goalText, int goalMatches) {
        Set<String> around = Bot.call(UsageRegistry::targetsAround);
        Map<String, Declaration> chosen = new LinkedHashMap<>();
        for (Declaration declaration : all().values()) {
            if (declaration.targets().stream().anyMatch(around::contains)) {
                chosen.put(declaration.id(), declaration);
            }
        }
        if (goalText != null && !goalText.isBlank()) {
            for (Declaration declaration : matches(goalText, goalMatches)) {
                chosen.putIfAbsent(declaration.id(), declaration);
            }
        }
        JsonArray out = new JsonArray();
        for (Declaration declaration : chosen.values()) {
            JsonObject row = brief(declaration);
            addPlaces(row, declaration);
            out.add(row);
        }
        return out;
    }

    /** Where the blocks a declaration is about stand, so that nobody plans a step to go and look, or to fetch another. */
    private static void addPlaces(JsonObject row, Declaration declaration) {
        JsonObject near = Bot.call(() -> blocksNear(declaration));
        if (!near.isEmpty()) {
            row.add("near", near);
        }
    }

    /** Client thread. For each block target, the nearest one standing within reach of a short walk. */
    private static JsonObject blocksNear(Declaration declaration) {
        Map<Block, String> wanted = new LinkedHashMap<>();
        for (String target : declaration.targets()) {
            if (target.startsWith("block:")) {
                Identifier id = Identifier.tryParse(target.substring("block:".length()));
                if (id != null) {
                    BuiltInRegistries.BLOCK.getOptional(id).ifPresent(block -> wanted.put(block, id.toString()));
                }
            }
        }
        JsonObject out = new JsonObject();
        if (wanted.isEmpty()) {
            return out;
        }
        BlockPos center = Bot.player().blockPosition();
        Map<Block, BlockPos> nearest = new LinkedHashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-NEAR_RADIUS, -NEAR_HEIGHT, -NEAR_RADIUS), center.offset(NEAR_RADIUS, NEAR_HEIGHT, NEAR_RADIUS))) {
            Block block = Bot.level().getBlockState(pos).getBlock();
            if (wanted.containsKey(block)) {
                BlockPos best = nearest.get(block);
                if (best == null || pos.distSqr(center) < best.distSqr(center)) {
                    nearest.put(block, pos.immutable());
                }
            }
        }
        nearest.forEach((block, pos) -> out.add(wanted.get(block), J.obj("pos", J.nums(pos.getX(), pos.getY(), pos.getZ()),
                "distance", Math.round(Math.sqrt(pos.distSqr(center))))));
        return out;
    }

    private static JsonObject brief(Declaration declaration) {
        JsonObject actions = new JsonObject();
        declaration.actions().forEach((name, action) -> actions.add(name, describe(action)));
        return J.obj("id", declaration.id(), "summary", declaration.summary(), "targets", String.join(", ", declaration.targets()),
                "actions", actions);
    }

    private static JsonObject describe(Action action) {
        JsonObject out = J.obj("summary", action.summary());
        if (!action.params().isEmpty()) {
            JsonObject params = new JsonObject();
            action.params().forEach(p -> params.addProperty(p.name(), p.description()));
            out.add("params", params);
        }
        if (action.read()) {
            out.addProperty("read", true);
        }
        return out;
    }

    /** Declarations whose id, summary, targets, the targets' names in the game's language, or manual hold the query's words. */
    private static JsonElement search(String query, int limit) {
        JsonArray out = new JsonArray();
        for (Declaration declaration : matches(query, limit)) {
            out.add(brief(declaration));
        }
        return J.obj("declarations", out, "note", out.isEmpty() ? "nothing matched; usage list with scope all shows every declaration" : null);
    }

    private static List<Declaration> matches(String query, int limit) {
        List<String> words = new ArrayList<>();
        for (String word : query.toLowerCase(Locale.ROOT).split("[\\s,;:：，。、]+")) {
            if (word.length() >= 2 || (!word.isEmpty() && word.codePointAt(0) > 0x2E80)) {
                words.add(word);
            }
        }
        Map<Declaration, Integer> scored = new LinkedHashMap<>();
        for (Declaration declaration : all().values()) {
            String text = Bot.call(() -> searchText(declaration));
            int score = 0;
            for (String word : words) {
                if (text.contains(word)) {
                    score += 2;
                } else if (word.codePointAt(0) > 0x2E80 && word.length() > 2) {
                    // Chinese has no spaces between words: a long run of characters matches by its pieces.
                    for (int i = 0; i + 2 <= word.length(); i++) {
                        if (text.contains(word.substring(i, i + 2))) {
                            score++;
                            break;
                        }
                    }
                }
            }
            if (score > 0) {
                scored.put(declaration, score);
            }
        }
        return scored.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Client thread. Everything a search may hit, lower-cased: ids, summary, manual, and what the targets are called. */
    private static String searchText(Declaration declaration) {
        StringBuilder text = new StringBuilder(declaration.id()).append('\n').append(declaration.summary()).append('\n')
                .append(declaration.manual()).append('\n');
        for (String target : declaration.targets()) {
            text.append(target).append(' ');
            String name = localName(target);
            if (name != null) {
                text.append(name).append(' ');
            }
        }
        declaration.actions().forEach((name, action) -> text.append(name).append(' ').append(action.summary()).append('\n'));
        return text.toString().toLowerCase(Locale.ROOT);
    }

    /** The name a target shows in the game's language, or null when it is not a known item, block or entity. */
    private static String localName(String target) {
        int colon = target.indexOf(':');
        if (colon < 0) {
            return null;
        }
        Identifier id = Identifier.tryParse(target.substring(colon + 1));
        if (id == null) {
            return null;
        }
        return switch (target.substring(0, colon)) {
            case "item" -> BuiltInRegistries.ITEM.getOptional(id).map(item -> new ItemStack(item).getHoverName().getString()).orElse(null);
            case "block" -> BuiltInRegistries.BLOCK.getOptional(id).map(block -> Component.translatable(block.getDescriptionId()).getString()).orElse(null);
            case "entity" -> BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(type -> type.getDescription().getString()).orElse(null);
            default -> null;
        };
    }

    private static JsonElement read(String id) {
        Declaration declaration = find(id);
        JsonObject out = J.obj("id", declaration.id(), "targets", String.join(", ", declaration.targets()),
                "summary", declaration.summary(), "manual", declaration.manual(), "source", declaration.source());
        JsonObject actions = new JsonObject();
        declaration.actions().forEach((name, action) -> {
            JsonObject row = describe(action);
            row.addProperty("kind", kind(declaration, name, action));
            actions.add(name, row);
        });
        out.add("actions", actions);
        addPlaces(out, declaration);
        out.addProperty("how_to_run", "usage invoke runs an action now; in a plan, a step's run holds "
                + "{\"fn\":\"invokeUsage\",\"args\":{\"id\":\"" + declaration.id() + "\",\"action\":\"<name>\",\"args\":{...}}}");
        return out;
    }

    private static String kind(Declaration declaration, String name, Action action) {
        if (HANDLERS.containsKey(declaration.id() + "#" + name)) {
            return "handler";
        }
        if (declaration.handlerClass() != null && action.steps().isEmpty()) {
            return "code";
        }
        return "steps";
    }

    /** Whether an action only looks, so planning may run it; unknown ids and actions fail as invoke would. */
    public static boolean isRead(String id, String name) {
        Declaration declaration = find(id);
        Action action = declaration.actions().get(name);
        if (action == null) {
            throw new ApiError("bad_argument", id + " has no action '" + name + "'; it has " + declaration.actions().keySet());
        }
        return action.read();
    }

    public static JsonElement invoke(String id, String name, JsonObject args) {
        Declaration declaration = find(id);
        Action action = declaration.actions().get(name);
        if (action == null) {
            throw new ApiError("bad_argument", id + " has no action '" + name + "'; it has " + declaration.actions().keySet());
        }
        if (!action.read()) {
            Bot.requireControl();
        }
        Function<JsonObject, JsonElement> handler = HANDLERS.get(id + "#" + name);
        if (handler != null) {
            return handler.apply(args);
        }
        if (declaration.handlerClass() != null && action.steps().isEmpty()) {
            return checked(invoker(declaration).invoke(name, args));
        }
        if (action.steps().isEmpty()) {
            throw new ApiError("not_implemented", "the declaration " + id + " gives '" + name + "' neither steps nor code",
                    "its mod may be missing the handler; try the manual's words with the api or the screen tool");
        }
        JsonArray calls = new JsonArray();
        for (JsonElement step : action.steps()) {
            calls.add(substitute(step, args));
        }
        return Api.batch(calls);
    }

    /**
     * The code a declaration names in {@code handler}: a class with a public static method
     * {@code invoke(String action, JsonObject args, BiFunction<String, JsonObject, JsonObject> game)}, or without the
     * third parameter when it needs no game functions. Only Gson and the JDK appear in that signature, so a mod ships
     * it without depending on this mod; the class is loaded only when this mod is present and the action is used.
     * {@code game.apply(fn, args)} runs any function of the api and answers as the api does.
     */
    private static Invoker invoker(Declaration declaration) {
        return CLASS_INVOKERS.computeIfAbsent(declaration.handlerClass(), className -> {
            Class<?> type;
            try {
                type = Class.forName(className, true, UsageRegistry.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                throw new ApiError("not_found", "the declaration " + declaration.id() + " names handler class " + className + ", which did not load: " + e,
                        "the mod that ships the declaration may be an older build");
            }
            Method withGame = null;
            Method plain = null;
            for (Method method : type.getMethods()) {
                if (!method.getName().equals("invoke") || !Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 3 && params[0] == String.class && params[1] == JsonObject.class && params[2] == BiFunction.class) {
                    withGame = method;
                } else if (params.length == 2 && params[0] == String.class && params[1] == JsonObject.class) {
                    plain = method;
                }
            }
            if (withGame == null && plain == null) {
                throw new ApiError("not_found", className + " has no public static invoke(String action, JsonObject args[, BiFunction game]) method");
            }
            Method method = withGame != null ? withGame : plain;
            boolean passGame = withGame != null;
            return (action, args) -> {
                try {
                    Object result = passGame ? method.invoke(null, action, args, GAME) : method.invoke(null, action, args);
                    if (result instanceof JsonElement json) {
                        return json;
                    }
                    return result == null ? null : J.obj("result", result.toString());
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (cause instanceof ApiError api) {
                        throw api;
                    }
                    if (cause instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        throw new ApiError("interrupted", "stopped: control was taken back or the goal changed");
                    }
                    CogitoMod.LOG.warn("Usage action {} of {} failed", action, declaration.id(), cause);
                    throw new ApiError("mod_error", cause.toString());
                } catch (IllegalAccessException e) {
                    throw new ApiError("mod_error", e.toString());
                }
            };
        });
    }

    /** A mod without this mod's classes reports a failure as an object with error, message and hint; it fails here. */
    private static JsonElement checked(JsonElement result) {
        if (result != null && result.isJsonObject() && result.getAsJsonObject().has("error")
                && result.getAsJsonObject().get("error").isJsonPrimitive()) {
            JsonObject failure = result.getAsJsonObject();
            throw new ApiError(J.str(failure, "error"), J.str(failure, "message", "the action failed"), J.str(failure, "hint", null));
        }
        return result;
    }

    /** Replaces "$name" string values in a step with the caller's arguments. */
    private static JsonElement substitute(JsonElement template, JsonObject args) {
        if (template.isJsonObject()) {
            JsonObject out = new JsonObject();
            template.getAsJsonObject().entrySet().forEach(e -> out.add(e.getKey(), substitute(e.getValue(), args)));
            return out;
        }
        if (template.isJsonArray()) {
            JsonArray out = new JsonArray();
            template.getAsJsonArray().forEach(e -> out.add(substitute(e, args)));
            return out;
        }
        if (template.isJsonPrimitive() && template.getAsJsonPrimitive().isString() && template.getAsString().startsWith("$")) {
            String name = template.getAsString().substring(1);
            if (!args.has(name)) {
                throw new ApiError("bad_argument", "this action needs '" + name + "'");
            }
            return args.get(name);
        }
        return template;
    }

    private static Declaration find(String id) {
        Declaration declaration = all().get(id);
        if (declaration == null) {
            throw new ApiError("not_found", "no usage declaration '" + id + "'",
                    "usage search finds one by words and usage list with scope all shows them; none means use describeItem and the screen tool");
        }
        return declaration;
    }

    /** Client thread. Items you carry, blocks within 6 and entities within 8, in the form targets use. */
    private static Set<String> targetsAround() {
        Set<String> out = new HashSet<>();
        LocalPlayer player = Bot.player();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                out.add("item:" + Bot.itemId(stack));
            }
        }
        BlockPos center = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-6, -3, -6), center.offset(6, 3, 6))) {
            BlockState state = Bot.level().getBlockState(pos);
            if (!state.isAir()) {
                out.add("block:" + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            }
        }
        for (Entity entity : Bot.level().getEntities(player, player.getBoundingBox().inflate(8), e -> e != player)) {
            out.add("entity:" + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        }
        return out;
    }
}
