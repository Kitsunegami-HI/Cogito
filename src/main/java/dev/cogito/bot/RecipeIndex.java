package dev.cogito.bot;

import dev.cogito.compat.RecipeView;
import dev.cogito.compat.Recipes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What makes what, by result, from every recipe the client can know ({@link Recipes} says where those come from on
 * this Minecraft version). Client thread.
 */
final class RecipeIndex {
    /** A furnace recipe: any one of the inputs gives this many of the result. */
    record Smelt(List<Item> inputs, int makes) {}

    /** Where the client holds only the unlocked recipes, they grow as the player plays. */
    private static final long REFRESH_MILLIS = 60_000;

    private static RecipeIndex current;
    private static Object builtFor;
    private static long builtAt;

    private final Map<Item, List<WindowFns.CraftPlan>> crafting = new HashMap<>();
    private final Map<Item, List<Smelt>> smelting = new HashMap<>();
    private final Set<String> seen = new HashSet<>();

    private RecipeIndex() {}

    static RecipeIndex get() {
        Object key = Recipes.source();
        boolean stale = Recipes.grows() && System.currentTimeMillis() - builtAt > REFRESH_MILLIS;
        if (current == null || key != builtFor || stale) {
            current = build();
            builtFor = key;
            builtAt = System.currentTimeMillis();
        }
        return current;
    }

    List<WindowFns.CraftPlan> crafting(Item item) {
        return crafting.getOrDefault(item, List.of());
    }

    List<Smelt> smelting(Item item) {
        return smelting.getOrDefault(item, List.of());
    }

    /** The result of smelting this item, or null if a furnace cannot take it. */
    Item smeltsInto(Item input) {
        for (Map.Entry<Item, List<Smelt>> entry : smelting.entrySet()) {
            for (Smelt smelt : entry.getValue()) {
                if (smelt.inputs().contains(input)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private static RecipeIndex build() {
        RecipeIndex index = new RecipeIndex();
        Recipes.all(index::addCrafting, index::addSmelting);
        return index;
    }

    private void addCrafting(RecipeView.Crafting recipe) {
        WindowFns.CraftPlan plan = WindowFns.toPlan(recipe);
        ItemStack result = recipe.result();
        StringBuilder signature = new StringBuilder(Bot.itemId(result)).append(plan.width()).append('x').append(plan.height());
        for (List<ItemStack> cell : plan.cells()) {
            signature.append('|').append(cell.isEmpty() ? "" : Bot.itemId(cell.get(0))).append(cell.size());
        }
        if (seen.add(signature.toString())) {
            crafting.computeIfAbsent(result.getItem(), item -> new ArrayList<>()).add(plan);
        }
    }

    private void addSmelting(RecipeView.Smelting recipe) {
        smelting.computeIfAbsent(recipe.result().getItem(), item -> new ArrayList<>()).add(new Smelt(recipe.inputs(), recipe.result().getCount()));
    }
}
