package dev.cogito.compat;

import dev.cogito.bot.Bot;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Minecraft 1.21.1: the server sends the client every recipe, of every mod, in singleplayer and on servers alike,
 * so the client's own recipe manager answers everything. Client thread.
 */
public final class Recipes {
    private Recipes() {}

    /** What the recipes were read from; a new one means the recipes may have changed. */
    public static Object source() {
        return manager();
    }

    /** True when the client holds only some recipes, which grow as the player unlocks more. Never here. */
    public static boolean grows() {
        return false;
    }

    /** Every crafting and plain furnace recipe the player can use. */
    public static void all(Consumer<RecipeView.Crafting> crafting, Consumer<RecipeView.Smelting> smelting) {
        for (RecipeHolder<?> holder : manager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe instanceof CraftingRecipe craftingRecipe) {
                addCrafting(craftingRecipe, crafting);
            } else if (recipe.getType() == RecipeType.SMELTING && recipe instanceof AbstractCookingRecipe cooking) {
                addSmelting(cooking, smelting);
            }
        }
    }

    /** The crafting recipes to list as craftable: every one the client knows. */
    public static List<RecipeView.Crafting> listed() {
        List<RecipeView.Crafting> out = new ArrayList<>();
        for (RecipeHolder<?> holder : manager().getRecipes()) {
            if (holder.value() instanceof CraftingRecipe crafting) {
                addCrafting(crafting, out::add);
            }
        }
        return out;
    }

    /** The recipe manager that holds every recipe of every mod. */
    public static RecipeManager everyRecipe() {
        return manager();
    }

    public static String id(RecipeHolder<?> holder) {
        return holder.id().toString();
    }

    private static RecipeManager manager() {
        return Bot.level().getRecipeManager();
    }

    private static List<ItemStack> options(Ingredient ingredient) {
        return ingredient.isEmpty() ? List.of() : List.copyOf(Arrays.asList(ingredient.getItems()));
    }

    private static void addCrafting(CraftingRecipe recipe, Consumer<RecipeView.Crafting> out) {
        if (recipe.isSpecial()) {
            return;
        }
        ItemStack result = recipe.getResultItem(Bot.level().registryAccess());
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (result.isEmpty() || ingredients.isEmpty()) {
            return;
        }
        List<List<ItemStack>> cells = new ArrayList<>();
        if (recipe instanceof ShapedRecipe shaped) {
            for (Ingredient ingredient : ingredients) {
                cells.add(options(ingredient));
            }
            out.accept(new RecipeView.Crafting(shaped.getWidth(), shaped.getHeight(), cells, result, true));
        } else {
            for (Ingredient ingredient : ingredients) {
                if (!ingredient.isEmpty()) {
                    cells.add(options(ingredient));
                }
            }
            int side = cells.size() <= 4 ? 2 : 3;
            out.accept(new RecipeView.Crafting(side, (cells.size() + side - 1) / side, cells, result, false));
        }
    }

    private static void addSmelting(AbstractCookingRecipe recipe, Consumer<RecipeView.Smelting> out) {
        ItemStack result = recipe.getResultItem(Bot.level().registryAccess());
        List<Item> inputs = new ArrayList<>();
        for (ItemStack stack : options(recipe.getIngredients().get(0))) {
            inputs.add(stack.getItem());
        }
        if (!result.isEmpty() && !inputs.isEmpty()) {
            out.accept(new RecipeView.Smelting(inputs, result));
        }
    }
}
