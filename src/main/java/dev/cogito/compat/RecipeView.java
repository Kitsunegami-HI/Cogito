package dev.cogito.compat;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Recipes as the shared code sees them on every Minecraft version; {@link Recipes} reads them from the game. */
public final class RecipeView {
    private RecipeView() {}

    /**
     * A crafting grid recipe. Each cell lists the items that fit it, empty for a cell left free.
     * A shapeless recipe's cells are its ingredients in any order.
     */
    public record Crafting(int width, int height, List<List<ItemStack>> cells, ItemStack result, boolean shaped) {}

    /** A plain furnace recipe: any one of the inputs gives the result. */
    public record Smelting(List<Item> inputs, ItemStack result) {}
}
