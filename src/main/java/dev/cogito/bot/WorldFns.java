package dev.cogito.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cogito.memory.MemoryStore;
import dev.cogito.mixin.GuiAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/** Reading the world: everything here is instant and changes nothing. */
final class WorldFns {
    private static final int DATA_CLIP = 1000;
    private static final int FIND_RADIUS_LIMIT = 256;
    private static final int WORTH_REMEMBERING = 48;

    private WorldFns() {}

    static void register() {
        Api.read("status()", "you: position, health, food, oxygen, xp, gamemode, dimension, time, weather, held item, open window, crosshair, and action_bar, the line above the hotbar where the game and mods say things such as why something was refused",
                args -> Bot.call(WorldFns::status));
        Api.read("inventory()", "every stack you carry, with Inventory slot (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand)",
                args -> Bot.call(WorldFns::inventory));
        Api.read("heldItem()", "the stack in your main hand",
                args -> Bot.call(() -> Bot.stackJson(Bot.player().getMainHandItem())));
        Api.read("blockAt(x,y,z)", "id, name and state of one block",
                args -> Bot.call(() -> blockJson(Bot.pos(args))));
        Api.read("describeBlock(x,y,z)", "full facts on a block: state, tags, hardness, tool, tooltip, and data, the live contents of its block entity (speed, inventories, progress), which shows whether a machine is really running",
                args -> Bot.call(() -> describeBlock(Bot.pos(args))));
        Api.read("describeItem(item|slot)", "tooltip text, tags, stack size, food, block it places; for unknown items",
                args -> Bot.call(() -> describeItem(args)));
        Api.read("modText(term,[limit=30])", "what mods say about something in their own text: tutorials, tooltips, guides. term is an id or word such as millstone. Read this before guessing how a modded thing works",
                args -> Bot.call(() -> ModText.lookup(J.str(args, "term"), J.integer(args, "limit", 30))));
        Api.read("blockAtCursor()", "the block under the crosshair",
                args -> Bot.call(() -> Bot.mc().hitResult instanceof BlockHitResult hit
                        && hit.getType() == HitResult.Type.BLOCK ? blockJson(hit.getBlockPos()) : J.obj("block", "none")));
        Api.read("entityAtCursor()", "the entity under the crosshair",
                args -> Bot.call(() -> Bot.mc().hitResult instanceof EntityHitResult hit
                        ? Bot.entityJson(hit.getEntity()) : J.obj("entity", "none")));
        Api.read("findBlocks(blocks,[radius=32],[count=16])", "nearest blocks by id or #tag in loaded chunks, as far as you can see (radius up to " + FIND_RADIUS_LIMIT + "), for looking out over a wide area",
                args -> Bot.call(() -> findBlocks(args)));
        Api.read("canSeeBlock(x,y,z)", "whether a line from your eyes reaches the block",
                args -> Bot.call(() -> J.obj("visible", Bot.visiblePoint(Bot.pos(args)) != null)));
        Api.read("canDigBlock(x,y,z)", "whether the block can be broken from here with what you hold",
                args -> Bot.call(() -> canDig(Bot.pos(args))));
        Api.read("digTime(x,y,z)", "seconds to break the block with what you hold",
                args -> Bot.call(() -> J.obj("seconds", digSeconds(Bot.pos(args)))));
        Api.read("isABed(x,y,z)", "whether the block is a bed",
                args -> Bot.call(() -> J.obj("bed", Bot.level().getBlockState(Bot.pos(args)).is(BlockTags.BEDS))));
        Api.read("entities([radius=24],[type])", "nearby entities, nearest first, with the ids other functions take",
                args -> Bot.call(() -> entities(args)));
        Api.read("nearestEntity([type])", "the closest entity, optionally of a type such as minecraft:cow",
                args -> Bot.call(() -> nearest(args)));
        Api.read("players()", "players the client knows about, with distance when in view",
                args -> Bot.call(WorldFns::players));
        Api.read("time()", "game day and time of day", args -> Bot.call(WorldFns::time));
        Api.read("getControlState(control)", "whether a movement control is held",
                args -> Bot.call(() -> J.obj("down", ActionFns.mapping(J.str(args, "control")).isDown())));
        Api.read("waitForTicks(n)", "wait n game ticks (20 per second)", args -> {
            Bot.waitTicks(J.integer(args, "n"));
            return null;
        });
        Api.read("waitForChunksToLoad()", "wait until the view around you has finished loading", args -> {
            boolean ready = Bot.waitUntil(() -> Bot.mc().levelRenderer.hasRenderedAllSections(), 20000);
            return J.obj("loaded", ready);
        });
    }

    private static JsonObject status() {
        LocalPlayer p = Bot.player();
        ClientLevel level = Bot.level();
        Minecraft mc = Bot.mc();
        JsonObject out = J.obj(
                "pos", J.nums(p.getX(), p.getY(), p.getZ()),
                "yaw", Math.round(net.minecraft.util.Mth.wrapDegrees(p.getYRot())), "pitch", Math.round(p.getXRot()),
                "health", Math.round(p.getHealth() * 10) / 10.0,
                "armor", p.getArmorValue(),
                "food", p.getFoodData().getFoodLevel(),
                "saturation", Math.round(p.getFoodData().getSaturationLevel() * 10) / 10.0,
                "oxygen", p.getAirSupply(),
                "xp_level", p.experienceLevel,
                "gamemode", mc.gameMode == null ? "unknown" : mc.gameMode.getPlayerMode().getName(),
                "dimension", level.dimension().identifier().toString(),
                "biome", level.getBiome(p.blockPosition()).unwrapKey().map(key -> key.identifier().toString()).orElse("unknown"),
                "time", time(),
                "raining", level.isRaining(), "thundering", level.isThundering(),
                "on_ground", p.onGround(), "in_water", p.isInWater(), "in_lava", p.isInLava(),
                "sleeping", p.isSleeping());
        if (p.isPassenger()) {
            out.addProperty("riding", p.getVehicle().getType().toShortString());
        }
        if (!p.getActiveEffects().isEmpty()) {
            JsonObject effects = new JsonObject();
            for (net.minecraft.world.effect.MobEffectInstance effect : p.getActiveEffects()) {
                effects.addProperty(effect.getEffect().unwrapKey().map(key -> key.identifier().toString()).orElse("unknown"),
                        effect.isInfiniteDuration() ? -1 : effect.getDuration() / 20);
            }
            out.add("effects_seconds_left", effects);
        }
        out.add("held", Bot.stackJson(p.getMainHandItem()));
        out.addProperty("window", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
        out.addProperty("crosshair", Bot.cursorText());
        // Mods and the game say things above the hotbar, such as why something was refused. It is data like chat.
        if (mc.gui instanceof GuiAccessor gui && gui.cogito$overlayMessageTime() > 0 && gui.cogito$overlayMessage() != null) {
            out.addProperty("action_bar", gui.cogito$overlayMessage().getString());
        }
        return out;
    }

    private static JsonObject time() {
        long day = Bot.level().getOverworldClockTime();
        long tod = day % 24000;
        return J.obj("day", day / 24000, "tick_of_day", tod, "phase", tod < 12000 ? "day" : tod < 13000 ? "dusk" : tod < 23000 ? "night" : "dawn");
    }

    private static JsonObject inventory() {
        Inventory inv = Bot.player().getInventory();
        JsonArray stacks = new JsonArray();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                JsonObject row = Bot.stackJson(stack);
                row.addProperty("slot", i);
                stacks.add(row);
            }
        }
        return J.obj("selected_hotbar_slot", inv.getSelectedSlot(), "free_slots", WindowFns.freeSlots(), "stacks", stacks);
    }

    static JsonObject blockJson(BlockPos pos) {
        BlockState state = Bot.level().getBlockState(pos);
        JsonObject out = J.obj("id", Bot.blockId(state), "name", state.getBlock().getName().getString(),
                "pos", J.nums(pos.getX(), pos.getY(), pos.getZ()));
        if (!state.getProperties().isEmpty()) {
            JsonObject props = new JsonObject();
            for (Property<?> property : state.getProperties()) {
                props.addProperty(property.getName(), state.getValue(property).toString());
            }
            out.add("state", props);
        }
        return out;
    }

    private static JsonObject describeBlock(BlockPos pos) {
        ClientLevel level = Bot.level();
        BlockState state = level.getBlockState(pos);
        JsonObject out = blockJson(pos);
        out.addProperty("hardness", state.getDestroySpeed(level, pos));
        out.addProperty("needs_correct_tool", state.requiresCorrectToolForDrops());
        out.addProperty("right_tool_in_hand", Bot.player().getMainHandItem().isCorrectToolForDrops(state));
        out.addProperty("liquid", !state.getFluidState().isEmpty());
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity != null) {
            out.addProperty("block_entity", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString());
            out.addProperty("data", blockEntityData(entity));
        }
        JsonArray tags = new JsonArray();
        state.typeHolder().tags().limit(24).forEach(tag -> tags.add("#" + tag.location()));
        out.add("tags", tags);
        Item asItem = state.getBlock().asItem();
        if (asItem != net.minecraft.world.item.Items.AIR) {
            out.add("tooltip", tooltip(new ItemStack(asItem)));
        }
        String id = Bot.blockId(state);
        addExplanationHint(out, id);
        return out;
    }

    /**
     * The block entity as the client knows it: whatever a mod syncs, read without knowing the mod. A machine's
     * speed, progress and inventories show up here. On a server some of it may never reach the client.
     */
    private static String blockEntityData(BlockEntity entity) {
        try {
            String text = entity.saveWithoutMetadata(Bot.level().registryAccess()).toString();
            return text.length() > DATA_CLIP ? text.substring(0, DATA_CLIP) + "…" : text;
        } catch (RuntimeException e) {
            return "unreadable: " + e;
        }
    }

    private static void addExplanationHint(JsonObject out, String id) {
        if (!id.startsWith("minecraft:")) {
            int explanations = ModText.explanationCount(id);
            if (explanations > 0) {
                out.addProperty("explanations", explanations + " lines of the mod's own text; read them with modText(\"" + id + "\")");
            }
        }
    }

    private static JsonObject describeItem(JsonObject args) {
        ItemStack stack;
        if (args.has("slot")) {
            stack = Bot.player().getInventory().getItem(J.integer(args, "slot"));
        } else {
            stack = new ItemStack(Bot.item(J.str(args, "item")));
        }
        if (stack.isEmpty()) {
            return J.obj("item", "empty");
        }
        JsonObject out = Bot.stackJson(stack);
        out.addProperty("max_stack", stack.getMaxStackSize());
        out.add("tooltip", tooltip(stack));
        var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
        if (food != null) {
            out.add("food", J.obj("nutrition", food.nutrition(), "saturation", food.saturation()));
        }
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
            out.addProperty("places", BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString());
        }
        JsonArray tags = new JsonArray();
        stack.typeHolder().tags().limit(24).forEach(tag -> tags.add("#" + tag.location()));
        out.add("tags", tags);
        addExplanationHint(out, Bot.itemId(stack));
        return out;
    }

    private static JsonArray tooltip(ItemStack stack) {
        JsonArray lines = new JsonArray();
        List<Component> text = stack.getTooltipLines(Item.TooltipContext.of(Bot.level()), Bot.player(), TooltipFlag.NORMAL);
        text.forEach(line -> lines.add(line.getString()));
        return lines;
    }

    private static JsonObject findBlocks(JsonObject args) {
        Set<Block> wanted = Bot.blocks(J.strings(args, "blocks"));
        int radius = Math.min(FIND_RADIUS_LIMIT, J.integer(args, "radius", 32));
        int count = Math.max(1, J.integer(args, "count", 16));
        ClientLevel level = Bot.level();
        BlockPos center = Bot.player().blockPosition();
        // The farthest of those kept comes first, to make way for a nearer one. Asking for stone over a wide radius
        // would otherwise list millions of blocks.
        Comparator<BlockPos> nearest = Comparator.comparingDouble(pos -> pos.distSqr(center));
        PriorityQueue<BlockPos> kept = new PriorityQueue<>(count + 1, nearest.reversed());
        int total = 0;
        int minY = Math.max(level.getMinY(), center.getY() - radius);
        int maxY = Math.min(level.getMaxY(), center.getY() + radius);
        for (int chunkX = (center.getX() - radius) >> 4; chunkX <= (center.getX() + radius) >> 4; chunkX++) {
            for (int chunkZ = (center.getZ() - radius) >> 4; chunkZ <= (center.getZ() + radius) >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int index = 0; index < sections.length; index++) {
                    LevelChunkSection section = sections[index];
                    // What a section can hold is known without looking at its blocks, which is what makes a wide search cheap.
                    if (section.hasOnlyAir() || !section.maybeHas(state -> wanted.contains(state.getBlock()))) {
                        continue;
                    }
                    int baseY = chunk.getSectionYFromSectionIndex(index) << 4;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockPos pos = new BlockPos((chunkX << 4) + x, baseY + y, (chunkZ << 4) + z);
                                if (pos.getY() < minY || pos.getY() > maxY || Math.abs(pos.getX() - center.getX()) > radius
                                        || Math.abs(pos.getZ() - center.getZ()) > radius || !wanted.contains(section.getBlockState(x, y, z).getBlock())) {
                                    continue;
                                }
                                total++;
                                kept.add(pos);
                                if (kept.size() > count) {
                                    kept.poll();
                                }
                            }
                        }
                    }
                }
            }
        }
        List<BlockPos> found = new ArrayList<>(kept);
        found.sort(nearest);
        // Something sighted from afar is worth keeping: a step's own words about it are soon out of the planner's mind.
        if (!found.isEmpty() && Math.sqrt(found.get(0).distSqr(center)) >= WORTH_REMEMBERING) {
            BlockPos first = found.get(0);
            String id = Bot.blockId(level.getBlockState(first));
            MemoryStore.notePlace("seen_" + id.substring(id.indexOf(':') + 1), first, level.dimension().identifier().toString(), "sighted from afar by findBlocks");
        }
        JsonArray out = new JsonArray();
        for (BlockPos pos : found) {
            JsonObject row = J.obj("id", Bot.blockId(level.getBlockState(pos)), "pos", J.nums(pos.getX(), pos.getY(), pos.getZ()),
                    "distance", Math.round(Math.sqrt(pos.distSqr(center))));
            out.add(row);
        }
        return J.obj("total_in_radius", total, "blocks", out,
                "note", "only loaded terrain was searched; an empty result does not prove there is none further away");
    }

    private static double digSeconds(BlockPos pos) {
        BlockState state = Bot.level().getBlockState(pos);
        float perTick = state.getDestroyProgress(Bot.player(), Bot.level(), pos);
        return perTick <= 0 ? -1 : Math.round(Math.ceil(1f / perTick) / 20.0 * 100) / 100.0;
    }

    private static JsonObject canDig(BlockPos pos) {
        BlockState state = Bot.level().getBlockState(pos);
        LocalPlayer player = Bot.player();
        boolean breakable = !state.isAir() && state.getDestroySpeed(Bot.level(), pos) >= 0;
        boolean inReach = player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= Bot.REACH + 1.0;
        boolean drops = !state.requiresCorrectToolForDrops() || player.getMainHandItem().isCorrectToolForDrops(state);
        return J.obj("breakable", breakable, "in_reach", inReach, "drops_with_current_tool", drops,
                "visible", Bot.visiblePoint(pos) != null);
    }

    private static List<Entity> nearbyEntities(double radius, String type) {
        LocalPlayer player = Bot.player();
        List<Entity> out = new ArrayList<>();
        for (Entity entity : Bot.level().entitiesForRendering()) {
            if (entity == player || !entity.isAlive() || entity.distanceTo(player) > radius) {
                continue;
            }
            if (type == null || BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).equals(Bot.id(type))) {
                out.add(entity);
            }
        }
        out.sort(Comparator.comparingDouble(player::distanceTo));
        return out;
    }

    private static JsonObject entities(JsonObject args) {
        JsonArray out = new JsonArray();
        for (Entity entity : nearbyEntities(J.number(args, "radius", 24), J.str(args, "type", null))) {
            if (out.size() >= 40) {
                break;
            }
            out.add(Bot.entityJson(entity));
        }
        return J.obj("entities", out);
    }

    private static JsonObject nearest(JsonObject args) {
        List<Entity> found = nearbyEntities(128, J.str(args, "type", null));
        return found.isEmpty() ? J.obj("entity", "none") : Bot.entityJson(found.get(0));
    }

    private static JsonObject players() {
        JsonArray out = new JsonArray();
        LocalPlayer me = Bot.player();
        me.connection.getOnlinePlayers().forEach(info -> {
            JsonObject row = J.obj("name", info.getProfile().name(), "latency_ms", info.getLatency());
            Bot.level().players().stream()
                    .filter(p -> p.getUUID().equals(info.getProfile().id()))
                    .findFirst()
                    .ifPresent(p -> row.addProperty("distance", Math.round(p.distanceTo(me))));
            out.add(row);
        });
        return J.obj("players", out);
    }

    /** Whether the line from the eye to a point is clear; used by aiming helpers and reads. */
    static boolean clearLine(Vec3 from, Vec3 to) {
        return Bot.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                Bot.player())).getType() == HitResult.Type.MISS;
    }
}
