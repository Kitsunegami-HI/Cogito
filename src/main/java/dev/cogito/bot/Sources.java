package dev.cogito.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cogito.CogitoMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where things come from when no recipe makes them. What drops something other than itself has to be written down, as
 * AltoClef's catalogue does. Whether a vanilla block gives its own item is read from its loot table in the game jar, which
 * is there on any server. A mod's block has no table the client can see, and is assumed to drop itself. Which tool a block
 * needs is read from its tags.
 */
final class Sources {
    private static final Map<Item, List<Block>> DROPS = new HashMap<>();
    private static final Map<Item, List<EntityType<?>>> MOBS = new HashMap<>();
    private static final Map<Item, Double> CHANCE = new HashMap<>();
    private static final Map<Block, Boolean> DROPS_ITSELF = new ConcurrentHashMap<>();
    private static final List<Item> PICKAXES = List.of(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
    private static final List<Item> OTHER_TOOLS = List.of(Items.WOODEN_SHOVEL, Items.WOODEN_AXE, Items.WOODEN_HOE, Items.WOODEN_SWORD, Items.SHEARS);
    /** Where each ore is most common, for digging toward it when none is known. */
    private static final Map<Item, Integer> ORE_LEVEL = new HashMap<>();
    private static volatile BlockList keptWhole;

    /** A list that answers contains at once: Baritone asks about every block it considers, and a modpack can make these long. */
    static final class BlockList extends ArrayList<Block> {
        private final Set<Block> members = new HashSet<>();

        @Override
        public boolean add(Block block) {
            members.add(block);
            return super.add(block);
        }

        @Override
        public boolean addAll(Collection<? extends Block> blocks) {
            members.addAll(blocks);
            return super.addAll(blocks);
        }

        @Override
        public boolean contains(Object block) {
            return members.contains(block);
        }
    }

    /**
     * Containers, benches and machines, vanilla's and every mod's: what stands in the world may hold things or be someone's.
     * They are not a source to mine, and the path finder may not break them. On purpose, the dig function still can.
     */
    static BlockList keptWhole() {
        BlockList list = keptWhole;
        if (list == null) {
            list = new BlockList();
            list.addAll(List.of(Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
                    Blocks.BREWING_STAND, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST, Blocks.ENCHANTING_TABLE,
                    Blocks.ANVIL, Blocks.SHULKER_BOX, Blocks.HOPPER, Blocks.DISPENSER, Blocks.DROPPER, Blocks.BEACON, Blocks.LECTERN));
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block instanceof EntityBlock && !BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("minecraft")) {
                    list.add(block);
                }
            }
            keptWhole = list;
        }
        return list;
    }

    static boolean isKeptWhole(Block block) {
        return keptWhole().contains(block);
    }

    static {
        drops(Items.COBBLESTONE, Blocks.STONE, Blocks.COBBLESTONE);
        drops(Items.COBBLED_DEEPSLATE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE);
        drops(Items.COAL, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
        drops(Items.RAW_IRON, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
        drops(Items.RAW_GOLD, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE);
        drops(Items.RAW_COPPER, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
        drops(Items.DIAMOND, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
        drops(Items.EMERALD, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
        drops(Items.REDSTONE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
        drops(Items.LAPIS_LAZULI, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
        drops(Items.QUARTZ, Blocks.NETHER_QUARTZ_ORE);
        drops(Items.GOLD_NUGGET, Blocks.NETHER_GOLD_ORE);
        drops(Items.GLOWSTONE_DUST, Blocks.GLOWSTONE);
        drops(Items.CLAY_BALL, Blocks.CLAY);
        drops(Items.SNOWBALL, Blocks.SNOW_BLOCK);
        drops(Items.MELON_SLICE, Blocks.MELON);
        drops(Items.AMETHYST_SHARD, Blocks.AMETHYST_CLUSTER);
        drops(Items.DIRT, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT);
        drops(Items.FLINT, Blocks.GRAVEL);
        drops(Items.WHEAT_SEEDS, Blocks.SHORT_GRASS, Blocks.TALL_GRASS);
        drops(Items.APPLE, Blocks.OAK_LEAVES, Blocks.DARK_OAK_LEAVES);
        drops(Items.WHEAT, Blocks.WHEAT);
        CHANCE.put(Items.FLINT, 0.1);
        CHANCE.put(Items.WHEAT_SEEDS, 0.125);
        CHANCE.put(Items.APPLE, 0.05);

        mob(Items.BEEF, EntityType.COW);
        mob(Items.LEATHER, EntityType.COW);
        mob(Items.PORKCHOP, EntityType.PIG);
        mob(Items.MUTTON, EntityType.SHEEP);
        mob(Items.WHITE_WOOL, EntityType.SHEEP);
        mob(Items.CHICKEN, EntityType.CHICKEN);
        mob(Items.FEATHER, EntityType.CHICKEN);
        mob(Items.RABBIT, EntityType.RABBIT);
        mob(Items.RABBIT_HIDE, EntityType.RABBIT);
        mob(Items.STRING, EntityType.SPIDER);
        mob(Items.SPIDER_EYE, EntityType.SPIDER);
        mob(Items.BONE, EntityType.SKELETON);
        mob(Items.ARROW, EntityType.SKELETON);
        mob(Items.ROTTEN_FLESH, EntityType.ZOMBIE);
        mob(Items.GUNPOWDER, EntityType.CREEPER);
        mob(Items.ENDER_PEARL, EntityType.ENDERMAN);
        mob(Items.BLAZE_ROD, EntityType.BLAZE);
        mob(Items.SLIME_BALL, EntityType.SLIME);
        mob(Items.INK_SAC, EntityType.SQUID);

        ORE_LEVEL.put(Items.COAL, 48);
        ORE_LEVEL.put(Items.RAW_COPPER, 48);
        ORE_LEVEL.put(Items.RAW_IRON, 16);
        ORE_LEVEL.put(Items.LAPIS_LAZULI, 0);
        ORE_LEVEL.put(Items.RAW_GOLD, -16);
        ORE_LEVEL.put(Items.REDSTONE, -58);
        ORE_LEVEL.put(Items.DIAMOND, -58);
    }

    private Sources() {}

    private static void drops(Item item, Block... blocks) {
        DROPS.put(item, List.of(blocks));
    }

    private static void mob(Item item, EntityType<?> type) {
        MOBS.put(item, List.of(type));
    }

    /** Blocks that give this item when broken, best known first. */
    static List<Block> blocksDropping(Item item) {
        List<Block> listed = DROPS.get(item);
        if (listed != null) {
            return listed;
        }
        if (item instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            BlockState state = block.defaultBlockState();
            if (!state.is(Tags.Blocks.ORES) && !state.isAir() && state.getFluidState().isEmpty() && block.defaultDestroyTime() >= 0
                    && DROPS_ITSELF.computeIfAbsent(block, Sources::readDropsItself)) {
                return List.of(block);
            }
        }
        return List.of();
    }

    /** True if plain mining gives this block's item. Glass, for one, needs silk touch, and breaks into nothing without it. */
    private static boolean readDropsItself(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        if (!id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
            return true;
        }
        Identifier table = Identifier.withDefaultNamespace("loot_table/blocks/" + id.getPath() + ".json");
        IoSupplier<InputStream> resource = Bot.mc().getVanillaPackResources().getResource(PackType.SERVER_DATA, table);
        if (resource == null) {
            return false;
        }
        String self = BuiltInRegistries.ITEM.getKey(block.asItem()).toString();
        try (Reader reader = new InputStreamReader(resource.get(), StandardCharsets.UTF_8)) {
            JsonObject loot = JsonParser.parseReader(reader).getAsJsonObject();
            if (!loot.has("pools")) {
                return false;
            }
            for (JsonElement pool : loot.getAsJsonArray("pools")) {
                JsonObject poolObject = pool.getAsJsonObject();
                if (!needsSpecialTool(poolObject) && poolObject.has("entries") && anyEntryGives(poolObject.getAsJsonArray("entries"), self)) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            CogitoMod.LOG.debug("Could not read the loot table of {}: {}", id, e.toString());
            return true;
        }
    }

    private static boolean anyEntryGives(JsonArray entries, String item) {
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            if (needsSpecialTool(entry)) {
                continue;
            }
            if (entry.has("name") && entry.get("name").getAsString().equals(item)) {
                return true;
            }
            if (entry.has("children") && anyEntryGives(entry.getAsJsonArray("children"), item)) {
                return true;
            }
        }
        return false;
    }

    /** A condition on what is held: silk touch or shears, which obtain does not plan for. */
    private static boolean needsSpecialTool(JsonObject lootPart) {
        if (!lootPart.has("conditions")) {
            return false;
        }
        String conditions = lootPart.get("conditions").toString();
        return conditions.contains("minecraft:match_tool") && !conditions.contains("minecraft:inverted");
    }

    static List<EntityType<?>> mobsDropping(Item item) {
        return MOBS.getOrDefault(item, List.of());
    }

    /** How often one broken block gives the item. */
    static double dropChance(Item item) {
        return CHANCE.getOrDefault(item, 1.0);
    }

    static Integer oreLevel(Item item) {
        return ORE_LEVEL.get(item);
    }

    /** Worth walking off to look for: the world generates it, as opposed to something a player builds. */
    static boolean natural(Block block) {
        BlockState state = block.defaultBlockState();
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                || state.is(Tags.Blocks.ORES) || state.is(BlockTags.SMALL_FLOWERS) || block == Blocks.GRAVEL || block == Blocks.CLAY
                || block == Blocks.COBBLESTONE || block == Blocks.COBBLED_DEEPSLATE || block == Blocks.SUGAR_CANE
                || block == Blocks.CACTUS || block == Blocks.BAMBOO || block == Blocks.PUMPKIN || block == Blocks.MELON
                || block == Blocks.KELP || block == Blocks.VINE || block == Blocks.LILY_PAD || block == Blocks.SHORT_GRASS
                || block == Blocks.TALL_GRASS || block == Blocks.SNOW || block == Blocks.SNOW_BLOCK || block == Blocks.ICE
                || block == Blocks.AMETHYST_CLUSTER || block == Blocks.GLOWSTONE || block == Blocks.SOUL_SAND
                || block == Blocks.OBSIDIAN || block == Blocks.BROWN_MUSHROOM || block == Blocks.RED_MUSHROOM;
    }

    private static final List<Item> AXES = List.of(Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE);
    private static final List<Item> SHOVELS = List.of(Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL);
    private static final List<Item> HOES = List.of(Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE);
    /** Tiers worth making for a job; higher ones count only when already carried. */
    private static final int TIERS_WORTH_MAKING = 2;

    /**
     * The tools of the right kind for this block: bare hands (null), the lowest that harvests it, and, when tools are
     * being weighed up at all, the cheap tiers above what is carried. Carried ones cost nothing and are picked up by
     * bestToolFor, so they need no entry of their own.
     */
    static List<Item> toolChoices(BlockState state, Map<Item, Integer> inventory, boolean weighUpBetterOnes) {
        Set<Item> out = new LinkedHashSet<>();
        out.add(null);
        Item needed = toolNeeded(state);
        if (needed != null) {
            out.add(needed);
        }
        if (weighUpBetterOnes) {
            List<Item> kind = state.is(BlockTags.MINEABLE_WITH_PICKAXE) ? PICKAXES : state.is(BlockTags.MINEABLE_WITH_AXE) ? AXES
                    : state.is(BlockTags.MINEABLE_WITH_SHOVEL) ? SHOVELS : state.is(BlockTags.MINEABLE_WITH_HOE) ? HOES : List.of();
            int bestCarried = -1;
            for (int i = 0; i < kind.size(); i++) {
                if (inventory.getOrDefault(kind.get(i), 0) > 0) {
                    bestCarried = i;
                }
            }
            for (int i = bestCarried + 1; i < Math.min(kind.size(), TIERS_WORTH_MAKING); i++) {
                out.add(kind.get(i));
            }
        }
        return new ArrayList<>(out);
    }

    /** The carried item that breaks this block fastest, or null for bare hands. */
    static Item bestToolFor(BlockState state, Map<Item, Integer> inventory) {
        Item best = null;
        float bestSpeed = 1f;
        for (Map.Entry<Item, Integer> entry : inventory.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            ItemStack stack = new ItemStack(entry.getKey());
            float speed = stack.getDestroySpeed(state);
            boolean harvests = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
            if (harvests && speed > bestSpeed) {
                bestSpeed = speed;
                best = entry.getKey();
            }
        }
        return best;
    }

    /** How long one block takes with this tool, as the game works it out, or -1 if it would drop nothing. */
    static double secondsToBreak(BlockState state, Item tool) {
        float hardness = state.getBlock().defaultDestroyTime();
        if (hardness < 0) {
            return -1;
        }
        ItemStack stack = tool == null ? ItemStack.EMPTY : new ItemStack(tool);
        boolean harvests = !state.requiresCorrectToolForDrops() || (tool != null && stack.isCorrectToolForDrops(state));
        if (!harvests) {
            return -1;
        }
        float speed = tool == null ? 1f : Math.max(1f, stack.getDestroySpeed(state));
        if (hardness == 0) {
            return 0.05;
        }
        return Math.ceil(hardness * 30 / speed) / 20.0;
    }

    /** Whether a block that has not been seen could be found at all in this dimension: nether rock is not under the overworld. */
    static boolean grows(Block block, String dimension) {
        BlockState state = block.defaultBlockState();
        String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
        boolean nether = state.is(BlockTags.BASE_STONE_NETHER) || id.startsWith("nether_") || id.startsWith("soul_") || id.contains("warped_") || id.contains("crimson_")
                || block == Blocks.GLOWSTONE || block == Blocks.MAGMA_BLOCK || block == Blocks.ANCIENT_DEBRIS;
        boolean end = id.startsWith("end_") || id.startsWith("chorus_") || id.startsWith("purpur_");
        if (nether) {
            return dimension.equals("minecraft:the_nether");
        }
        if (end) {
            return dimension.equals("minecraft:the_end");
        }
        return !dimension.equals("minecraft:the_nether") && !dimension.equals("minecraft:the_end");
    }

    /** The lowest tool that makes this block drop anything, or null if a bare hand does. */
    static Item toolNeeded(BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return null;
        }
        for (List<Item> tools : List.of(PICKAXES, OTHER_TOOLS)) {
            for (Item tool : tools) {
                if (new ItemStack(tool).isCorrectToolForDrops(state)) {
                    return tool;
                }
            }
        }
        return null;
    }

    /**
     * Blocks by id or #tag, as names a player would use: "iron_ore" also means its deepslate form, and an item such as
     * "coal" or "diamond" means the blocks that drop it.
     */
    static Set<Block> resolveBlocks(List<String> names) {
        List<String> plain = new ArrayList<>();
        Set<Block> out = new LinkedHashSet<>();
        for (String name : names) {
            String id = name.startsWith("#") ? name : Bot.id(name).toString();
            if (!id.startsWith("#") && net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(Bot.id(id)).map(DROPS::containsKey).orElse(false)
                    && net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(Bot.id(id)).isEmpty()) {
                out.addAll(DROPS.get(Bot.item(id)));
                continue;
            }
            plain.add(id);
            if (id.endsWith("_ore") && !id.contains("deepslate_") && !id.contains("nether_")) {
                String deepslate = id.replace(":", ":deepslate_");
                if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(Bot.id(deepslate)).isPresent()) {
                    plain.add(deepslate);
                }
            }
        }
        if (!plain.isEmpty()) {
            out.addAll(Bot.blocks(plain));
        }
        return out;
    }
}
