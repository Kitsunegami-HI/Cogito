package dev.cogito.bot;

import dev.cogito.compat.Mc;
import com.google.gson.JsonArray;
import dev.cogito.memory.MemoryStore;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cogito.agent.AgentLoop;
import dev.cogito.agent.Reflexes;
import dev.cogito.control.Control;
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Whole tasks that run to the end without asking a model anything, built from the same functions the model calls, so
 * they press the same keys and open the same windows. The list follows Mindcraft's skills.js. Each one survives a
 * reflex: it waits for the reflex to end and carries on from how things are now.
 */
final class Skills {
    private static final int MAX_RESUMES = 12;
    private static final int BENCH_RADIUS = 24;
    /** As far as a table gets reused from, or it is left behind and another must be made. */
    private static final int PACK_UP_RADIUS = BENCH_RADIUS + 8;
    private static final long KILL_TIMEOUT_MILLIS = 45_000;
    private static final float HUNT_STOP_HEALTH = 8;
    private static final long SMELT_STALL_MILLIS = 25_000;
    private static final long SECONDS_PER_ITEM = 12;
    private static final List<EntityType<?>> FOOD_ANIMALS = List.of(EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN, EntityType.RABBIT);
    private static final int HUNGRY_FOOD = 12;
    private static final int STARVING_FOOD = 6;
    private static final int FOOD_SEARCH_RADIUS = 32;
    private static final int FOOD_CHANCE_RADIUS = 16;
    private static final long CHANCE_HUNT_EVERY_MILLIS = 180_000;
    private static volatile long nextChanceHunt;
    private static final Map<String, EquipmentSlot> ARMOR_SLOTS = Map.of("head", EquipmentSlot.HEAD, "torso", EquipmentSlot.CHEST, "legs", EquipmentSlot.LEGS, "feet", EquipmentSlot.FEET);

    /** Tables and furnaces a skill put down itself, to be taken along again. */
    private static final Set<BlockPos> PLACED = ConcurrentHashMap.newKeySet();
    private static final Map<BlockPos, Long> LOST_BENCHES = new ConcurrentHashMap<>();
    private static final long LOST_BENCH_MILLIS = 10 * 60_000;
    private static final Map<Item, Long> DISCARDED = new ConcurrentHashMap<>();
    private static final long DISCARDED_MILLIS = 10 * 60_000;
    private static final long TIDY_EVERY_MILLIS = 60_000;
    private static final int CROWDED_FREE_SLOTS = 2;
    private static volatile long lastTidy;
    /** How much clear air a thrown thing wants, how far to look for it, and how far up to throw so that it carries. */
    private static final double OPEN_ENOUGH = 4;
    private static final double OPEN_LOOKED_AT = 8;
    private static final float THROW_PITCH = -20;
    private static final int WALK_OUT_DISTANCE = 8;
    private static final long WALK_OUT_MILLIS = 20_000;
    private static final int PLENTY_OF_FOOD = 60;
    private static final int RAW_WORTH_COOKING = 2;
    private static final int COBBLE_TO_SPARE = 12;
    private static final long FOOD_TRY_EVERY_MILLIS = 90_000;
    private static volatile long nextFoodTry;
    /** Things with better uses than being eaten. */
    private static final Set<Item> VALUABLE = Set.of(Items.GOLD_NUGGET, Items.GOLD_INGOT, Items.GOLD_BLOCK, Items.IRON_INGOT, Items.IRON_NUGGET, Items.DIAMOND,
            Items.EMERALD, Items.ENDER_PEARL, Items.BLAZE_ROD, Items.BLAZE_POWDER, Items.GHAST_TEAR);

    private Skills() {}

    static void register() {
        Api.skill("obtain(item,[count=1],[timeout_s=600])", "get an item until you carry count of it: works out everything needed on the way, gathering, tools, crafting, smelting, hunting, and does it all", Obtain::run);
        Api.read("obtainPlan(item,[count=1])", "what obtain would do, in order, without doing it; says if there is no way from here", Obtain::preview);
        Api.skill("craftItem(item,[count=1])", "make count more of an item: finds or puts down a crafting table, walks to it, crafts, takes the table back; fails naming what is missing", Skills::craftItem);
        Api.skill("smeltItem(item,[count=1],[fuel])", "smelt in a furnace, item being what goes in or what should come out: finds or puts down a furnace, picks fuel, waits, takes the result, takes the furnace back", Skills::smeltItem);
        Api.skill("hunt(mob,[count=1],[radius=48])", "kill the nearest mobs of a type, chasing them, and pick up what they drop", Skills::huntFn);
        Api.skill("gatherFood([count=4],[radius=48])", "hunt nearby animals until you carry count pieces of food", Skills::gatherFoodFn);
        Api.skill("pickupItems([radius=12],[item])", "walk over nearby dropped items to pick them up", args -> J.obj("picked_up", pickup(J.integer(args, "radius", 12), J.str(args, "item", null))));
        Api.skill("sleepInBed([radius=32])", "walk to the nearest bed and sleep until morning; says why if the game refuses", Skills::sleepInBed);
        Api.skill("putInChest(item,[count],[radius=24])", "walk to the nearest chest or barrel and store items in it", Skills::putInChest);
        Api.skill("takeFromChest(item,[count],[radius=24])", "walk to the nearest chest or barrel and take items out", Skills::takeFromChest);
        Api.skill("viewChest([radius=24])", "walk to the nearest chest or barrel, read what it holds, close it", Skills::viewChest);
        Api.skill("dropItems(items)", "throw away what you choose so that it stays gone: turns to open space first, walks out of somewhere cramped, and it is not picked up again. items is a list of item ids, each meaning all of that item, or of {item,count}", Skills::dropItemsFn);
        Api.skill("organizeInventory()", "tidy the pack: merges part stacks and puts pickaxe, sword, axe, shovel, food, building blocks and torches in the hotbar; says how many slots are free and which tools are beaten by better ones you carry", args -> WindowFns.organize());
        Api.skill("cookFood([count=16])", "cook the raw food you carry: fetches a furnace if need be, burns the fuel you carry, takes the food out and the furnace back", Skills::cookFoodFn);
        Api.read("foodOptions()", "the food you carry with what each is worth, marking what is raw, and what you could make from what you carry, by furnace or crafting, with how much food that gains", args -> Bot.call(Skills::foodOptions));
        Api.skill("goToPlace(name,[range=2],[timeout_s=240])", "walk to a place remembered in this world, such as home", Skills::goToPlace);
        Api.skill("placeNearby(item)", "put a carried block down somewhere within reach, for a machine or a bench, and say where; makes room in a wall if there is none", args -> {
            BlockPos at = placeNearby(Bot.call(() -> Bot.item(J.str(args, "item"))));
            return J.obj("pos", J.nums(at.getX(), at.getY(), at.getZ()));
        });
        Api.skill("equipBestArmor()", "wear the best armor you carry in every slot", args -> equipBestArmor());
        Api.skill("goToNearestBlock(blocks,[range=2],[radius=64])", "walk to the nearest of these blocks", Skills::goToNearestBlock);
        Api.skill("goToNearestEntity(type,[range=2],[radius=64])", "walk to the nearest entity of a type", Skills::goToNearestEntity);
        Api.skill("goToPlayer(name,[range=3])", "walk to a player in view", Skills::goToPlayer);
        Api.skill("followPlayer(name,[range=3],[seconds=60])", "keep following a player for a while", Skills::followPlayer);
        Api.skill("moveAway(distance)", "walk at least this far from where you stand, whichever way is passable", args -> Pathing.runAway(J.integer(args, "distance"), 60_000));
        Api.skill("goToY(y,[timeout_s=120])", "go to a height, digging or building as needed", args -> Pathing.toY(J.integer(args, "y"), J.integer(args, "timeout_s", 120) * 1000L));
        Api.skill("goToSurface([timeout_s=120])", "go up to the open air above you, digging as needed", args -> {
            long deadline = System.currentTimeMillis() + J.integer(args, "timeout_s", 120) * 1000L;
            while (true) {
                JsonElement reached = Pathing.toY(Bot.call(Skills::openAirHere), Math.max(1000, deadline - System.currentTimeMillis()));
                // A height is all that goal asks for, and walking there can end under a hill. A slab or snow underfoot is
                // the top of the column too, so one less still counts as open.
                if (Bot.call(() -> Bot.player().getBlockY() >= openAirHere() - 1)) {
                    return reached;
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw new ApiError("timeout", "still under cover", "use goToY to go higher, or walk out first");
                }
            }
        });
    }

    /** Client thread. The height of the first open air over where the player stands. */
    private static int openAirHere() {
        return Bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING, Bot.player().getBlockX(), Bot.player().getBlockZ());
    }

    // ---- shared ----

    /** Runs a skill; when a reflex takes the body it waits, then runs it again from how things are now. */
    static JsonElement resilient(Api.Handler handler, JsonObject args) {
        for (int resumes = 0; ; resumes++) {
            try {
                return handler.call(args);
            } catch (ApiError e) {
                if (!e.kind.equals("interrupted_by_reflex") || resumes >= MAX_RESUMES) {
                    throw e;
                }
                Reflexes.rejoin();
                say("carrying on after a reflex");
            }
        }
    }

    /** A function the skills build on. Fails as an exception, so a skill reads as a plain sequence. */
    static JsonObject call(String fn, JsonObject args) {
        JsonObject out = Api.call(fn, args);
        if (!out.get("ok").getAsBoolean()) {
            throw new ApiError(J.str(out, "error", "failed"), J.str(out, "message", fn + " failed"), J.str(out, "hint", null));
        }
        JsonElement result = out.get("result");
        return result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
    }

    /** Failures that mean "stop what you are doing", as opposed to "this attempt did not work". */
    static boolean isControlFailure(ApiError e) {
        return switch (e.kind) {
            case "human_in_control", "interrupted", "interrupted_by_reflex", "not_in_world", "agent_busy" -> true;
            default -> false;
        };
    }

    private static void say(String text) {
        AgentLoop.get().noteSkill(text);
    }

    /** A value that must survive a reflex restarting the skill, kept in its arguments. */
    private static int remember(JsonObject args, String key, Supplier<Integer> first) {
        if (!args.has(key)) {
            args.addProperty(key, first.get());
        }
        return args.get(key).getAsInt();
    }

    private static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static int carried(Item item) {
        return Bot.call(() -> Obtain.carried().getOrDefault(item, 0));
    }

    static void closeWindow() {
        if (Control.isAi() && Bot.call(() -> Bot.mc().screen != null)) {
            WindowFns.close();
        }
    }

    private static JsonObject at(BlockPos pos) {
        return J.obj("x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
    }

    static BlockPos nearestBlock(Predicate<BlockState> wanted, int radius) {
        return nearestBlock(wanted, radius, pos -> false);
    }

    private static BlockPos nearestBlock(Predicate<BlockState> wanted, int radius, Predicate<BlockPos> leaveOut) {
        return Bot.call(() -> {
            var level = Bot.level();
            BlockPos center = Bot.player().blockPosition();
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int height = Math.min(radius, 32);
            for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
                for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                    if (!level.hasChunk(x >> 4, z >> 4)) {
                        continue;
                    }
                    for (int y = Math.max(Mc.minY(level), center.getY() - height); y <= Math.min(Mc.maxY(level), center.getY() + height); y++) {
                        cursor.set(x, y, z);
                        if (wanted.test(level.getBlockState(cursor)) && !leaveOut.test(cursor)) {
                            double distance = cursor.distSqr(center);
                            if (distance < bestDistance) {
                                bestDistance = distance;
                                best = cursor.immutable();
                            }
                        }
                    }
                }
            }
            return best;
        });
    }

    /** Grass and flowers stop no walk, but a click lands on them and not on what is behind: a person cuts them away. Says whether that did it. */
    private static boolean clearPlantsInTheWay(BlockPos pos) {
        for (int tries = 0; tries < 3; tries++) {
            if (inReach(pos)) {
                return true;
            }
            BlockPos plant = Bot.call(() -> {
                Vec3 eye = Bot.player().getEyePosition();
                Vec3 middle = Vec3.atCenterOf(pos);
                if (eye.distanceTo(middle) > Bot.REACH) {
                    return null;
                }
                BlockHitResult hit = Bot.level().clip(new ClipContext(eye, middle, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, Bot.player()));
                boolean soft = hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(pos)
                        && Bot.level().getBlockState(hit.getBlockPos()).canBeReplaced();
                return soft ? hit.getBlockPos() : null;
            });
            if (plant == null) {
                return false;
            }
            try {
                call("dig", at(plant));
            } catch (ApiError e) {
                if (isControlFailure(e)) {
                    throw e;
                }
                return false;
            }
        }
        return inReach(pos);
    }

    private static boolean inReach(BlockPos pos) {
        return Bot.call(() -> Bot.player().getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= Bot.REACH && Bot.visiblePoint(pos) != null);
    }

    /**
     * Gets close enough to use a block. Arm's reach is about four blocks, so the walk aims near rather than at it: a
     * chest high up in a stack is used from the ground in front, not by digging into the stack. Digging is the last try.
     */
    static void walkInReach(BlockPos pos) {
        closeWindow();
        for (int range : new int[] {3, 2}) {
            if (clearPlantsInTheWay(pos)) {
                return;
            }
            if (Pathing.hasBaritone()) {
                try {
                    BaritoneBridge.walkNear(pos, range, 45_000);
                } catch (ApiError e) {
                    if (isControlFailure(e)) {
                        throw e;
                    }
                }
            } else {
                JsonObject args = at(pos);
                args.addProperty("range", range);
                args.addProperty("timeout_s", 45);
                call("goto", args);
            }
        }
        if (!clearPlantsInTheWay(pos)) {
            JsonObject args = at(pos);
            args.addProperty("range", 2);
            args.addProperty("timeout_s", 45);
            call("goto", args);
        }
        if (!clearPlantsInTheWay(pos)) {
            throw new ApiError("out_of_reach", "could not get within reach of " + pos.toShortString());
        }
    }

    /** Spots beside you where a block can go: empty, on something solid, not where you stand, and in reach. */
    private static List<BlockPos> freeSpots() {
        LocalPlayer player = Bot.player();
        var level = Bot.level();
        BlockPos center = player.blockPosition();
        AABB body = player.getBoundingBox().inflate(0.1);
        Vec3 eye = player.getEyePosition();
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-3, -1, -3), center.offset(3, 1, 3))) {
            BlockState state = level.getBlockState(pos);
            if (state.canBeReplaced() && state.getFluidState().isEmpty() && !body.intersects(new AABB(pos))
                    && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                    && eye.distanceTo(Vec3.atCenterOf(pos)) <= Bot.REACH - 0.3) {
                out.add(pos.immutable());
            }
        }
        out.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        return out;
    }

    /** A table, furnace or stand a skill put down is taken along again by packUpBenches. */
    static void notePlaced(BlockPos pos) {
        PLACED.add(pos);
    }

    /** Benches a skill is still working at: an obtain called from inside it must not pack them up on its way out. */
    private static final Set<BlockPos> IN_USE = ConcurrentHashMap.newKeySet();

    static void keep(BlockPos pos) {
        IN_USE.add(pos);
    }

    static void release(BlockPos pos) {
        IN_USE.remove(pos);
    }

    static BlockPos placeNearby(Item item) {
        closeWindow();
        // Mining often ends in mid-fall down its own shaft, and nothing is within reach to build on from up there.
        Bot.waitUntil(() -> Bot.player().onGround() || Bot.player().isInWater(), 3000);
        ApiError last = null;
        for (int round = 0; round < 2; round++) {
            List<BlockPos> spots = Bot.call(Skills::freeSpots);
            for (BlockPos spot : spots.subList(0, Math.min(6, spots.size()))) {
                try {
                    JsonObject args = at(spot);
                    args.addProperty("item", idOf(item));
                    call("placeBlock", args);
                    return spot;
                } catch (ApiError e) {
                    if (isControlFailure(e)) {
                        throw e;
                    }
                    last = e;
                    // A click that opened something instead of placing leaves its window up; the next try needs it gone.
                    closeWindow();
                }
            }
            // In a shaft or tunnel there is no room to put anything: a person would dig a place for it into the wall.
            // Just after arriving somewhere the ground may also not have loaded, so it gets a moment either way.
            if (round == 0) {
                hollowOutWall();
                Bot.waitTicks(10);
            }
        }
        throw new ApiError("no_room", "nowhere to put " + idOf(item) + " down here" + (last == null ? "" : ": " + last.getMessage()), "move to open ground");
    }

    /** Digs out one block of wall at foot level, away from lava and water, to make a space. */
    private static void hollowOutWall() {
        BlockPos wall = Bot.call(() -> {
            var level = Bot.level();
            BlockPos feet = Bot.player().blockPosition();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos pos = feet.relative(side);
                BlockState state = level.getBlockState(pos);
                // Never a chest, a furnace or anything else that holds things, and never a wall of one's own building.
                boolean diggable = !state.isAir() && state.getFluidState().isEmpty() && state.getDestroySpeed(level, pos) >= 0
                        && !state.is(Blocks.CRAFTING_TABLE) && level.getBlockEntity(pos) == null && !Blueprints.ownCells().contains(pos);
                boolean safe = level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
                for (Direction around : Direction.values()) {
                    safe &= level.getBlockState(pos.relative(around)).getFluidState().isEmpty();
                }
                if (diggable && safe) {
                    return pos.immutable();
                }
            }
            return null;
        });
        if (wall != null) {
            call("dig", at(wall));
        }
    }

    /** Takes back what a skill put down, if it is close and the right tool is at hand. Otherwise it stays where it is. */
    static void packUpBenches() {
        for (BlockPos pos : new ArrayList<>(PLACED)) {
            if (cookingAt(pos) || IN_USE.contains(pos)) {
                continue;
            }
            try {
                boolean worth = Bot.call(() -> {
                    BlockState state = Bot.level().getBlockState(pos);
                    boolean ours = state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE) || state.is(Blocks.BREWING_STAND);
                    boolean canHarvest = !state.requiresCorrectToolForDrops() || Obtain.carried().keySet().stream().anyMatch(item -> new ItemStack(item).isCorrectToolForDrops(state));
                    return ours && canHarvest && Math.sqrt(pos.distSqr(Bot.player().blockPosition())) <= PACK_UP_RADIUS;
                });
                if (worth) {
                    walkInReach(pos);
                    call("dig", at(pos));
                    if (pickup(6, null) == 0) {
                        say("could not find the " + Bot.blockId(Bot.call(() -> Bot.level().getBlockState(pos))) + " that was dug up at " + pos.toShortString());
                    }
                }
                PLACED.remove(pos);
            } catch (ApiError e) {
                if (isControlFailure(e)) {
                    throw e;
                }
                PLACED.remove(pos);
            }
        }
    }

    // ---- crafting ----

    private static JsonElement craftItem(JsonObject args) {
        Item item = Bot.call(() -> Bot.item(J.str(args, "item")));
        int count = J.integer(args, "count", 1);
        int target = remember(args, "_target", () -> carried(item) + count);
        int left = target - carried(item);
        if (left > 0) {
            try {
                craft(item, left);
            } catch (ApiError e) {
                packUpAfterFailure(e);
                throw e;
            }
        }
        packUpBenches();
        return J.obj("item", idOf(item), "now_have", carried(item));
    }

    /** A skill that failed should still not leave its table or furnace lying around, unless it was told to stop. */
    private static void packUpAfterFailure(ApiError cause) {
        if (!isControlFailure(cause)) {
            try {
                packUpBenches();
            } catch (ApiError ignored) {
                // The failure that brought us here is the one worth reporting.
            }
        }
    }

    /** Crafts with what is carried. A table is only fetched once it is certain the ingredients are there. */
    static void craft(Item item, int count) {
        closeWindow();
        boolean needsTable = Bot.call(() -> {
            List<WindowFns.CraftPlan> plans = WindowFns.plansFor(item);
            if (plans.isEmpty()) {
                throw new ApiError("no_recipe", "no known crafting recipe makes " + idOf(item), "obtain also knows mining, smelting and hunting");
            }
            for (WindowFns.CraftPlan plan : plans) {
                if (WindowFns.missing(plan).isEmpty()) {
                    return plan.width() > 2 || plan.height() > 2;
                }
            }
            throw new ApiError("missing_ingredients", "cannot craft " + idOf(item) + ": missing " + WindowFns.missing(plans.get(0)), "obtain gets what is missing as well");
        });
        if (needsTable) {
            benchInReach(Blocks.CRAFTING_TABLE.defaultBlockState(), Items.CRAFTING_TABLE);
        }
        try {
            call("craft", J.obj("item", idOf(item), "count", count));
        } catch (ApiError e) {
            // Part stacks of one thing take up places for nothing. If merging them makes room, there is nothing to decide yet.
            if (!e.kind.equals("inventory_full") || !tidiedSomeRoom()) {
                throw e;
            }
            call("craft", J.obj("item", idOf(item), "count", count));
        }
    }

    private static boolean tidiedSomeRoom() {
        int before = Bot.call(WindowFns::freeSlots);
        WindowFns.organize();
        lastTidy = System.currentTimeMillis();
        return Bot.call(WindowFns::freeSlots) > before;
    }

    /** Between pieces of a long job, when the pack is nearly full: tidied now, it is not found full in the middle of something. */
    static void tidyWhenCrowded() {
        if (System.currentTimeMillis() - lastTidy > TIDY_EVERY_MILLIS && Bot.call(WindowFns::freeSlots) <= CROWDED_FREE_SLOTS) {
            lastTidy = System.currentTimeMillis();
            WindowFns.organize();
        }
    }

    /** What the model chose to throw away. Not picked up by accident a moment later, unless it asks for that very thing. */
    private static JsonElement dropItemsFn(JsonObject args) {
        JsonElement raw = args.get("items");
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().isEmpty()) {
            throw new ApiError("bad_argument", "items is a list of item ids, each meaning all of that item, or of {item,count}");
        }
        Map<String, Integer> amounts = new LinkedHashMap<>();
        for (JsonElement entry : raw.getAsJsonArray()) {
            if (entry.isJsonObject()) {
                amounts.put(J.str(entry.getAsJsonObject(), "item"), J.integer(entry.getAsJsonObject(), "count", WindowFns.EVERYTHING));
            } else {
                amounts.put(entry.getAsString(), WindowFns.EVERYTHING);
            }
        }
        closeWindow();
        boolean open = faceOpenSpace();
        JsonObject dropped = new JsonObject();
        JsonArray notCarried = new JsonArray();
        WindowFns.throwFromInventory(amounts).forEach((id, count) -> {
            if (count > 0) {
                dropped.addProperty(id, count);
                noteDiscarded(id);
            } else {
                notCarried.add(id);
            }
        });
        JsonObject out = J.obj("dropped", dropped, "free_slots", Bot.call(WindowFns::freeSlots));
        if (!notCarried.isEmpty()) {
            out.add("not_carried", notCarried);
        }
        if (!open) {
            out.addProperty("cramped", true);
        }
        return out;
    }

    private static void noteDiscarded(String id) {
        if (!id.startsWith("#")) {
            DISCARDED.put(Bot.call(() -> Bot.item(id)), System.currentTimeMillis());
        }
    }

    private static boolean discarded(Item item) {
        Long since = DISCARDED.get(item);
        return since != null && System.currentTimeMillis() - since < DISCARDED_MILLIS;
    }

    /**
     * Turns to where a thrown thing flies clear and lands out of reach of the feet, walking out of somewhere cramped first:
     * thrown at a wall it falls back and is picked up again at once. Says whether it found such a place.
     */
    private static boolean faceOpenSpace() {
        for (int tries = 0; tries < 3; tries++) {
            float[] best = Bot.call(Skills::mostOpenDirection);
            if (best[1] >= OPEN_ENOUGH) {
                call("look", J.obj("yaw", best[0], "pitch", THROW_PITCH));
                return true;
            }
            if (tries < 2) {
                try {
                    Pathing.runAway(WALK_OUT_DISTANCE, WALK_OUT_MILLIS);
                } catch (ApiError e) {
                    if (isControlFailure(e)) {
                        throw e;
                    }
                }
            } else {
                call("look", J.obj("yaw", best[0], "pitch", THROW_PITCH));
            }
        }
        return false;
    }

    /** Client thread. The yaw with the longest clear run at throwing height, and how long that is. */
    private static float[] mostOpenDirection() {
        LocalPlayer player = Bot.player();
        Vec3 eye = player.getEyePosition();
        float bestYaw = player.getYRot();
        double bestClear = -1;
        for (int i = 0; i < 16; i++) {
            float yaw = i * 22.5f;
            double radians = Math.toRadians(yaw);
            Vec3 way = new Vec3(-Math.sin(radians), 0, Math.cos(radians));
            double clear = Math.min(clearRun(eye, way), clearRun(eye.add(0, -1, 0), way));
            if (clear > bestClear) {
                bestClear = clear;
                bestYaw = yaw;
            }
        }
        return new float[] {bestYaw, (float) bestClear};
    }

    private static double clearRun(Vec3 from, Vec3 way) {
        Vec3 to = from.add(way.scale(OPEN_LOOKED_AT));
        BlockHitResult hit = Bot.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, Bot.player()));
        return hit.getType() == HitResult.Type.MISS ? OPEN_LOOKED_AT : hit.getLocation().distanceTo(from);
    }

    /** Client thread. Food carried that a furnace would make better, and how much of each. */
    private static Map<Item, Integer> rawFoodCarried() {
        Map<Item, Integer> raw = new LinkedHashMap<>();
        Inventory inventory = Bot.player().getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (Bot.cooksIntoBetterFood(stack)) {
                raw.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return raw;
    }

    /** Client thread. The same things a furnace is fed with here. */
    private static boolean fuelCarried() {
        Inventory inventory = Bot.player().getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(ItemTags.COALS) || stack.is(ItemTags.LOGS_THAT_BURN) || stack.is(ItemTags.PLANKS) || stack.is(Items.STICK)) {
                return true;
            }
        }
        return false;
    }

    private static JsonElement cookFoodFn(JsonObject args) {
        int left = J.integer(args, "count", 16);
        Map<Item, Integer> raw = Bot.call(Skills::rawFoodCarried);
        if (raw.isEmpty()) {
            return J.obj("cooked", 0, "note", "nothing raw is carried");
        }
        if (Bot.call(() -> !fuelCarried())) {
            throw new ApiError("no_fuel", "nothing to burn: carry coal, charcoal, logs, planks or sticks", "obtain some wood first");
        }
        if (!furnaceAvailable()) {
            call("obtain", J.obj("item", "minecraft:furnace"));
        }
        int before = foodPointsCarried();
        for (Map.Entry<Item, Integer> entry : raw.entrySet()) {
            int count = Math.min(entry.getValue(), left);
            if (count <= 0) {
                break;
            }
            // Each kind takes a furnace of its own. With no other to be had, the next kind waits for this one.
            while (!furnaceAvailable() && anythingCooking()) {
                collectSmelt(true);
            }
            startSmelt(entry.getKey(), count, null);
            left -= count;
        }
        while (anythingCooking()) {
            collectSmelt(true);
        }
        packUpBenches();
        return J.obj("food_points_gained", foodPointsCarried() - before, "still_raw", Bot.call(Skills::rawFoodCarried).size());
    }

    private static int foodPointsCarried() {
        return Bot.call(() -> {
            Inventory inventory = Bot.player().getInventory();
            int total = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inventory.getItem(i);
                FoodProperties food = stack.get(DataComponents.FOOD);
                if (food != null) {
                    total += food.nutrition() * stack.getCount();
                }
            }
            return total;
        });
    }

    /** One thing that can be made into food right now, and what it is worth. */
    private record FoodChoice(Item item, Item from, boolean furnace, int canMake, int makes, int nutrition, float saturation, int gain, boolean valuable, Map<Item, Integer> uses) {}

    /** Client thread. What can be made into food from what is carried, the best gain first. */
    private static List<FoodChoice> foodChoices() {
        RecipeIndex recipes = RecipeIndex.get();
        Map<Item, Integer> have = Obtain.carried();
        List<FoodChoice> out = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : have.entrySet()) {
            Item result = recipes.smeltsInto(entry.getKey());
            FoodProperties cooked = result == null ? null : new ItemStack(result).get(DataComponents.FOOD);
            if (cooked != null) {
                FoodProperties before = new ItemStack(entry.getKey()).get(DataComponents.FOOD);
                int gain = cooked.nutrition() - (before == null ? 0 : before.nutrition());
                if (gain > 0) {
                    out.add(new FoodChoice(result, entry.getKey(), true, entry.getValue(), 1, cooked.nutrition(), cooked.saturation(), gain, false, Map.of(entry.getKey(), 1)));
                }
            }
        }
        for (Item item : BuiltInRegistries.ITEM) {
            FoodProperties food = new ItemStack(item).get(DataComponents.FOOD);
            if (food == null) {
                continue;
            }
            for (WindowFns.CraftPlan plan : recipes.crafting(item)) {
                if (!WindowFns.missing(plan).isEmpty()) {
                    continue;
                }
                Map<Item, Integer> uses = new LinkedHashMap<>();
                for (List<ItemStack> cell : plan.cells()) {
                    for (ItemStack option : cell) {
                        if (have.getOrDefault(option.getItem(), 0) > 0) {
                            uses.merge(option.getItem(), 1, Integer::sum);
                            break;
                        }
                    }
                }
                int rounds = Integer.MAX_VALUE;
                int eaten = 0;
                boolean valuable = false;
                for (Map.Entry<Item, Integer> use : uses.entrySet()) {
                    rounds = Math.min(rounds, have.getOrDefault(use.getKey(), 0) / use.getValue());
                    FoodProperties inside = new ItemStack(use.getKey()).get(DataComponents.FOOD);
                    eaten += inside == null ? 0 : inside.nutrition() * use.getValue();
                    valuable |= VALUABLE.contains(use.getKey());
                }
                int gain = food.nutrition() * plan.makes() - eaten;
                if (gain > 0 && rounds > 0 && rounds < Integer.MAX_VALUE) {
                    out.add(new FoodChoice(item, null, false, rounds * plan.makes(), plan.makes(), food.nutrition(), food.saturation(), gain, valuable, uses));
                }
                break;
            }
        }
        out.sort(Comparator.comparingInt((FoodChoice choice) -> choice.valuable() ? 1 : 0).thenComparing(Comparator.comparingInt(FoodChoice::gain).reversed()));
        return out;
    }

    /** Client thread. For a model weighing what to do about food. */
    private static JsonObject foodOptions() {
        JsonArray carried = new JsonArray();
        Obtain.carried().forEach((item, count) -> {
            ItemStack stack = new ItemStack(item);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food != null) {
                JsonObject row = J.obj("item", idOf(item), "count", count, "nutrition", food.nutrition(), "saturation", Math.round(food.saturation() * 10) / 10.0);
                if (Bot.cooksIntoBetterFood(stack)) {
                    row.addProperty("raw", true);
                }
                carried.add(row);
            }
        });
        JsonArray make = new JsonArray();
        for (FoodChoice choice : foodChoices()) {
            JsonObject uses = new JsonObject();
            choice.uses().forEach((item, count) -> uses.addProperty(idOf(item), count));
            JsonObject row = J.obj("item", idOf(choice.item()), "via", choice.furnace() ? "furnace" : "crafting", "uses", uses, "can_make", choice.canMake(),
                    "nutrition", choice.nutrition(), "saturation", Math.round(choice.saturation() * 10) / 10.0, "food_gained_each_time", choice.gain());
            if (choice.furnace()) {
                row.addProperty("seconds_each", 10);
            }
            if (choice.valuable()) {
                row.addProperty("uses_something_valuable", true);
            }
            make.add(row);
        }
        return J.obj("carried", carried, "can_make", make, "note", "food_gained_each_time counts what the ingredients would have fed by themselves; valuable things usually have better uses");
    }

    /**
     * Between pieces of a long job, and only from what is at hand: raw meat goes in a furnace, which goes on working while
     * the job does, and the likes of wheat become bread. One furnace is always left for the job.
     */
    private static void improveFoodInPassing() {
        if (System.currentTimeMillis() < nextFoodTry || foodPointsCarried() >= PLENTY_OF_FOOD) {
            return;
        }
        nextFoodTry = System.currentTimeMillis() + FOOD_TRY_EVERY_MILLIS;
        Map<Item, Integer> raw = Bot.call(Skills::rawFoodCarried);
        boolean cookedSome = false;
        for (Map.Entry<Item, Integer> entry : raw.entrySet()) {
            boolean already = COOKING.stream().anyMatch(cooking -> cooking.input() == entry.getKey());
            boolean furnaceAtHand = furnaceSlotsLeft() > 1 && furnaceAvailable()
                    && (Bot.call(Skills::freeFurnace) != null || carried(Items.FURNACE) > 0 || carried(Items.COBBLESTONE) >= COBBLE_TO_SPARE);
            if (!already && entry.getValue() >= RAW_WORTH_COOKING && furnaceAtHand && Bot.call(Skills::fuelCarried)) {
                say("cooking " + entry.getValue() + " " + idOf(entry.getKey()) + " while the job goes on");
                startSmelt(entry.getKey(), entry.getValue(), null);
                cookedSome = true;
            }
        }
        if (cookedSome) {
            return;
        }
        Item recipe = Bot.call(() -> {
            boolean table = carried(Items.CRAFTING_TABLE) > 0 || nearestBlock(state -> state.is(Blocks.CRAFTING_TABLE), BENCH_RADIUS, Skills::benchIsLost) != null;
            for (FoodChoice choice : foodChoices()) {
                if (!choice.furnace() && !choice.valuable() && table) {
                    return choice.item();
                }
            }
            return null;
        });
        if (recipe != null) {
            say("making " + idOf(recipe) + " from what is carried");
            craft(recipe, 1);
        }
    }

    /** Whether a bench has turned out to be out of reach lately, so that it does not count as being there. */
    static boolean benchIsLost(BlockPos pos) {
        Long since = LOST_BENCHES.get(pos);
        if (since == null) {
            return false;
        }
        if (System.currentTimeMillis() - since > LOST_BENCH_MILLIS) {
            LOST_BENCHES.remove(pos);
            return false;
        }
        return true;
    }

    static boolean anyBenchLost() {
        return !LOST_BENCHES.isEmpty();
    }

    /**
     * A bench that can really be used. One on a ledge or behind a wall is near and no use, and insisting on it is how a
     * whole job goes round in circles: after a real try it stops counting, and another goes down right here.
     */
    private static BlockPos benchInReach(BlockState block, Item item) {
        for (int tries = 0; ; tries++) {
            BlockPos bench = bench(block, item);
            try {
                walkInReach(bench);
                return bench;
            } catch (ApiError e) {
                if (isControlFailure(e) || tries >= 2) {
                    throw e;
                }
                LOST_BENCHES.put(bench, System.currentTimeMillis());
                say("cannot get to the bench at " + bench.toShortString() + ", so another one it is");
            }
        }
    }

    /** A block to work at: one within walking distance, or one that is carried or can be made, put down here. */
    private static BlockPos bench(BlockState block, Item item) {
        BlockPos near = nearestBlock(state -> state.is(block.getBlock()), BENCH_RADIUS, Skills::benchIsLost);
        if (near != null) {
            return near;
        }
        if (carried(item) == 0) {
            craft(item, 1);
        }
        BlockPos placed = placeNearby(item);
        PLACED.add(placed);
        return placed;
    }

    // ---- smelting ----

    private static JsonElement smeltItem(JsonObject args) {
        Item named = Bot.call(() -> Bot.item(J.str(args, "item")));
        Item input = Bot.call(() -> {
            RecipeIndex recipes = RecipeIndex.get();
            Map<Item, Integer> have = Obtain.carried();
            if (recipes.smeltsInto(named) != null && have.getOrDefault(named, 0) > 0) {
                return named;
            }
            for (RecipeIndex.Smelt smelt : recipes.smelting(named)) {
                for (Item option : smelt.inputs()) {
                    if (have.getOrDefault(option, 0) > 0) {
                        return option;
                    }
                }
            }
            if (recipes.smeltsInto(named) != null || !recipes.smelting(named).isEmpty()) {
                throw new ApiError("missing_ingredients", "you carry nothing to smelt for " + idOf(named), "obtain gets it as well");
            }
            throw new ApiError("no_recipe", "a furnace does nothing with " + idOf(named), "raw ores go in as raw_iron, raw_gold and so on");
        });
        Item output = Bot.call(() -> RecipeIndex.get().smeltsInto(input));
        int count = J.integer(args, "count", 1);
        int target = remember(args, "_target", () -> carried(output) + count);
        int left = target - carried(output);
        if (left > 0) {
            try {
                smelt(input, left, J.str(args, "fuel", null));
            } catch (ApiError e) {
                packUpAfterFailure(e);
                throw e;
            }
        }
        packUpBenches();
        return J.obj("item", idOf(output), "now_have", carried(output));
    }

    private static AbstractFurnaceMenu furnaceMenu() {
        if (Bot.player().containerMenu instanceof AbstractFurnaceMenu menu) {
            return menu;
        }
        throw new ApiError("no_window", "the furnace is not open");
    }

    private static ItemStack furnaceSlot(int slot) {
        return Bot.call(() -> furnaceMenu().getSlot(slot).getItem().copy());
    }

    static int smelt(Item input, int count, String fuelChoice) {
        closeWindow();
        BlockPos furnace = benchInReach(Blocks.FURNACE.defaultBlockState(), Items.FURNACE);
        call("openFurnace", at(furnace));
        if (Bot.call(() -> !(Bot.mc().screen instanceof AbstractFurnaceScreen<?>))) {
            throw new ApiError("no_window", "the furnace at " + furnace.toShortString() + " did not open");
        }
        Bot.waitTicks(2);
        int taken = 0;
        try {
            taken += takeFrom(AbstractFurnaceMenu.RESULT_SLOT);
            ItemStack inside = furnaceSlot(AbstractFurnaceMenu.INGREDIENT_SLOT);
            int already = 0;
            if (!inside.isEmpty()) {
                if (inside.getItem() == input) {
                    already = inside.getCount();
                } else {
                    takeFrom(AbstractFurnaceMenu.INGREDIENT_SLOT);
                }
            }
            addFuel(input, count, fuelChoice);
            if (count - already > 0) {
                call("windowPut", J.obj("slot", AbstractFurnaceMenu.INGREDIENT_SLOT, "item", idOf(input), "count", count - already));
            }
            long deadline = System.currentTimeMillis() + count * SECONDS_PER_ITEM * 1000 + 15_000;
            long lastChange = System.currentTimeMillis();
            while (taken < count && System.currentTimeMillis() < deadline) {
                Bot.requireControl();
                ItemStack result = furnaceSlot(AbstractFurnaceMenu.RESULT_SLOT);
                if (!result.isEmpty()) {
                    taken += takeFrom(AbstractFurnaceMenu.RESULT_SLOT);
                    lastChange = System.currentTimeMillis();
                } else if (furnaceSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).isEmpty()) {
                    break;
                } else if (System.currentTimeMillis() - lastChange > SMELT_STALL_MILLIS) {
                    break;
                } else if (furnaceSlot(AbstractFurnaceMenu.FUEL_SLOT).isEmpty() && Bot.call(() -> !furnaceMenu().isLit())) {
                    // Ran out with more to do: it may have been fed one kind of fuel when there is another to burn.
                    try {
                        addFuel(input, count - taken, fuelChoice);
                    } catch (ApiError e) {
                        if (isControlFailure(e)) {
                            throw e;
                        }
                        break;
                    }
                }
                Bot.pause(500);
            }
            takeFrom(AbstractFurnaceMenu.INGREDIENT_SLOT);
        } finally {
            closeWindow();
        }
        return taken;
    }

    private static int takeFrom(int slot) {
        ItemStack stack = furnaceSlot(slot);
        if (stack.isEmpty()) {
            return 0;
        }
        call("windowTake", J.obj("slot", slot));
        return stack.getCount();
    }

    // ---- smelting in the background ----

    /** A furnace loaded and burning, to be emptied when what it makes is wanted. count is what is still to come out. */
    record Cooking(BlockPos furnace, Item input, Item output, int count, long since) {}

    private static final List<Cooking> COOKING = new CopyOnWriteArrayList<>();
    /** A person with a lot to smelt puts down more than one furnace. */
    private static final int MAX_FURNACES = 3;
    private static final int FURNACE_STACK = 64;

    /** What the furnaces will give, for a planner to count as if it were already carried. */
    static Map<Item, Integer> coming() {
        Map<Item, Integer> out = new HashMap<>();
        for (Cooking cooking : COOKING) {
            out.merge(cooking.output(), cooking.count(), Integer::sum);
        }
        return out;
    }

    static boolean anythingCooking() {
        return !COOKING.isEmpty();
    }

    /** How many more furnaces may be lit at once. */
    static int furnaceSlotsLeft() {
        return MAX_FURNACES - COOKING.size();
    }

    private static boolean cookingAt(BlockPos pos) {
        for (Cooking cooking : COOKING) {
            if (cooking.furnace().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /** Client thread. The nearest furnace nothing is cooking in. */
    private static BlockPos freeFurnace() {
        var level = Bot.level();
        BlockPos center = Bot.player().blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-BENCH_RADIUS, -8, -BENCH_RADIUS), center.offset(BENCH_RADIUS, 8, BENCH_RADIUS))) {
            if (level.getBlockState(pos).is(Blocks.FURNACE) && !cookingAt(pos)) {
                double distance = pos.distSqr(center);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    /** Whether a smelt could start now: a furnace stands free, or another can be put down. */
    static boolean furnaceAvailable() {
        if (COOKING.size() >= MAX_FURNACES) {
            return false;
        }
        return Bot.call(Skills::freeFurnace) != null || carried(Items.FURNACE) > 0 || Bot.call(() -> craftableNow(Items.FURNACE));
    }

    /** Loads a furnace with up to a stack and fuel for it, and leaves it burning. */
    static Cooking startSmelt(Item input, int count, String fuelChoice) {
        closeWindow();
        BlockPos furnace = Bot.call(Skills::freeFurnace);
        if (furnace == null) {
            if (COOKING.size() >= MAX_FURNACES) {
                collectSmelt(true);
                furnace = Bot.call(Skills::freeFurnace);
            }
            if (furnace == null) {
                if (carried(Items.FURNACE) == 0) {
                    craft(Items.FURNACE, 1);
                }
                furnace = placeNearby(Items.FURNACE);
                PLACED.add(furnace);
            }
        }
        walkInReach(furnace);
        call("openFurnace", at(furnace));
        if (Bot.call(() -> !(Bot.mc().screen instanceof AbstractFurnaceScreen<?>))) {
            throw new ApiError("no_window", "the furnace at " + furnace.toShortString() + " did not open");
        }
        Bot.waitTicks(2);
        int loaded;
        try {
            takeFrom(AbstractFurnaceMenu.RESULT_SLOT);
            ItemStack inside = furnaceSlot(AbstractFurnaceMenu.INGREDIENT_SLOT);
            int already = 0;
            if (!inside.isEmpty()) {
                if (inside.getItem() == input) {
                    already = inside.getCount();
                } else {
                    takeFrom(AbstractFurnaceMenu.INGREDIENT_SLOT);
                }
            }
            int put = Math.min(Math.min(count, FURNACE_STACK) - already, carried(input));
            addFuel(input, already + Math.max(0, put), fuelChoice);
            if (put > 0) {
                call("windowPut", J.obj("slot", AbstractFurnaceMenu.INGREDIENT_SLOT, "item", idOf(input), "count", put));
            }
            loaded = already + Math.max(0, put);
        } finally {
            closeWindow();
        }
        Item output = Bot.call(() -> RecipeIndex.get().smeltsInto(input));
        Cooking cooking = new Cooking(furnace, input, output == null ? input : output, loaded, System.currentTimeMillis());
        COOKING.add(cooking);
        say("left " + loaded + " " + idOf(input) + " cooking at " + furnace.toShortString() + (COOKING.size() > 1 ? " (" + COOKING.size() + " furnaces going)" : ""));
        return cooking;
    }

    /**
     * Empties the furnaces: what is done comes out now. With waitForOne, it stays at the one lit first until that has
     * finished, which is the shortest wait there is.
     */
    static int collectSmelt(boolean waitForOne) {
        int taken = 0;
        List<Cooking> order = new ArrayList<>(COOKING);
        order.sort(Comparator.comparingLong(Cooking::since));
        for (Cooking cooking : order) {
            taken += emptyFurnace(cooking, waitForOne && cooking == order.get(0));
        }
        return taken;
    }

    private static int emptyFurnace(Cooking cooking, boolean wait) {
        closeWindow();
        if (Bot.call(() -> !Bot.level().getBlockState(cooking.furnace()).is(Blocks.FURNACE))) {
            say("the furnace at " + cooking.furnace().toShortString() + " is gone, with " + cooking.count() + " " + idOf(cooking.output()) + " in it");
            COOKING.remove(cooking);
            return 0;
        }
        walkInReach(cooking.furnace());
        call("openFurnace", at(cooking.furnace()));
        if (Bot.call(() -> !(Bot.mc().screen instanceof AbstractFurnaceScreen<?>))) {
            throw new ApiError("no_window", "the furnace at " + cooking.furnace().toShortString() + " did not open");
        }
        Bot.waitTicks(2);
        int taken = 0;
        try {
            taken += takeFrom(AbstractFurnaceMenu.RESULT_SLOT);
            int left = furnaceSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getCount();
            long deadline = System.currentTimeMillis() + left * SECONDS_PER_ITEM * 1000 + 15_000;
            long lastChange = System.currentTimeMillis();
            while (wait && left > 0 && System.currentTimeMillis() < deadline) {
                Bot.requireControl();
                ItemStack result = furnaceSlot(AbstractFurnaceMenu.RESULT_SLOT);
                if (!result.isEmpty()) {
                    taken += takeFrom(AbstractFurnaceMenu.RESULT_SLOT);
                    lastChange = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastChange > SMELT_STALL_MILLIS) {
                    break;
                } else if (furnaceSlot(AbstractFurnaceMenu.FUEL_SLOT).isEmpty() && Bot.call(() -> !furnaceMenu().isLit())) {
                    try {
                        addFuel(cooking.input(), left, null);
                    } catch (ApiError e) {
                        if (isControlFailure(e)) {
                            throw e;
                        }
                        break;
                    }
                }
                Bot.pause(500);
                left = furnaceSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getCount();
            }
            taken += takeFrom(AbstractFurnaceMenu.RESULT_SLOT);
            left = furnaceSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getCount();
            boolean done = left == 0 || (wait && System.currentTimeMillis() >= deadline);
            if (done) {
                takeFrom(AbstractFurnaceMenu.INGREDIENT_SLOT);
                takeFrom(AbstractFurnaceMenu.FUEL_SLOT);
                COOKING.remove(cooking);
            } else {
                COOKING.remove(cooking);
                COOKING.add(new Cooking(cooking.furnace(), cooking.input(), cooking.output(), left, cooking.since()));
            }
        } finally {
            closeWindow();
        }
        if (taken > 0) {
            say("took " + taken + " " + idOf(cooking.output()) + " out of the furnace");
        }
        return taken;
    }

    /** Enough to burn: coal first, then wood. Never tools, and never what is about to be smelted. */
    private static void addFuel(Item input, int count, String fuelChoice) {
        record Choice(String id, int burn, int have) {}
        int ticksNeeded = count * 200;
        Choice choice = Bot.call(() -> {
            Inventory inventory = Bot.player().getInventory();
            Item chosen = fuelChoice == null ? null : Bot.item(fuelChoice);
            Item best = null;
            int bestBurn = 0;
            int total = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inventory.getItem(i);
                int burn = Mc.burnTicks(stack);
                boolean allowed = burn > 0 && stack.getItem() != input && !stack.isDamageableItem()
                        && (chosen != null ? stack.getItem() == chosen
                        : stack.is(ItemTags.COALS) || stack.is(ItemTags.LOGS_THAT_BURN) || stack.is(ItemTags.PLANKS) || stack.is(Items.STICK));
                if (allowed && burn > bestBurn) {
                    best = stack.getItem();
                    bestBurn = burn;
                }
            }
            if (best != null) {
                for (int i = 0; i < 36; i++) {
                    if (inventory.getItem(i).getItem() == best) {
                        total += inventory.getItem(i).getCount();
                    }
                }
            }
            return best == null ? null : new Choice(idOf(best), bestBurn, total);
        });
        ItemStack inSlot = furnaceSlot(AbstractFurnaceMenu.FUEL_SLOT);
        int burnsAlready = Bot.call(() -> Mc.burnTicks(inSlot) * inSlot.getCount());
        if (burnsAlready >= ticksNeeded) {
            return;
        }
        if (choice == null) {
            if (inSlot.isEmpty() && Bot.call(() -> !furnaceMenu().isLit())) {
                throw new ApiError("no_fuel", "nothing to burn: carry coal, charcoal or wood");
            }
            return;
        }
        int wanted = (int) Math.ceil((double) (ticksNeeded - burnsAlready) / choice.burn());
        boolean sameFuel = inSlot.isEmpty() || idOf(inSlot.getItem()).equals(choice.id());
        if (!sameFuel) {
            return;
        }
        call("windowPut", J.obj("slot", AbstractFurnaceMenu.FUEL_SLOT, "item", choice.id(), "count", Math.min(wanted, choice.have())));
    }

    // ---- fighting and food ----

    private static EntityType<?> entityType(String raw) {
        return BuiltInRegistries.ENTITY_TYPE.getOptional(Mc.id(raw))
                .orElseThrow(() -> new ApiError("bad_argument", "no entity type named '" + raw + "'"));
    }

    private static Entity nearestOf(List<EntityType<?>> types, int radius) {
        return nearestOf(types, radius, Set.of());
    }

    private static Entity nearestOf(List<EntityType<?>> types, int radius, Set<Entity> leaveOut) {
        return Bot.call(() -> {
            LocalPlayer player = Bot.player();
            Entity best = null;
            double bestDistance = radius;
            for (Entity entity : Bot.level().entitiesForRendering()) {
                if (entity != player && entity.isAlive() && types.contains(entity.getType()) && !leaveOut.contains(entity)) {
                    double distance = entity.distanceTo(player);
                    if (distance < bestDistance) {
                        best = entity;
                        bestDistance = distance;
                    }
                }
            }
            return best;
        });
    }

    private static JsonElement huntFn(JsonObject args) {
        EntityType<?> type = Bot.call(() -> entityType(J.str(args, "mob")));
        int count = J.integer(args, "count", 1);
        int radius = J.integer(args, "radius", 48);
        int kills = J.integer(args, "_kills", 0);
        Set<Entity> leftAlone = new HashSet<>();
        for (int tries = 0; kills < count && tries < count * 4 + 2; tries++) {
            if (kills > 0 && Bot.call(() -> Bot.player().getHealth()) <= HUNT_STOP_HEALTH) {
                return J.obj("killed", kills, "stopped_early", "health is low: eat and rest before going on");
            }
            Entity target = nearestOf(List.of(type), radius, leftAlone);
            if (target == null) {
                break;
            }
            if (killOrLeave(target, leftAlone)) {
                kills++;
                args.addProperty("_kills", kills);
                pickup(8, null);
            }
        }
        if (kills > 0) {
            return J.obj("killed", kills);
        }
        if (!leftAlone.isEmpty()) {
            throw new ApiError("got_away", leftAlone.size() + " " + J.str(args, "mob") + " in range, but each stayed out of reach or got away",
                    "look for one in the open, or try another place");
        }
        String hint = Bot.call(() -> type.getCategory() == MobCategory.MONSTER)
                ? "monsters appear in the dark: on the surface at night, or in caves. Explore farther"
                : "explore, or try another time of day";
        throw new ApiError("not_found", "no " + J.str(args, "mob") + " within " + radius + " blocks", hint);
    }

    /** Hunting for what a mob drops: goes on until the inventory shows it, as drops are not guaranteed. */
    static void hunt(List<EntityType<?>> types, int count, Item wanted) {
        int target = carried(wanted) + count;
        Set<Entity> leftAlone = new HashSet<>();
        for (int tries = 0; tries < count * 4 + 2 && carried(wanted) < target; tries++) {
            Entity entity = nearestOf(types, 64, leftAlone);
            if (entity == null) {
                throw new ApiError("not_found", "none of " + types + " within 64 blocks" + (leftAlone.isEmpty() ? "" : " that could be reached"));
            }
            if (killOrLeave(entity, leftAlone)) {
                pickup(8, null);
            }
        }
    }

    /** Whether it died. One that stayed out of reach or got away goes on the list of those to leave alone this time. */
    private static boolean killOrLeave(Entity target, Set<Entity> leftAlone) {
        try {
            if (kill(target)) {
                return true;
            }
        } catch (ApiError e) {
            if (!"timeout".equals(e.kind)) {
                throw e;
            }
        }
        leftAlone.add(target);
        return false;
    }

    /**
     * Goes after it until it is gone, and says whether it died. A mob the client stops tracking is removed too, and that
     * is no kill: an enderman teleports out of range, and anything can despawn. Only a dying one has no health left.
     */
    private static boolean kill(Entity target) {
        closeWindow();
        equipBestWeapon();
        long deadline = System.currentTimeMillis() + KILL_TIMEOUT_MILLIS;
        boolean chasing = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                Bot.requireControl();
                if (Bot.call(() -> !target.isAlive() || target.isRemoved())) {
                    return Bot.call(() -> target instanceof LivingEntity living ? living.getHealth() <= 0 : true);
                }
                boolean hittable = Bot.call(() -> target.distanceTo(Bot.player()) <= 3.0 && Bot.player().hasLineOfSight(target));
                if (hittable) {
                    if (chasing) {
                        Pathing.stop();
                        chasing = false;
                    }
                    if (Bot.call(() -> Bot.player().getAttackStrengthScale(0.5f) >= 0.95f)) {
                        try {
                            call("attack", J.obj("id", target.getId()));
                        } catch (ApiError e) {
                            if (isControlFailure(e)) {
                                throw e;
                            }
                        }
                    }
                    Bot.pause(100);
                } else {
                    if (!chasing || !Pathing.following()) {
                        Pathing.follow(entity -> entity == target, 2);
                        chasing = true;
                    }
                    Bot.pause(200);
                }
            }
            throw new ApiError("timeout", "could not kill " + target.getName().getString() + " in " + KILL_TIMEOUT_MILLIS / 1000 + "s", "it may be out of reach");
        } finally {
            if (chasing && Control.isAi()) {
                Pathing.stop();
            }
        }
    }

    private static void equipBestWeapon() {
        String best = Bot.call(() -> {
            Inventory inventory = Bot.player().getInventory();
            double top = attackDamage(inventory.getItem(Mc.selectedSlot(inventory)));
            String found = null;
            for (int i = 0; i < 36; i++) {
                double damage = attackDamage(inventory.getItem(i));
                if (damage > top) {
                    top = damage;
                    found = Bot.itemId(inventory.getItem(i));
                }
            }
            return found;
        });
        if (best != null) {
            call("equip", J.obj("item", best));
        }
    }

    static double attackDamage(ItemStack stack) {
        double[] total = {0};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.is(Attributes.ATTACK_DAMAGE)) {
                total[0] += modifier.amount();
            }
        });
        return total[0];
    }

    private static int foodCarried() {
        return Bot.call(() -> {
            Inventory inventory = Bot.player().getInventory();
            int total = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.has(DataComponents.FOOD) && !stack.is(Items.ROTTEN_FLESH) && !stack.is(Items.SPIDER_EYE)) {
                    total += stack.getCount();
                }
            }
            return total;
        });
    }

    private static JsonElement gatherFoodFn(JsonObject args) {
        int count = J.integer(args, "count", 4);
        int radius = J.integer(args, "radius", 48);
        int kills = 0;
        while (foodCarried() < count && kills < count * 2) {
            Entity animal = nearestOf(FOOD_ANIMALS, radius);
            if (animal == null) {
                if (kills == 0 && foodCarried() == 0) {
                    throw new ApiError("not_found", "no animals within " + radius + " blocks", "walk somewhere else, or look for crops");
                }
                break;
            }
            kill(animal);
            kills++;
            pickup(8, null);
        }
        return J.obj("food_carried", foodCarried());
    }

    /**
     * Between pieces of a long job: a body with no food left is about to have a much worse problem, and the time to do
     * something about it is while an animal happens to be close, not when the hunger bar is already empty.
     */
    static void keepFed() {
        try {
            improveFoodInPassing();
        } catch (ApiError e) {
            if (isControlFailure(e)) {
                throw e;
            }
        }
        if (foodCarried() > 0) {
            return;
        }
        int food = Bot.call(() -> Bot.player().getFoodData().getFoodLevel());
        boolean hungry = food <= HUNGRY_FOOD;
        Entity animal = nearestOf(FOOD_ANIMALS, hungry ? FOOD_SEARCH_RADIUS : FOOD_CHANCE_RADIUS);
        try {
            if (animal != null && (hungry || System.currentTimeMillis() >= nextChanceHunt)) {
                say((hungry ? "hungry with no food: hunting " : "no food on me and one is close: hunting ") + animal.getName().getString());
                nextChanceHunt = System.currentTimeMillis() + CHANCE_HUNT_EVERY_MILLIS;
                kill(animal);
                pickup(8, null);
            } else if (food <= STARVING_FOOD && animal == null) {
                say("getting hungry with nothing to eat: looking for animals");
                gatherFoodFn(J.obj("count", 2, "radius", FOOD_SEARCH_RADIUS * 2));
            }
        } catch (ApiError e) {
            if (isControlFailure(e)) {
                throw e;
            }
        }
    }

    /**
     * Between pieces of a long job: a sword costs next to nothing once there is stone, and a long job underground always
     * ends up meeting something. Only from what is already at hand, and only when a table is too.
     */
    static void keepArmed() {
        Item sword = Bot.call(() -> {
            Inventory inventory = Bot.player().getInventory();
            double best = 0;
            for (int i = 0; i < 36; i++) {
                best = Math.max(best, attackDamage(inventory.getItem(i)));
            }
            boolean table = Obtain.carried().getOrDefault(Items.CRAFTING_TABLE, 0) > 0;
            Item choice = null;
            if (best < 4 && craftableNow(Items.STONE_SWORD)) {
                choice = Items.STONE_SWORD;
            } else if (best < 3 && craftableNow(Items.WOODEN_SWORD)) {
                choice = Items.WOODEN_SWORD;
            }
            return choice != null && table ? choice : null;
        });
        if (sword != null) {
            say("making a " + idOf(sword) + " while the materials are at hand");
            try {
                craft(sword, 1);
            } catch (ApiError e) {
                if (isControlFailure(e)) {
                    throw e;
                }
            }
        }
    }

    /** Client thread. Whether one of the item's recipes can be made from what is really carried. */
    static boolean craftableNow(Item item) {
        for (WindowFns.CraftPlan plan : WindowFns.plansFor(item)) {
            if (WindowFns.missing(plan).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ---- picking up ----

    static int pickup(int radius, String only) {
        closeWindow();
        Item wanted = only == null ? null : Bot.call(() -> Bot.item(only));
        Set<Integer> skipped = new HashSet<>();
        int picked = 0;
        boolean waited = false;
        for (int attempt = 0; attempt < 16; attempt++) {
            Bot.requireControl();
            ItemEntity nearest = Bot.call(() -> {
                ItemEntity best = null;
                double bestDistance = radius;
                for (Entity entity : Bot.level().entitiesForRendering()) {
                    if (entity instanceof ItemEntity item && item.isAlive() && !skipped.contains(item.getId())
                            && (wanted == null ? !discarded(item.getItem().getItem()) : item.getItem().getItem() == wanted)) {
                        double distance = item.distanceTo(Bot.player());
                        if (distance < bestDistance) {
                            best = item;
                            bestDistance = distance;
                        }
                    }
                }
                return best;
            });
            if (nearest == null) {
                // What was just dug up takes a moment to appear on the client.
                if (!waited) {
                    waited = true;
                    Bot.pause(800);
                    continue;
                }
                break;
            }
            try {
                call("goto", J.obj("x", nearest.getBlockX(), "y", nearest.getBlockY(), "z", nearest.getBlockZ(), "range", 1, "timeout_s", 15));
                // Standing a block away is sometimes not close enough: step onto it.
                if (!Bot.waitUntil(() -> !nearest.isAlive(), 1200)) {
                    call("goto", J.obj("x", nearest.getBlockX(), "y", nearest.getBlockY(), "z", nearest.getBlockZ(), "range", 0, "timeout_s", 10));
                }
            } catch (ApiError e) {
                if (isControlFailure(e)) {
                    throw e;
                }
                skipped.add(nearest.getId());
                continue;
            }
            if (Bot.waitUntil(() -> !nearest.isAlive(), 1500)) {
                picked++;
            } else {
                skipped.add(nearest.getId());
            }
        }
        return picked;
    }

    // ---- sleeping ----

    private static JsonElement sleepInBed(JsonObject args) {
        BlockPos bed = nearestBlock(state -> state.is(BlockTags.BEDS), J.integer(args, "radius", 32));
        if (bed == null) {
            throw new ApiError("not_found", "no bed within " + J.integer(args, "radius", 32) + " blocks", "obtain a bed and placeBlock it");
        }
        walkInReach(bed);
        JsonObject result = call("sleep", at(bed));
        if (!result.get("sleeping").getAsBoolean()) {
            throw new ApiError("cannot_sleep", "the game refused to let you sleep", "beds only work at night or in a storm, with no monsters close by");
        }
        boolean awake = Bot.waitUntil(() -> !Bot.player().isSleeping(), 30_000);
        if (!awake) {
            call("wake", new JsonObject());
        }
        return J.obj("slept_through", awake, "time", Bot.call(() -> Mc.dayTime(Bot.level()) % 24000));
    }

    // ---- chests ----

    private static BlockPos openNearestChest(int radius) {
        BlockPos chest = nearestBlock(state -> state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL), radius);
        if (chest == null) {
            throw new ApiError("not_found", "no chest or barrel within " + radius + " blocks");
        }
        walkInReach(chest);
        JsonObject opened = call("openChest", at(chest));
        if (!opened.get("opened").getAsBoolean()) {
            throw new ApiError("no_window", "the chest at " + chest.toShortString() + " did not open", "something may be on top of it");
        }
        Bot.waitTicks(2);
        return chest;
    }

    private static JsonElement moveItems(JsonObject args, String to) {
        openNearestChest(J.integer(args, "radius", 24));
        try {
            JsonObject transfer = J.obj("item", J.str(args, "item"), "to", to);
            if (args.has("count")) {
                transfer.addProperty("count", J.integer(args, "count"));
            }
            JsonObject result = call("transfer", transfer);
            return J.obj("moved", result.get("moved"));
        } finally {
            closeWindow();
        }
    }

    private static JsonElement putInChest(JsonObject args) {
        return moveItems(args, "window");
    }

    private static JsonElement takeFromChest(JsonObject args) {
        return moveItems(args, "inventory");
    }

    /**
     * Takes from one particular chest, the one the planner was told holds the item. What the chest really holds gets
     * remembered on the way, so a chest someone emptied is not counted on twice.
     */
    static int takeFromChestAt(BlockPos pos, String item, int count) {
        closeWindow();
        walkInReach(pos);
        JsonObject opened = call("openChest", at(pos));
        if (!opened.get("opened").getAsBoolean()) {
            throw new ApiError("no_window", "the chest at " + pos.toShortString() + " did not open", "something may be on top of it");
        }
        Bot.waitTicks(2);
        try {
            JsonObject result = call("transfer", J.obj("item", item, "count", count, "to", "inventory"));
            return result.get("moved").getAsInt();
        } catch (ApiError e) {
            if (e.kind.equals("not_found")) {
                throw new ApiError("not_there", "the chest at " + pos.toShortString() + " no longer holds " + item, "it was taken; the chest is remembered as it is now");
            }
            throw e;
        } finally {
            closeWindow();
        }
    }

    private static JsonElement goToPlace(JsonObject args) {
        String name = J.str(args, "name");
        JsonObject place = MemoryStore.place(name);
        if (place == null) {
            throw new ApiError("not_found", "no place named " + name + " is remembered in this world", "memory op worlds lists the places; op place remembers one");
        }
        String here = Bot.call(() -> Mc.dimension(Bot.level()));
        if (!J.str(place, "dimension", here).equals(here)) {
            throw new ApiError("wrong_dimension", name + " is in " + J.str(place, "dimension", "") + ", and you are in " + here, "find the way there first");
        }
        JsonArray pos = place.getAsJsonArray("pos");
        JsonObject result = call("goto", J.obj("x", pos.get(0).getAsInt(), "y", pos.get(1).getAsInt(), "z", pos.get(2).getAsInt(),
                "range", J.integer(args, "range", 2), "timeout_s", J.integer(args, "timeout_s", 240)));
        result.addProperty("place", name);
        return result;
    }

    private static JsonElement viewChest(JsonObject args) {
        BlockPos chest = openNearestChest(J.integer(args, "radius", 24));
        try {
            JsonObject window = Bot.call(WindowFns::summary);
            return J.obj("pos", J.nums(chest.getX(), chest.getY(), chest.getZ()), "items", window.get("window_items"));
        } finally {
            closeWindow();
        }
    }

    // ---- armor ----

    private static double armorValue(ItemStack stack, EquipmentSlot slot) {
        double[] total = {0};
        stack.forEachModifier(slot, (attribute, modifier) -> {
            if (attribute.is(Attributes.ARMOR)) {
                total[0] += modifier.amount();
            }
        });
        return total[0];
    }

    private static JsonElement equipBestArmor() {
        JsonArray worn = new JsonArray();
        for (Map.Entry<String, EquipmentSlot> entry : ARMOR_SLOTS.entrySet()) {
            String best = Bot.call(() -> {
                LocalPlayer player = Bot.player();
                Inventory inventory = player.getInventory();
                double top = armorValue(player.getItemBySlot(entry.getValue()), entry.getValue());
                String found = null;
                for (int i = 0; i < 36; i++) {
                    ItemStack stack = inventory.getItem(i);
                    EquipmentSlot slot = Mc.equipSlot(stack);
                    if (slot == entry.getValue() && armorValue(stack, entry.getValue()) > top) {
                        top = armorValue(stack, entry.getValue());
                        found = Bot.itemId(stack);
                    }
                }
                return found;
            });
            if (best != null) {
                if (Bot.call(() -> !Bot.player().getItemBySlot(entry.getValue()).isEmpty())) {
                    call("unequip", J.obj("dest", entry.getKey()));
                }
                call("equip", J.obj("item", best, "dest", entry.getKey()));
                worn.add(best);
            }
        }
        return J.obj("put_on", worn);
    }

    // ---- going places ----

    private static JsonElement goToNearestBlock(JsonObject args) {
        int radius = J.integer(args, "radius", 64);
        Set<net.minecraft.world.level.block.Block> wanted = Bot.call(() -> Sources.resolveBlocks(J.strings(args, "blocks")));
        BlockPos pos = nearestBlock(state -> wanted.contains(state.getBlock()), radius);
        if (pos == null) {
            throw new ApiError("not_found", "none of " + J.strings(args, "blocks") + " within " + radius + " blocks");
        }
        JsonObject go = at(pos);
        go.addProperty("range", J.integer(args, "range", 2));
        call("goto", go);
        return WorldFns.blockJson(pos);
    }

    private static JsonElement goToNearestEntity(JsonObject args) {
        EntityType<?> type = Bot.call(() -> entityType(J.str(args, "type")));
        int radius = J.integer(args, "radius", 64);
        Entity entity = nearestOf(List.of(type), radius);
        if (entity == null) {
            throw new ApiError("not_found", "no " + J.str(args, "type") + " within " + radius + " blocks");
        }
        call("goto", J.obj("x", entity.getBlockX(), "y", entity.getBlockY(), "z", entity.getBlockZ(), "range", J.integer(args, "range", 2)));
        return Bot.call(() -> Bot.entityJson(entity));
    }

    private static Player playerNamed(String name) {
        for (Player player : Bot.level().players()) {
            if (player.getName().getString().equalsIgnoreCase(name)) {
                return player;
            }
        }
        throw new ApiError("not_found", "no player named " + name + " in view", "players() lists who is online");
    }

    private static JsonElement goToPlayer(JsonObject args) {
        Player other = Bot.call(() -> playerNamed(J.str(args, "name")));
        call("goto", J.obj("x", other.getBlockX(), "y", other.getBlockY(), "z", other.getBlockZ(), "range", J.integer(args, "range", 3)));
        return J.obj("reached", other.getName().getString());
    }

    private static JsonElement followPlayer(JsonObject args) {
        Player other = Bot.call(() -> playerNamed(J.str(args, "name")));
        long end = System.currentTimeMillis() + J.integer(args, "seconds", 60) * 1000L;
        Pathing.follow(entity -> entity == other, J.integer(args, "range", 3));
        try {
            while (System.currentTimeMillis() < end) {
                Bot.requireControl();
                if (Bot.call(() -> !other.isAlive() || other.isRemoved())) {
                    break;
                }
                Bot.pause(250);
            }
        } finally {
            if (Control.isAi()) {
                Pathing.stop();
            }
        }
        return J.obj("followed", other.getName().getString());
    }

    /** Off in a new direction each time, turning by the golden angle so that the walks do not retrace each other. */
    static void explore(int tries) {
        closeWindow();
        Vec3 here = Bot.call(() -> Bot.player().position());
        double angle = tries * 2.399963;
        int distance = 48 + 16 * tries;
        int x = (int) (here.x + Math.cos(angle) * distance);
        int z = (int) (here.z + Math.sin(angle) * distance);
        say("looking further away, around " + x + ", " + z);
        try {
            call("goto", J.obj("x", x, "z", z, "range", 8, "timeout_s", 40));
        } catch (ApiError e) {
            if (isControlFailure(e)) {
                throw e;
            }
        }
    }
}
