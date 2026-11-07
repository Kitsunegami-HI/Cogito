package dev.cogito.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cogito.CogitoMod;
import dev.cogito.Config;
import dev.cogito.Legacy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Which model providers there are, their keys, and which model each layer of the agent uses. Kept outside the game
 * folder on purpose, as Logos keeps its book sprite's: a modpack is usually shared by zipping the whole instance, and a
 * key under it would go along. The file lives in the user's own settings folder ({@code %APPDATA%\cogito} on
 * Windows) and holds keys, so nothing here is ever logged with its key, and nothing reads a key from the game folder.
 * A provider with no key of its own may take one from an environment variable instead.
 */
public final class LlmSettings {
    public static final String FILE = "llm.json";
    /** The user's settings folder for this mod, outside every game instance; {@code -Dcogito.settingsDir} overrides it. */
    public static final Path DIR = defaultDir();
    public static final List<String> EFFORTS = List.of("default", "low", "medium", "high", "xhigh", "max");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicInteger VERSION = new AtomicInteger();
    private static volatile Settings current;

    private LlmSettings() {}

    /** The kinds of API a provider speaks, each through its maker's own SDK. */
    public enum Kind {
        ANTHROPIC("anthropic", "Anthropic", "https://api.anthropic.com", "ANTHROPIC_API_KEY"),
        OPENAI("openai", "OpenAI-compatible", "https://api.openai.com/v1", "OPENAI_API_KEY"),
        GEMINI("gemini", "Gemini", "https://generativelanguage.googleapis.com", "GEMINI_API_KEY", "GOOGLE_API_KEY");

        public final String id;
        public final String label;
        public final String officialUrl;
        /** Environment variables that hold this maker's key, used only when the provider talks to the maker itself. */
        public final List<String> keyVariables;

        Kind(String id, String label, String officialUrl, String... keyVariables) {
            this.id = id;
            this.label = label;
            this.officialUrl = officialUrl;
            this.keyVariables = List.of(keyVariables);
        }

        public static Kind of(String id) {
            for (Kind kind : values()) {
                if (kind.id.equalsIgnoreCase(id)) {
                    return kind;
                }
            }
            return ANTHROPIC;
        }
    }

    /**
     * One place to send requests to. {@code baseUrl} empty means the maker's own API; {@code keyVariable} names an
     * environment variable to take the key from when none is stored; a price below zero means not known.
     */
    public record Profile(String id, String name, Kind kind, String baseUrl, String apiKey, String keyVariable, boolean images,
                          double inputPrice, double outputPrice) {
        public Profile {
            id = id == null ? "" : id.strip();
            name = name == null || name.isBlank() ? kind.label : name.strip();
            baseUrl = baseUrl == null ? "" : baseUrl.strip();
            apiKey = apiKey == null ? "" : apiKey.strip();
            keyVariable = keyVariable == null ? "" : keyVariable.strip();
        }

        /** Talks to the maker itself rather than to a relay or a local server. */
        public boolean official() {
            return baseUrl.isEmpty() || host(baseUrl).equalsIgnoreCase(host(kind.officialUrl));
        }

        /** A local or relay server speaking the OpenAI API may need no key at all. */
        public boolean keyOptional() {
            return kind == Kind.OPENAI && !official();
        }

        public Profile withKey(String key) {
            return new Profile(id, name, kind, baseUrl, key, keyVariable, images, inputPrice, outputPrice);
        }

        @Override
        public String toString() {
            // Some relays carry the key in the query string; it stays out too.
            int q = baseUrl.indexOf('?');
            return "Profile[" + id + ", " + kind.id + ", " + (q < 0 ? baseUrl : baseUrl.substring(0, q) + "?…") + ", key=" + hint(apiKey) + "]";
        }
    }

    /** Which provider and model one layer of the agent uses, and how hard it thinks. */
    public record Role(String profile, String model, String effort) {
        public Role {
            profile = profile == null ? "" : profile.strip();
            model = model == null ? "" : model.strip();
            effort = effort == null || !EFFORTS.contains(effort) ? "default" : effort;
        }
    }

    public record Settings(List<Profile> profiles, Role planner, Role executor) {
        public Settings {
            profiles = List.copyOf(profiles);
        }

        public Profile profile(String id) {
            for (Profile profile : profiles) {
                if (profile.id().equals(id)) {
                    return profile;
                }
            }
            return null;
        }
    }

    public enum KeySource { STORED, ENVIRONMENT, NONE }

    /** The key a provider would use, where it comes from, and the variable it was or would be read from. */
    public record Key(String value, KeySource source, String variable) {
        public String hint() {
            return LlmSettings.hint(value);
        }

        @Override
        public String toString() {
            return "Key[" + source + ", " + variable + ", " + hint() + "]";
        }
    }

    public static Settings get() {
        Settings settings = current;
        if (settings == null) {
            synchronized (LlmSettings.class) {
                if (current == null) {
                    current = read();
                }
                settings = current;
            }
        }
        return settings;
    }

    /** Goes up on every save, so clients built for an older version can be dropped. */
    public static int version() {
        return VERSION.get();
    }

    public static Path file() {
        return DIR.resolve(FILE);
    }

    public static void save(Settings settings) throws IOException {
        JsonObject root = new JsonObject();
        root.add("planner", role(settings.planner()));
        root.add("executor", role(settings.executor()));
        JsonArray profiles = new JsonArray();
        for (Profile p : settings.profiles()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.id());
            o.addProperty("name", p.name());
            o.addProperty("kind", p.kind().id);
            o.addProperty("baseUrl", p.baseUrl());
            o.addProperty("apiKey", p.apiKey());
            o.addProperty("keyVariable", p.keyVariable());
            o.addProperty("images", p.images());
            o.addProperty("inputPrice", p.inputPrice());
            o.addProperty("outputPrice", p.outputPrice());
            profiles.add(o);
        }
        root.add("profiles", profiles);
        Files.createDirectories(DIR);
        Path tmp = DIR.resolve(FILE + ".tmp");
        Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: the folder under the user's profile is already private to them.
        }
        Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        current = settings;
        VERSION.incrementAndGet();
    }

    /** A stored key first; else the variable the provider names; else, for the maker's own API, the maker's usual variable. */
    public static Key key(Profile p) {
        if (!p.apiKey().isEmpty()) {
            return new Key(p.apiKey(), KeySource.STORED, null);
        }
        List<String> variables = !p.keyVariable().isEmpty() ? List.of(p.keyVariable())
                : p.official() ? p.kind().keyVariables : List.of();
        for (String variable : variables) {
            String value = env(variable);
            if (value != null) {
                return new Key(value, KeySource.ENVIRONMENT, variable);
            }
        }
        return new Key("", KeySource.NONE, variables.isEmpty() ? null : variables.getFirst());
    }

    /** The key as it may be shown: its last four characters, or nothing when there is none. */
    public static String hint(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        return "****" + (key.length() > 8 ? key.substring(key.length() - 4) : "");
    }

    /**
     * An environment variable. Dev runs also read the file named by {@code cogito.envFile}, which lies outside the
     * game folder; there a plain {@code api-key} line counts as the Anthropic key.
     */
    static String env(String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            return value.strip();
        }
        String named = System.getProperty("cogito.envFile");
        if (named == null || named.isBlank()) {
            return null;
        }
        Path file = Path.of(named);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String wanted = normalize(name);
        try {
            for (String raw : Files.readAllLines(file)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int split = indexOfSeparator(line);
                if (split <= 0) {
                    continue;
                }
                String found = normalize(line.substring(0, split));
                if (found.equals("api-key")) {
                    found = "anthropic-api-key";
                }
                if (found.equals(wanted)) {
                    String v = line.substring(split + 1).strip();
                    return v.isEmpty() ? null : v;
                }
            }
        } catch (IOException e) {
            CogitoMod.LOG.warn("Could not read the env file: {}", e.getClass().getSimpleName());
        }
        return null;
    }

    private static String normalize(String name) {
        return name.strip().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static int indexOfSeparator(String line) {
        int colon = line.indexOf(':');
        int equals = line.indexOf('=');
        if (colon < 0) {
            return equals;
        }
        return equals < 0 ? colon : Math.min(colon, equals);
    }

    private static Settings read() {
        if (System.getProperty("cogito.settingsDir") == null) {
            Legacy.carryOver(DIR.resolveSibling(Legacy.OLD_NAME), DIR);
        }
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return defaults();
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            JsonObject root = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
            List<Profile> profiles = new ArrayList<>();
            if (root.has("profiles") && root.get("profiles").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("profiles")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject o = element.getAsJsonObject();
                    Kind kind = Kind.of(string(o, "kind", "anthropic"));
                    String id = string(o, "id", kind.id);
                    if (!id.isEmpty()) {
                        profiles.add(new Profile(id, string(o, "name", kind.label), kind, string(o, "baseUrl", ""),
                                string(o, "apiKey", ""), string(o, "keyVariable", ""), bool(o, "images", true),
                                number(o, "inputPrice"), number(o, "outputPrice")));
                    }
                }
            }
            if (profiles.isEmpty()) {
                profiles.addAll(defaultProfiles());
            }
            Settings fallback = defaults();
            return new Settings(profiles, role(root, "planner", fallback.planner()), role(root, "executor", fallback.executor()));
        } catch (IOException | RuntimeException e) {
            // The class only; the file holds keys.
            CogitoMod.LOG.warn("Could not read the model provider settings {}: {}", file, e.getClass().getSimpleName());
            return defaults();
        }
    }

    /** One provider of each kind, none with a key, and the models the game folder's config.json named before this file existed. */
    private static Settings defaults() {
        String plannerModel = "claude-haiku-5-5", executorModel = "claude-haiku-5-5", plannerEffort = "medium", executorEffort = "low";
        try {
            Path old = Config.home().resolve("config.json");
            if (Files.isRegularFile(old)) {
                JsonObject o = JsonParser.parseString(Files.readString(old)).getAsJsonObject();
                plannerModel = string(o, "plannerModel", plannerModel);
                executorModel = string(o, "executorModel", executorModel);
                plannerEffort = string(o, "plannerEffort", plannerEffort);
                executorEffort = string(o, "executorEffort", executorEffort);
            }
        } catch (IOException | RuntimeException ignored) {
            // No game yet, or no old settings: the defaults stand.
        }
        return new Settings(defaultProfiles(), new Role("anthropic", plannerModel, plannerEffort),
                new Role("anthropic", executorModel, executorEffort));
    }

    private static List<Profile> defaultProfiles() {
        List<Profile> out = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            out.add(new Profile(kind.id, kind.label, kind, "", "", "", true, -1, -1));
        }
        return out;
    }

    private static JsonObject role(Role role) {
        JsonObject o = new JsonObject();
        o.addProperty("profile", role.profile());
        o.addProperty("model", role.model());
        o.addProperty("effort", role.effort());
        return o;
    }

    private static Role role(JsonObject root, String name, Role fallback) {
        if (!root.has(name) || !root.get(name).isJsonObject()) {
            return fallback;
        }
        JsonObject o = root.getAsJsonObject(name);
        return new Role(string(o, "profile", fallback.profile()), string(o, "model", fallback.model()), string(o, "effort", fallback.effort()));
    }

    private static String string(JsonObject o, String k, String fallback) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : fallback;
    }

    private static boolean bool(JsonObject o, String k, boolean fallback) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : fallback;
    }

    private static double number(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String host(String url) {
        try {
            String host = URI.create(url.strip()).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static Path defaultDir() {
        String override = System.getProperty("cogito.settingsDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String home = System.getProperty("user.home", ".");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData != null && !appData.isBlank() ? appData : home, "cogito");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "cogito");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return Path.of(xdg != null && !xdg.isBlank() ? xdg : home + "/.config", "cogito");
    }
}
