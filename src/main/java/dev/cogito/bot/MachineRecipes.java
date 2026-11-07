package dev.cogito.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Every non-crafting recipe, of any mod, read through the game's own recipe codec: the JSON shows what the
 * machine takes and makes without knowing the mod. Only a singleplayer world holds the recipes; a server keeps
 * them to itself, and there the answer says so instead of looking like "no recipe".
 */
final class MachineRecipes {
    private static final int CLIP = 400;

    private record Entry(String id, JsonObject json) {}

    private static RecipeManager cachedFor;
    private static List<Entry> cache = List.of();

    private MachineRecipes() {}

    private static List<Entry> all() {
        IntegratedServer server = Bot.mc().getSingleplayerServer();
        if (server == null) {
            return null;
        }
        RecipeManager manager = server.getRecipeManager();
        if (manager != cachedFor) {
            RegistryOps<JsonElement> ops = Bot.level().registryAccess().createSerializationContext(JsonOps.INSTANCE);
            List<Entry> entries = new ArrayList<>();
            for (RecipeHolder<?> holder : manager.getRecipes()) {
                Recipe<?> recipe = holder.value();
                if (recipe instanceof CraftingRecipe) {
                    continue;
                }
                // A recipe its mod cannot write out is skipped, not fatal: the others still answer.
                Recipe.CODEC.encodeStart(ops, recipe).result()
                        .filter(JsonElement::isJsonObject)
                        .ifPresent(json -> entries.add(new Entry(holder.id().identifier().toString(), json.getAsJsonObject())));
            }
            cache = entries;
            cachedFor = manager;
        }
        return cache;
    }

    private static boolean mentions(JsonObject json, String[] fields, List<String> needles) {
        for (String field : fields) {
            JsonElement value = json.get(field);
            if (value != null) {
                String text = value.toString();
                for (String needle : needles) {
                    if (text.contains(needle)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static JsonObject rows(List<String> needles, String[] fields, int limit) {
        List<Entry> entries = all();
        if (entries == null) {
            return J.obj("machine_recipes", new JsonArray(), "machine_recipes_note",
                    "a server does not share its machine recipes with the client; JEI, the mod's text or the window itself may show them");
        }
        JsonArray out = new JsonArray();
        int total = 0;
        for (Entry entry : entries) {
            if (mentions(entry.json(), fields, needles)) {
                total++;
                if (out.size() < limit) {
                    String text = entry.json().toString();
                    out.add(J.obj("id", entry.id(), "type", J.str(entry.json(), "type", "unknown"),
                            "recipe", text.length() > CLIP ? text.substring(0, CLIP) + "…" : text));
                }
            }
        }
        return J.obj("machine_recipes", out, "machine_recipes_total", total);
    }

    /** Machine recipes whose result is this item. */
    static JsonObject making(Item item, int limit) {
        String id = "\"" + BuiltInRegistries.ITEM.getKey(item) + "\"";
        return rows(List.of(id), new String[] {"results", "result", "output", "outputs"}, limit);
    }

    /** Machine recipes that take this item, directly or through one of its tags. */
    static JsonObject using(Item item, int limit) {
        List<String> needles = new ArrayList<>();
        needles.add("\"" + BuiltInRegistries.ITEM.getKey(item) + "\"");
        new ItemStack(item).typeHolder().tags().forEach(tag -> {
            needles.add("\"#" + tag.location() + "\"");
            needles.add("\"" + tag.location() + "\"");
        });
        return rows(needles, new String[] {"ingredients", "ingredient", "input", "inputs", "base", "addition", "template"}, limit);
    }
}
