package dev.cogito.bot;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A building as a grid of block states, bottom layer first. A cell can also be empty, which means "leave whatever is
 * there", as opposed to air, which means "clear this". The text form is a palette and layers of characters, which a
 * person can read and edit, and which a model can write and reason about one slice at a time:
 *
 * <pre>
 * description: a small hut
 * ground_layer: 0
 * palette:
 * c minecraft:cobblestone
 * O minecraft:oak_planks
 * layer 0
 * ccc
 * c.c
 * layer 1
 * OOO
 * O O
 * </pre>
 *
 * Rows run north to south and each row west to east. A space leaves the cell alone, and "." is air unless the palette
 * says otherwise.
 */
final class Blueprint {
    private static final int MAX_CELLS = 8_000_000;
    private static final Pattern LAYER_HEADER = Pattern.compile("(?i)^layer(\\s+\\S+)?\\s*:?\\s*$");
    private static final String SYMBOLS = "#%&*+=@$0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** What one block of this kind takes from the inventory. */
    record Cost(Item item, int count) {}

    final String name;
    final String description;
    final String source;
    final int width;
    final int height;
    final int length;
    /** How many of the bottom layers go below the ground it stands on: a foundation or a cellar. */
    final int groundLayer;
    private final BlockState[] cells;

    Blueprint(String name, String description, String source, int width, int height, int length, int groundLayer, BlockState[] cells) {
        this.name = name;
        this.description = description;
        this.source = source;
        this.width = width;
        this.height = height;
        this.length = length;
        this.groundLayer = groundLayer;
        this.cells = cells;
    }

    private static int index(int x, int y, int z, int width, int length) {
        return (y * length + z) * width + x;
    }

    /** Null when the cell is to be left alone. */
    BlockState at(int x, int y, int z) {
        return cells[index(x, y, z, width, length)];
    }

    static BlockState[] newCells(int width, int height, int length) {
        long size = (long) width * height * length;
        if (size > MAX_CELLS || size <= 0) {
            throw new ApiError("too_big", "a blueprint of " + width + "x" + height + "x" + length + " is more than can be handled");
        }
        return new BlockState[(int) size];
    }

    static void put(BlockState[] cells, int width, int length, int x, int y, int z, BlockState state) {
        cells[index(x, y, z, width, length)] = state;
    }

    /** Turns it clockwise by 0, 90, 180 or 270 degrees, blocks' own facing included. */
    Blueprint rotated(int degrees) {
        int turns = Math.floorMod(degrees, 360) / 90;
        if (turns == 0) {
            return this;
        }
        Rotation rotation = switch (turns) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            default -> Rotation.COUNTERCLOCKWISE_90;
        };
        int newWidth = turns == 2 ? width : length;
        int newLength = turns == 2 ? length : width;
        BlockState[] turned = newCells(newWidth, height, newLength);
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    BlockState state = at(x, y, z);
                    int nx = switch (turns) {
                        case 1 -> length - 1 - z;
                        case 2 -> width - 1 - x;
                        default -> z;
                    };
                    int nz = switch (turns) {
                        case 1 -> x;
                        case 2 -> length - 1 - z;
                        default -> width - 1 - x;
                    };
                    put(turned, newWidth, newLength, nx, y, nz, state == null ? null : state.rotate(rotation));
                }
            }
        }
        return new Blueprint(name, description, source, newWidth, height, newLength, groundLayer, turned);
    }

    // ---- what it takes ----

    /**
     * What placing this state uses up. Null if nothing: air, or the second half of a door or a bed, which comes with the
     * first. placeable() says apart the ones that cannot be placed at all.
     */
    static Cost costOf(BlockState state) {
        if (state.isAir()) {
            return null;
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return null;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
            return null;
        }
        Item item = state.getBlock().asItem();
        if (item == Items.AIR || state.getBlock() instanceof LiquidBlock) {
            return null;
        }
        boolean doubleSlab = state.hasProperty(BlockStateProperties.SLAB_TYPE) && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE;
        return new Cost(item, doubleSlab ? 2 : 1);
    }

    /** Blocks no chain of neighbours ties to the lowest layer that has any, as "layer, row, column" the way a blueprint is written. */
    List<String> floatingCells() {
        boolean[] reached = new boolean[cells.length];
        Deque<int[]> queue = new ArrayDeque<>();
        for (int y = 0; y < height && queue.isEmpty(); y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    if (solidAt(x, y, z)) {
                        reached[index(x, y, z, width, length)] = true;
                        queue.add(new int[] {x, y, z});
                    }
                }
            }
        }
        int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int[] at = queue.remove();
            for (int[] side : sides) {
                int x = at[0] + side[0];
                int y = at[1] + side[1];
                int z = at[2] + side[2];
                if (x >= 0 && y >= 0 && z >= 0 && x < width && y < height && z < length && solidAt(x, y, z) && !reached[index(x, y, z, width, length)]) {
                    reached[index(x, y, z, width, length)] = true;
                    queue.add(new int[] {x, y, z});
                }
            }
        }
        List<String> floating = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    if (solidAt(x, y, z) && !reached[index(x, y, z, width, length)]) {
                        floating.add("layer " + y + ", row " + z + ", column " + x);
                    }
                }
            }
        }
        return floating;
    }

    private boolean solidAt(int x, int y, int z) {
        BlockState state = at(x, y, z);
        return state != null && !state.isAir();
    }

    /** Whether a person with the item could put this here. Water, lava, fire and the like cannot be built this way. */
    static boolean placeable(BlockState state) {
        if (state.isAir() || costOf(state) != null) {
            return true;
        }
        return (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getBlock().asItem() != Items.AIR)
                || (state.hasProperty(BlockStateProperties.BED_PART) && state.getBlock().asItem() != Items.AIR);
    }

    // ---- reading ----

    static BlockState parseBlock(String text) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text.trim(), false).blockState();
        } catch (CommandSyntaxException e) {
            throw new ApiError("bad_block", "'" + text.trim() + "' is not a block: " + e.getMessage(), "write an id with its state if needed, such as minecraft:oak_stairs[facing=east]");
        }
    }

    /** From a palette and layers of text rows. Rows may differ in length: what is missing is left alone. */
    static Blueprint fromLayers(String name, String description, String source, int groundLayer, Map<Character, BlockState> palette, List<List<String>> layers) {
        if (layers.isEmpty()) {
            throw new ApiError("bad_blueprint", "a blueprint needs at least one layer");
        }
        int height = layers.size();
        int width = 0;
        int length = 0;
        for (List<String> layer : layers) {
            length = Math.max(length, layer.size());
            for (String row : layer) {
                width = Math.max(width, row.length());
            }
        }
        if (width == 0 || length == 0) {
            throw new ApiError("bad_blueprint", "every layer is empty");
        }
        BlockState[] cells = newCells(width, height, length);
        for (int y = 0; y < height; y++) {
            List<String> layer = layers.get(y);
            for (int z = 0; z < layer.size(); z++) {
                String row = layer.get(z);
                for (int x = 0; x < row.length(); x++) {
                    char symbol = row.charAt(x);
                    if (symbol == ' ') {
                        continue;
                    }
                    BlockState state = palette.get(symbol);
                    if (state == null && symbol == '.') {
                        state = Blocks.AIR.defaultBlockState();
                    }
                    if (state == null) {
                        throw new ApiError("bad_blueprint", "layer " + y + " row " + z + " column " + x + " uses '" + symbol + "', which the palette does not define",
                                "add it to the palette, use a space to leave a cell alone, or . for air");
                    }
                    put(cells, width, length, x, y, z, state);
                }
            }
        }
        return new Blueprint(name, description, source, width, height, length, groundLayer, cells);
    }

    static Blueprint parseText(String name, String text, String source) {
        Map<Character, BlockState> palette = new HashMap<>();
        List<List<String>> layers = new ArrayList<>();
        String description = "";
        int groundLayer = 0;
        boolean inPalette = false;
        List<String> layer = null;
        for (String raw : text.split("\\R")) {
            String line = raw.stripTrailing();
            String lower = line.toLowerCase(Locale.ROOT);
            if (LAYER_HEADER.matcher(line).matches()) {
                inPalette = false;
                layer = new ArrayList<>();
                layers.add(layer);
            } else if (layer != null) {
                layer.add(line);
            } else if (lower.startsWith("description:")) {
                description = line.substring(12).trim();
            } else if (lower.startsWith("ground_layer:")) {
                groundLayer = Integer.parseInt(line.substring(13).trim());
            } else if (lower.startsWith("palette:")) {
                inPalette = true;
            } else if (inPalette && line.length() >= 2) {
                palette.put(line.charAt(0), parseBlock(line.substring(1).replaceFirst("^[\\s=:]+", "")));
            }
        }
        for (List<String> rows : layers) {
            while (!rows.isEmpty() && rows.get(rows.size() - 1).isBlank()) {
                rows.remove(rows.size() - 1);
            }
        }
        return fromLayers(name, description, source, groundLayer, palette, layers);
    }

    // ---- writing ----

    /** One block state as text that parseBlock reads back. */
    static String blockText(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    /** A symbol for each kind of block: its initial if free, so that a person can read it at a glance. */
    private Map<BlockState, Character> symbols() {
        Map<BlockState, Character> out = new LinkedHashMap<>();
        for (BlockState state : cells) {
            if (state == null || out.containsKey(state) || state.isAir()) {
                continue;
            }
            String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            char symbol = path.charAt(0);
            if (out.containsValue(symbol)) {
                symbol = Character.toUpperCase(symbol);
            }
            for (int i = 0; out.containsValue(symbol) && i < SYMBOLS.length(); i++) {
                symbol = SYMBOLS.charAt(i);
            }
            out.put(state, symbol);
        }
        return out;
    }

    /** The whole thing as text, or from one layer only. Too many kinds of block for one character each gets refused. */
    String toText(Integer onlyLayer) {
        Map<BlockState, Character> symbols = symbols();
        if (symbols.size() > SYMBOLS.length()) {
            throw new ApiError("too_big", "it has " + symbols.size() + " kinds of block, too many to write out as characters");
        }
        StringBuilder out = new StringBuilder("palette:\n");
        symbols.forEach((state, symbol) -> out.append(symbol).append(' ').append(blockText(state)).append('\n'));
        for (int y = onlyLayer == null ? 0 : onlyLayer; y < (onlyLayer == null ? height : onlyLayer + 1); y++) {
            out.append("layer ").append(y).append('\n');
            for (int z = 0; z < length; z++) {
                StringBuilder row = new StringBuilder();
                for (int x = 0; x < width; x++) {
                    BlockState state = at(x, y, z);
                    row.append(state == null ? ' ' : state.isAir() ? '.' : symbols.get(state));
                }
                out.append(row.toString().stripTrailing()).append('\n');
            }
        }
        return out.toString();
    }
}
