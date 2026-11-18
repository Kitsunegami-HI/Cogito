package dev.cogito.bot;

import dev.cogito.CogitoMod;
import dev.cogito.compat.Mc;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.input.Input;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Predicate;

/** Only loaded when Baritone is present, so the mod still starts without it. */
final class BaritoneBridge {
    /** How long mining may go without the inventory gaining anything before it counts as stuck. */
    private static final long STALL_MILLIS = 45_000;
    private static final long FULL_PACK_MILLIS = 20_000;

    private static final int MESSAGES_KEPT = 3;
    private static final int SEARCH_RADIUS = 48;
    /** Polls of 200 ms in a row without the tool before mining is given up, so a tool swap in progress does not count. */
    private static final int WITHOUT_TOOL_POLLS = 3;
    private static final long WALK_AWAY_MILLIS = 60_000;

    private static boolean configured;
    private static volatile String lastMessage = "";
    private static final Deque<String> recentMessages = new ConcurrentLinkedDeque<>();

    private static volatile boolean dropsReady;

    private BaritoneBridge() {}

    /**
     * Worker thread. To mine, Baritone works out what each block drops from the vanilla data pack, which it loads the
     * first time it is asked and finishes on the game thread. Asked on the game thread, as starting to mine is, that
     * waits for itself forever (Baritone 1.11.3 on Minecraft 1.21.1 froze the game this way), so it is loaded here
     * first, where waiting is safe.
     */
    private static void prepareDrops() {
        if (dropsReady || Bot.mc().isSameThread()) {
            return;
        }
        try {
            BlockOptionalMeta.ServerLevelStub.fastCreate().registryAccess();
        } catch (RuntimeException | LinkageError e) {
            CogitoMod.LOG.warn("Could not load Baritone's block drop data ahead of mining: {}", e.toString());
        }
        dropsReady = true;
    }

    private static IBaritone baritone() {
        if (!configured) {
            Settings settings = BaritoneAPI.getSettings();
            settings.chatControl.value = false;
            settings.chatDebug.value = false;
            settings.allowInventory.value = true;
            settings.allowSprint.value = true;
            // Someone watching through the player's eyes should see where it is going: turn the view with the walk, smoothly.
            settings.freeLook.value = false;
            settings.blockFreeLook.value = false;
            settings.smoothLook.value = true;
            // Walking through a chest or a furnace on its way somewhere is not a shortcut a person takes, and a mod's machine
            // may hold work that is lost with it. Forbidden rather than made dear: a raised cost did not stop it digging through a
            // table to save two steps. Taking one's own bench back goes by the dig function, which is not Baritone.
            settings.blocksToDisallowBreaking.value = Sources.keptWhole();
            Sources.BlockList obstacles = new Sources.BlockList();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (!BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("minecraft") && bumpsButLooksPassable(block)) {
                    obstacles.add(block);
                }
            }
            settings.blocksToAvoid.value = obstacles;
            // The picture the model sees should be the game: no path lines, no status messages over the world.
            settings.renderPath.value = false;
            settings.renderGoal.value = false;
            settings.renderSelectionBoxes.value = false;
            settings.renderCachedChunks.value = false;
            settings.logger.value = BaritoneBridge::heard;
            configured = true;
        }
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /**
     * Baritone asks a block's isPathfindable whether it can walk through it, and the game's default says yes for anything
     * that is not a full cube. Vanilla's own furniture overrides that and a mod's often does not, so a modded table or
     * machine looks like air and Baritone walks into it for ever. Anything of a mod's that has something to bump into
     * counts as an obstacle, apart from what Baritone knows how to use: doors, gates, ladders, slabs and stairs.
     */
    private static boolean bumpsButLooksPassable(Block block) {
        if (block instanceof DoorBlock || block instanceof FenceGateBlock || block instanceof TrapDoorBlock
                || block instanceof SlabBlock || block instanceof StairBlock || block instanceof LadderBlock) {
            return false;
        }
        try {
            BlockState state = block.defaultBlockState();
            return !state.is(BlockTags.CLIMBABLE) && state.isPathfindable(PathComputationType.LAND)
                    && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
                    && !state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            // A shape that needs a real world to work out: nothing can be said about it here.
            return false;
        }
    }

    /** Its messages come in runs, and the last in a run ("Unable to do it") says less than the one before it ("Missing materials for"). */
    private static void heard(Component message) {
        String line = message.getString().replace("[Baritone] ", "");
        recentMessages.addLast(line);
        while (recentMessages.size() > MESSAGES_KEPT) {
            recentMessages.removeFirst();
        }
        lastMessage = String.join(" / ", recentMessages);
    }

    private static void forgetMessages() {
        recentMessages.clear();
        lastMessage = "";
    }

    static JsonObject travel(int x, Integer y, int z, int range, long timeout) {
        Goal goal = y == null ? new GoalXZ(x, z) : new GoalNear(new BlockPos(x, y, z), range);
        Bot.run(() -> baritone().getCustomGoalProcess().setGoalAndPath(goal));
        boolean started = Bot.waitUntil(() -> baritone().getCustomGoalProcess().isActive(), 2000);
        // Already there, Baritone is done within a tick, which can be before anyone looks. That is arriving, not failing.
        boolean alreadyThere = !started && Bot.call(() -> goal.isInGoal(baritone().getPlayerContext().playerFeet()));
        if (!started && !alreadyThere) {
            throw new ApiError("no_path", "Baritone did not start walking to " + x + ", " + z, "the target may be unloaded or unreachable; try a nearer waypoint");
        }
        boolean finished = alreadyThere || waitIdle(() -> baritone().getCustomGoalProcess().isActive(), timeout);
        JsonObject out = Bot.call(() -> {
            double dx = Bot.player().getX() - x - 0.5;
            double dz = Bot.player().getZ() - z - 0.5;
            double distance = Math.hypot(dx, dz);
            return J.obj("distance", Math.round(distance * 10) / 10.0, "pos",
                    J.nums(Bot.player().getX(), Bot.player().getY(), Bot.player().getZ()), "method", "baritone");
        });
        if (!finished) {
            Bot.run(BaritoneBridge::stop);
            throw new ApiError("timeout", "goto did not finish in " + timeout / 1000 + "s; now at " + out.get("pos"),
                    "try a nearer waypoint");
        }
        double distance = out.get("distance").getAsDouble();
        if (distance > Math.max(range, 2) + 2) {
            throw new ApiError("no_path", "Baritone stopped " + Math.round(distance) + " blocks short at " + out.get("pos"),
                    "the target may be unloaded or unreachable; try a nearer waypoint");
        }
        out.addProperty("arrived", true);
        return out;
    }

    /**
     * What the inventory gained decides when this is done. Baritone's own count looks for the block's item, so
     * stone, which drops cobblestone, would never finish. Quantity 0 tells it to keep going until told to stop.
     * until names the one item that counts, for a caller that knows what it is after. legit means looking for ore the
     * way a person must, down at oreLevel, rather than knowing where it is from chunk data: the polite way on a server.
     */
    static JsonObject collect(Set<Block> blocks, int count, long timeout, Map<String, Integer> before, String until, boolean legit, Integer oreLevel) {
        // Baritone may not break these, and sent after them it would wander off looking for some it may.
        if (blocks.stream().allMatch(Sources::isKeptWhole)) {
            throw new ApiError("kept_whole", "containers and machines are not collected: what they hold would be lost",
                    "to take one on purpose, go within reach and use dig, then pickupItems");
        }
        long deadline = System.currentTimeMillis() + timeout;
        // Set to 0 below when there is nothing to do; the rest of the method reports as usual.
        long lastProgress = System.currentTimeMillis();
        long lastGain = lastProgress;
        Vec3 anchor = Bot.call(() -> Bot.player().position());
        int best = 0;
        String stopped = "timeout";
        int withoutToolPolls = 0;
        forgetMessages();
        Set<BlockPos> own = Blueprints.ownCells();
        Runnable startMining = () -> {
            Settings settings = BaritoneAPI.getSettings();
            baritone();
            settings.legitMine.value = legit;
            if (oreLevel != null) {
                settings.legitMineYLevel.value = oreLevel;
            }
            baritone().getMineProcess().mine(0, blocks.toArray(new Block[0]));
        };
        prepareDrops();
        try {
            if (leadAwayFromOwn(blocks, own, timeout)) {
                Bot.run(startMining);
                Bot.waitUntil(() -> baritone().getMineProcess().isActive(), 2000);
            } else {
                stopped = "nothing_left_in_range";
                deadline = 0;
            }
            while (System.currentTimeMillis() < deadline) {
                Bot.requireControl();
                int gained = Bot.call(() -> until != null ? gains(before).getOrDefault(until, 0) : countable(gains(before), blocks));
                if (gained >= count) {
                    stopped = "got_enough";
                    break;
                }
                if (Bot.call(() -> breakingOwn(own))) {
                    Bot.run(BaritoneBridge::stop);
                    if (!leadAwayFromOwn(blocks, own, timeout)) {
                        stopped = "nothing_left_in_range";
                        break;
                    }
                    Bot.run(startMining);
                    Bot.waitUntil(() -> baritone().getMineProcess().isActive(), 2000);
                    continue;
                }
                // The pickaxe broke and Baritone carries on with bare hands, which gives nothing. Better to stop now and make another.
                if (Bot.call(BaritoneBridge::breakingWithoutTheTool)) {
                    if (++withoutToolPolls >= WITHOUT_TOOL_POLLS) {
                        stopped = "no_tool";
                        break;
                    }
                } else {
                    withoutToolPolls = 0;
                }
                // A long walk or tunnel down to the ore gains nothing for a while, and that is not being stuck.
                Vec3 here = Bot.call(() -> Bot.player().position());
                if (gained > best) {
                    lastGain = System.currentTimeMillis();
                }
                // Digging on with a full pack looks like work and gains nothing: the drops just stay on the ground.
                if (System.currentTimeMillis() - lastGain > FULL_PACK_MILLIS && Bot.call(WindowFns::freeSlots) == 0) {
                    stopped = "inventory_full";
                    break;
                }
                if (gained > best || here.distanceTo(anchor) > 4) {
                    best = Math.max(best, gained);
                    anchor = here;
                    lastProgress = System.currentTimeMillis();
                }
                if (System.currentTimeMillis() - lastProgress > STALL_MILLIS) {
                    stopped = "no_progress";
                    break;
                }
                if (!Bot.call(() -> baritone().getMineProcess().isActive())) {
                    stopped = "nothing_left_in_range";
                    break;
                }
                Bot.pause(200);
            }
        } finally {
            // Also when control was taken back or a reflex fired: leave nothing walking.
            Bot.mc().execute(BaritoneBridge::stop);
        }
        Bot.waitTicks(2);
        JsonObject gainedItems = new JsonObject();
        Bot.call(() -> gains(before)).forEach(gainedItems::addProperty);
        JsonObject out = J.obj("finished", stopped.equals("got_enough"), "stopped_because", stopped);
        out.add("gained", gainedItems);
        if (stopped.equals("inventory_full")) {
            out.addProperty("hint", "every slot is taken and what is dug stays on the ground: decide what you need least and call dropItems with it");
        }
        if (stopped.equals("no_tool")) {
            out.addProperty("hint", "the block needs a tool you no longer hold; get one and try again");
        }
        if (!lastMessage.isEmpty() && !stopped.equals("got_enough")) {
            out.addProperty("baritone_said", lastMessage);
        }
        return out;
    }

    /**
     * Client thread. True while it is breaking a block that drops nothing without a tool it no longer has anywhere.
     * Baritone takes a moment to bring a tool from the inventory to the hand, so one that is carried does not count.
     */
    private static boolean breakingWithoutTheTool() {
        if (!(Bot.mc().hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !baritone().getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) {
            return false;
        }
        BlockState state = Bot.level().getBlockState(hit.getBlockPos());
        if (!state.requiresCorrectToolForDrops()) {
            return false;
        }
        var inventory = Bot.player().getInventory();
        for (int i = 0; i < 36; i++) {
            if (inventory.getItem(i).isCorrectToolForDrops(state)) {
                return false;
            }
        }
        return true;
    }

    static boolean pathing() {
        return baritone().getPathingBehavior().isPathing() || baritone().getPathingControlManager().mostRecentInControl().isPresent();
    }

    /** Client thread. Baritone mines the nearest match, and it has no way to be told that some are not to be taken. */
    private static boolean breakingOwn(Set<BlockPos> own) {
        return !own.isEmpty() && Bot.mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && own.contains(hit.getBlockPos()) && baritone().getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT);
    }

    /**
     * A log in the wall of the cabin is as good a log as any to Baritone, and the nearest. So when what it would go for
     * first is part of something built here, this walks to the nearest that is not, which then is the nearest. False if
     * there is no other in sight.
     */
    private static boolean leadAwayFromOwn(Set<Block> blocks, Set<BlockPos> own, long timeout) {
        if (own.isEmpty()) {
            return true;
        }
        BlockPos[] nearest = Bot.call(() -> nearestWithAndWithoutOwn(blocks, own));
        if (nearest[0] == null || !own.contains(nearest[0])) {
            return true;
        }
        if (nearest[1] == null) {
            return false;
        }
        try {
            walkTo(new GoalNear(nearest[1], 2), Math.min(timeout, WALK_AWAY_MILLIS));
        } catch (ApiError e) {
            if (Skills.isControlFailure(e)) {
                throw e;
            }
        }
        return true;
    }

    /** Client thread. The nearest match of any kind, then the nearest that is not part of a building. */
    private static BlockPos[] nearestWithAndWithoutOwn(Set<Block> blocks, Set<BlockPos> own) {
        var level = Bot.level();
        BlockPos center = Bot.player().blockPosition();
        BlockPos any = null;
        BlockPos other = null;
        double anyDistance = Double.MAX_VALUE;
        double otherDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minY = Math.max(Mc.minY(level), center.getY() - SEARCH_RADIUS);
        int maxY = Math.min(Mc.maxY(level), center.getY() + SEARCH_RADIUS);
        for (int x = center.getX() - SEARCH_RADIUS; x <= center.getX() + SEARCH_RADIUS; x++) {
            for (int z = center.getZ() - SEARCH_RADIUS; z <= center.getZ() + SEARCH_RADIUS; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    if (!blocks.contains(level.getBlockState(cursor).getBlock())) {
                        continue;
                    }
                    double distance = cursor.distSqr(center);
                    if (distance < anyDistance) {
                        anyDistance = distance;
                        any = cursor.immutable();
                    }
                    if (distance < otherDistance && !own.contains(cursor)) {
                        otherDistance = distance;
                        other = cursor.immutable();
                    }
                }
            }
        }
        return new BlockPos[] {any, other};
    }

    /** What a cell of a build should hold, given what is there now. Null leaves it out of the build. */
    interface BuildPlan {
        BlockState want(int x, int y, int z, BlockState current);
    }

    /** Starts Baritone's builder. */
    static void startBuild(String name, BlockPos origin, int width, int height, int length, BuildPlan plan) {
        forgetMessages();
        ISchematic schematic = new ISchematic() {
            @Override
            public boolean inSchematic(int x, int y, int z, BlockState current) {
                return x >= 0 && y >= 0 && z >= 0 && x < width && y < height && z < length && plan.want(x, y, z, current) != null;
            }

            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                return plan.want(x, y, z, current);
            }

            @Override
            public int widthX() {
                return width;
            }

            @Override
            public int heightY() {
                return height;
            }

            @Override
            public int lengthZ() {
                return length;
            }
        };
        Bot.run(() -> {
            baritone();
            Settings settings = BaritoneAPI.getSettings();
            // Stand on it and dig down. Otherwise it digs a hole next to the building to stand level with the block.
            settings.breakFromAbove.value = true;
            settings.goalBreakFromAbove.value = true;
            baritone().getBuilderProcess().build(name, schematic, origin);
        });
    }

    static boolean building() {
        return baritone().getBuilderProcess().isActive();
    }

    static boolean buildPaused() {
        return baritone().getBuilderProcess().isPaused();
    }

    /** What Baritone would put down to stand on. */
    static boolean isScaffolding(BlockState state) {
        return BaritoneAPI.getSettings().acceptableThrowawayItems.value.contains(state.getBlock().asItem());
    }

    /**
     * Walks to one cell, without digging: what stands in the way is usually what has just been built, and glass breaks
     * into nothing. Scaffolding it may put down, since a place to stand is sometimes what is missing.
     */
    static void walkToCell(BlockPos feet, long timeout) {
        if (!Bot.call(() -> Bot.player().blockPosition().equals(feet))) {
            walkWithoutDigging(new GoalBlock(feet), timeout);
        }
    }

    static void walkNear(BlockPos pos, int range, long timeout) {
        walkWithoutDigging(new GoalNear(pos, range), timeout);
    }

    private static void walkWithoutDigging(Goal goal, long timeout) {
        Settings settings = BaritoneAPI.getSettings();
        Bot.run(() -> {
            baritone();
            settings.allowBreak.value = false;
        });
        try {
            walkTo(goal, timeout);
            Bot.waitTicks(3);
        } finally {
            Bot.mc().execute(() -> settings.allowBreak.value = true);
        }
    }

    /** The formats Baritone can read: .schem, .schematic and .litematic. */
    static Blueprint readSchematic(Path file, String name, String source) {
        var format = BaritoneAPI.getProvider().getSchematicSystem().getByFile(file.toFile())
                .orElseThrow(() -> new ApiError("blueprint_error", "Baritone does not know the format of " + source));
        IStaticSchematic schematic;
        try (InputStream in = Files.newInputStream(file)) {
            schematic = format.parse(in);
        } catch (IOException | RuntimeException e) {
            throw new ApiError("blueprint_error", "could not read " + source + ": " + e);
        }
        int width = schematic.widthX();
        int height = schematic.heightY();
        int length = schematic.lengthZ();
        BlockState[] cells = Blueprint.newCells(width, height, length);
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    BlockState state = schematic.getDirect(x, y, z);
                    Blueprint.put(cells, width, length, x, y, z, state == null || state.is(Blocks.STRUCTURE_VOID) ? null : state);
                }
            }
        }
        return new Blueprint(name, "", source, width, height, length, 0, cells);
    }

    static void follow(Predicate<Entity> who, int radius) {
        Bot.run(() -> {
            baritone();
            BaritoneAPI.getSettings().followRadius.value = radius;
            baritone().getFollowProcess().follow(who);
        });
    }

    static boolean following() {
        return Bot.call(() -> baritone().getFollowProcess().isActive());
    }

    /** Walks to any goal Baritone understands, and waits for it. */
    private static JsonObject walkTo(Goal goal, long timeout) {
        Bot.run(() -> baritone().getCustomGoalProcess().setGoalAndPath(goal));
        boolean started = Bot.waitUntil(() -> baritone().getCustomGoalProcess().isActive(), 2000);
        boolean finished = started && waitIdle(() -> baritone().getCustomGoalProcess().isActive(), timeout);
        if (!finished) {
            Bot.run(BaritoneBridge::stop);
        }
        return Bot.call(() -> J.obj("finished", finished, "pos", J.nums(Bot.player().getX(), Bot.player().getY(), Bot.player().getZ())));
    }

    static JsonObject toY(int y, long timeout) {
        JsonObject out = walkTo(new GoalYLevel(y), timeout);
        double at = Bot.call(() -> Bot.player().getY());
        if (Math.abs(at - y) > 2) {
            throw new ApiError("no_path", "stopped at y " + Math.round(at) + ", not " + y + (lastMessage.isEmpty() ? "" : ": " + lastMessage));
        }
        return out;
    }

    static JsonObject runAway(int distance, long timeout) {
        BlockPos from = Bot.call(() -> Bot.player().blockPosition());
        return walkTo(new GoalRunAway(distance, from), timeout);
    }

    static Map<String, Integer> carried() {
        Map<String, Integer> out = new HashMap<>();
        var inventory = Bot.player().getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                out.merge(Bot.itemId(stack), stack.getCount(), Integer::sum);
            }
        }
        return out;
    }

    private static Map<String, Integer> gains(Map<String, Integer> before) {
        Map<String, Integer> out = new HashMap<>();
        carried().forEach((id, now) -> {
            int more = now - before.getOrDefault(id, 0);
            if (more > 0) {
                out.put(id, more);
            }
        });
        return out;
    }

    /**
     * Digging down to stone also brings up dirt, and that must not count toward stone. The client does not have
     * loot tables, so this asks: is it something that is not a block (ore drops), or a block mined with the same tool?
     */
    private static int countable(Map<String, Integer> gained, Set<Block> wanted) {
        List<TagKey<Block>> tools = List.of(BlockTags.MINEABLE_WITH_PICKAXE, BlockTags.MINEABLE_WITH_AXE, BlockTags.MINEABLE_WITH_SHOVEL, BlockTags.MINEABLE_WITH_HOE);
        Set<TagKey<Block>> wantedTools = new HashSet<>();
        for (Block block : wanted) {
            for (TagKey<Block> tool : tools) {
                if (block.defaultBlockState().is(tool)) {
                    wantedTools.add(tool);
                }
            }
        }
        int total = 0;
        for (Map.Entry<String, Integer> entry : gained.entrySet()) {
            Block block = Block.byItem(Bot.item(entry.getKey()));
            boolean counts = block == Blocks.AIR || wanted.contains(block) || wantedTools.isEmpty()
                    || wantedTools.stream().anyMatch(tool -> block.defaultBlockState().is(tool));
            if (counts) {
                total += entry.getValue();
            }
        }
        return total;
    }

    private static boolean waitIdle(java.util.function.Supplier<Boolean> active, long timeout) {
        try {
            return Bot.waitUntil(() -> !active.get(), timeout);
        } catch (ApiError e) {
            // Control was taken back or the goal changed: leave nothing walking.
            Bot.mc().execute(BaritoneBridge::stop);
            throw e;
        }
    }

    static void stop() {
        baritone().getPathingBehavior().cancelEverything();
    }
}
