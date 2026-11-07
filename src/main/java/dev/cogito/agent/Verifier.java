package dev.cogito.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cogito.bot.ApiError;
import dev.cogito.bot.Blueprints;
import dev.cogito.bot.Bot;
import dev.cogito.usage.UsageRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checks a step against the game state with no model involved. Every condition is one line of text:
 *
 * <pre>
 * has_item minecraft:oak_log 3     has_item #minecraft:logs 3      has_at_most minecraft:wheat 4
 * near 120 64 -35 4                moved 3
 * block_at 120 64 -35 minecraft:chest
 * dimension minecraft:the_nether
 * y_below 20                       y_above 60
 * health_at_least 10               food_at_least 10
 * window_open                      window_closed
 * </pre>
 */
public final class Verifier {
    public static final String VOCABULARY = """
            has_item <id or #tag> <at least this many> | has_at_most <id or #tag> <no more than this many> | near <x> <y> <z> <radius> | moved <blocks> | block_at <x> <y> <z> <id> | \
            dimension <id> | y_below <y> | y_above <y> | health_at_least <n> | food_at_least <n> | window_open | window_closed | \
            built <blueprint name> [percent, 100 if left out] | usage <declaration id> <read action> [its args as one JSON object], true when a mod's own check answers met, for what only the mod can tell; its manual names them""";

    public record Result(boolean met, String detail) {}

    private Verifier() {}

    /** Client thread. startPos is where the player stood when the step began. */
    public static Result check(List<String> conditions, Vec3 startPos) {
        List<String> notes = new ArrayList<>();
        boolean all = true;
        for (String condition : conditions) {
            String problem;
            try {
                problem = checkOne(condition.trim().split("\\s+"), startPos);
            } catch (RuntimeException e) {
                problem = "could not check: " + e.getMessage();
            }
            if (problem != null) {
                all = false;
                notes.add("'" + condition + "': " + problem);
            }
        }
        return new Result(all, String.join("; ", notes));
    }

    /** Returns null when met, otherwise what is still missing. */
    private static String checkOne(String[] c, Vec3 startPos) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = Bot.player();
        switch (c[0]) {
            case "has_item" -> {
                int want = Integer.parseInt(c[2]);
                int have = count(player, c[1]);
                return have >= want ? null : "have " + have + " of " + want;
            }
            case "has_at_most" -> {
                int limit = Integer.parseInt(c[2]);
                int have = count(player, c[1]);
                return have <= limit ? null : "still have " + have + ", want at most " + limit;
            }
            case "near" -> {
                double distance = player.position().distanceTo(new Vec3(num(c[1]), num(c[2]), num(c[3])));
                return distance <= num(c[4]) ? null : Math.round(distance) + " blocks away";
            }
            case "moved" -> {
                double distance = player.position().distanceTo(startPos);
                return distance >= num(c[1]) ? null : "moved only " + Math.round(distance * 10) / 10.0;
            }
            case "block_at" -> {
                BlockPos pos = new BlockPos((int) num(c[1]), (int) num(c[2]), (int) num(c[3]));
                String id = Bot.blockId(Bot.level().getBlockState(pos));
                return id.equals(c[4].contains(":") ? c[4] : "minecraft:" + c[4]) ? null : "it is " + id;
            }
            case "dimension" -> {
                String id = Bot.level().dimension().identifier().toString();
                return id.equals(c[1]) ? null : "in " + id;
            }
            case "y_below" -> {
                return player.getY() < num(c[1]) ? null : "y is " + Math.round(player.getY());
            }
            case "y_above" -> {
                return player.getY() > num(c[1]) ? null : "y is " + Math.round(player.getY());
            }
            case "health_at_least" -> {
                return player.getHealth() >= num(c[1]) ? null : "health is " + player.getHealth();
            }
            case "food_at_least" -> {
                return player.getFoodData().getFoodLevel() >= num(c[1]) ? null : "food is " + player.getFoodData().getFoodLevel();
            }
            case "window_open" -> {
                return mc.screen != null ? null : "no window is open";
            }
            case "window_closed" -> {
                return mc.screen == null ? null : "a window is open";
            }
            case "built" -> {
                int want = c.length > 2 ? Integer.parseInt(c[2]) : 100;
                int percent = Blueprints.percentBuilt(c[1]);
                return percent >= want ? null : percent < 0 ? "not started in this world" : percent + "% built";
            }
            case "usage" -> {
                return checkDeclared(c);
            }
            default -> throw new ApiError("bad_condition", "unknown condition " + c[0]);
        }
    }

    /**
     * A mod's own condition: a read action of its usage declaration, answering met true or false, with why when it is
     * false. Only read actions may be used: this runs on the client thread and must not wait or act on the world.
     */
    private static String checkDeclared(String[] c) {
        if (c.length < 3) {
            throw new ApiError("bad_condition", "usage needs a declaration id and a read action");
        }
        if (!UsageRegistry.isRead(c[1], c[2])) {
            throw new ApiError("bad_condition", c[2] + " of " + c[1] + " is not a read action");
        }
        JsonObject args = new JsonObject();
        if (c.length > 3) {
            String json = String.join(" ", Arrays.copyOfRange(c, 3, c.length));
            args = JsonParser.parseString(json).getAsJsonObject();
        }
        JsonElement result = UsageRegistry.invoke(c[1], c[2], args);
        if (result == null || !result.isJsonObject() || !result.getAsJsonObject().has("met")) {
            throw new ApiError("bad_condition", c[2] + " of " + c[1] + " does not answer met, so it cannot be a condition");
        }
        JsonObject answer = result.getAsJsonObject();
        if (answer.get("met").getAsBoolean()) {
            return null;
        }
        return answer.has("why") ? answer.get("why").getAsString() : "its check says no";
    }

    private static double num(String value) {
        return Double.parseDouble(value);
    }

    private static int count(LocalPlayer player, String wanted) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (Bot.matchesItem(stack, wanted)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
