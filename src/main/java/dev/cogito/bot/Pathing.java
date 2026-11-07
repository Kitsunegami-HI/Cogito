package dev.cogito.bot;

import com.google.gson.JsonObject;
import dev.cogito.Config;
import dev.cogito.control.VirtualInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Travel. Baritone does the path when it is installed; otherwise a plain walk toward the target. */
final class Pathing {
    private static final boolean BARITONE = detect();

    private Pathing() {}

    private static boolean detect() {
        try {
            Class.forName("baritone.api.BaritoneAPI");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    static boolean hasBaritone() {
        return BARITONE;
    }

    private static void requireBaritone(String what) {
        if (!BARITONE) {
            throw new ApiError("needs_baritone", what + " needs Baritone", "install it, or use goto and the single functions");
        }
    }

    static JsonObject travel(JsonObject args) {
        Bot.readyForWorldInput();
        int x = J.integer(args, "x");
        int z = J.integer(args, "z");
        Integer y = args.has("y") ? J.integer(args, "y") : null;
        int range = J.integer(args, "range", 2);
        long timeout = J.integer(args, "timeout_s", 120) * 1000L;
        if (BARITONE) {
            return BaritoneBridge.travel(x, y, z, range, timeout);
        }
        return walkStraight(x, z, range, timeout);
    }

    static JsonObject collect(JsonObject args) {
        requireBaritone("collectBlock");
        Bot.readyForWorldInput();
        Set<Block> blocks = Bot.call(() -> Sources.resolveBlocks(J.strings(args, "blocks")));
        // Mining something that drops nothing wastes minutes, so the tool comes first.
        String tool = Bot.call(() -> toolMissingFor(blocks));
        if (tool != null) {
            Skills.call("obtain", J.obj("item", tool));
            Bot.readyForWorldInput();
        }
        String until = args.has("until_item") ? Bot.call(() -> Bot.itemId(new ItemStack(Bot.item(J.str(args, "until_item"))))) : null;
        // Only ore is hidden. Trees and stone are there to be seen, and hunting for them by tunnel would be absurd.
        boolean legit = Config.get().legitMiningOnServers && Bot.call(() -> Bot.mc().getSingleplayerServer() == null
                && blocks.stream().anyMatch(block -> block.defaultBlockState().is(Tags.Blocks.ORES)));
        Integer oreLevel = args.has("ore_level") ? J.integer(args, "ore_level") : null;
        return BaritoneBridge.collect(blocks, J.integer(args, "count"), J.integer(args, "timeout_s", 120) * 1000L,
                baseline(args), until, legit, oreLevel);
    }

    /** What was carried when this started, kept in the arguments so that it survives a reflex restarting the skill. */
    private static Map<String, Integer> baseline(JsonObject args) {
        if (!args.has("_before")) {
            JsonObject saved = new JsonObject();
            Bot.call(BaritoneBridge::carried).forEach(saved::addProperty);
            args.add("_before", saved);
        }
        Map<String, Integer> out = new HashMap<>();
        args.getAsJsonObject("_before").entrySet().forEach(entry -> out.put(entry.getKey(), entry.getValue().getAsInt()));
        return out;
    }

    /** The lowest tool to get if nothing carried makes any of these blocks drop, or null if something does. */
    private static String toolMissingFor(Set<Block> blocks) {
        var inventory = Bot.player().getInventory();
        for (Block block : blocks) {
            BlockState state = block.defaultBlockState();
            if (!state.requiresCorrectToolForDrops()) {
                return null;
            }
            for (int i = 0; i < 36; i++) {
                if (inventory.getItem(i).isCorrectToolForDrops(state)) {
                    return null;
                }
            }
        }
        if (blocks.isEmpty()) {
            return null;
        }
        net.minecraft.world.item.Item tool = Sources.toolNeeded(blocks.iterator().next().defaultBlockState());
        return tool == null ? null : Bot.itemId(new ItemStack(tool));
    }

    static void follow(Predicate<Entity> who, int radius) {
        requireBaritone("following");
        Bot.readyForWorldInput();
        BaritoneBridge.follow(who, radius);
    }

    static boolean following() {
        return BARITONE && BaritoneBridge.following();
    }

    static JsonObject toY(int y, long timeout) {
        requireBaritone("changing height");
        Bot.readyForWorldInput();
        return BaritoneBridge.toY(y, timeout);
    }

    static JsonObject runAway(int distance, long timeout) {
        requireBaritone("moveAway");
        Bot.readyForWorldInput();
        return BaritoneBridge.runAway(distance, timeout);
    }

    static void stop() {
        if (BARITONE) {
            Bot.run(BaritoneBridge::stop);
        }
        Bot.run(ActionFns::clearControls);
    }

    private static JsonObject walkStraight(int x, int z, int range, long timeout) {
        long deadline = System.currentTimeMillis() + timeout;
        try {
            Bot.run(() -> {
                VirtualInput.setMapping(ActionFns.options().keyUp, true);
                VirtualInput.setMapping(ActionFns.options().keySprint, true);
            });
            while (true) {
                Bot.requireControl();
                double distance = Bot.call(() -> {
                    LocalPlayer p = Bot.player();
                    return Math.hypot(p.getX() - x - 0.5, p.getZ() - z - 0.5);
                });
                if (distance <= range) {
                    return J.obj("arrived", true, "method", "straight_walk");
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new ApiError("timeout", "still " + Math.round(distance) + " blocks away");
                }
                Bot.lookAt(Bot.call(() -> new Vec3(x + 0.5, Bot.player().getEyeY(), z + 0.5)));
                Bot.run(() -> VirtualInput.setMapping(ActionFns.options().keyJump, Bot.player().horizontalCollision));
                Bot.pause(150);
            }
        } finally {
            Bot.run(ActionFns::clearControls);
        }
    }
}
