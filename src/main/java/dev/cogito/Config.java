package dev.cogito;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings from {@code <game>/cogito/config.json}. System properties named {@code cogito.<field>} win,
 * which is how the dev run turns on test-only switches. Model providers, models and keys are not here but in
 * {@link dev.cogito.llm.LlmSettings}, outside the game folder, so that sharing an instance never shares a key.
 */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance = new Config();

    /** "llm", "vla" (HTTP device policy) or "scripted" (built-in test policy). */
    public String executor = "llm";
    public String vlaEndpoint = "";
    public int mcpPort = 25599;
    public boolean mcpEnabled = true;
    /** Lets the AI drive screens outside a world: title screen, world list, server list. */
    public boolean allowMenus = false;
    /** Lets an MCP client hand control to the AI. The takeover key always wins regardless. */
    public boolean allowExternalTakeover = false;
    /** Lets the AI send slash commands. Plain chat is always allowed. */
    public boolean allowCommands = false;
    public boolean reflexes = true;
    /** On a server, look for ore the way a person must, instead of knowing where it is from chunk data. */
    public boolean legitMiningOnServers = true;
    public int maxTurnsPerStep = 30;
    public int maxReplans = 6;
    /** Estimated spend per goal, in US dollars; the goal stops when it is reached. */
    public double maxGoalCostUsd = 1.0;
    /** The same for a long-term goal, which runs across many plans. */
    public double maxLongGoalCostUsd = 10.0;
    public int screenshotMaxEdge = 1280;
    /** The planner gets a picture of the game with every plan. */
    public boolean planWithScreenshot = true;
    /** The executor gets a picture when a step starts. */
    public boolean stepWithScreenshot = true;
    /** The executor gets a new picture after any turn that acted on the world, so it cannot skip looking at the result. */
    public boolean screenshotAfterActions = true;
    /** Longest edge of those automatic pictures: smaller than a requested screenshot, because there are many. */
    public int autoScreenshotEdge = 768;

    public static Config get() {
        return instance;
    }

    public static Path home() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("cogito");
    }

    public static void load() {
        Legacy.carryOver(home().resolveSibling(Legacy.OLD_NAME), home());
        Path file = home().resolve("config.json");
        try {
            Files.createDirectories(home());
            if (Files.exists(file)) {
                instance = GSON.fromJson(Files.readString(file), Config.class);
            } else {
                Files.writeString(file, GSON.toJson(instance));
            }
        } catch (IOException | RuntimeException e) {
            CogitoMod.LOG.warn("Could not read {}: {}", file, e.toString());
        }
        instance.allowMenus = flag("allowMenus", instance.allowMenus);
        instance.allowExternalTakeover = flag("allowExternalTakeover", instance.allowExternalTakeover);
        instance.allowCommands = flag("allowCommands", instance.allowCommands);
        instance.executor = System.getProperty("cogito.executor", instance.executor);
        if (Files.exists(home().resolve(".env"))) {
            // Earlier versions read the key from here. It is no longer read, so it should not stay in a folder that gets shared.
            CogitoMod.LOG.warn("{} is no longer read: enter the key in the Cogito settings, then delete that file",
                    home().resolve(".env"));
        }
    }

    private static boolean flag(String name, boolean current) {
        String value = System.getProperty("cogito." + name);
        return value == null ? current : Boolean.parseBoolean(value);
    }
}
