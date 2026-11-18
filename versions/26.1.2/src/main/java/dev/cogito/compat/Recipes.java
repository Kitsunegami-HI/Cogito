package dev.cogito.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.cogito.CogitoMod;
import dev.cogito.bot.Bot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.PackType;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Minecraft 26.1: recipes reach the client only as recipe book displays. A singleplayer world holds every recipe of
 * every mod. A server sends only what the player has unlocked, so there the vanilla recipes come from the game's own
 * jar; a recipe does not need unlocking to work in a crafting grid. Client thread.
 */
public final class Recipes {
    private static List<Recipe<?>> vanilla;
    private static Object vanillaFor;

    private Recipes() {}

    /** What the recipes were read from; a new one means the recipes may have changed. */
    public static Object source() {
        IntegratedServer server = Bot.mc().getSingleplayerServer();
        return server != null ? server.getRecipeManager() : Bot.player().connection;
    }

    /** True when the client holds only some recipes, which grow as the player unlocks more. */
    public static boolean grows() {
        return Bot.mc().getSingleplayerServer() == null;
    }

    /** Every crafting and plain furnace recipe the player can use, as far as the client can know them. */
    public static void all(Consumer<RecipeView.Crafting> crafting, Consumer<RecipeView.Smelting> smelting) {
        ContextMap context = SlotDisplayContext.fromLevel(Bot.level());
        IntegratedServer server = Bot.mc().getSingleplayerServer();
        if (server != null) {
            for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
                add(holder.value(), context, crafting, smelting);
            }
        } else {
            for (Recipe<?> recipe : vanillaRecipes()) {
                add(recipe, context, crafting, smelting);
            }
            unlocked(context, crafting);
        }
    }

    /** The crafting recipes to list as craftable: all of them in singleplayer, the unlocked ones on a server. */
    public static List<RecipeView.Crafting> listed() {
        ContextMap context = SlotDisplayContext.fromLevel(Bot.level());
        List<RecipeView.Crafting> out = new ArrayList<>();
        IntegratedServer server = Bot.mc().getSingleplayerServer();
        if (server != null) {
            for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
                for (RecipeDisplay display : holder.value().display()) {
                    addCrafting(display, context, out::add);
                }
            }
        } else {
            unlocked(context, out::add);
        }
        return out;
    }

    /** The recipe manager that holds every recipe of every mod, or null when the client has none (on a server). */
    public static RecipeManager everyRecipe() {
        IntegratedServer server = Bot.mc().getSingleplayerServer();
        return server == null ? null : server.getRecipeManager();
    }

    public static String id(RecipeHolder<?> holder) {
        return Mc.key(holder.id());
    }

    private static void unlocked(ContextMap context, Consumer<RecipeView.Crafting> crafting) {
        Bot.player().getRecipeBook().getCollections().forEach(collection -> {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                addCrafting(entry.display(), context, crafting);
            }
        });
    }

    private static void add(Recipe<?> recipe, ContextMap context, Consumer<RecipeView.Crafting> crafting, Consumer<RecipeView.Smelting> smelting) {
        for (RecipeDisplay display : recipe.display()) {
            if (display instanceof FurnaceRecipeDisplay furnace) {
                // Smoker and blast furnace recipes share this display, and a plain furnace cannot make those.
                if (recipe.getType() == RecipeType.SMELTING) {
                    addSmelting(furnace, context, smelting);
                }
            } else {
                addCrafting(display, context, crafting);
            }
        }
    }

    private static void addCrafting(RecipeDisplay display, ContextMap context, Consumer<RecipeView.Crafting> out) {
        ItemStack result = display.result().resolveForFirstStack(context);
        if (result.isEmpty()) {
            return;
        }
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            List<List<ItemStack>> cells = new ArrayList<>();
            for (SlotDisplay ingredient : shaped.ingredients()) {
                cells.add(ingredient.resolveForStacks(context));
            }
            out.accept(new RecipeView.Crafting(shaped.width(), shaped.height(), cells, result, true));
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            List<List<ItemStack>> cells = new ArrayList<>();
            for (SlotDisplay ingredient : shapeless.ingredients()) {
                cells.add(ingredient.resolveForStacks(context));
            }
            int side = cells.size() <= 4 ? 2 : 3;
            out.accept(new RecipeView.Crafting(side, (cells.size() + side - 1) / side, cells, result, false));
        }
    }

    private static void addSmelting(FurnaceRecipeDisplay furnace, ContextMap context, Consumer<RecipeView.Smelting> out) {
        ItemStack result = furnace.result().resolveForFirstStack(context);
        List<Item> inputs = new ArrayList<>();
        for (ItemStack stack : furnace.ingredient().resolveForStacks(context)) {
            inputs.add(stack.getItem());
        }
        if (!result.isEmpty() && !inputs.isEmpty()) {
            out.accept(new RecipeView.Smelting(inputs, result));
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
