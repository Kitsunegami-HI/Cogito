package dev.cogito.bot;

import dev.cogito.compat.Mc;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cogito.CogitoMod;
import dev.cogito.memory.MemoryStore;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * What the chests around hold. In a world of its own the client is also the server, so the contents can simply be
 * read. On a server a chest has to be opened once; what was seen is kept, in memory and on disk for this world, until
 * the chest is seen again. The planner counts a chest as a source like a tree or an ore, usually the cheapest one, and
 * finding it emptied by someone else is just one more thing to replan around.
 */
final class Containers {
    /** One chest as last seen. items are keyed as Bot.itemKey gives them, so potions keep their kind. */
    record Known(BlockPos pos, String dimension, String block, Map<String, Integer> items, long seenAt) {
        int count(Item item) {
            String id = Bot.itemId(new ItemStack(item));
            int total = 0;
            for (Map.Entry<String, Integer> entry : items.entrySet()) {
                if (entry.getKey().equals(id) || entry.getKey().startsWith(id + "#")) {
                    total += entry.getValue();
                }
            }
            return total;
        }

        int count(String key) {
            return items.getOrDefault(key, 0);
        }
    }

    private static final String FILE = "containers.json";
    /** How far around the player a world of its own is read directly. */
    private static final int LIVE_RADIUS = 96;
    private static final int ITEMS_SHOWN = 12;
    private static final long LIVE_WAIT_MILLIS = 2000;
    private static final Map<String, Map<BlockPos, Known>> BY_WORLD = new ConcurrentHashMap<>();

    private Containers() {}

    static void register() {
        Api.read("chestsKnown([radius=64],[max=12])", "chests, barrels and shulker boxes whose contents are known: read directly in a world of your own, remembered from opening them on a server. Nearest first, with what they hold",
                args -> Bot.call(() -> listJson(J.integer(args, "radius", 64), J.integer(args, "max", 12))));
        Api.read("findInChests(item,[radius=96])", "which known chests hold an item, nearest first. obtain takes from them by itself",
                args -> Bot.call(() -> findJson(J.str(args, "item"), J.integer(args, "radius", 96))));
        Api.read("forgetChest(x,y,z)", "drop what was remembered about a chest", args -> {
            BlockPos pos = Bot.pos(args);
            Bot.call(() -> {
                known().remove(pos);
                save();
                return null;
            });
            return J.obj("forgotten", pos.toShortString());
        });
    }

    // ---- what is known ----

    /** Client thread. */
    private static Map<BlockPos, Known> known() {
        String key = MemoryStore.currentWorldKey();
        if (key == null) {
            return new ConcurrentHashMap<>();
        }
        return BY_WORLD.computeIfAbsent(key, Containers::load);
    }

    private static Map<BlockPos, Known> load(String key) {
        Map<BlockPos, Known> out = new ConcurrentHashMap<>();
        String text = MemoryStore.readWorldFile(key, FILE);
        if (text == null) {
            return out;
        }
        try {
            JsonObject saved = JsonParser.parseString(text).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : saved.entrySet()) {
                String[] parts = entry.getKey().split(",");
                BlockPos pos = new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
                JsonObject row = entry.getValue().getAsJsonObject();
                Map<String, Integer> items = new LinkedHashMap<>();
                row.getAsJsonObject("items").entrySet().forEach(item -> items.put(item.getKey(), item.getValue().getAsInt()));
                out.put(pos, new Known(pos, J.str(row, "dimension", "minecraft:overworld"), J.str(row, "block", "minecraft:chest"), items, row.get("seen").getAsLong()));
            }
        } catch (RuntimeException e) {
            CogitoMod.LOG.warn("Could not read {} for {}: {}", FILE, key, e.toString());
        }
        return out;
    }

    /** Client thread. */
    private static void save() {
        String key = MemoryStore.currentWorldKey();
        if (key == null) {
            return;
        }
        JsonObject out = new JsonObject();
        for (Known known : known().values()) {
            JsonObject items = new JsonObject();
            known.items().forEach(items::addProperty);
            JsonObject row = J.obj("dimension", known.dimension(), "block", known.block(), "seen", known.seenAt());
            row.add("items", items);
            out.add(known.pos().getX() + "," + known.pos().getY() + "," + known.pos().getZ(), row);
        }
        MemoryStore.writeWorldFile(key, FILE, new GsonBuilder().setPrettyPrinting().create().toJson(out));
    }

    private static boolean storage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL) || state.is(BlockTags.SHULKER_BOXES);
    }

    private static String dimension() {
        return Mc.dimension(Bot.level());
    }

    /** Client thread. What the open window shows of the chest at pos, kept. Nothing happens for a window that is not a chest. */
    static void recordWindow(BlockPos pos) {
        if (!(Bot.mc().screen instanceof AbstractContainerScreen<?> screen) || !storage(Bot.level().getBlockState(pos))) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        Map<String, Integer> items = new LinkedHashMap<>();
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (!(slot.container instanceof Inventory) && !stack.isEmpty()) {
                items.merge(Bot.itemKey(stack), stack.getCount(), Integer::sum);
            }
        }
        Known before = known().get(pos);
        known().put(pos, new Known(pos, dimension(), Bot.blockId(Bot.level().getBlockState(pos)), items, System.currentTimeMillis()));
        if (before == null || !before.items().equals(items)) {
            save();
        }
    }

    /**
     * Client thread. In a world of its own, the chests around are read as they are. Chests that were remembered here
     * and are gone are forgotten.
     */
    static void refreshLive() {
        var server = Bot.mc().getSingleplayerServer();
        if (server == null) {
            return;
        }
        ResourceKey<Level> key = Bot.level().dimension();
        String dimension = dimension();
        BlockPos center = Bot.player().blockPosition();
        // The server only hands out its chunks on its own thread, so the look is taken there and waited for briefly.
        Map<BlockPos, Known> scanned;
        try {
            scanned = server.submit(() -> scanOnServer(server, key, dimension, center)).get(LIVE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | TimeoutException e) {
            CogitoMod.LOG.debug("Could not read the chests from the server: {}", e.toString());
            return;
        }
        Map<BlockPos, Known> known = known();
        Set<BlockPos> seen = new HashSet<>();
        boolean changed = false;
        for (Known fresh : scanned.values()) {
            Known before = known.get(fresh.pos());
            if (before == null || !before.items().equals(fresh.items())) {
                changed = true;
            }
            known.put(fresh.pos(), fresh);
            seen.add(fresh.pos());
        }
        int inner = LIVE_RADIUS - 16;
        changed |= known.entrySet().removeIf(entry -> entry.getValue().dimension().equals(dimension)
                && Math.abs(entry.getKey().getX() - center.getX()) <= inner && Math.abs(entry.getKey().getZ() - center.getZ()) <= inner
                && !seen.contains(entry.getKey()));
        if (changed) {
            save();
        }
    }

    /** Server thread. Every chest in the loaded chunks around, with what it holds. */
    private static Map<BlockPos, Known> scanOnServer(net.minecraft.server.MinecraftServer server, ResourceKey<Level> key, String dimension, BlockPos center) {
        Map<BlockPos, Known> out = new LinkedHashMap<>();
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            return out;
        }
        int chunks = LIVE_RADIUS >> 4;
        long now = System.currentTimeMillis();
        for (int cx = (center.getX() >> 4) - chunks; cx <= (center.getX() >> 4) + chunks; cx++) {
            for (int cz = (center.getZ() >> 4) - chunks; cz <= (center.getZ() >> 4) + chunks; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    if (!(entry.getValue() instanceof BaseContainerBlockEntity container) || !storage(container.getBlockState())) {
                        continue;
                    }
                    Map<String, Integer> items = new LinkedHashMap<>();
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack stack = container.getItem(i);
                        if (!stack.isEmpty()) {
                            items.merge(Bot.itemKey(stack), stack.getCount(), Integer::sum);
                        }
                    }
                    BlockPos pos = entry.getKey().immutable();
                    out.put(pos, new Known(pos, dimension, Bot.blockId(container.getBlockState()), items, now));
                }
            }
        }
        return out;
    }

    /** Client thread. Known chests in this dimension within radius, nearest first, after a fresh look where that is possible. */
    static List<Known> nearby(int radius) {
        refreshLive();
        String dimension = dimension();
        BlockPos center = Bot.player().blockPosition();
        List<Known> out = new ArrayList<>();
        for (Known known : known().values()) {
            if (known.dimension().equals(dimension) && known.pos().distSqr(center) <= (double) radius * radius) {
                out.add(known);
            }
        }
        out.sort(Comparator.comparingDouble(known -> known.pos().distSqr(center)));
        return out;
    }

    /** Client thread. Chests holding the item, nearest first. */
    static List<Known> holding(Item item, int radius) {
        List<Known> out = new ArrayList<>();
        for (Known known : nearby(radius)) {
            if (known.count(item) > 0) {
                out.add(known);
            }
        }
        return out;
    }

    /** Client thread. Chests holding a kind of item, such as a potion of one kind, nearest first. */
    static List<Known> holdingKey(String key, int radius) {
        List<Known> out = new ArrayList<>();
        for (Known known : nearby(radius)) {
            if (known.count(key) > 0) {
                out.add(known);
            }
        }
        return out;
    }

    // ---- for the model ----

    private static JsonObject knownJson(Known known, BlockPos center) {
        JsonObject items = new JsonObject();
        known.items().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(ITEMS_SHOWN)
                .forEach(entry -> items.addProperty(entry.getKey(), entry.getValue()));
        JsonObject out = J.obj("pos", J.nums(known.pos().getX(), known.pos().getY(), known.pos().getZ()), "block", known.block(),
                "distance", Math.round(Math.sqrt(known.pos().distSqr(center))), "seen_seconds_ago", (System.currentTimeMillis() - known.seenAt()) / 1000);
        out.add("items", items);
        if (known.items().size() > ITEMS_SHOWN) {
            out.addProperty("more_kinds", known.items().size() - ITEMS_SHOWN);
        }
        return out;
    }

    static JsonObject listJson(int radius, int max) {
        BlockPos center = Bot.player().blockPosition();
        List<Known> all = nearby(radius);
        JsonArray chests = new JsonArray();
        for (Known known : all.subList(0, Math.min(max, all.size()))) {
            chests.add(knownJson(known, center));
        }
        JsonObject out = J.obj("known", all.size(), "chests", chests);
        out.addProperty("note", Bot.mc().getSingleplayerServer() != null ? "read directly from this world" : "as last seen when opened; a chest not opened yet is not listed");
        return out;
    }

    private static JsonObject findJson(String item, int radius) {
        BlockPos center = Bot.player().blockPosition();
        boolean keyed = item.contains("#") && !item.startsWith("#");
        String key = keyed ? Mc.id(item.substring(0, item.indexOf('#'))) + "#" + Mc.id(item.substring(item.indexOf('#') + 1)) : null;
        List<Known> chests = keyed ? holdingKey(key, radius) : holding(Bot.item(item), radius);
        JsonArray rows = new JsonArray();
        for (Known known : chests) {
            int count = keyed ? known.count(key) : known.count(Bot.item(item));
            rows.add(J.obj("pos", J.nums(known.pos().getX(), known.pos().getY(), known.pos().getZ()), "count", count,
                    "distance", Math.round(Math.sqrt(known.pos().distSqr(center)))));
        }
        return J.obj("found", !chests.isEmpty(), "chests", rows);
    }
}
