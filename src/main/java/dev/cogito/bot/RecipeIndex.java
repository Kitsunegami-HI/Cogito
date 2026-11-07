package dev.cogito.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.cogito.CogitoMod;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.PackType;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What makes what, by result. A singleplayer world holds every recipe of every mod. A server sends only what the player
 * has unlocked, so there the vanilla recipes come from the game's own jar; a recipe does not need unlocking to work in
 * a crafting grid. Client thread.
 */
final class RecipeIndex {
    /** A furnace recipe: any one of the inputs gives this many of the result. */
    record Smelt(List<Item> inputs, int makes) {}

    /** A server's unlocked recipes grow as the player plays. */
    private static final long REFRESH_MILLIS = 60_000;

    private static RecipeIndex current;
    private static Object builtFor;
    private static long builtAt;
    private static List<Recipe<?>> vanilla;
    private static Object vanillaFor;

    private final Map<Item, List<WindowFns.CraftPlan>> crafting = new HashMap<>();
    private final Map<Item, List<Smelt>> smelting = new HashMap<>();
    private final Set<String> seen = new HashSet<>();

    private RecipeIndex() {}

    static RecipeIndex get() {
        IntegratedServer server = Bot.mc().getSingleplayerServer();
        Object key = server != null ? server.getRecipeManager() : Bot.player().connection;
        boolean stale = server == null && System.currentTimeMillis() - builtAt > REFRESH_MILLIS;
        if (current == null || key != builtFor || stale) {
            current = build(server);
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

    private static RecipeIndex build(IntegratedServer server) {
        RecipeIndex index = new RecipeIndex();
        ContextMap context = SlotDisplayContext.fromLevel(Bot.level());
        if (server != null) {
            for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
                index.add(holder.value(), context);
            }
        } else {
            for (Recipe<?> recipe : vanillaRecipes()) {
                index.add(recipe, context);
            }
            Bot.player().getRecipeBook().getCollections().forEach(collection -> {
                for (RecipeDisplayEntry entry : collection.getRecipes()) {
                    index.addCrafting(entry.display(), context);
                }
            });
        }
        return index;
    }

    private void add(Recipe<?> recipe, ContextMap context) {
        for (RecipeDisplay display : recipe.display()) {
            if (display instanceof FurnaceRecipeDisplay furnace) {
                // Smoker and blast furnace recipes share this display, and a plain furnace cannot make those.
                if (recipe.getType() == RecipeType.SMELTING) {
                    addSmelting(furnace, context);
                }
            } else {
                addCrafting(display, context);
            }
        }
    }

    private void addCrafting(RecipeDisplay display, ContextMap context) {
        WindowFns.CraftPlan plan = WindowFns.toPlan(display, context);
        if (plan == null) {
            return;
        }
        ItemStack result = display.result().resolveForFirstStack(context);
        if (result.isEmpty()) {
            return;
        }
        StringBuilder signature = new StringBuilder(Bot.itemId(result)).append(plan.width()).append('x').append(plan.height());
        for (List<ItemStack> cell : plan.cells()) {
            signature.append('|').append(cell.isEmpty() ? "" : Bot.itemId(cell.get(0))).append(cell.size());
        }
        if (seen.add(signature.toString())) {
            crafting.computeIfAbsent(result.getItem(), item -> new ArrayList<>()).add(plan);
        }
    }

    private void addSmelting(FurnaceRecipeDisplay furnace, ContextMap context) {
        ItemStack result = furnace.result().resolveForFirstStack(context);
        List<Item> inputs = new ArrayList<>();
        for (ItemStack stack : furnace.ingredient().resolveForStacks(context)) {
            inputs.add(stack.getItem());
        }
        if (!result.isEmpty() && !inputs.isEmpty()) {
            smelting.computeIfAbsent(result.getItem(), item -> new ArrayList<>()).add(new Smelt(inputs, result.getCount()));
        }
    }

    private static List<Recipe<?>> vanillaRecipes() {
        Object connection = Bot.player().connection;
        if (vanilla != null && vanillaFor == connection) {
            return vanilla;
        }
        RegistryOps<JsonElement> ops = Bot.level().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        List<Recipe<?>> out = new ArrayList<>();
        Bot.mc().getVanillaPackResources().listResources(PackType.SERVER_DATA, "minecraft", "recipe", (id, supplier) -> {
            try (Reader reader = new InputStreamReader(supplier.get(), StandardCharsets.UTF_8)) {
                Recipe.CODEC.parse(ops, JsonParser.parseReader(reader)).result().ifPresent(out::add);
            } catch (Exception e) {
                CogitoMod.LOG.debug("Skipping recipe {}: {}", id, e.toString());
            }
        });
        CogitoMod.LOG.info("Read {} vanilla recipes from the game for use on a server", out.size());
        vanilla = out;
        vanillaFor = connection;
        return out;
    }
}
