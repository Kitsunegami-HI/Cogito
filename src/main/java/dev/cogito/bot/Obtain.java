package dev.cogito.bot;

import dev.cogito.compat.Mc;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cogito.agent.AgentLoop;
import dev.cogito.agent.Reflexes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Get me an iron pickaxe" with no model: works out the tree of what is needed, does the first piece, and works it out
 * again from what is really carried now, so a broken tool, a fight or a death only means a different next piece. This is
 * the idea of AltoClef's task catalogue, driven by the game's own recipes so that it also covers mods' crafting.
 */
final class Obtain {
    private static final int MAX_DEPTH = 16;
    private static final int MAX_VISITS = 120_000;
    private static final int MAX_PIECES = 400;
    private static final int MAX_EXPLORES = 6;
    private static final int STRIKES = 3;
    private static final int SCAN_RADIUS = 40;
    private static final int SCAN_HEIGHT = 24;
    private static final int MOB_RADIUS = 48;
    private static final int SPARE_LOGS = 5;
    /** Seconds per block on top of breaking it: the step to the next one and picking up the drop. */
    private static final double PER_BLOCK_OVERHEAD = 1.0;
    /** Up to this many items go in one furnace; more gets spread over several. */
    private static final int SMALL_SMELT = 8;
    /** How far a known chest may be to count as a source. */
    private static final int TAKE_RADIUS = 96;
    private static final double WALK_BLOCKS_PER_SECOND = 4.0;
    /** Opening a chest and taking from it, in seconds. */
    private static final double TAKE_OVERHEAD = 3.0;
    private static final int DEAD_ENDS_SHOWN = 8;
    private static final long MINE_TIMEOUT_SECONDS = 120;
    private static final Set<Item> JUNK = Bot.itemsPresent("dirt", "gravel", "andesite", "diorite", "granite", "tuff",
            "netherrack", "rotten_flesh", "leaf_litter", "short_grass", "coarse_dirt", "wheat_seeds");

    /** One piece of the tree: something the body can go and do. */
    sealed interface Task permits Mine, Hunt, Craft, Smelt, Take {
        String key();

        String describe();
    }

    record Take(Item item, int count, BlockPos pos) implements Task {
        public String key() {
            return "take:" + idOf(item);
        }

        public String describe() {
            return "take " + count + " " + idOf(item) + " from the chest at " + pos.toShortString();
        }
    }

    record Mine(Item item, List<Block> blocks, int count) implements Task {
        public String key() {
            return "mine:" + idOf(item);
        }

        public String describe() {
            return "mine " + count + " " + idOf(item);
        }
    }

    record Hunt(Item item, List<EntityType<?>> mobs, int count) implements Task {
        public String key() {
            return "hunt:" + idOf(item);
        }

        public String describe() {
            return "hunt for " + count + " " + idOf(item);
        }
    }

    record Craft(Item item, int count) implements Task {
        public String key() {
            return "craft:" + idOf(item);
        }

        public String describe() {
            return "craft " + count + " " + idOf(item);
        }
    }

    record Smelt(Item item, List<Item> inputs, int count) implements Task {
        public String key() {
            return "smelt:" + idOf(item);
        }

        public String describe() {
            return "smelt " + count + " " + idOf(item);
        }
    }

    private record Found(double cost, List<Task> tasks, Map<Item, Integer> inventory) {}

    private Obtain() {}

    private static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    // ---- working out the tree ----

    /** What the world around offers right now: the planner's idea of "near". */
    private static final class Surroundings {
        final Map<Block, Double> nearest = new HashMap<>();
        final Set<EntityType<?>> mobs = new HashSet<>();
        final String dimension;

        Surroundings() {
            var level = Bot.level();
            dimension = Mc.dimension(level);
            BlockPos center = Bot.player().blockPosition();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int minY = Math.max(Mc.minY(level), center.getY() - SCAN_HEIGHT);
            int maxY = Math.min(Mc.maxY(level), center.getY() + SCAN_HEIGHT);
            boolean anyLost = Skills.anyBenchLost();
            for (int x = center.getX() - SCAN_RADIUS; x <= center.getX() + SCAN_RADIUS; x++) {
                for (int z = center.getZ() - SCAN_RADIUS; z <= center.getZ() + SCAN_RADIUS; z++) {
                    if (!level.hasChunk(x >> 4, z >> 4)) {
                        continue;
                    }
                    for (int y = minY; y <= maxY; y++) {
                        cursor.set(x, y, z);
                        BlockState state = level.getBlockState(cursor);
                        // A bench that cannot be got to is as good as not there, or the plan keeps counting on it.
                        if (!state.isAir() && !(anyLost && Skills.benchIsLost(cursor))) {
                            double distance = cursor.distSqr(center);
                            nearest.merge(state.getBlock(), distance, Math::min);
                        }
                    }
                }
            }
            for (Entity entity : level.entitiesForRendering()) {
                if (entity.isAlive() && entity.distanceTo(Bot.player()) <= MOB_RADIUS) {
                    mobs.add(entity.getType());
                }
            }
        }

        Double distanceTo(Collection<Block> blocks) {
            Double best = null;
            for (Block block : blocks) {
                Double distance = nearest.get(block);
                if (distance != null && (best == null || distance < best)) {
                    best = distance;
                }
            }
            return best == null ? null : Math.sqrt(best);
        }

        boolean sees(Block block) {
            return nearest.containsKey(block);
        }
    }

    /** Tries every way of getting each thing from what is carried and nearby, and keeps the cheapest. Client thread. */
    private static final class Search {
        final RecipeIndex recipes = RecipeIndex.get();
        final Surroundings around = new Surroundings();
        final Set<String> banned;
        final Set<String> deadEnds = new HashSet<>();
        final Set<Item> path = new HashSet<>();
        int visits;
        /** How deep inside the search for a tool this is; tools for tools are not weighed up. */
        int toolSearches;

        Search(Set<String> banned) {
            this.banned = banned;
        }

        Found solve(Item item, int count, Map<Item, Integer> inventory) {
            int have = inventory.getOrDefault(item, 0);
            if (have >= count) {
                Map<Item, Integer> after = new HashMap<>(inventory);
                after.put(item, have - count);
                return new Found(0, List.of(), after);
            }
            int need = count - have;
            if (path.contains(item) || path.size() > MAX_DEPTH || ++visits > MAX_VISITS) {
                return null;
            }
            Map<Item, Integer> rest = new HashMap<>(inventory);
            rest.put(item, 0);
            path.add(item);
            // Chests that hold it are one way, on their own or for part of it; everything else is weighed against that.
            Found best = madeOrFound(item, need, rest);
            Found chests = viaTake(item, need, rest);
            if (chests != null) {
                int taken = 0;
                for (Task task : chests.tasks()) {
                    taken += ((Take) task).count();
                }
                if (taken >= need) {
                    best = cheaper(best, chests);
                } else {
                    Found remainder = madeOrFound(item, need - taken, chests.inventory());
                    if (remainder != null) {
                        best = cheaper(best, new Found(chests.cost() + remainder.cost(), join(chests.tasks(), remainder.tasks()), remainder.inventory()));
                    }
                }
            }
            path.remove(item);
            if (best == null) {
                deadEnds.add(idOf(item));
            }
            return best;
        }

        private Found madeOrFound(Item item, int need, Map<Item, Integer> rest) {
            Found best = null;
            for (WindowFns.CraftPlan plan : recipes.crafting(item)) {
                best = cheaper(best, viaCraft(item, need, plan, rest));
            }
            for (RecipeIndex.Smelt smelt : recipes.smelting(item)) {
                best = cheaper(best, viaSmelt(item, need, smelt, rest));
            }
            best = cheaper(best, viaMine(item, need, rest));
            best = cheaper(best, viaHunt(item, need, rest));
            return best;
        }

        /** Out of the chests known to hold it, nearest first, as much as they have. Null when none does. */
        private Found viaTake(Item item, int need, Map<Item, Integer> inventory) {
            if (banned.contains("take:" + idOf(item))) {
                return null;
            }
            List<Task> tasks = new ArrayList<>();
            double cost = 0;
            int got = 0;
            BlockPos here = Bot.player().blockPosition();
            for (Containers.Known known : Containers.holding(item, TAKE_RADIUS)) {
                int take = Math.min(known.count(item), need - got);
                if (take <= 0) {
                    break;
                }
                tasks.add(new Take(item, take, known.pos()));
                cost += Math.sqrt(known.pos().distSqr(here)) / WALK_BLOCKS_PER_SECOND + TAKE_OVERHEAD;
                got += take;
            }
            if (tasks.isEmpty()) {
                return null;
            }
            Map<Item, Integer> after = new HashMap<>(inventory);
            after.merge(item, got, Integer::sum);
            return new Found(cost, tasks, after);
        }

        private static Found cheaper(Found a, Found b) {
            return a == null || (b != null && b.cost() < a.cost()) ? b : a;
        }

        private static List<Task> join(List<Task> a, List<Task> b) {
            List<Task> out = new ArrayList<>(a);
            out.addAll(b);
            return out;
        }

        /** Any mix of the options will do. What is carried goes first; the rest comes from the cheapest single option. */
        private Found chooseOption(List<Item> options, int count, Map<Item, Integer> inventory) {
            Map<Item, Integer> left = new HashMap<>(inventory);
            int needed = count;
            for (Item option : options) {
                int take = Math.min(left.getOrDefault(option, 0), needed);
                if (take > 0) {
                    left.merge(option, -take, Integer::sum);
                    needed -= take;
                }
            }
            if (needed == 0) {
                return new Found(0, List.of(), left);
            }
            Found best = null;
            for (Item option : options) {
                // A tool or armor piece is only worth melting down when it is already carried.
                if (left.getOrDefault(option, 0) <= 0 && new ItemStack(option).isDamageableItem()) {
                    continue;
                }
                best = cheaper(best, solve(option, needed, left));
            }
            return best;
        }

        /** A block to work at: use one nearby, keep one that is carried, or get one. It is not used up. */
        private Found bench(Block block, Item item, Map<Item, Integer> inventory) {
            if (around.sees(block) || inventory.getOrDefault(item, 0) > 0) {
                return new Found(0, List.of(), inventory);
            }
            Found got = solve(item, 1, inventory);
            if (got == null) {
                return null;
            }
            Map<Item, Integer> after = new HashMap<>(got.inventory());
            after.merge(item, 1, Integer::sum);
            return new Found(got.cost(), got.tasks(), after);
        }

        private Found viaCraft(Item item, int need, WindowFns.CraftPlan plan, Map<Item, Integer> inventory) {
            if (banned.contains("craft:" + idOf(item))) {
                return null;
            }
            int rounds = (int) Math.ceil((double) need / plan.makes());
            Map<List<Item>, Integer> groups = new HashMap<>();
            for (List<ItemStack> cell : plan.cells()) {
                if (!cell.isEmpty()) {
                    List<Item> options = new ArrayList<>();
                    for (ItemStack stack : cell) {
                        options.add(stack.getItem());
                    }
                    groups.merge(options, rounds, Integer::sum);
                }
            }
            double cost = 0.3 * rounds;
            List<Task> tasks = List.of();
            Map<Item, Integer> current = inventory;
            if (plan.width() > 2 || plan.height() > 2) {
                Found table = bench(Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE, current);
                if (table == null) {
                    return null;
                }
                cost += table.cost();
                tasks = join(tasks, table.tasks());
                current = table.inventory();
            }
            for (Map.Entry<List<Item>, Integer> group : groups.entrySet()) {
                Found part = chooseOption(group.getKey(), group.getValue(), current);
                if (part == null) {
                    return null;
                }
                cost += part.cost();
                tasks = join(tasks, part.tasks());
                current = part.inventory();
            }
            Map<Item, Integer> after = new HashMap<>(current);
            after.merge(item, rounds * plan.makes() - need, Integer::sum);
            return new Found(cost, join(tasks, List.of(new Craft(item, need))), after);
        }

        private Found viaSmelt(Item item, int need, RecipeIndex.Smelt smelt, Map<Item, Integer> inventory) {
            if (banned.contains("smelt:" + idOf(item))) {
                return null;
            }
            Found furnace = bench(Blocks.FURNACE, Items.FURNACE, inventory);
            if (furnace == null) {
                return null;
            }
            double cost = furnace.cost() + need * 3.0;
            List<Task> tasks = furnace.tasks();
            Found input = chooseOption(smelt.inputs(), need, furnace.inventory());
            if (input == null) {
                return null;
            }
            cost += input.cost();
            tasks = join(tasks, input.tasks());
            Found fuel = fuel(need, input.inventory());
            if (fuel == null) {
                return null;
            }
            return new Found(cost + fuel.cost(), join(join(tasks, fuel.tasks()), List.of(new Smelt(item, smelt.inputs(), need))), fuel.inventory());
        }

        /** Enough to burn: coal lasts eight items and wood one and a half, whichever is cheaper to come by. */
        private Found fuel(int need, Map<Item, Integer> inventory) {
            double units = 0;
            Map<Item, Integer> left = new HashMap<>(inventory);
            for (Map.Entry<Item, Integer> entry : inventory.entrySet()) {
                double each = fuelValue(entry.getKey());
                while (each > 0 && units < need && left.getOrDefault(entry.getKey(), 0) > 0) {
                    units += each;
                    left.merge(entry.getKey(), -1, Integer::sum);
                }
            }
            if (units >= need) {
                return new Found(0, List.of(), left);
            }
            double shortfall = need - units;
            Found coal = solve(Items.COAL, (int) Math.ceil(shortfall / 8), left);
            List<Item> logs = new ArrayList<>();
            BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.LOGS_THAT_BURN).forEach(holder -> logs.add(holder.value()));
            Found wood = chooseOption(logs, (int) Math.ceil(shortfall / 1.5), left);
            return cheaper(coal, wood);
        }

        /**
         * Mining is costed in seconds, with the tool it would be done with. A better tool is one more option, its own cost
         * included: so chopping a forest gets an axe first and three logs do not, and a stone pickaxe gets made for a long dig.
         */
        private Found viaMine(Item item, int need, Map<Item, Integer> inventory) {
            // A chest or a machine that stands somewhere is someone's, or holds something: it is not where such a thing comes from.
            List<Block> blocks = Sources.blocksDropping(item).stream().filter(block -> !Sources.isKeptWhole(block)).toList();
            if (blocks.isEmpty() || banned.contains("mine:" + idOf(item))) {
                return null;
            }
            Double distance = around.distanceTo(blocks);
            if (distance == null && (!Sources.natural(blocks.get(0)) || !Sources.grows(blocks.get(0), around.dimension))) {
                return null;
            }
            BlockState state = blocks.get(0).defaultBlockState();
            double walk = distance == null ? 60 : distance / 8;
            Found best = null;
            // Inside the search for a tool, no further tools are weighed up: an axe for the handle of a pickaxe is not worth
            // the search it costs, and the branching would otherwise run away.
            for (Item tool : Sources.toolChoices(state, inventory, toolSearches == 0)) {
                Map<Item, Integer> current = inventory;
                List<Task> tasks = List.of();
                double cost = walk;
                if (tool != null && inventory.getOrDefault(tool, 0) == 0) {
                    toolSearches++;
                    Found got;
                    try {
                        got = solve(tool, 1, current);
                    } finally {
                        toolSearches--;
                    }
                    if (got == null) {
                        continue;
                    }
                    Map<Item, Integer> after = new HashMap<>(got.inventory());
                    after.merge(tool, 1, Integer::sum);
                    current = after;
                    cost += got.cost();
                    tasks = got.tasks();
                }
                double seconds = Sources.secondsToBreak(state, Sources.bestToolFor(state, current));
                if (seconds < 0) {
                    continue;
                }
                cost += need * (seconds + PER_BLOCK_OVERHEAD) / Sources.dropChance(item);
                best = cheaper(best, new Found(cost, join(tasks, List.of(new Mine(item, blocks, need))), current));
            }
            return best;
        }

        private Found viaHunt(Item item, int need, Map<Item, Integer> inventory) {
            List<EntityType<?>> mobs = Sources.mobsDropping(item);
            if (mobs.isEmpty() || banned.contains("hunt:" + idOf(item))) {
                return null;
            }
            boolean near = mobs.stream().anyMatch(around.mobs::contains);
            return new Found(need * 6.0 + (near ? 0 : 90), List.of(new Hunt(item, mobs, need)), inventory);
        }
    }

    private static double fuelValue(Item item) {
        ItemStack stack = new ItemStack(item);
        if (stack.is(ItemTags.COALS)) {
            return 8;
        }
        return stack.is(ItemTags.LOGS_THAT_BURN) || stack.is(ItemTags.PLANKS) ? 1.5 : 0;
    }

    /** What a plan can count on: what is carried, and what the furnaces are about to give. */
    private static Map<Item, Integer> plannable() {
        Map<Item, Integer> out = carried();
        Skills.coming().forEach((item, count) -> out.merge(item, count, Integer::sum));
        return out;
    }

    static Map<Item, Integer> carried() {
        Map<Item, Integer> out = new HashMap<>();
        var inventory = Bot.player().getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                out.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return out;
    }

    // ---- previewing ----

    /** The tree without doing any of it, for a planner or a person who wants to know what it would take. */
    static JsonElement preview(JsonObject args) {
        Item item = Bot.item(J.str(args, "item"));
        int count = J.integer(args, "count", 1);
        return Bot.call(() -> {
            Search search = new Search(Set.of());
            Found found = search.solve(item, count, plannable());
            JsonArray pieces = new JsonArray();
            if (found != null) {
                found.tasks().forEach(task -> pieces.add(task.describe()));
            }
            JsonObject out = J.obj("possible", found != null, "pieces", pieces, "visits", search.visits);
            if (found == null) {
                JsonArray dead = new JsonArray();
                search.deadEnds.forEach(dead::add);
                out.add("no_source_for", dead);
            }
            return out;
        });
    }

    /**
     * Client thread. Can all of this be had from here, and what does it come down to in raw work? Goes through the items
     * one after another on one imagined inventory, as gathering them would use each other's leftovers.
     */
    static JsonObject check(Map<Item, Integer> wanted) {
        Map<Item, Integer> inventory = carried();
        Search search = new Search(Set.of());
        JsonArray cannot = new JsonArray();
        Map<String, Integer> raw = new HashMap<>();
        double seconds = 0;
        for (Map.Entry<Item, Integer> entry : wanted.entrySet()) {
            search.visits = 0;
            Found found = search.solve(entry.getKey(), entry.getValue(), inventory);
            if (found == null) {
                cannot.add(idOf(entry.getKey()));
                continue;
            }
            inventory = found.inventory();
            for (Task task : found.tasks()) {
                switch (task) {
                    case Mine mine -> {
                        raw.merge(idOf(mine.item()), mine.count(), Integer::sum);
                        seconds += mine.count() * 2.5;
                    }
                    case Hunt hunt -> {
                        raw.merge(idOf(hunt.item()), hunt.count(), Integer::sum);
                        seconds += hunt.count() * 15;
                    }
                    case Smelt smelt -> seconds += smelt.count() * 10.5;
                    case Craft craft -> seconds += 3;
                    case Take take -> seconds += TAKE_OVERHEAD;
                }
            }
        }
        JsonObject rawWork = new JsonObject();
        raw.forEach(rawWork::addProperty);
        return J.obj("can_get_everything", cannot.isEmpty(), "cannot_get", cannot, "raw_work", rawWork, "gather_minutes", Math.round(seconds / 6) / 10.0);
    }

    // ---- doing it ----

    static JsonElement run(JsonObject args) {
        Item item = Bot.item(J.str(args, "item"));
        String itemId = idOf(item);
        int count = J.integer(args, "count", 1);
        // A bottle of something is brewed, not crafted: the brewing skill works the chain out and comes back here for each ingredient.
        if (args.has("potion") || (item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) && J.str(args, "item").contains("#")) {
            String potion = args.has("potion") ? J.str(args, "potion") : J.str(args, "item").substring(J.str(args, "item").indexOf('#') + 1);
            return Brewing.brewFn(J.obj("potion", potion, "form", itemId, "count", count));
        }
        long started = System.currentTimeMillis();
        long deadline = started + J.integer(args, "timeout_s", 600) * 1000L;
        Set<String> banned = new HashSet<>();
        Map<String, Integer> strikes = new HashMap<>();
        Map<String, Integer> explored = new HashMap<>();
        JsonArray did = new JsonArray();
        String lastTrouble = null;
        boolean finished = false;
        // A caller with several things to get says so, and comes back for what the furnaces make instead of waiting now.
        boolean leaveCooking = J.bool(args, "leave_cooking", false);
        boolean cooking = false;
        try {
            for (int piece = 0; piece < MAX_PIECES; piece++) {
                Bot.requireControl();
                if (Bot.call(() -> carried().getOrDefault(item, 0)) >= count) {
                    finished = true;
                    break;
                }
                // The last of it is in a furnace: go and get it, unless the caller has other things to do meanwhile.
                if (Skills.anythingCooking() && Bot.call(() -> plannable().getOrDefault(item, 0)) >= count) {
                    if (leaveCooking) {
                        cooking = true;
                        break;
                    }
                    say("waiting on the furnace for the last " + itemId);
                    Skills.collectSmelt(true);
                    continue;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new ApiError("timeout", "still no " + count + " " + itemId + " after " + J.integer(args, "timeout_s", 600) + "s; did: " + did,
                            lastTrouble);
                }
                Found plan;
                Set<String> deadEnds = new HashSet<>();
                plan = Bot.call(() -> {
                    Search search = new Search(banned);
                    Found found = search.solve(item, count, plannable());
                    deadEnds.addAll(search.deadEnds);
                    return found;
                });
                if (plan == null || plan.tasks().isEmpty()) {
                    List<String> shown = new ArrayList<>(deadEnds);
                    // The ingredients of the thing itself first; the long tail of everything else looked at adds little.
                    shown.sort(Comparator.comparingInt((String id) -> id.equals(itemId) ? 0 : 1).thenComparing(id -> id));
                    String noSource = shown.size() <= DEAD_ENDS_SHOWN ? shown.toString()
                            : shown.subList(0, DEAD_ENDS_SHOWN) + " and " + (shown.size() - DEAD_ENDS_SHOWN) + " more";
                    throw new ApiError("no_way", "no way to get " + itemId + " from here. No source for: " + noSource + ". Did: " + did
                            + (lastTrouble == null ? "" : ". Last trouble: " + lastTrouble),
                            "it may need another dimension, a trade, or something to be found first");
                }
                // While a furnace works, the first thing that can be done now, rather than standing by it.
                Task task = pickDoable(plan.tasks());
                if (task == null) {
                    if (Skills.anythingCooking()) {
                        if (leaveCooking) {
                            cooking = true;
                            break;
                        }
                        say("nothing to do until the furnace is done");
                        Skills.collectSmelt(true);
                        continue;
                    }
                    task = plan.tasks().get(0);
                }
                Map<Item, Integer> before = Bot.call(Obtain::carried);
                if (!(task instanceof Craft) && !(task instanceof Smelt)) {
                    Skills.packUpBenches();
                }
                say(task.describe() + " for " + itemId);
                try {
                    perform(task, plan, explored, banned, deadline);
                } catch (ApiError e) {
                    if (e.kind.equals("interrupted_by_reflex")) {
                        Reflexes.rejoin();
                        continue;
                    }
                    // Nothing here can settle what to part with, and trying again only wastes the attempt on a dead end.
                    if (Skills.isControlFailure(e) || e.kind.equals("inventory_full")) {
                        throw e;
                    }
                    lastTrouble = task.key() + ": " + e.getMessage();
                    say("trouble with " + task.describe() + ": " + e.getMessage());
                }
                Map<Item, Integer> after = Bot.call(Obtain::carried);
                if (after.equals(before)) {
                    int count2 = strikes.merge(task.key(), 1, Integer::sum);
                    if (count2 >= STRIKES) {
                        banned.add(task.key());
                        say("giving up on: " + task.key());
                    }
                } else {
                    strikes.remove(task.key());
                    did.add(task.describe());
                }
                try {
                    Skills.keepFed();
                    Skills.keepArmed();
                    Skills.tidyWhenCrowded();
                } catch (ApiError e) {
                    if (!e.kind.equals("interrupted_by_reflex")) {
                        throw e;
                    }
                    Reflexes.rejoin();
                }
            }
        } finally {
            if (finished) {
                // Done, so nothing is left behind: whatever still cooks comes out, and the furnaces come along.
                if (Skills.anythingCooking()) {
                    Skills.collectSmelt(true);
                }
                Skills.packUpBenches();
            }
        }
        int have = Bot.call(() -> carried().getOrDefault(item, 0));
        if (cooking) {
            JsonObject out = J.obj("item", itemId, "have", have, "cooking", true, "seconds", (System.currentTimeMillis() - started) / 1000, "did", did);
            out.addProperty("note", "the rest is in a furnace; call again to fetch it");
            return out;
        }
        if (!finished) {
            throw new ApiError("no_way", "too many pieces of work for " + itemId + "; did: " + did);
        }
        return J.obj("item", itemId, "have", have, "seconds", (System.currentTimeMillis() - started) / 1000, "did", did);
    }

    private static void say(String text) {
        AgentLoop.get().noteSkill(text);
    }

    private static void perform(Task task, Found plan, Map<String, Integer> explored, Set<String> banned, long deadline) {
        switch (task) {
            case Mine mine -> mine(mine, plan, explored, banned, deadline);
            case Hunt hunt -> Skills.hunt(hunt.mobs(), hunt.count(), hunt.item());
            case Craft craft -> Skills.craft(craft.item(), craft.count());
            case Smelt smelt -> smelt(smelt);
            case Take take -> Skills.takeFromChestAt(take.pos(), idOf(take.item()), take.count());
        }
    }

    /**
     * The first piece that can be done with what is really carried. A craft that needs what a furnace is still making is
     * not one; neither is a smelt with every furnace busy. Null means the only thing left is to wait on a furnace.
     */
    private static Task pickDoable(List<Task> tasks) {
        for (Task task : tasks) {
            boolean doable = switch (task) {
                case Mine mine -> true;
                case Hunt hunt -> true;
                case Take take -> true;
                case Craft craft -> Bot.call(() -> Skills.craftableNow(craft.item()));
                case Smelt smelt -> Bot.call(() -> {
                    Map<Item, Integer> have = carried();
                    return smelt.inputs().stream().anyMatch(input -> have.getOrDefault(input, 0) > 0);
                }) && Skills.furnaceAvailable();
            };
            if (doable) {
                return task;
            }
        }
        return null;
    }

    private static void mine(Mine mine, Found plan, Map<String, Integer> explored, Set<String> banned, long deadline) {
        // The rest of the tree may want the same thing again: fetch it all now, instead of walking back for it.
        int total = 0;
        for (Task other : plan.tasks()) {
            if (other instanceof Mine same && same.item() == mine.item()) {
                total += same.count();
            }
        }
        // Wood runs out at the worst moment, deep underground, and takes little extra time up here.
        if (new ItemStack(mine.item()).is(ItemTags.LOGS)) {
            total = Math.max(total, SPARE_LOGS);
        }
        makeRoom(mine.item());
        JsonArray names = new JsonArray();
        mine.blocks().forEach(block -> names.add(BuiltInRegistries.BLOCK.getKey(block).toString()));
        long secondsLeft = Math.max(5, (deadline - System.currentTimeMillis()) / 1000);
        JsonObject args = J.obj("blocks", names, "count", total, "until_item", idOf(mine.item()), "timeout_s", Math.min(MINE_TIMEOUT_SECONDS, secondsLeft));
        Integer level = Sources.oreLevel(mine.item());
        if (level != null) {
            args.addProperty("ore_level", level);
        }
        JsonObject result = Skills.call("collectBlock", args);
        if ("inventory_full".equals(J.str(result, "stopped_because", ""))) {
            throw new ApiError("inventory_full", "every slot of the inventory is taken, so what is dug stays on the ground",
                    J.str(result, "hint", "decide what you need least and call dropItems with it"));
        }
        JsonObject gained = result.getAsJsonObject("gained");
        boolean nothing = gained == null || !gained.has(idOf(mine.item()));
        if (nothing && "nothing_left_in_range".equals(J.str(result, "stopped_because", ""))) {
            int tries = explored.merge(mine.key(), 1, Integer::sum);
            if (tries > MAX_EXPLORES) {
                banned.add(mine.key());
                say("searched " + MAX_EXPLORES + " times and found no " + idOf(mine.item()));
            } else {
                Skills.explore(tries);
            }
        }
    }

    private static void smelt(Smelt smelt) {
        Item input = Bot.call(() -> {
            Map<Item, Integer> carried = carried();
            for (Item option : smelt.inputs()) {
                if (carried.getOrDefault(option, 0) > 0) {
                    return option;
                }
            }
            throw new ApiError("missing_ingredients", "nothing to smelt into " + idOf(smelt.item()));
        });
        // Lit and left: the rest of the plan goes on meanwhile, and the output is fetched when something needs it. A big
        // batch is split over the furnaces there are room for, as a person puts down a second furnace for a lot of stone.
        int remaining = smelt.count();
        while (remaining > 0 && Skills.furnaceAvailable()) {
            int furnaces = Math.max(1, Skills.furnaceSlotsLeft());
            int batch = remaining <= SMALL_SMELT ? remaining : (int) Math.ceil((double) remaining / furnaces);
            Skills.Cooking cooking = Skills.startSmelt(input, Math.min(batch, remaining), null);
            if (cooking.count() <= 0) {
                break;
            }
            remaining -= cooking.count();
        }
    }

    /** A full inventory stalls mining in a way that looks like nothing at all, so room is made first. */
    private static void makeRoom(Item wanted) {
        List<Item> junk = Bot.call(() -> {
            var inventory = Bot.player().getInventory();
            int free = 0;
            List<Item> out = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) {
                    free++;
                } else if (JUNK.contains(stack.getItem()) && stack.getItem() != wanted && !out.contains(stack.getItem())) {
                    out.add(stack.getItem());
                }
            }
            return free > 2 ? List.<Item>of() : out;
        });
        if (!junk.isEmpty()) {
            JsonArray items = new JsonArray();
            junk.forEach(item -> items.add(idOf(item)));
            Skills.call("dropItems", J.obj("items", items));
        }
    }
}
