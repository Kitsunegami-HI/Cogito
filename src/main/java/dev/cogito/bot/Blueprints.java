package dev.cogito.bot;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cogito.Config;
import dev.cogito.memory.MemoryStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Building from a blueprint with no model between blocks. Mindcraft's build goal is the model for how it runs: compare
 * the world to the plan, fetch what is missing, carry on. BlueprintBuilder does the placing. Baritone's own builder was
 * tried first and given up on for this: it counts anything that faces a way as missing and stops, digs out floors without
 * refilling them, and was seen to use up materials. A planner gets the totals first, so that it can decide whether a
 * building is worth what it costs before anything is touched.
 */
public final class Blueprints {
    private static final String BUILDS_FILE = "builds.json";
    private static final String[] BUILT_IN = {"small_cabin", "dirt_shelter"};
    private static final long STALL_MILLIS = 45_000;
    private static final int MAX_ROUNDS = 8;
    private static final int SCAFFOLD_BLOCKS = 16;
    /** How far around a build scaffolding is looked for afterwards. */
    private static final int SCAFFOLD_MARGIN = 4;
    private static final int MAX_WATCHED_CELLS = 2_000_000;
    private static final int GATHER_PASSES = 3;
    private static final int STACK = 64;
    private static final int WRONG_SHOWN = 10;
    private static final int READABLE_CELLS = 40_000;
    private static final double SECONDS_PER_BLOCK = 0.6;

    /** Where a blueprint was put in this world, so that it can be carried on with or checked later. */
    private record Placement(String name, BlockPos origin, int rotation) {}

    /** How far a placed blueprint has got. needed is what the unfinished cells still take; turned counts blocks in place but facing the wrong way. */
    private record Progress(int solid, int placed, int toClear, int turned, Map<Item, Integer> needed, List<String> wrong) {
        boolean complete() {
            return placed >= solid && toClear == 0;
        }

        int percent() {
            return solid == 0 ? 100 : placed * 100 / solid;
        }
    }

    private static final Map<String, Blueprint> LOADED = new HashMap<>();
    private static final Map<String, Long> LOADED_AT = new HashMap<>();
    private static final Map<String, Set<BlockPos>> OPEN_BEFORE = new HashMap<>();
    private static Set<BlockPos> ownCells = Set.of();
    private static String ownCellsWorld;

    private Blueprints() {}

    static void register() {
        Api.read("blueprintList()", "the blueprints kept in the game folder and built in, with size and what they are", args -> list());
        Api.read("blueprintRead(name,[layer])", "a blueprint as a palette and layers of characters; layer gives just one slice", args -> read(args));
        Api.read("blueprintInfo(name,[x,y,z],[rotation])", "size, materials needed, what you carry, what is missing, whether obtain can get it and roughly how long, before deciding to build. With coordinates, counts only what is still undone there", args -> info(args));
        Api.read("blueprintSave(name,palette,layers,[description],[ground_layer])", "design a building and keep it. palette maps one character to a block id, with state when direction matters, such as {\"s\":\"minecraft:oak_stairs[facing=east]\"}. layers go bottom up, each a list of rows, north to south, each row west to east. Space leaves a cell alone, . is air and clears it. ground_layer says how many bottom layers sit below the ground. Answers like blueprintInfo", args -> save(args));
        Api.read("findBuildSite(name,[radius=24],[rotation=0])", "the flattest clear spot nearby for a blueprint", args -> {
            Blueprint plain = load(J.str(args, "name"));
            Blueprint turned = plain.rotated(J.integer(args, "rotation", 0));
            BlockPos site = Bot.call(() -> findSite(turned, J.integer(args, "radius", 24)));
            return J.obj("x", site.getX(), "y", site.getY(), "z", site.getZ());
        });
        Api.read("buildStatus(name)", "how far a blueprint placed in this world has got, and what is wrong", args -> status(J.str(args, "name")));
        Api.skill("gatherMaterials(name,[x,y,z],[rotation=0])", "obtain what a blueprint still needs, as much as the inventory holds; fails only if none of it can be had", args -> gatherFn(args));
        Api.skill("buildBlueprint(name,[x,y,z],[rotation=0],[gather=true],[timeout_s=900])", "build a blueprint with no model: goes there, places layer after layer with scaffolding, clears what is in the way, and fetches anything that runs out. With no coordinates it continues where it was started, or finds a flat spot", args -> build(args));
    }

    // ---- loading ----

    private static Path folder() {
        return Config.home().resolve("blueprints");
    }

    private static String nameOf(Path file) {
        String base = file.getFileName().toString();
        int dot = base.lastIndexOf('.');
        return dot < 0 ? base : base.substring(0, dot);
    }

    private static boolean readable(Path file) {
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".nbt") || lower.endsWith(".schem") || lower.endsWith(".schematic") || lower.endsWith(".litematic");
    }

    private static List<Path> files() {
        if (!Files.isDirectory(folder())) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(folder())) {
            return stream.filter(Files::isRegularFile).filter(Blueprints::readable).sorted().toList();
        } catch (IOException e) {
            throw new ApiError("blueprint_error", e.toString());
        }
    }

    /** A file in the game folder first, so that a person can override a built-in one. */
    static synchronized Blueprint load(String name) {
        for (Path file : files()) {
            if (nameOf(file).equalsIgnoreCase(name)) {
                long changed = file.toFile().lastModified();
                Blueprint cached = LOADED.get(name);
                if (cached != null && LOADED_AT.getOrDefault(name, -1L) == changed) {
                    return cached;
                }
                Blueprint fresh = readFile(file);
                LOADED.put(name, fresh);
                LOADED_AT.put(name, changed);
                return fresh;
            }
        }
        try (InputStream in = Blueprints.class.getResourceAsStream("/assets/cogito/blueprints/" + name + ".txt")) {
            if (in != null) {
                return Blueprint.parseText(name, new String(in.readAllBytes(), StandardCharsets.UTF_8), "built in");
            }
        } catch (IOException e) {
            throw new ApiError("blueprint_error", e.toString());
        }
        throw new ApiError("not_found", "no blueprint named '" + name + "'", "blueprintList shows what there is, and blueprintSave makes a new one");
    }

    private static Blueprint readFile(Path file) {
        String name = nameOf(file);
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String source = "cogito/blueprints/" + file.getFileName();
        try {
            if (lower.endsWith(".txt")) {
                return Blueprint.parseText(name, Files.readString(file), source);
            }
            if (lower.endsWith(".nbt")) {
                return readStructure(name, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()), source);
            }
        } catch (IOException e) {
            throw new ApiError("blueprint_error", "could not read " + source + ": " + e);
        }
        if (!Pathing.hasBaritone()) {
            throw new ApiError("needs_baritone", "reading " + source + " needs Baritone, which knows these formats", "the .txt and .nbt forms work without it");
        }
        return BaritoneBridge.readSchematic(file, name, source);
    }

    /** The game's own structure block format: a palette, and a list of the cells that have a block. */
    private static Blueprint readStructure(String name, CompoundTag tag, String source) {
        ListTag size = tag.getListOrEmpty("size");
        int width = size.getIntOr(0, 0);
        int height = size.getIntOr(1, 0);
        int length = size.getIntOr(2, 0);
        ListTag palette = tag.getListOrEmpty("palette");
        if (palette.isEmpty()) {
            palette = tag.getListOrEmpty("palettes").getListOrEmpty(0);
        }
        BlockState[] states = new BlockState[palette.size()];
        for (int i = 0; i < states.length; i++) {
            BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, palette.getCompoundOrEmpty(i));
            states[i] = state.is(Blocks.STRUCTURE_VOID) ? null : state;
        }
        BlockState[] cells = Blueprint.newCells(width, height, length);
        ListTag blocks = tag.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompoundOrEmpty(i);
            ListTag pos = block.getListOrEmpty("pos");
            Blueprint.put(cells, width, length, pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0), states[block.getIntOr("state", 0)]);
        }
        return new Blueprint(name, "", source, width, height, length, 0, cells);
    }

    // ---- reading out ----

    private static JsonElement list() {
        JsonArray out = new JsonArray();
        List<String> seen = new ArrayList<>();
        for (Path file : files()) {
            seen.add(nameOf(file));
            out.add(row(nameOf(file)));
        }
        for (String name : BUILT_IN) {
            if (!seen.contains(name)) {
                out.add(row(name));
            }
        }
        return J.obj("blueprints", out, "folder", "cogito/blueprints");
    }

    private static JsonObject row(String name) {
        try {
            Blueprint blueprint = load(name);
            return J.obj("name", name, "size", J.nums(blueprint.width, blueprint.height, blueprint.length), "source", blueprint.source, "description", blueprint.description);
        } catch (ApiError e) {
            return J.obj("name", name, "unreadable", e.getMessage());
        }
    }

    private static JsonElement read(JsonObject args) {
        Blueprint blueprint = load(J.str(args, "name"));
        Integer layer = args.has("layer") ? J.integer(args, "layer") : null;
        if (layer != null && (layer < 0 || layer >= blueprint.height)) {
            throw new ApiError("bad_argument", "layers run from 0 to " + (blueprint.height - 1));
        }
        long cells = (long) blueprint.width * blueprint.length * (layer == null ? blueprint.height : 1);
        if (cells > READABLE_CELLS) {
            throw new ApiError("too_big", "that is " + cells + " cells, too many to read at once", "ask for one layer");
        }
        return J.obj("name", blueprint.name, "size", J.nums(blueprint.width, blueprint.height, blueprint.length), "text", blueprint.toText(layer));
    }

    private static List<String> rowsOf(JsonElement layer) {
        List<String> rows = new ArrayList<>();
        if (layer.isJsonArray()) {
            layer.getAsJsonArray().forEach(row -> rows.add(row.getAsString().stripTrailing()));
        } else {
            for (String row : layer.getAsString().split("\\R")) {
                rows.add(row.stripTrailing());
            }
        }
        return rows;
    }

    private static JsonElement save(JsonObject args) {
        String name = J.str(args, "name");
        if (!name.matches("[A-Za-z0-9_\\-]+")) {
            throw new ApiError("bad_argument", "a blueprint name uses letters, digits, _ and - only");
        }
        if (!args.has("palette") || !args.get("palette").isJsonObject() || !args.has("layers") || !args.get("layers").isJsonArray()) {
            throw new ApiError("bad_argument", "palette must map characters to blocks and layers must be a list");
        }
        Map<Character, BlockState> palette = new HashMap<>();
        StringBuilder text = new StringBuilder();
        String description = J.str(args, "description", "");
        int groundLayer = J.integer(args, "ground_layer", 0);
        text.append("description: ").append(description).append("\nground_layer: ").append(groundLayer).append("\npalette:\n");
        for (Map.Entry<String, JsonElement> entry : args.getAsJsonObject("palette").entrySet()) {
            if (entry.getKey().length() != 1 || entry.getKey().charAt(0) == ' ') {
                throw new ApiError("bad_blueprint", "palette keys are single characters, and a space means leave alone: '" + entry.getKey() + "'");
            }
            palette.put(entry.getKey().charAt(0), Blueprint.parseBlock(entry.getValue().getAsString()));
            text.append(entry.getKey()).append(' ').append(entry.getValue().getAsString().trim()).append('\n');
        }
        List<List<String>> layers = new ArrayList<>();
        int index = 0;
        for (JsonElement layer : args.getAsJsonArray("layers")) {
            List<String> rows = rowsOf(layer);
            layers.add(rows);
            text.append("layer ").append(index++).append('\n');
            rows.forEach(row -> text.append(row).append('\n'));
        }
        Path file = folder().resolve(name + ".txt");
        Blueprint blueprint = Blueprint.fromLayers(name, description, "cogito/blueprints/" + name + ".txt", groundLayer, palette, layers);
        try {
            Files.createDirectories(folder());
            Files.writeString(file, text.toString());
        } catch (IOException e) {
            throw new ApiError("blueprint_error", e.toString());
        }
        JsonObject out = totals(blueprint, null);
        out.addProperty("saved", "cogito/blueprints/" + name + ".txt");
        return out;
    }

    private static JsonElement info(JsonObject args) {
        Blueprint plain = load(J.str(args, "name"));
        Placement placement = args.has("x") ? new Placement(plain.name, Bot.pos(args), J.integer(args, "rotation", 0)) : savedPlacement(plain.name);
        return totals(plain, placement);
    }

    /** The numbers a planner decides on. */
    private static JsonObject totals(Blueprint plain, Placement placement) {
        return Bot.call(() -> {
            Blueprint blueprint = placement == null ? plain : plain.rotated(placement.rotation());
            Map<Item, Integer> needed = new LinkedHashMap<>();
            int unplaceable = 0;
            int solid = 0;
            Map<String, Integer> impossible = new HashMap<>();
            for (int y = 0; y < blueprint.height; y++) {
                for (int z = 0; z < blueprint.length; z++) {
                    for (int x = 0; x < blueprint.width; x++) {
                        BlockState state = blueprint.at(x, y, z);
                        if (state == null || state.isAir()) {
                            continue;
                        }
                        solid++;
                        if (!Blueprint.placeable(state)) {
                            unplaceable++;
                            impossible.merge(Bot.blockId(state), 1, Integer::sum);
                        }
                    }
                }
            }
            JsonObject out = J.obj("name", blueprint.name, "size", J.nums(blueprint.width, blueprint.height, blueprint.length), "blocks", solid, "description", blueprint.description);
            if (placement == null) {
                needed = wholeCost(blueprint);
            } else {
                Progress progress = measure(blueprint, placement.origin());
                needed = progress.needed();
                out.addProperty("percent_built", progress.percent());
                out.add("at", J.nums(placement.origin().getX(), placement.origin().getY(), placement.origin().getZ()));
            }
            Map<Item, Integer> carried = Obtain.carried();
            JsonArray materials = new JsonArray();
            Map<Item, Integer> missing = new LinkedHashMap<>();
            final Map<Item, Integer> totals = needed;
            totals.entrySet().stream().sorted(Map.Entry.<Item, Integer>comparingByValue().reversed()).forEach(entry -> {
                int have = carried.getOrDefault(entry.getKey(), 0);
                materials.add(J.obj("item", Bot.itemId(new ItemStack(entry.getKey())), "need", entry.getValue(), "have", have, "missing", Math.max(0, entry.getValue() - have)));
                if (have < entry.getValue()) {
                    missing.put(entry.getKey(), entry.getValue());
                }
            });
            out.add("materials", materials);
            if (unplaceable > 0) {
                JsonObject skipped = new JsonObject();
                impossible.forEach(skipped::addProperty);
                out.add("cannot_be_placed_and_will_be_skipped", skipped);
            }
            List<String> floating = plain.floatingCells();
            if (!floating.isEmpty()) {
                out.addProperty("floating_blocks", floating.size());
                JsonArray shown = new JsonArray();
                floating.stream().limit(WRONG_SHOWN).forEach(shown::add);
                out.add("first_floating", shown);
                out.addProperty("floating_means", "nothing connects these to the lowest layer, so there is nothing to place them against and they will stay unbuilt; connect them with more blocks");
            }
            JsonObject supply = Obtain.check(missing);
            supply.addProperty("stacks_to_carry", stacks(totals));
            supply.addProperty("fits_in_inventory", stacks(totals) <= 30);
            supply.addProperty("build_minutes", Math.round(solid * SECONDS_PER_BLOCK / 6) / 10.0);
            out.add("supply", supply);
            return out;
        });
    }

    private static int stacks(Map<Item, Integer> items) {
        int total = 0;
        for (int count : items.values()) {
            total += (int) Math.ceil((double) count / STACK);
        }
        return total;
    }

    private static Map<Item, Integer> wholeCost(Blueprint blueprint) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (int y = 0; y < blueprint.height; y++) {
            for (int z = 0; z < blueprint.length; z++) {
                for (int x = 0; x < blueprint.width; x++) {
                    BlockState state = blueprint.at(x, y, z);
                    if (state != null) {
                        Blueprint.Cost cost = Blueprint.costOf(state);
                        if (cost != null) {
                            out.merge(cost.item(), cost.count(), Integer::sum);
                        }
                    }
                }
            }
        }
        return out;
    }

    // ---- where it goes ----

    private static Map<String, Placement> placements() {
        Map<String, Placement> out = new HashMap<>();
        String key = Bot.call(MemoryStore::currentWorldKey);
        String text = key == null ? null : MemoryStore.readWorldFile(key, BUILDS_FILE);
        if (text != null) {
            JsonObject saved = JsonParser.parseString(text).getAsJsonObject();
            saved.entrySet().forEach(entry -> {
                JsonObject row = entry.getValue().getAsJsonObject();
                out.put(entry.getKey(), new Placement(entry.getKey(), new BlockPos(J.integer(row, "x"), J.integer(row, "y"), J.integer(row, "z")), J.integer(row, "rotation", 0)));
            });
        }
        return out;
    }

    private static Placement savedPlacement(String name) {
        return placements().get(name);
    }

    private static void savePlacement(Placement placement) {
        String key = Bot.call(MemoryStore::currentWorldKey);
        if (key == null) {
            return;
        }
        Map<String, Placement> all = placements();
        all.put(placement.name(), placement);
        ownCellsWorld = null;
        JsonObject out = new JsonObject();
        all.forEach((name, row) -> out.add(name, J.obj("x", row.origin().getX(), "y", row.origin().getY(), "z", row.origin().getZ(), "rotation", row.rotation())));
        MemoryStore.writeWorldFile(key, BUILDS_FILE, new GsonBuilder().setPrettyPrinting().create().toJson(out));
        MemoryStore.notePlace("build_" + placement.name(), placement.origin(), Bot.call(() -> Bot.level().dimension().identifier().toString()), "where the blueprint " + placement.name() + " was started");
    }

    /** Worker thread. Every block the blueprints started in this world call for: what was built here is not a place to fetch materials from. */
    static synchronized Set<BlockPos> ownCells() {
        String world = Bot.call(MemoryStore::currentWorldKey);
        if (world == null) {
            return Set.of();
        }
        if (world.equals(ownCellsWorld)) {
            return ownCells;
        }
        Set<BlockPos> cells = new HashSet<>();
        for (Placement placement : placements().values()) {
            Blueprint blueprint;
            try {
                blueprint = load(placement.name()).rotated(placement.rotation());
            } catch (ApiError e) {
                continue;
            }
            for (int y = 0; y < blueprint.height; y++) {
                for (int z = 0; z < blueprint.length; z++) {
                    for (int x = 0; x < blueprint.width; x++) {
                        BlockState want = blueprint.at(x, y, z);
                        if (want != null && !want.isAir()) {
                            cells.add(placement.origin().offset(x, y, z));
                        }
                    }
                }
            }
        }
        ownCells = cells;
        ownCellsWorld = world;
        return cells;
    }

    /** The top of the ground in a column: leaves and logs do not count, since they are what gets cleared. */
    private static int groundHeight(int x, int z) {
        var level = Bot.level();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
        while (cursor.getY() > level.getMinY()) {
            cursor.move(0, -1, 0);
            BlockState state = level.getBlockState(cursor);
            if (!state.getFluidState().isEmpty()) {
                return Integer.MIN_VALUE;
            }
            if (state.blocksMotion() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)) {
                return cursor.getY() + 1;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Client thread. The flattest dry spot near the player, close being better; trees count against a spot. */
    private static BlockPos findSite(Blueprint blueprint, int radius) {
        var level = Bot.level();
        BlockPos center = Bot.player().blockPosition();
        Map<Long, Integer> heights = new HashMap<>();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int cx = center.getX() - radius; cx <= center.getX() + radius; cx += 2) {
            for (int cz = center.getZ() - radius; cz <= center.getZ() + radius; cz += 2) {
                int low = Integer.MAX_VALUE;
                int high = Integer.MIN_VALUE;
                Map<Integer, Integer> counts = new HashMap<>();
                boolean usable = true;
                for (int x = cx; x < cx + blueprint.width && usable; x++) {
                    for (int z = cz; z < cz + blueprint.length; z++) {
                        if (!level.hasChunk(x >> 4, z >> 4)) {
                            usable = false;
                            break;
                        }
                        int column = x;
                        int row = z;
                        int ground = heights.computeIfAbsent(BlockPos.asLong(x, 0, z), key -> groundHeight(column, row));
                        if (ground == Integer.MIN_VALUE) {
                            usable = false;
                            break;
                        }
                        low = Math.min(low, ground);
                        high = Math.max(high, ground);
                        counts.merge(ground, 1, Integer::sum);
                    }
                }
                if (!usable || high - low > 6) {
                    continue;
                }
                int level0 = counts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
                double score = (high - low) * 4 + Math.hypot(cx - center.getX(), cz - center.getZ()) * 0.15;
                if (score < bestScore) {
                    bestScore = score;
                    best = new BlockPos(cx, level0 - blueprint.groundLayer, cz);
                }
            }
        }
        if (best == null) {
            throw new ApiError("no_site", "no flat dry spot within " + radius + " blocks", "move somewhere else, or give coordinates");
        }
        return best;
    }

    // ---- comparing with the world ----

    /** Client thread. Only the kind of block counts towards being built: one facing the wrong way is still there, and is counted apart. */
    private static Progress measure(Blueprint blueprint, BlockPos origin) {
        var level = Bot.level();
        int solid = 0;
        int placed = 0;
        int toClear = 0;
        int turned = 0;
        Map<Item, Integer> needed = new LinkedHashMap<>();
        List<String> wrong = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < blueprint.height; y++) {
            for (int z = 0; z < blueprint.length; z++) {
                for (int x = 0; x < blueprint.width; x++) {
                    BlockState want = blueprint.at(x, y, z);
                    if (want == null || !Blueprint.placeable(want)) {
                        continue;
                    }
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState here = level.getBlockState(cursor);
                    if (want.isAir()) {
                        // Water cannot be dug out; a building that wants a dry room under the sea is not something this can do.
                        if (!here.isAir() && here.getFluidState().isEmpty()) {
                            toClear++;
                        }
                        continue;
                    }
                    solid++;
                    if (here.getBlock() == want.getBlock()) {
                        placed++;
                        if (!BlueprintBuilder.same(here, want)) {
                            turned++;
                            if (wrong.size() < WRONG_SHOWN) {
                                wrong.add(cursor.toShortString() + " is " + Blueprint.blockText(here) + ", plan says " + Blueprint.blockText(want));
                            }
                        }
                        continue;
                    }
                    Blueprint.Cost cost = Blueprint.costOf(want);
                    if (cost != null) {
                        needed.merge(cost.item(), cost.count(), Integer::sum);
                    }
                    if (wrong.size() < WRONG_SHOWN) {
                        wrong.add(cursor.toShortString() + " wants " + Bot.blockId(want) + ", is " + Bot.blockId(here));
                    }
                }
            }
        }
        return new Progress(solid, placed, toClear, turned, needed, wrong);
    }

    private static JsonElement status(String name) {
        Placement placement = savedPlacement(name);
        if (placement == null) {
            throw new ApiError("not_found", name + " has not been started in this world");
        }
        Blueprint blueprint = load(name).rotated(placement.rotation());
        return progressJson(blueprint, placement, Bot.call(() -> measure(blueprint, placement.origin())));
    }

    private static JsonObject progressJson(Blueprint blueprint, Placement placement, Progress progress) {
        JsonArray wrong = new JsonArray();
        progress.wrong().forEach(wrong::add);
        return J.obj("name", blueprint.name, "at", J.nums(placement.origin().getX(), placement.origin().getY(), placement.origin().getZ()),
                "percent", progress.percent(), "placed", progress.placed(), "of", progress.solid(), "still_to_clear", progress.toClear(),
                "facing_the_wrong_way", progress.turned(), "first_wrong", wrong);
    }

    /** For a step's done_when: how much of a blueprint placed in this world stands, or -1 if it was never started. */
    public static int percentBuilt(String name) {
        Placement placement = savedPlacement(name);
        if (placement == null) {
            return -1;
        }
        Blueprint blueprint = load(name).rotated(placement.rotation());
        return measure(blueprint, placement.origin()).percent();
    }

    // ---- gathering ----

    private static JsonElement gatherFn(JsonObject args) {
        Blueprint plain = load(J.str(args, "name"));
        Placement placement = args.has("x") ? new Placement(plain.name, Bot.pos(args), J.integer(args, "rotation", 0)) : savedPlacement(plain.name);
        Map<Item, Integer> needed = Bot.call(() -> placement == null ? wholeCost(plain)
                : measure(plain.rotated(placement.rotation()), placement.origin()).needed());
        return gather(needed, plain.height > 2);
    }

    /**
     * Obtains each material in turn, biggest first. A building can need more than a body can carry, so it stops at what
     * fits; building then uses that up and comes back for the rest. What cannot be had is reported, not fatal.
     */
    private static JsonObject gather(Map<Item, Integer> needed, boolean tall) {
        JsonObject got = new JsonObject();
        JsonObject failed = new JsonObject();
        int slotsLeft = Bot.call(() -> {
            var inventory = Bot.player().getInventory();
            int free = 0;
            for (int i = 0; i < 36; i++) {
                if (inventory.getItem(i).isEmpty()) {
                    free++;
                }
            }
            return free;
        });
        List<Map.Entry<Item, Integer>> order = new ArrayList<>(needed.entrySet());
        order.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        boolean partial = false;
        ApiError lastFailure = null;
        // One material can use up another: planks and doors are made from the logs the walls also need. So look again.
        // Whatever a furnace is making is left cooking while the next material is fetched, and collected on a later pass.
        boolean fetched = true;
        for (int pass = 0; pass < GATHER_PASSES && fetched; pass++) {
            fetched = false;
            boolean lastPass = pass == GATHER_PASSES - 1;
            for (Map.Entry<Item, Integer> entry : order) {
                Item item = entry.getKey();
                String id = Bot.itemId(new ItemStack(item));
                int have = Bot.call(() -> Obtain.carried().getOrDefault(item, 0));
                if (have >= entry.getValue() || failed.has(id)) {
                    continue;
                }
                // Keep a few slots free for tools' leftovers and for scaffolding.
                int canCarry = have + Math.max(0, slotsLeft - 3) * STACK;
                int target = Math.min(entry.getValue(), canCarry);
                if (target < entry.getValue()) {
                    partial = true;
                }
                if (target <= have) {
                    continue;
                }
                try {
                    JsonObject result = Skills.call("obtain", J.obj("item", id, "count", target, "leave_cooking", !lastPass));
                    if (!J.bool(result, "cooking", false)) {
                        got.addProperty(id, target);
                        slotsLeft -= (int) Math.ceil((double) (target - have) / STACK);
                    }
                    fetched = true;
                } catch (ApiError e) {
                    if (Skills.isControlFailure(e)) {
                        throw e;
                    }
                    failed.addProperty(id, e.getMessage());
                    lastFailure = e;
                }
            }
        }
        if (Skills.anythingCooking()) {
            say("fetching what the furnaces made");
            Skills.collectSmelt(true);
            Skills.packUpBenches();
        }
        if (tall) {
            ensureScaffolding();
        }
        if (got.isEmpty() && lastFailure != null) {
            throw new ApiError(lastFailure.kind, "could not get any of the materials: " + failed, lastFailure.hint);
        }
        JsonObject out = J.obj("obtained", got, "partial", partial);
        if (!failed.isEmpty()) {
            out.add("could_not_get", failed);
        }
        return out;
    }

    /** Baritone stands on these to reach high up. It takes them from the inventory, so a few should be there. */
    private static void ensureScaffolding() {
        int have = Bot.call(() -> {
            Map<Item, Integer> carried = Obtain.carried();
            return carried.getOrDefault(Items.DIRT, 0) + carried.getOrDefault(Items.COBBLESTONE, 0) + carried.getOrDefault(Items.NETHERRACK, 0);
        });
        if (have < SCAFFOLD_BLOCKS) {
            try {
                Skills.call("obtain", J.obj("item", "minecraft:dirt", "count", SCAFFOLD_BLOCKS));
            } catch (ApiError e) {
                if (Skills.isControlFailure(e)) {
                    throw e;
                }
            }
        }
    }

    // ---- building ----

    private static Placement placementFor(JsonObject args, Blueprint plain) {
        if (args.has("x")) {
            Placement given = new Placement(plain.name, Bot.pos(args), J.integer(args, "rotation", 0));
            savePlacement(given);
            return given;
        }
        Placement saved = savedPlacement(plain.name);
        if (saved != null) {
            return saved;
        }
        int rotation = J.integer(args, "rotation", 0);
        Blueprint turned = plain.rotated(rotation);
        BlockPos site = Bot.call(() -> findSite(turned, 24));
        Placement found = new Placement(plain.name, site, rotation);
        savePlacement(found);
        return found;
    }

    private static JsonElement build(JsonObject args) {
        if (!Pathing.hasBaritone()) {
            throw new ApiError("needs_baritone", "building needs Baritone");
        }
        Blueprint plain = load(J.str(args, "name"));
        Placement placement = placementFor(args, plain);
        Blueprint blueprint = plain.rotated(placement.rotation());
        boolean gatherAsNeeded = J.bool(args, "gather", true);
        long deadline = System.currentTimeMillis() + J.integer(args, "timeout_s", 900) * 1000L;
        long started = System.currentTimeMillis();
        int noGainRounds = 0;
        int lastPlaced = -1;
        BlueprintBuilder builder = new BlueprintBuilder(blueprint, placement.origin(), deadline, Blueprints::say);
        // Kept across a fight or a death that interrupts this: what went up before that is still scaffolding to take down.
        String buildKey = plain.name + "@" + placement.origin().toShortString();
        Set<BlockPos> wasOpen = OPEN_BEFORE.containsKey(buildKey) ? OPEN_BEFORE.get(buildKey) : Bot.call(() -> openCellsAround(blueprint, placement.origin()));
        OPEN_BEFORE.put(buildKey, wasOpen);
        for (int round = 0; round < MAX_ROUNDS && System.currentTimeMillis() < deadline; round++) {
            Progress progress = Bot.call(() -> measure(blueprint, placement.origin()));
            if (progress.complete()) {
                break;
            }
            if (gatherAsNeeded) {
                Map<Item, Integer> stillMissing = missingFrom(progress);
                if (!stillMissing.isEmpty()) {
                    say("fetching materials for " + blueprint.name + ": short of " + stillMissing.size() + " kinds");
                }
                try {
                    gather(progress.needed(), blueprint.height > 2);
                } catch (ApiError e) {
                    // Nothing more can be had, but what is carried may still get a good way.
                    if (Skills.isControlFailure(e) || progress.needed().keySet().stream().noneMatch(item -> Bot.call(() -> Obtain.carried().getOrDefault(item, 0)) > 0)) {
                        throw e;
                    }
                }
            }
            if (progress.placed() <= lastPlaced) {
                if (++noGainRounds >= 3) {
                    break;
                }
            } else {
                noGainRounds = 0;
            }
            lastPlaced = progress.placed();
            say("building " + blueprint.name + ", " + progress.percent() + "% done");
            Bot.readyForWorldInput();
            builder.buildAll();
        }
        Progress built = Bot.call(() -> measure(blueprint, placement.origin()));
        if (built.complete()) {
            removeScaffolding(blueprint, placement.origin(), wasOpen, deadline);
            OPEN_BEFORE.remove(buildKey);
            // The first roof of one's own is home, until the agent decides otherwise.
            if (MemoryStore.place("home") == null) {
                MemoryStore.notePlace("home", placement.origin(), Bot.call(() -> Bot.level().dimension().identifier().toString()),
                        "the " + blueprint.name + " built here" + (blueprint.description.isEmpty() ? "" : ": " + blueprint.description));
            }
        }
        Progress end = Bot.call(() -> measure(blueprint, placement.origin()));
        JsonObject out = progressJson(blueprint, placement, end);
        out.addProperty("seconds", (System.currentTimeMillis() - started) / 1000);
        if (!end.complete() && end.percent() < 100) {
            throw new ApiError("build_incomplete", blueprint.name + " is " + end.percent() + "% built: " + out,
                    "look at the first_wrong cells: what is missing, or something with nothing to attach to");
        }
        return out;
    }

    private static Map<Item, Integer> missingFrom(Progress progress) {
        Map<Item, Integer> carried = Bot.call(Obtain::carried);
        Map<Item, Integer> out = new HashMap<>();
        progress.needed().forEach((item, count) -> {
            if (carried.getOrDefault(item, 0) < count) {
                out.put(item, count - carried.getOrDefault(item, 0));
            }
        });
        return out;
    }


    // ---- scaffolding ----

    /** Client thread. The cells around a build that hold nothing yet, so that what appears in them can be told from what was always there. */
    private static Set<BlockPos> openCellsAround(Blueprint blueprint, BlockPos origin) {
        Set<BlockPos> open = new HashSet<>();
        int volume = (blueprint.width + 2 * SCAFFOLD_MARGIN) * (blueprint.height + SCAFFOLD_MARGIN) * (blueprint.length + 2 * SCAFFOLD_MARGIN);
        if (volume > MAX_WATCHED_CELLS) {
            return open;
        }
        for (int x = -SCAFFOLD_MARGIN; x < blueprint.width + SCAFFOLD_MARGIN; x++) {
            for (int y = 0; y < blueprint.height + SCAFFOLD_MARGIN; y++) {
                for (int z = -SCAFFOLD_MARGIN; z < blueprint.length + SCAFFOLD_MARGIN; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (Bot.level().getBlockState(pos).canBeReplaced()) {
                        open.add(pos);
                    }
                }
            }
        }
        return open;
    }

    private static boolean insideAndSolid(Blueprint blueprint, BlockPos origin, BlockPos pos) {
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        if (x < 0 || y < 0 || z < 0 || x >= blueprint.width || y >= blueprint.height || z >= blueprint.length) {
            return false;
        }
        BlockState want = blueprint.at(x, y, z);
        return want != null && !want.isAir();
    }

    /** What Baritone stood on. A building is not finished with dirt pillars around it. */
    private static void removeScaffolding(Blueprint blueprint, BlockPos origin, Set<BlockPos> wasOpen, long deadline) {
        Set<BlockPos> left = Bot.call(() -> {
            Set<BlockPos> found = new HashSet<>();
            for (BlockPos pos : wasOpen) {
                if (BaritoneBridge.isScaffolding(Bot.level().getBlockState(pos)) && !insideAndSolid(blueprint, origin, pos)) {
                    found.add(pos);
                }
            }
            return found;
        });
        if (left.isEmpty()) {
            return;
        }
        say("taking down " + left.size() + " blocks of scaffolding");
        BlockPos corner = origin.offset(-SCAFFOLD_MARGIN, 0, -SCAFFOLD_MARGIN);
        BaritoneBridge.BuildPlan plan = (x, y, z, current) -> {
            BlockPos pos = corner.offset(x, y, z);
            if (left.contains(pos)) {
                return Blocks.AIR.defaultBlockState();
            }
            // The building itself is in the plan as it stands, so that Baritone counts digging through it as a cost.
            return insideAndSolid(blueprint, origin, pos) ? current : null;
        };
        Bot.readyForWorldInput();
        BaritoneBridge.startBuild(blueprint.name + " scaffolding", corner, blueprint.width + 2 * SCAFFOLD_MARGIN, blueprint.height + SCAFFOLD_MARGIN,
                blueprint.length + 2 * SCAFFOLD_MARGIN, plan);
        long lastGain = System.currentTimeMillis();
        long best = Long.MAX_VALUE;
        try {
            Bot.waitUntil(BaritoneBridge::building, 2000);
            while (System.currentTimeMillis() < deadline && System.currentTimeMillis() - lastGain < STALL_MILLIS) {
                Bot.requireControl();
                Bot.pause(500);
                if (!Bot.call(BaritoneBridge::building) || Bot.call(BaritoneBridge::buildPaused)) {
                    return;
                }
                long standing = Bot.call(() -> left.stream().filter(pos -> !Bot.level().getBlockState(pos).isAir()).count());
                if (standing < best) {
                    best = standing;
                    lastGain = System.currentTimeMillis();
                }
            }
            say("left some scaffolding standing");
        } finally {
            Bot.mc().execute(BaritoneBridge::stop);
        }
    }

    private static void say(String text) {
        dev.cogito.agent.AgentLoop.get().noteSkill(text);
    }
}
