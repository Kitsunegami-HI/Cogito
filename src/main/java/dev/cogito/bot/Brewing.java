package dev.cogito.bot;

import dev.cogito.compat.Mc;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Potions are not in the recipe book: a brewing stand turns one bottle into another, one ingredient at a time, and the
 * game only answers "what does this ingredient do to this bottle". So the chain is searched out from the bottles at
 * hand, water included, to the potion wanted, and then walked: each ingredient fetched with obtain, chests first, and
 * each step brewed. The whole thing is one skill, so that a planner can ask for a potion as it asks for a pickaxe.
 */
final class Brewing {
    record Step(Item ingredient, ItemStack result) {}

    /** From start, which is carried or can be had, these steps lead to the potion. */
    record Chain(ItemStack start, List<Step> steps) {}

    private static final int BOTTLES_PER_BREW = 3;
    private static final int BOTTLE_SLOTS = 3;
    private static final int INGREDIENT_SLOT = 3;
    private static final int FUEL_SLOT = 4;
    private static final long BREW_WAIT_MILLIS = 35_000;
    private static final int CHEST_RADIUS = 96;
    private static final int STAND_RADIUS = 24;
    private static final int WATER_RADIUS = 48;

    private Brewing() {}

    static void register() {
        Api.read("brewPlan(potion,[form=potion],[count=1])", "the brewing steps to a potion from water, or from a potion carried or seen in a chest. form: potion, splash or lingering. potion is an id such as minecraft:long_invisibility; potions() lists them",
                args -> Bot.call(() -> planJson(args)));
        Api.read("potions([query])", "potion ids with their names, for brew; query narrows by id or name",
                args -> Bot.call(() -> potionsJson(J.str(args, "query", ""))));
        Api.skill("brew(potion,[form=potion],[count=1])", "brew a potion from scratch, with no model: works out the chain back to water or to a potion at hand, fetches bottles, a stand, fuel and every ingredient with obtain (chests first), and brews step by step. form potion, splash or lingering; long and strong kinds are their own ids, such as minecraft:long_invisibility",
                Brewing::brewFn);
    }

    // ---- naming ----

    static Holder<Potion> potion(String raw) {
        return Mc.holder(BuiltInRegistries.POTION, raw).map(holder -> (Holder<Potion>) holder)
                .orElseThrow(() -> new ApiError("bad_argument", "no potion named " + raw, "potions() lists the ids; long_ and strong_ kinds are separate"));
    }

    static Item formItem(String form) {
        return switch (form.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace("_potion", "")) {
            case "potion", "drink", "drinkable", "" -> Items.POTION;
            case "splash" -> Items.SPLASH_POTION;
            case "lingering" -> Items.LINGERING_POTION;
            default -> throw new ApiError("bad_argument", "form must be potion, splash or lingering");
        };
    }

    private static JsonObject potionsJson(String query) {
        JsonArray out = new JsonArray();
        String needle = query.toLowerCase(Locale.ROOT);
        Mc.holders(BuiltInRegistries.POTION).forEach(holder -> {
            String id = Mc.key(holder.key());
            String name = PotionContents.createItemStack(Items.POTION, holder).getHoverName().getString();
            if (needle.isEmpty() || id.contains(needle) || name.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(J.obj("id", id, "name", name));
            }
        });
        return J.obj("potions", out);
    }

    // ---- the chain ----

    /** Every item a brewing stand accepts as an ingredient here, mods included. */
    private static List<Item> ingredients(PotionBrewing brewing) {
        List<Item> out = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (brewing.isIngredient(new ItemStack(item))) {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * The brewing recipes in force: the server's own in a world of one's own, mods included; on a server the client has
     * nothing, so the vanilla set is built here.
     */
    private static PotionBrewing brewing() {
        var server = Bot.mc().getSingleplayerServer();
        if (server != null) {
            return server.potionBrewing();
        }
        PotionBrewing fromLevel = Bot.level().potionBrewing();
        if (fromLevel.isIngredient(new ItemStack(Items.NETHER_WART))) {
            return fromLevel;
        }
        return PotionBrewing.bootstrap(Bot.level().enabledFeatures(), Bot.level().registryAccess());
    }

    /** Client thread. The shortest chain from any of the starts, the earlier starts preferred among equals. Null if none leads there. */
    static Chain chain(ItemStack target, List<ItemStack> starts) {
        PotionBrewing brewing = brewing();
        List<Item> ingredients = ingredients(brewing);
        Map<String, ItemStack> stacks = new HashMap<>();
        Map<String, String> parent = new HashMap<>();
        Map<String, Item> via = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (ItemStack start : starts) {
            String key = Bot.itemKey(start);
            if (!parent.containsKey(key)) {
                parent.put(key, null);
                stacks.put(key, start);
                queue.add(key);
            }
        }
        String wanted = Bot.itemKey(target);
        while (!queue.isEmpty() && !parent.containsKey(wanted)) {
            String key = queue.poll();
            ItemStack from = stacks.get(key);
            for (Item ingredient : ingredients) {
                // The game takes the ingredient first, then the bottle.
                ItemStack result = brewing.mix(new ItemStack(ingredient), from);
                if (result.isEmpty()) {
                    continue;
                }
                String resultKey = Bot.itemKey(result);
                if (!parent.containsKey(resultKey)) {
                    parent.put(resultKey, key);
                    via.put(resultKey, ingredient);
                    stacks.put(resultKey, result);
                    queue.add(resultKey);
                }
            }
        }
        if (!parent.containsKey(wanted)) {
            return null;
        }
        LinkedList<Step> steps = new LinkedList<>();
        String current = wanted;
        while (parent.get(current) != null) {
            steps.addFirst(new Step(via.get(current), stacks.get(current)));
            current = parent.get(current);
        }
        return new Chain(stacks.get(current), steps);
    }

    private static ItemStack waterBottle() {
        return PotionContents.createItemStack(Items.POTION, Potions.WATER);
    }

    /** Client thread. Bottles to start from: potions carried and in known chests that could cover the count, then water. */
    private static List<ItemStack> starts(int count) {
        Map<String, Integer> have = new LinkedHashMap<>();
        Inventory inventory = Bot.player().getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (isBottle(stack)) {
                have.merge(Bot.itemKey(stack), stack.getCount(), Integer::sum);
            }
        }
        for (Containers.Known known : Containers.nearby(CHEST_RADIUS)) {
            known.items().forEach((key, n) -> {
                if (key.startsWith("minecraft:potion#") || key.startsWith("minecraft:splash_potion#") || key.startsWith("minecraft:lingering_potion#")) {
                    have.merge(key, n, Integer::sum);
                }
            });
        }
        List<ItemStack> starts = new ArrayList<>();
        String water = Bot.itemKey(waterBottle());
        have.forEach((key, n) -> {
            if (n >= count && !key.equals(water)) {
                starts.add(stackOf(key));
            }
        });
        starts.add(waterBottle());
        return starts;
    }

    private static boolean isBottle(ItemStack stack) {
        return stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION);
    }

    /** A stack from a key such as minecraft:potion#minecraft:night_vision. */
    static ItemStack stackOf(String key) {
        int hash = key.indexOf('#');
        if (hash < 0) {
            return new ItemStack(Bot.item(key));
        }
        return PotionContents.createItemStack(Bot.item(key.substring(0, hash)), potion(key.substring(hash + 1)));
    }

    private static JsonObject planJson(JsonObject args) {
        ItemStack target = PotionContents.createItemStack(formItem(J.str(args, "form", "potion")), potion(J.str(args, "potion")));
        int count = J.integer(args, "count", 1);
        Chain chain = chain(target, starts(count));
        if (chain == null) {
            return J.obj("possible", false, "target", Bot.itemKey(target), "why", "no sequence of ingredients leads there from water or anything at hand");
        }
        JsonArray steps = new JsonArray();
        for (Step step : chain.steps()) {
            steps.add(J.obj("add", Bot.itemId(new ItemStack(step.ingredient())), "gives", Bot.itemKey(step.result())));
        }
        JsonObject out = J.obj("possible", true, "target", Bot.itemKey(target), "start", Bot.itemKey(chain.start()), "steps", steps);
        out.addProperty("minutes", Math.round(chain.steps().size() * 0.5 * 10) / 10.0);
        out.addProperty("note", "each step takes about 20 seconds in the stand; the ingredients and the stand itself are fetched with obtain");
        return out;
    }

    // ---- doing it ----

    private static int carriedKey(String key) {
        return Bot.call(() -> {
            Inventory inventory = Bot.player().getInventory();
            int total = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && Bot.itemKey(stack).equals(key)) {
                    total += stack.getCount();
                }
            }
            return total;
        });
    }

    static JsonElement brewFn(JsonObject args) {
        ItemStack target = Bot.call(() -> PotionContents.createItemStack(formItem(J.str(args, "form", "potion")), potion(J.str(args, "potion"))));
        String targetKey = Bot.itemKey(target);
        int wanted = J.integer(args, "count", 1);
        long started = System.currentTimeMillis();
        List<String> did = new ArrayList<>();
        while (carriedKey(targetKey) < wanted) {
            Bot.requireControl();
            int batch = Math.min(BOTTLES_PER_BREW, wanted - carriedKey(targetKey));
            try {
                brewBatch(target, batch, did);
            } catch (ApiError e) {
                if (Skills.isControlFailure(e) || did.isEmpty()) {
                    throw e;
                }
                throw new ApiError(e.kind, e.getMessage() + ". Got this far: " + did, e.hint);
            }
        }
        JsonObject out = J.obj("potion", targetKey, "have", carriedKey(targetKey), "seconds", (System.currentTimeMillis() - started) / 1000);
        JsonArray steps = new JsonArray();
        did.forEach(steps::add);
        out.add("did", steps);
        return out;
    }

    private static void brewBatch(ItemStack target, int batch, List<String> did) {
        Chain chain = Bot.call(() -> chain(target, starts(batch)));
        if (chain == null) {
            throw new ApiError("no_recipe", "nothing brews into " + Bot.itemKey(target) + " from water or anything at hand", "potions() lists what exists; some need an ingredient from a mod");
        }
        String startKey = Bot.itemKey(chain.start());
        // The bottles to begin with.
        if (startKey.equals(Bot.itemKey(waterBottle()))) {
            int need = batch - carriedKey(startKey);
            if (need > 0) {
                Skills.call("obtain", J.obj("item", "minecraft:glass_bottle", "count", need));
                fillBottles(need);
                did.add("filled " + need + " bottles");
            }
        } else if (carriedKey(startKey) < batch) {
            takeKeyFromChests(startKey, batch - carriedKey(startKey));
            did.add("took " + startKey + " from a chest");
        }
        // The stand, and powder to light it. The stand is kept out of the hands of the obtains that follow, which pack up
        // benches as they finish.
        BlockPos stand = Skills.nearestBlock(state -> state.is(Blocks.BREWING_STAND), STAND_RADIUS);
        if (stand == null) {
            Skills.call("obtain", J.obj("item", "minecraft:brewing_stand", "count", 1));
            stand = Skills.placeNearby(Items.BREWING_STAND);
            Skills.notePlaced(stand);
            did.add("put down a brewing stand");
        }
        Skills.keep(stand);
        try {
            brewAt(stand, chain, startKey, batch, did);
        } finally {
            Skills.release(stand);
        }
        Skills.packUpBenches();
    }

    private static void brewAt(BlockPos stand, Chain chain, String startKey, int batch, List<String> did) {
        if (Bot.call(() -> Obtain.carried().getOrDefault(Items.BLAZE_POWDER, 0)) == 0) {
            Skills.call("obtain", J.obj("item", "minecraft:blaze_powder", "count", 1));
        }
        // One ingredient per step, fetched before the stand is opened each time. Whatever happens, the bottles come back
        // out at the end: a half-brewed potion carried is where the next try starts from.
        boolean bottlesIn = false;
        try {
            for (Step step : chain.steps()) {
                String ingredient = Bot.itemId(new ItemStack(step.ingredient()));
                if (Bot.call(() -> Obtain.carried().getOrDefault(step.ingredient(), 0)) == 0) {
                    Skills.call("obtain", J.obj("item", ingredient, "count", 1));
                }
                openStand(stand);
                try {
                    if (!bottlesIn) {
                        for (int slot = 0; slot < Math.min(batch, BOTTLE_SLOTS); slot++) {
                            Skills.call("windowPut", J.obj("slot", slot, "item", startKey, "count", 1));
                        }
                        bottlesIn = true;
                    }
                    if (Bot.call(() -> standMenu().getFuel()) <= 0) {
                        Skills.call("windowPut", J.obj("slot", FUEL_SLOT, "item", "minecraft:blaze_powder", "count", 1));
                    }
                    Skills.call("windowPut", J.obj("slot", INGREDIENT_SLOT, "item", ingredient, "count", 1));
                    String resultKey = Bot.itemKey(step.result());
                    // Done when the bottles have become the next thing. The ingredient slot may still hold what the
                    // ingredient left behind, such as the glass bottle from dragon's breath, which then comes out.
                    boolean brewed = Bot.waitUntil(() -> {
                        BrewingStandMenu menu = standMenu();
                        ItemStack first = menu.getSlot(0).getItem();
                        return menu.getBrewingTicks() == 0 && !first.isEmpty() && Bot.itemKey(first).equals(resultKey);
                    }, BREW_WAIT_MILLIS);
                    if (!brewed) {
                        throw new ApiError("brew_failed", "the stand did not turn the bottles into " + resultKey + " with " + ingredient,
                                "is there fuel, and did the right bottles go in?");
                    }
                    if (Bot.call(() -> !standMenu().getSlot(INGREDIENT_SLOT).getItem().isEmpty())) {
                        Skills.call("windowTake", J.obj("slot", INGREDIENT_SLOT));
                    }
                    did.add("brewed " + resultKey);
                } finally {
                    Skills.closeWindow();
                }
            }
        } finally {
            if (bottlesIn) {
                emptyStand(stand);
            }
        }
    }

    private static void emptyStand(BlockPos stand) {
        try {
            openStand(stand);
            for (int slot = 0; slot < BOTTLE_SLOTS; slot++) {
                int taken = slot;
                if (Bot.call(() -> !standMenu().getSlot(taken).getItem().isEmpty())) {
                    Skills.call("windowTake", J.obj("slot", taken));
                }
            }
        } catch (ApiError e) {
            if (Skills.isControlFailure(e)) {
                throw e;
            }
        } finally {
            Skills.closeWindow();
        }
    }

    private static BrewingStandMenu standMenu() {
        if (Bot.player().containerMenu instanceof BrewingStandMenu menu) {
            return menu;
        }
        throw new ApiError("no_window", "the brewing stand is not open");
    }

    private static void openStand(BlockPos stand) {
        Skills.closeWindow();
        Skills.walkInReach(stand);
        JsonObject opened = Skills.call("openBlock", J.obj("x", stand.getX(), "y", stand.getY(), "z", stand.getZ()));
        if (!opened.get("opened").getAsBoolean() || Bot.call(() -> !(Bot.player().containerMenu instanceof BrewingStandMenu))) {
            throw new ApiError("no_window", "the brewing stand at " + stand.toShortString() + " did not open");
        }
        Bot.waitTicks(2);
    }

    /** Glass bottles become water bottles at any water source: look at the water and use them. With none around, a bucket of water is poured out and taken back. */
    static void fillBottles(int count) {
        BlockPos water = Skills.nearestBlock(state -> state.getFluidState().isSource() && state.getFluidState().is(FluidTags.WATER), WATER_RADIUS);
        boolean poured = false;
        if (water == null) {
            water = pourWater();
            poured = true;
        }
        Skills.walkInReach(water);
        WindowFns.equip("minecraft:glass_bottle", "hand");
        String waterKey = Bot.itemKey(waterBottle());
        BlockPos source = water;
        for (int i = 0; i < count; i++) {
            Bot.readyForWorldInput();
            Bot.lookAt(Vec3.atCenterOf(source));
            int before = carriedKey(waterKey);
            ActionFns.tapUse();
            if (!Bot.waitUntil(() -> carriedKey(waterKey) > before, 1500)) {
                throw new ApiError("fill_failed", "the bottle did not fill at " + source.toShortString(), "stand closer to the water, looking at it");
            }
        }
        if (poured) {
            try {
                WindowFns.equip("minecraft:bucket", "hand");
                Bot.readyForWorldInput();
                Bot.lookAt(Vec3.atCenterOf(source));
                ActionFns.tapUse();
                Bot.waitUntil(() -> Bot.level().getFluidState(source).isEmpty(), 1500);
            } catch (ApiError e) {
                if (Skills.isControlFailure(e)) {
                    throw e;
                }
            }
        }
    }

    /** A bucket of water, had from a chest or a pond, poured onto the ground two blocks away. The source block it makes. */
    private static BlockPos pourWater() {
        Skills.call("obtain", J.obj("item", "minecraft:water_bucket", "count", 1));
        BlockPos ground = Bot.call(() -> {
            var level = Bot.level();
            BlockPos feet = Bot.player().blockPosition();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos spot = feet.relative(side, 2);
                BlockPos below = spot.below();
                if (level.getBlockState(spot).isAir() && level.getBlockState(spot.above()).isAir()
                        && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) && Bot.visiblePoint(below) != null) {
                    return below;
                }
            }
            return null;
        });
        if (ground == null) {
            throw new ApiError("no_room", "nowhere close to pour water out", "move to open ground");
        }
        WindowFns.equip("minecraft:water_bucket", "hand");
        Bot.readyForWorldInput();
        Bot.aimAtBlock(ground);
        ActionFns.tapUse();
        BlockPos water = ground.above();
        if (!Bot.waitUntil(() -> Bot.level().getFluidState(water).is(FluidTags.WATER), 1500)) {
            throw new ApiError("pour_failed", "the water did not appear at " + water.toShortString());
        }
        return water;
    }

    private static void takeKeyFromChests(String key, int count) {
        int left = count;
        for (Containers.Known known : Bot.call(() -> Containers.holdingKey(key, CHEST_RADIUS))) {
            if (left <= 0) {
                break;
            }
            try {
                left -= Skills.takeFromChestAt(known.pos(), key, Math.min(left, known.count(key)));
            } catch (ApiError e) {
                if (Skills.isControlFailure(e)) {
                    throw e;
                }
            }
        }
        if (left > 0) {
            throw new ApiError("not_found", "could not get " + count + " " + key + " out of the chests that had it");
        }
    }
}
