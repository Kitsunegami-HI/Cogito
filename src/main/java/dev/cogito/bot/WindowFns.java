package dev.cogito.bot;

import dev.cogito.compat.Mc;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import dev.cogito.control.VirtualInput;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import dev.cogito.compat.Click;
import dev.cogito.compat.RecipeView;
import dev.cogito.compat.Recipes;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Windows, operated the way a person does: the real screen opens, the cursor visibly moves to each slot and
 * clicks. This is where Mineflayer's headless window calls get their picture back.
 */
final class WindowFns {
    private WindowFns() {}

    static void register() {
        Api.read("window()", "the open window: type, title, items with slot numbers, cursor stack, trades",
                args -> Bot.call(WindowFns::summary));
        for (String name : new String[] {"openBlock", "openContainer", "openChest", "openFurnace", "openDispenser",
                "openEnchantmentTable", "openAnvil"}) {
            Api.act(name + "(x,y,z)", "right-click a block in reach and return its window", args -> {
                BlockPos pos = Bot.pos(args);
                Bot.readyForWorldInput();
                Bot.aimAtBlock(pos);
                Bot.run(() -> VirtualInput.tapMapping(ActionFns.options().keyUse));
                JsonObject out = afterClick();
                if (out.get("opened").getAsBoolean()) {
                    // What a chest shows is remembered, from now until it is next seen.
                    lastOpenedBlock = pos;
                    Bot.run(() -> Containers.recordWindow(pos));
                }
                return out;
            });
        }
        for (String name : new String[] {"openEntity", "openVillager"}) {
            Api.act(name + "(id)", "right-click an entity in reach and return its window", args -> {
                Bot.readyForWorldInput();
                Bot.aimAtEntity(Bot.call(() -> Bot.entityById(J.integer(args, "id"))));
                Bot.run(() -> VirtualInput.tapMapping(ActionFns.options().keyUse));
                return afterClick();
            });
        }
        Api.act("openInventory()", "open your inventory with the inventory key", args -> {
            openInventory();
            return Bot.call(WindowFns::summary);
        });
        Api.act("closeWindow()", "close the open window with Escape", args -> {
            close();
            return null;
        });
        Api.act("clickWindow(slot,[button=0],[mode=0])", "move the cursor to a slot and click; button 0 left 1 right; mode 0 click, 1 shift-click, 2 hotbar swap (button=0-8), 4 drop, 6 double-click", args -> {
            clickMode(J.integer(args, "slot"), J.integer(args, "button", 0), J.integer(args, "mode", 0));
            return Bot.call(WindowFns::summary);
        });
        Api.act("moveSlotItem(from,to)", "pick up the stack in one slot and put it down in another", args -> {
            int from = J.integer(args, "from");
            click(from, 0, false);
            click(J.integer(args, "to"), 0, false);
            returnCarried(from);
            return Bot.call(WindowFns::summary);
        });
        Api.act("transfer(item,[count],to)", "move items between the window and your inventory; to is \"window\" or \"inventory\"", args -> transfer(args));
        Api.act("windowPut(slot,item,[count])", "put items from your inventory into one exact window slot (furnace input, fuel)", args -> windowPut(args));
        Api.act("windowTake(slot)", "shift-click a window slot's stack into your inventory (furnace output, chest)", args -> {
            click(J.integer(args, "slot"), 0, true);
            return Bot.call(WindowFns::summary);
        });
        Api.act("putAway(slot)", "shift-click a slot so its stack goes to the other side", args -> {
            click(J.integer(args, "slot"), 0, true);
            return null;
        });
        Api.read("recipesFor(item)", "what makes an item: crafting recipes (size, ingredients, whether you can make it now) and machine recipes of any mod (its type names the machine)",
                args -> Bot.call(() -> recipesFor(Bot.item(J.str(args, "item")))));
        Api.read("recipesUsing(item)", "machine recipes that take an item as an input: what you could make from it, and in which kind of machine",
                args -> Bot.call(() -> MachineRecipes.using(Bot.item(J.str(args, "item")), 12)));
        Api.read("recipesAll([limit=50])", "crafting recipes you can make with what you carry",
                args -> Bot.call(() -> recipesAll(J.integer(args, "limit", 50))));
        Api.act("craft(item,[count=1])", "craft by hand in a visible grid: opens the inventory, or a crafting table in reach for 3x3 recipes; smelting is windowPut on a furnace", args -> craft(args));
        Api.act("trade(index,[times=1])", "in an open villager window: select an offer and take its result", args -> trade(args));
    }

    // ---- reading ----

    static JsonObject afterClick() {
        boolean opened = Bot.waitUntil(() -> Bot.mc().screen != null, 500);
        return Bot.call(() -> opened ? J.obj("opened", true, "window", summary())
                : J.obj("opened", false, "crosshair", Bot.cursorText(), "held", Bot.stackJson(Bot.player().getMainHandItem())));
    }

    static JsonObject summary() {
        Screen screen = Bot.mc().screen;
        if (screen == null) {
            return J.obj("window", "none");
        }
        JsonObject out = J.obj("type", screen.getClass().getSimpleName(), "title", screen.getTitle().getString());
        if (screen instanceof AbstractContainerScreen<?> container) {
            AbstractContainerMenu menu = container.getMenu();
            JsonArray own = new JsonArray();
            JsonArray yours = new JsonArray();
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    JsonObject row = Bot.stackJson(stack);
                    row.addProperty("slot", i);
                    (slot.container instanceof Inventory ? yours : own).add(row);
                }
            }
            out.addProperty("slot_count", menu.slots.size());
            out.add("window_items", own);
            out.add("your_items", yours);
            ItemStack carried = menu.getCarried();
            if (!carried.isEmpty()) {
                out.add("cursor", Bot.stackJson(carried));
            }
            if (menu instanceof MerchantMenu merchant) {
                out.add("trades", trades(merchant));
            }
        }
        return out;
    }

    private static JsonArray trades(MerchantMenu menu) {
        JsonArray out = new JsonArray();
        int index = 0;
        for (MerchantOffer offer : menu.getOffers()) {
            JsonObject row = J.obj("index", index++, "pay", Bot.stackJson(offer.getCostA()),
                    "get", Bot.stackJson(offer.getResult()), "sold_out", offer.isOutOfStock());
            if (!offer.getCostB().isEmpty()) {
                row.add("pay2", Bot.stackJson(offer.getCostB()));
            }
            out.add(row);
        }
        return out;
    }

    // ---- window primitives ----

    private static AbstractContainerScreen<?> containerScreen() {
        if (Bot.mc().screen instanceof AbstractContainerScreen<?> screen) {
            return screen;
        }
        throw new ApiError("no_window", "no item window is open", "openBlock, openEntity or openInventory first");
    }

    private static double[] guiToWindow(double guiX, double guiY) {
        Window window = Bot.mc().getWindow();
        return new double[] {guiX * window.getScreenWidth() / window.getGuiScaledWidth(),
                guiY * window.getScreenHeight() / window.getGuiScaledHeight()};
    }

    /** Worker thread. Glides the cursor over a slot and clicks it, as a person would. */
    static void click(int slotIndex, int button, boolean shift) {
        Bot.requireControl();
        boolean reachable = Bot.call(() -> {
            AbstractContainerScreen<?> screen = containerScreen();
            AbstractContainerMenu menu = screen.getMenu();
            if (slotIndex < 0 || slotIndex >= menu.slots.size()) {
                throw new ApiError("bad_argument", "slot " + slotIndex + " is not in this window (0-"
                        + (menu.slots.size() - 1) + ")");
            }
            double[] gui = slotCenter(screen, menu.slots.get(slotIndex));
            if (!onScreen(gui)) {
                return false;
            }
            double[] target = guiToWindow(gui[0], gui[1]);
            VirtualInput.glideCursorTo(target[0], target[1]);
            return true;
        });
        if (reachable) {
            pressHere(button, shift);
        } else {
            slotInput(slotIndex, button, shift ? Click.QUICK_MOVE : Click.PICKUP);
        }
    }

    /** Where a slot's middle is, in GUI coordinates, going by what the slot says. */
    private static double[] slotCenter(AbstractContainerScreen<?> screen, Slot slot) {
        var accessor = (dev.cogito.mixin.ContainerScreenAccessor) screen;
        return new double[] {accessor.cogito$leftPos() + slot.x + 8, accessor.cogito$topPos() + slot.y + 8};
    }

    /** Whether the cursor can get there. A minimized window has no size at all, so it has nowhere to go. */
    private static boolean onScreen(double[] gui) {
        Window window = Bot.mc().getWindow();
        return window.getScreenWidth() > 0 && window.getScreenHeight() > 0
                && gui[0] >= 0 && gui[1] >= 0 && gui[0] < window.getGuiScaledWidth() && gui[1] < window.getGuiScaledHeight();
    }

    /**
     * For a slot no cursor can reach by the numbers it gives. Screens that lay themselves out, such as those of LDLib2,
     * can leave a wrong position on a slot while drawing it elsewhere, and in a minimized window nothing has a position.
     * This goes to the game's own slot handling, which is where a click on that slot would have ended up.
     */
    private static void slotInput(int slotIndex, int button, Click input) {
        Bot.run(() -> {
            LocalPlayer player = Bot.player();
            Mc.click(containerScreen().getMenu().containerId, slotIndex, button, input, player);
        });
        Bot.pause(90);
    }

    private static void pressHere(int button, boolean shift) {
        if (!Bot.waitUntil(VirtualInput::cursorIdle, 2000)) {
            throw new ApiError("timeout", "the cursor did not arrive; is the window minimized?");
        }
        Bot.run(() -> {
            if (shift) {
                VirtualInput.keyDown(GLFW.GLFW_KEY_LEFT_SHIFT);
            }
            VirtualInput.mouseButton(button, true);
        });
        Bot.pause(40);
        Bot.run(() -> {
            VirtualInput.mouseButton(button, false);
            if (shift) {
                VirtualInput.keyUp(GLFW.GLFW_KEY_LEFT_SHIFT);
            }
        });
        // Long enough to clear the game's double-click window and let the server answer.
        Bot.pause(90);
    }

    private static void clickMode(int slot, int button, int mode) {
        switch (mode) {
            case 0 -> click(slot, button, false);
            case 1 -> click(slot, 0, true);
            case 2 -> {
                if (button < 0 || button > 8) {
                    throw new ApiError("bad_argument", "for mode 2, button is the hotbar slot 0-8");
                }
                if (!moveCursorOnly(slot)) {
                    slotInput(slot, button, Click.SWAP);
                    return;
                }
                Bot.run(() -> VirtualInput.tapMapping(ActionFns.options().keyHotbarSlots[button]));
                Bot.pause(90);
            }
            case 4 -> {
                if (!moveCursorOnly(slot)) {
                    slotInput(slot, 0, Click.THROW);
                    return;
                }
                Bot.run(() -> VirtualInput.tapMapping(ActionFns.options().keyDrop));
                Bot.pause(90);
            }
            case 6 -> {
                click(slot, 0, false);
                click(slot, 0, false);
            }
            default -> throw new ApiError("bad_argument", "mode must be 0, 1, 2, 4 or 6; use the screen tool for drags");
        }
    }

    /** Whether the cursor could go there; a slot outside the window can only be reached through slotInput. */
    private static boolean moveCursorOnly(int slotIndex) {
        boolean reachable = Bot.call(() -> {
            AbstractContainerScreen<?> screen = containerScreen();
            double[] gui = slotCenter(screen, screen.getMenu().slots.get(slotIndex));
            if (!onScreen(gui)) {
                return false;
            }
            double[] target = guiToWindow(gui[0], gui[1]);
            VirtualInput.glideCursorTo(target[0], target[1]);
            return true;
        });
        if (reachable) {
            Bot.waitUntil(VirtualInput::cursorIdle, 2000);
            // Keys act on the slot under the cursor, and the screen works that out while it draws.
            Bot.waitTicks(2);
        }
        return reachable;
    }

    private static void returnCarried(int home) {
        if (Bot.call(() -> !containerScreen().getMenu().getCarried().isEmpty())) {
            click(home, 0, false);
        }
    }

    /** The block whose window is open, for remembering what it held as it closes. */
    private static volatile BlockPos lastOpenedBlock;

    static void close() {
        Bot.requireControl();
        if (Bot.call(() -> Bot.mc().screen == null)) {
            lastOpenedBlock = null;
            return;
        }
        BlockPos opened = lastOpenedBlock;
        if (opened != null) {
            Bot.run(() -> Containers.recordWindow(opened));
            lastOpenedBlock = null;
        }
        Bot.run(() -> VirtualInput.keyTap(GLFW.GLFW_KEY_ESCAPE));
        if (!Bot.waitUntil(() -> Bot.mc().screen == null, 1500)) {
            throw new ApiError("close_failed", "the window stayed open: " + Bot.call(() -> Bot.mc().screen.getClass().getSimpleName()),
                    "it may need a button: use the screen tool");
        }
        Bot.waitTicks(2);
    }

    private static void openInventory() {
        Bot.readyForWorldInput();
        Bot.run(() -> VirtualInput.tapMapping(ActionFns.options().keyInventory));
        if (!Bot.waitUntil(() -> Bot.mc().screen instanceof AbstractContainerScreen<?>, 1500)) {
            throw new ApiError("timeout", "the inventory did not open");
        }
        Bot.waitTicks(1);
    }

    /** Slots of the open menu that hold your own main inventory and hotbar. */
    private static List<Integer> inventorySlots(Predicate<ItemStack> filter) {
        AbstractContainerMenu menu = containerScreen().getMenu();
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container instanceof Inventory && slot.getContainerSlot() < 36 && filter.test(slot.getItem())) {
                out.add(i);
            }
        }
        return out;
    }

    // ---- moving items ----

    private static JsonObject transfer(JsonObject args) {
        String item = J.str(args, "item");
        String to = J.str(args, "to");
        int wanted = J.integer(args, "count", Integer.MAX_VALUE);
        int moved = 0;
        while (moved < wanted) {
            Bot.requireControl();
            Integer source = Bot.call(() -> {
                AbstractContainerMenu menu = containerScreen().getMenu();
                for (int i = 0; i < menu.slots.size(); i++) {
                    Slot slot = menu.slots.get(i);
                    boolean mine = slot.container instanceof Inventory;
                    if (mine == to.equals("window") && Bot.matchesItem(slot.getItem(), item)) {
                        return i;
                    }
                }
                return null;
            });
            if (source == null) {
                break;
            }
            int inStack = Bot.call(() -> containerScreen().getMenu().slots.get(source).getItem().getCount());
            if (moved + inStack <= wanted) {
                click(source, 0, true);
                moved += inStack;
            } else {
                moved += moveSome(source, to, wanted - moved);
            }
        }
        if (moved == 0) {
            throw new ApiError("not_found", "no " + item + " on the " + (to.equals("window") ? "inventory" : "window") + " side");
        }
        JsonObject out = J.obj("moved", moved);
        out.add("window", Bot.call(WindowFns::summary));
        return out;
    }

    /** Part of a stack: pick it all up, drop items one by one into a free target slot, put the rest back. */
    private static int moveSome(int source, String to, int count) {
        Integer free = Bot.call(() -> {
            AbstractContainerMenu menu = containerScreen().getMenu();
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                boolean mine = slot.container instanceof Inventory;
                if (mine != to.equals("window") && slot.getItem().isEmpty()) {
                    return i;
                }
            }
            return null;
        });
        if (free == null) {
            throw new ApiError("no_room", "no empty slot on the target side");
        }
        click(source, 0, false);
        for (int i = 0; i < count; i++) {
            click(free, 1, false);
        }
        returnCarried(source);
        return count;
    }

    private static JsonObject windowPut(JsonObject args) {
        int target = J.integer(args, "slot");
        String item = J.str(args, "item");
        Integer source = Bot.call(() -> {
            List<Integer> found = inventorySlots(stack -> Bot.matchesItem(stack, item));
            return found.isEmpty() ? null : found.get(0);
        });
        if (source == null) {
            throw new ApiError("not_found", "you carry no " + item);
        }
        int have = Bot.call(() -> containerScreen().getMenu().slots.get(source).getItem().getCount());
        int count = Math.min(J.integer(args, "count", have), have);
        click(source, 0, false);
        if (count == have) {
            click(target, 0, false);
        } else {
            for (int i = 0; i < count; i++) {
                click(target, 1, false);
            }
        }
        returnCarried(source);
        return Bot.call(WindowFns::summary);
    }

    // ---- equipment ----

    static void equip(String item, String dest) {
        switch (dest) {
            case "hand" -> equipHand(item);
            case "off-hand" -> {
                equipHand(item);
                Bot.readyForWorldInput();
                Bot.run(() -> VirtualInput.tapMapping(ActionFns.options().keySwapOffhand));
                Bot.waitTicks(2);
            }
            case "head", "torso", "legs", "feet" -> equipArmor(item);
            default -> throw new ApiError("bad_argument", "dest must be hand, off-hand, head, torso, legs or feet");
        }
    }

    private static void equipHand(String item) {
        Bot.requireControl();
        int found = Bot.call(() -> {
            Inventory inv = Bot.player().getInventory();
            if (Bot.matchesItem(inv.getItem(Mc.selectedSlot(inv)), item)) {
                return -2;
            }
            for (int i = 0; i < 36; i++) {
                if (Bot.matchesItem(inv.getItem(i), item)) {
                    return i;
                }
            }
            return -1;
        });
        if (found == -2) {
            return;
        }
        if (found == -1) {
            throw new ApiError("not_found", "you carry no " + item, "inventory() lists what you have");
        }
        equipInventorySlot(found);
    }

    /** Brings an Inventory slot to the hand: hotbar by number key, main inventory by a visible swap. */
    private static void equipInventorySlot(int slot) {
        if (slot < 9) {
            ActionFns.selectHotbar(slot);
            return;
        }
        Bot.readyForWorldInput();
        if (Bot.call(() -> Bot.mc().gameMode != null && Bot.mc().gameMode.getPlayerMode().isCreative())) {
            throw new ApiError("creative_mode", "the creative inventory has different slots", "pick it from the hotbar, or use the screen tool");
        }
        int place = Bot.call(() -> Bot.hotbarPlaceToBorrow(Bot.player().getInventory()));
        openInventory();
        // InventoryMenu maps Inventory slot 9-35 to itself and hotbar slot n to 36+n.
        click(slot, 0, false);
        click(36 + place, 0, false);
        returnCarried(slot);
        close();
        ActionFns.selectHotbar(place);
    }

    static final int ONE_STACK = -1;
    static final int EVERYTHING = -2;
    private static final int OFF_HAND_SLOT = 45;
    private static final int MAX_MERGES = 30;
    private static final int HOTBAR_START = 36;
    private static final Set<Item> NOT_REALLY_FOOD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.CHICKEN);

    /**
     * What a tidy pack has in its hotbar, place by place, each scoring what would go there: the best of its kind, or none.
     * The pickaxe comes first because Baritone puts a pickaxe it finds in the main inventory in place 0 by itself, and would
     * undo anything that pushed it out of there.
     */
    private static final List<ToDoubleFunction<ItemStack>> HOTBAR = List.of(
            stack -> stack.is(ItemTags.PICKAXES) ? stack.getDestroySpeed(Blocks.STONE.defaultBlockState()) : 0,
            stack -> stack.is(ItemTags.SWORDS) ? Skills.attackDamage(stack) + 1 : 0,
            stack -> stack.is(ItemTags.AXES) ? stack.getDestroySpeed(Blocks.OAK_LOG.defaultBlockState()) : 0,
            stack -> stack.is(ItemTags.SHOVELS) ? stack.getDestroySpeed(Blocks.DIRT.defaultBlockState()) : 0,
            WindowFns::foodWorth,
            stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE) || stack.is(Items.DIRT) || stack.is(Items.NETHERRACK) ? stack.getCount() : 0,
            stack -> stack.is(Items.TORCH) ? stack.getCount() : 0);

    private static double foodWorth(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food == null || NOT_REALLY_FOOD.contains(stack.getItem()) ? 0 : food.nutrition() + food.saturation();
    }

    /** Client thread. Empty places among the 36 that hold things. */
    static int freeSlots() {
        Inventory inventory = Bot.player().getInventory();
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (inventory.getItem(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /**
     * Throws things out of the pack from the inventory window, as a person does: the cursor goes to the stack and the
     * drop key does it, whatever is in hand. They fly the way the player looks. Each amount is a number of items,
     * ONE_STACK or EVERYTHING. Answers how many of each went.
     */
    static Map<String, Integer> throwFromInventory(Map<String, Integer> amounts) {
        Bot.requireControl();
        if (Bot.call(() -> Bot.mc().screen != null)) {
            close();
        }
        openInventory();
        Map<String, Integer> thrown = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Integer> entry : amounts.entrySet()) {
                thrown.put(entry.getKey(), throwOne(entry.getKey(), entry.getValue()));
            }
        } finally {
            close();
        }
        return thrown;
    }

    private static int throwOne(String item, int amount) {
        List<Integer> slots = Bot.call(() -> inventorySlots(stack -> Bot.matchesItem(stack, item)));
        int thrown = 0;
        for (int slot : slots) {
            int here = Bot.call(() -> containerScreen().getMenu().slots.get(slot).getItem().getCount());
            moveCursorOnly(slot);
            if (amount < 0 || amount - thrown >= here) {
                slotInput(slot, 1, Click.THROW);
                thrown += here;
            } else {
                for (int i = thrown; i < amount; i++) {
                    slotInput(slot, 0, Click.THROW);
                }
                thrown = amount;
            }
            if (amount == ONE_STACK || (amount >= 0 && thrown >= amount)) {
                break;
            }
        }
        return thrown;
    }

    /** Tidies the pack: part stacks merged, and tools, food, building blocks and torches in their places in the hotbar. */
    static JsonObject organize() {
        Bot.requireControl();
        if (Bot.call(() -> Bot.mc().gameMode != null && Bot.mc().gameMode.getPlayerMode().isCreative())) {
            throw new ApiError("creative_mode", "the creative inventory has different slots");
        }
        if (Bot.call(() -> Bot.mc().screen != null)) {
            close();
        }
        openInventory();
        int merged = 0;
        int moved = 0;
        try {
            for (int i = 0; i < MAX_MERGES; i++) {
                int[] pair = Bot.call(WindowFns::partStacks);
                if (pair == null) {
                    break;
                }
                click(pair[0], 0, false);
                click(pair[1], 0, false);
                returnCarried(pair[0]);
                merged++;
            }
            moved = arrangeHotbar();
        } finally {
            close();
        }
        JsonArray spare = new JsonArray();
        Bot.call(WindowFns::spareTools).forEach(spare::add);
        return J.obj("merged_stacks", merged, "moved_to_hotbar", moved, "free_slots", Bot.call(WindowFns::freeSlots), "spare_tools", spare);
    }

    /** Client thread. Two part stacks of one thing as menu slots, from and to, or null. */
    private static int[] partStacks() {
        AbstractContainerMenu menu = containerScreen().getMenu();
        List<Integer> slots = inventorySlots(stack -> !stack.isEmpty() && stack.isStackable() && stack.getCount() < stack.getMaxStackSize());
        for (int a = 0; a < slots.size(); a++) {
            for (int b = a + 1; b < slots.size(); b++) {
                if (ItemStack.isSameItemSameComponents(menu.slots.get(slots.get(a)).getItem(), menu.slots.get(slots.get(b)).getItem())) {
                    // Toward the hotbar, which comes last: that is where it gets used from.
                    return new int[] {slots.get(a), slots.get(b)};
                }
            }
        }
        return null;
    }

    private static int arrangeHotbar() {
        int moved = 0;
        for (int place = 0; place < HOTBAR.size(); place++) {
            int at = place;
            int best = Bot.call(() -> {
                AbstractContainerMenu menu = containerScreen().getMenu();
                ToDoubleFunction<ItemStack> score = HOTBAR.get(at);
                int bestSlot = HOTBAR_START + at;
                double bestScore = score.applyAsDouble(menu.slots.get(bestSlot).getItem());
                for (int slot : inventorySlots(stack -> !stack.isEmpty())) {
                    // What an earlier place took stays where it is.
                    boolean taken = slot >= HOTBAR_START && slot < HOTBAR_START + at;
                    double value = taken ? 0 : score.applyAsDouble(menu.slots.get(slot).getItem());
                    if (value > bestScore) {
                        bestSlot = slot;
                        bestScore = value;
                    }
                }
                return bestSlot;
            });
            if (best != HOTBAR_START + place) {
                moveCursorOnly(best);
                slotInput(best, place, Click.SWAP);
                moved++;
            }
        }
        return moved;
    }

    /** Client thread. Tools beaten by one of the same kind that is also carried: the first things a person would part with. */
    static List<String> spareTools() {
        Inventory inventory = Bot.player().getInventory();
        List<String> spare = new ArrayList<>();
        for (int kind = 0; kind < 4; kind++) {
            ToDoubleFunction<ItemStack> score = HOTBAR.get(kind);
            double best = 0;
            for (int i = 0; i < 36; i++) {
                best = Math.max(best, score.applyAsDouble(inventory.getItem(i)));
            }
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inventory.getItem(i);
                double value = score.applyAsDouble(stack);
                if (value > 0 && value < best) {
                    spare.add(Bot.itemId(stack));
                }
            }
        }
        return spare;
    }

    /** The failure a full pack gives: it says what the choice is, which is the model's to make. */
    private static ApiError inventoryFull(Item result) {
        List<String> spare = Bot.call(WindowFns::spareTools);
        return new ApiError("inventory_full", "every slot of the inventory is taken, so " + Bot.itemId(new ItemStack(result)) + " has nowhere to go",
                "decide what you need least and call dropItems with it; organizeInventory merges part stacks first"
                        + (spare.isEmpty() ? "" : "; beaten by better ones you carry: " + String.join(", ", spare)));
    }

    /** Client thread. Whether a result would have somewhere to go once its ingredients have gone into the grid. */
    private static boolean roomFor(Item result, CraftPlan plan, int rounds) {
        Inventory inventory = Bot.player().getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || (stack.getItem() == result && stack.getCount() < stack.getMaxStackSize())) {
                return true;
            }
        }
        Map<Item, Integer> uses = new HashMap<>();
        for (List<ItemStack> cell : plan.cells()) {
            for (ItemStack option : cell) {
                uses.merge(option.getItem(), rounds, Integer::sum);
            }
        }
        for (Map.Entry<Item, Integer> use : uses.entrySet()) {
            int have = countCarried(use.getKey());
            if (have > 0 && have <= use.getValue()) {
                return true;
            }
        }
        return false;
    }

    private static void equipArmor(String item) {
        openInventory();
        Integer source = Bot.call(() -> {
            List<Integer> found = inventorySlots(stack -> Bot.matchesItem(stack, item));
            return found.isEmpty() ? null : found.get(0);
        });
        if (source == null) {
            close();
            throw new ApiError("not_found", "you carry no " + item);
        }
        click(source, 0, true);
        close();
    }

    static void unequip(String dest) {
        if (dest.equals("off-hand")) {
            // Not the swap-hands key: that would put it in the main hand, and what the hand held in the off hand.
            openInventory();
            click(OFF_HAND_SLOT, 0, true);
            close();
            if (!Bot.call(() -> Bot.player().getOffhandItem().isEmpty())) {
                throw new ApiError("inventory_full", "every slot of the inventory is taken, so the off-hand item stayed", "dropItems first");
            }
            return;
        }
        int slot = switch (dest) {
            case "head" -> 5;
            case "torso" -> 6;
            case "legs" -> 7;
            case "feet" -> 8;
            default -> throw new ApiError("bad_argument", "dest must be off-hand, head, torso, legs or feet");
        };
        openInventory();
        click(slot, 0, true);
        close();
    }

    /** Picks the fastest tool you carry for a block, if it beats what is in hand. */
    static void equipBestTool(BlockState state) {
        int best = Bot.call(() -> {
            Inventory inv = Bot.player().getInventory();
            float bestSpeed = inv.getItem(Mc.selectedSlot(inv)).getDestroySpeed(state);
            boolean harvests = inv.getItem(Mc.selectedSlot(inv)).isCorrectToolForDrops(state);
            int found = -1;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                float speed = stack.getDestroySpeed(state);
                boolean better = (stack.isCorrectToolForDrops(state) && !harvests)
                        || (stack.isCorrectToolForDrops(state) == harvests && speed > bestSpeed);
                if (!stack.isEmpty() && better) {
                    found = i;
                    bestSpeed = speed;
                    harvests = stack.isCorrectToolForDrops(state);
                }
            }
            return found;
        });
        if (best >= 0) {
            equipInventorySlot(best);
        }
    }

    // ---- crafting ----

    record CraftPlan(int width, int height, List<List<ItemStack>> cells, int makes, boolean shaped) {}

    /** A grid cell holds a stack at most. */
    private static final int MAX_PER_CELL = 64;

    static CraftPlan toPlan(RecipeView.Crafting recipe) {
        return new CraftPlan(recipe.width(), recipe.height(), recipe.cells(), recipe.result().getCount(), recipe.shaped());
    }

    static List<CraftPlan> plansFor(Item item) {
        return RecipeIndex.get().crafting(item);
    }

    /** What is missing to craft a plan once, or an empty list when the inventory covers it. */
    static List<String> missing(CraftPlan plan) {
        Inventory inv = Bot.player().getInventory();
        List<ItemStack> pool = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            pool.add(inv.getItem(i).copy());
        }
        List<String> out = new ArrayList<>();
        for (List<ItemStack> options : plan.cells()) {
            if (options.isEmpty()) {
                continue;
            }
            boolean ok = false;
            for (ItemStack have : pool) {
                if (!have.isEmpty() && options.stream().anyMatch(option -> option.getItem() == have.getItem())) {
                    have.shrink(1);
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                out.add(Bot.itemId(options.get(0)));
            }
        }
        return out;
    }

    private static JsonObject planJson(CraftPlan plan) {
        JsonArray ingredients = new JsonArray();
        for (List<ItemStack> options : plan.cells()) {
            if (options.isEmpty()) {
                ingredients.add("empty");
            } else {
                ingredients.add(Bot.itemId(options.get(0)) + (options.size() > 1 ? " or similar (" + options.size() + " options)" : ""));
            }
        }
        JsonArray lacking = new JsonArray();
        missing(plan).forEach(lacking::add);
        return J.obj("size", plan.width() + "x" + plan.height(), "shaped", plan.shaped(), "makes", plan.makes(),
                "needs_crafting_table", plan.width() > 2 || plan.height() > 2, "ingredients", ingredients,
                "missing", lacking);
    }

    private static JsonObject recipesFor(Item item) {
        JsonArray out = new JsonArray();
        for (CraftPlan plan : plansFor(item)) {
            out.add(planJson(plan));
        }
        JsonObject result = J.obj("recipes", out);
        MachineRecipes.making(item, 8).entrySet().forEach(entry -> result.add(entry.getKey(), entry.getValue()));
        result.addProperty("note", "if both lists are empty the item may be mined, traded or looted");
        return result;
    }

    private static JsonObject recipesAll(int limit) {
        JsonArray out = new JsonArray();
        for (RecipeView.Crafting recipe : Recipes.listed()) {
            if (out.size() >= limit) {
                break;
            }
            CraftPlan plan = toPlan(recipe);
            if (missing(plan).isEmpty()) {
                JsonObject row = planJson(plan);
                row.addProperty("item", Bot.itemId(recipe.result()));
                out.add(row);
            }
        }
        return J.obj("craftable", out);
    }

    private static int countCarried(Item item) {
        Inventory inv = Bot.player().getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == item) {
                total += inv.getItem(i).getCount();
            }
        }
        return total;
    }

    private static JsonObject craft(JsonObject args) {
        Item item = Bot.item(J.str(args, "item"));
        int wanted = J.integer(args, "count", 1);
        CraftPlan plan = Bot.call(() -> {
            List<CraftPlan> plans = plansFor(item);
            if (plans.isEmpty()) {
                throw new ApiError("no_recipe", "no known crafting recipe makes " + J.str(args, "item"),
                        "it may come from a machine, mining or trade: describeItem and usage can help");
            }
            for (CraftPlan candidate : plans) {
                if (missing(candidate).isEmpty()) {
                    return candidate;
                }
            }
            throw new ApiError("missing_ingredients", "cannot craft: missing " + missing(plans.get(0)),
                    "recipesFor shows the ingredients");
        });
        if (!Bot.call(() -> roomFor(item, plan, (int) Math.ceil((double) wanted / plan.makes())))) {
            throw inventoryFull(item);
        }
        boolean needsTable = plan.width() > 2 || plan.height() > 2;
        int gridWidth = openGrid(needsTable);
        int startCount = Bot.call(() -> countCarried(item));
        try {
            // As a person does it: several of each ingredient into the grid at once, then one shift-click on the result.
            int made = 0;
            while (made < wanted) {
                Bot.requireControl();
                List<String> lacking = Bot.call(() -> missing(plan));
                if (!lacking.isEmpty()) {
                    throw new ApiError("missing_ingredients", "ran out after " + made + " were made: missing " + lacking);
                }
                int rounds = (int) Math.ceil((double) (wanted - made) / plan.makes());
                int perCell = Bot.call(() -> batchSize(plan, rounds));
                fillGrid(plan, gridWidth, perCell);
                if (!Bot.waitUntil(() -> containerScreen().getMenu().slots.get(0).getItem().getItem() == item, 1500)) {
                    throw new ApiError("no_result", "the grid made nothing; the recipe may be locked or changed on this server");
                }
                int before = Bot.call(() -> countCarried(item));
                click(0, 0, true);
                if (!Bot.waitUntil(() -> countCarried(item) > before, 1500)) {
                    throw inventoryFull(item);
                }
                Bot.waitTicks(2);
                made += Bot.call(() -> countCarried(item)) - before;
                takeBackLeftovers();
            }
        } finally {
            if (Bot.call(() -> Bot.mc().screen != null)) {
                close();
            }
        }
        return Bot.call(() -> J.obj("crafted", countCarried(item) - startCount, "now_have", countCarried(item)));
    }

    /** Opens the right grid and returns its width. Grid slots start at 1 in both menus. */
    private static int openGrid(boolean needsTable) {
        Screen open = Bot.call(() -> Bot.mc().screen);
        if (open instanceof CraftingScreen) {
            return 3;
        }
        if (open instanceof InventoryScreen && !needsTable) {
            return 2;
        }
        if (open != null) {
            close();
        }
        if (Bot.call(() -> Bot.mc().gameMode != null && Bot.mc().gameMode.getPlayerMode().isCreative()) && !needsTable) {
            throw new ApiError("creative_mode", "creative mode has no 2x2 grid", "open a crafting table, or take the item from the creative tabs with the screen tool");
        }
        if (!needsTable) {
            openInventory();
            return 2;
        }
        BlockPos table = Bot.call(() -> {
            LocalPlayer player = Bot.player();
            BlockPos center = player.blockPosition();
            BlockPos best = null;
            double bestDistance = (Bot.REACH + 0.5) * (Bot.REACH + 0.5);
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-5, -3, -5), center.offset(5, 4, 5))) {
                if (Bot.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                    double distance = player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos));
                    if (distance < bestDistance && Bot.visiblePoint(pos) != null) {
                        best = pos.immutable();
                        bestDistance = distance;
                    }
                }
            }
            return best;
        });
        if (table == null) {
            throw new ApiError("needs_crafting_table", "this is a 3x3 recipe and no crafting table is in reach",
                    "craft or find one, then placeBlock it nearby or goto it");
        }
        Bot.readyForWorldInput();
        Bot.aimAtBlock(table);
        Bot.run(() -> VirtualInput.tapMapping(ActionFns.options().keyUse));
        if (!Bot.waitUntil(() -> Bot.mc().screen instanceof CraftingScreen, 1500)) {
            throw new ApiError("timeout", "the crafting table did not open");
        }
        Bot.waitTicks(1);
        return 3;
    }

    /**
     * Client thread. How many of each ingredient to put in every cell for one shift-click: as many rounds as wanted, within
     * what is carried and what a slot holds. Cells that take the same kind of thing share what is carried.
     */
    private static int batchSize(CraftPlan plan, int rounds) {
        int perCell = Math.min(rounds, MAX_PER_CELL);
        Map<List<ItemStack>, Integer> cellsUsing = new HashMap<>();
        for (List<ItemStack> options : plan.cells()) {
            if (!options.isEmpty()) {
                cellsUsing.merge(options, 1, Integer::sum);
            }
        }
        Inventory inv = Bot.player().getInventory();
        for (Map.Entry<List<ItemStack>, Integer> entry : cellsUsing.entrySet()) {
            int carried = 0;
            int maxStack = MAX_PER_CELL;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && entry.getKey().stream().anyMatch(o -> o.getItem() == stack.getItem())) {
                    carried += stack.getCount();
                    maxStack = Math.min(maxStack, stack.getMaxStackSize());
                }
            }
            perCell = Math.min(perCell, Math.min(maxStack, carried / entry.getValue()));
        }
        return Math.max(1, perCell);
    }

    /** Puts perCell of each ingredient into its cell: a whole stack with one click where it fits exactly, otherwise one at a time. */
    private static void fillGrid(CraftPlan plan, int gridWidth, int perCell) {
        int home = -1;
        ItemStack holding = ItemStack.EMPTY;
        for (int i = 0; i < plan.cells().size(); i++) {
            List<ItemStack> options = plan.cells().get(i);
            if (options.isEmpty()) {
                continue;
            }
            int row = plan.shaped() ? i / plan.width() : i / gridWidth;
            int col = plan.shaped() ? i % plan.width() : i % gridWidth;
            int gridSlot = 1 + row * gridWidth + col;
            int placed = 0;
            while (placed < perCell) {
                Bot.requireControl();
                ItemStack held = holding;
                boolean match = !held.isEmpty() && options.stream().anyMatch(o -> o.getItem() == held.getItem());
                if (!match) {
                    if (home >= 0) {
                        returnCarried(home);
                    }
                    int source = Bot.call(() -> {
                        List<Integer> found = inventorySlots(stack -> options.stream().anyMatch(o -> !stack.isEmpty() && o.getItem() == stack.getItem()));
                        if (found.isEmpty()) {
                            throw new ApiError("missing_ingredients", "no " + Bot.itemId(options.get(0)) + " left to place");
                        }
                        return found.get(0);
                    });
                    click(source, 0, false);
                    home = source;
                    holding = Bot.call(() -> containerScreen().getMenu().getCarried().copy());
                    continue;
                }
                int want = perCell - placed;
                boolean cellEmpty = Bot.call(() -> containerScreen().getMenu().slots.get(gridSlot).getItem().isEmpty());
                if (cellEmpty && held.getCount() <= want) {
                    click(gridSlot, 0, false);
                    placed += held.getCount();
                } else {
                    click(gridSlot, 1, false);
                    placed++;
                }
                holding = Bot.call(() -> containerScreen().getMenu().getCarried().copy());
            }
        }
        if (home >= 0) {
            returnCarried(home);
        }
    }

    /** Whatever a batch left in the grid, such as when the inventory filled up first, comes back out. */
    private static void takeBackLeftovers() {
        List<Integer> full = Bot.call(() -> {
            List<Integer> out = new ArrayList<>();
            AbstractContainerMenu menu = containerScreen().getMenu();
            for (int i = 1; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (!(slot.container instanceof Inventory) && !slot.getItem().isEmpty()) {
                    out.add(i);
                }
            }
            return out;
        });
        for (int slot : full) {
            click(slot, 0, true);
        }
    }

    // ---- other windows ----

    private static JsonObject trade(JsonObject args) {
        int index = J.integer(args, "index");
        int times = J.integer(args, "times", 1);
        Bot.run(() -> {
            if (!(Bot.mc().screen instanceof MerchantScreen screen)) {
                throw new ApiError("no_window", "no villager window is open", "openVillager(id) first");
            }
            List<AbstractWidget> offers = new ArrayList<>();
            for (GuiEventListener child : screen.children()) {
                if (child instanceof AbstractWidget widget && widget.getClass().getSimpleName().contains("TradeOffer")) {
                    offers.add(widget);
                }
            }
            if (index < 0 || index >= offers.size()) {
                throw new ApiError("bad_argument", "this window shows offers 0-" + (offers.size() - 1)
                        + "; scroll it with the screen tool for later ones");
            }
            AbstractWidget offer = offers.get(index);
            double[] target = guiToWindow(offer.getX() + offer.getWidth() / 2.0, offer.getY() + offer.getHeight() / 2.0);
            VirtualInput.glideCursorTo(target[0], target[1]);
        });
        pressHere(0, false);
        // A plain click makes exactly one trade each time; a shift-click would trade until the payment runs out.
        int done = 0;
        for (int i = 0; i < times; i++) {
            if (!Bot.waitUntil(() -> !containerScreen().getMenu().slots.get(2).getItem().isEmpty(), 800)) {
                break;
            }
            click(2, 0, false);
            done++;
        }
        if (done == 0) {
            throw new ApiError("trade_failed", "no result appeared; you may lack the payment or the offer is sold out");
        }
        Integer free = Bot.call(() -> {
            List<Integer> empty = inventorySlots(ItemStack::isEmpty);
            return empty.isEmpty() ? null : empty.get(0);
        });
        if (free == null) {
            throw new ApiError("no_room", "the inventory is full; what you bought is still on the cursor");
        }
        click(free, 0, false);
        JsonObject out = J.obj("traded", done);
        out.add("window", Bot.call(WindowFns::summary));
        return out;
    }

    static JsonElement respawn() {
        Bot.requireControl();
        Bot.waitUntil(() -> {
            if (!(Bot.mc().screen instanceof DeathScreen)) {
                throw new ApiError("not_dead", "the death screen is not showing");
            }
            return respawnButton() != null && respawnButton().active;
        }, 4000);
        Bot.run(() -> {
            AbstractWidget button = respawnButton();
            if (button == null) {
                throw new ApiError("no_button", "no Respawn button found", "use the screen tool");
            }
            double[] target = guiToWindow(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0);
            VirtualInput.glideCursorTo(target[0], target[1]);
        });
        pressHere(0, false);
        boolean back = Bot.waitUntil(() -> !(Bot.mc().screen instanceof DeathScreen), 4000);
        return J.obj("respawned", back);
    }

    private static AbstractWidget respawnButton() {
        Screen screen = Bot.mc().screen;
        if (screen == null) {
            return null;
        }
        String label = net.minecraft.network.chat.Component.translatable("deathScreen.respawn").getString();
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget widget && widget.getMessage().getString().equals(label)) {
                return widget;
            }
        }
        return null;
    }

    /** Worker thread. Glides the cursor to the first button showing one of these labels and clicks it. */
    static String pressWidget(net.minecraft.network.chat.Component... labels) {
        String pressed = Bot.call(() -> {
            Screen screen = Bot.mc().screen;
            if (screen != null) {
                for (net.minecraft.network.chat.Component label : labels) {
                    for (GuiEventListener child : screen.children()) {
                        if (child instanceof AbstractWidget widget && widget.active && widget.visible && widget.getMessage().getString().equals(label.getString())) {
                            double[] target = guiToWindow(widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0);
                            VirtualInput.glideCursorTo(target[0], target[1]);
                            return label.getString();
                        }
                    }
                }
            }
            throw new ApiError("no_button", "no active button with that label on this screen", "use the screen tool");
        });
        pressHere(0, false);
        return pressed;
    }

    static JsonElement updateSign(JsonObject args) {
        BlockPos pos = Bot.pos(args);
        if (Bot.call(() -> !(Bot.mc().screen instanceof AbstractSignEditScreen))) {
            Bot.readyForWorldInput();
            Bot.aimAtBlock(pos);
            Bot.run(() -> VirtualInput.tapMapping(ActionFns.options().keyUse));
            if (!Bot.waitUntil(() -> Bot.mc().screen instanceof AbstractSignEditScreen, 1500)) {
                throw new ApiError("no_window", "the sign did not open for editing", "it may be waxed or protected");
            }
        }
        for (String line : J.strings(args, "lines")) {
            Bot.requireControl();
            Bot.run(() -> line.codePoints().forEach(VirtualInput::typeChar));
            Bot.pause(60);
            Bot.run(() -> VirtualInput.keyTap(GLFW.GLFW_KEY_DOWN));
            Bot.pause(40);
        }
        close();
        return J.obj("written", true);
    }

}
