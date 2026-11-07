package dev.cogito.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cogito.Config;
import dev.cogito.control.VirtualInput;
import dev.cogito.memory.MemoryStore;
import dev.cogito.mixin.FishingHookAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/** Acting in the world. Each function presses what a player would press and reads the result back. */
final class ActionFns {
    private ActionFns() {}

    static void register() {
        Api.act("setControlState(control,state,[confirm_hazard])", "hold or release forward, back, left, right, jump, sprint, sneak, attack or use. Forward refuses, or stops and tells you, before lava, fire or a long drop, until you confirm with confirm_hazard:true", args -> {
            KeyMapping key = mapping(J.str(args, "control"));
            boolean state = J.bool(args, "state", true);
            boolean confirmed = J.bool(args, "confirm_hazard", false);
            Bot.call(() -> {
                if (state && key == options().keyUp) {
                    if (!confirmed && dev.cogito.agent.Reflexes.hazardAhead()) {
                        throw new ApiError("hazard_ahead", dev.cogito.agent.Reflexes.HAZARD_AHEAD, "turn another way, or use goto, which finds a safe way round");
                    }
                    dev.cogito.agent.Reflexes.confirmHazard(confirmed);
                }
                VirtualInput.setMapping(key, state);
                return null;
            });
            return null;
        });
        Api.act("clearControlStates()", "release every held movement control", args -> {
            Bot.run(ActionFns::clearControls);
            return null;
        });
        Api.act("look(yaw,pitch)", "turn the view to a yaw and pitch in degrees", args -> {
            Bot.look((float) J.number(args, "yaw"), (float) J.number(args, "pitch"));
            return null;
        });
        Api.act("lookAt(x,y,z)", "turn the view toward a point", args -> {
            Bot.lookAt(new Vec3(J.number(args, "x"), J.number(args, "y"), J.number(args, "z")));
            return J.obj("crosshair", Bot.call(Bot::cursorText));
        });
        Api.act("setQuickBarSlot(slot)", "select hotbar slot 0-8 with its number key", args -> {
            selectHotbar(J.integer(args, "slot"));
            return null;
        });
        Api.act("equip(item,[dest=hand])", "put an item in hand, off-hand, head, torso, legs or feet; opens the inventory when it must", args -> {
            WindowFns.equip(J.str(args, "item"), J.str(args, "dest", "hand"));
            return Bot.call(() -> J.obj("held", Bot.stackJson(Bot.player().getMainHandItem())));
        });
        Api.act("unequip(dest)", "take armor or the off-hand item off, into the inventory", args -> {
            WindowFns.unequip(J.str(args, "dest"));
            return null;
        });
        Api.act("toss(item,[count=1])", "throw some of an item the way you look, from the inventory window, so it need not be in hand; to get rid of things use dropItems", args -> {
            toss(J.str(args, "item"), J.integer(args, "count", 1));
            return null;
        });
        Api.act("tossStack(item)", "throw one whole stack the way you look, from the inventory window; to get rid of things use dropItems", args -> {
            tossStack(J.str(args, "item"));
            return null;
        });
        Api.act("dig(x,y,z,[auto_tool=true])", "hold attack on a block in reach until it breaks; swaps to the best tool you carry", args -> dig(args));
        Api.act("stopDigging()", "release attack", args -> {
            Bot.run(() -> VirtualInput.setMapping(options().keyAttack, false));
            return null;
        });
        Api.act("placeBlock(x,y,z,[item])", "place the held block (or item) into the empty cell x,y,z against a neighbouring block", args -> placeBlock(args));
        Api.act("placeEntity(x,y,z,[item])", "use a spawn egg, boat or minecart on the top of a block or water at x,y,z; fails if nothing appeared", args -> placeEntity(args));
        Api.act("activateBlock(x,y,z,[hold_s])", "right-click a block in reach; hold_s keeps the button down for that many seconds, for things worked by holding such as cranks; result says if a window opened", args -> {
            double hold = J.number(args, "hold_s", 0);
            return hold > 0 ? holdUseOn(Bot.pos(args), hold) : activateAt(Bot.pos(args));
        });
        Api.act("useOn(x,y,z,item,[face])", "right-click a block with a specific item in hand. What it makes lands on the side you hit, and crosshair in the result names that side; face (up, down, north, south, east, west) asks for one, and fails if it cannot be hit from where you stand", args -> {
            equipIfAsked(args);
            if (args.has("face")) {
                Bot.readyForWorldInput();
                Bot.aimAtFace(Bot.pos(args), J.str(args, "face"));
                tapUse();
                return WindowFns.afterClick();
            }
            return activateAt(Bot.pos(args));
        });
        Api.act("activateEntity(id)", "right-click an entity in reach (villager, animal, boat)", args -> {
            Bot.readyForWorldInput();
            Entity entity = Bot.call(() -> Bot.entityById(J.integer(args, "id")));
            Bot.aimAtEntity(entity);
            tapUse();
            return WindowFns.afterClick();
        });
        Api.act("activateEntityAt(id,x,y,z)", "right-click a point on an entity, such as an armor stand part", args -> {
            Bot.readyForWorldInput();
            Entity entity = Bot.call(() -> Bot.entityById(J.integer(args, "id")));
            Bot.lookAt(new Vec3(J.number(args, "x"), J.number(args, "y"), J.number(args, "z")));
            if (!Bot.call(() -> Bot.mc().hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && hit.getEntity() == entity)) {
                throw new ApiError("aim_missed", "the crosshair landed on " + Bot.call(Bot::cursorText) + ", not on that entity",
                        "move closer; entity reach is about 3 blocks");
            }
            tapUse();
            return WindowFns.afterClick();
        });
        Api.act("activateItem([offhand=false])", "start using the held item and keep holding (bow, shield, food); deactivateItem releases", args -> {
            Bot.readyForWorldInput();
            Bot.run(() -> VirtualInput.setMapping(options().keyUse, true));
            return null;
        });
        Api.act("deactivateItem()", "stop using the held item", args -> {
            Bot.run(() -> VirtualInput.setMapping(options().keyUse, false));
            return null;
        });
        Api.act("consume([item])", "eat or drink until done", args -> consume(J.str(args, "item", null)));
        Api.act("fish([timeout_s=60])", "cast the held rod, wait for a bite and reel in", args -> fish(args));
        Api.act("attack(id)", "aim at an entity in reach and hit it once; mind the attack cooldown", args -> {
            Bot.readyForWorldInput();
            Entity entity = Bot.call(() -> Bot.entityById(J.integer(args, "id")));
            Bot.aimAtEntity(entity);
            Bot.run(() -> VirtualInput.tapMapping(options().keyAttack));
            Bot.waitTicks(2);
            return Bot.call(() -> entity.isAlive() ? Bot.entityJson(entity) : J.obj("target", "gone"));
        });
        Api.act("swingArm()", "swing the main hand", args -> {
            Bot.run(() -> Bot.player().swing(InteractionHand.MAIN_HAND));
            return null;
        });
        Api.act("mount(id)", "get on an entity in reach", args -> {
            Bot.readyForWorldInput();
            Entity entity = Bot.call(() -> Bot.entityById(J.integer(args, "id")));
            Bot.aimAtEntity(entity);
            tapUse();
            Bot.waitTicks(4);
            return Bot.call(() -> J.obj("riding", Bot.player().isPassenger()));
        });
        Api.act("dismount()", "get off with the sneak key", args -> {
            Bot.readyForWorldInput();
            holdForTicks(options().keyShift, 3);
            boolean off = Bot.waitUntil(() -> !Bot.player().isPassenger(), 1000);
            return J.obj("riding", !off);
        });
        Api.act("moveVehicle(left,forward)", "steer what you ride: left and forward from -1 to 1", args -> {
            double left = J.number(args, "left", 0);
            double forward = J.number(args, "forward", 0);
            Bot.run(() -> {
                VirtualInput.setMapping(options().keyLeft, left > 0);
                VirtualInput.setMapping(options().keyRight, left < 0);
                VirtualInput.setMapping(options().keyUp, forward > 0);
                VirtualInput.setMapping(options().keyDown, forward < 0);
            });
            return null;
        });
        Api.act("sleep(x,y,z)", "right-click a bed in reach at night", args -> {
            activateAt(Bot.pos(args));
            boolean asleep = Bot.waitUntil(() -> Bot.player().isSleeping(), 2000);
            if (asleep) {
                String dimension = Bot.call(() -> Bot.level().dimension().identifier().toString());
                MemoryStore.notePlace("bed", Bot.pos(args), dimension, "last slept here; this is where you come back after dying");
            }
            return J.obj("sleeping", asleep);
        });
        Api.act("wake()", "get out of bed, as the Leave Bed button does", args -> {
            Bot.run(() -> {
                LocalPlayer player = Bot.player();
                player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
            });
            return null;
        });
        Api.talk("chat(message)", "say something to the server; chat is public", args -> {
            String message = J.str(args, "message");
            if (message.startsWith("/") && !Config.get().allowCommands) {
                throw new ApiError("not_allowed", "slash commands are turned off for the AI", "allowCommands in config.json");
            }
            Bot.run(() -> {
                if (message.startsWith("/")) {
                    Bot.player().connection.sendCommand(message.substring(1));
                } else {
                    Bot.player().connection.sendChat(message);
                }
            });
            return null;
        });
        Api.talk("whisper(player,message)", "send a private message", args -> {
            String command = "msg " + J.str(args, "player") + " " + J.str(args, "message");
            Bot.run(() -> Bot.player().connection.sendCommand(command));
            return null;
        });
        Api.act("updateSign(x,y,z,lines)", "right-click a sign and type up to four lines", args -> WindowFns.updateSign(args));
        Api.act("goto(x,z,[y],[range=2],[timeout_s=120])", "walk there with Baritone (digs and builds if needed); straight-line walk if Baritone is absent", args -> Pathing.travel(args));
        Api.act("stopPathing()", "stop walking", args -> {
            Pathing.stop();
            return null;
        });
        Api.skill("collectBlock(blocks,count,[until_item],[timeout_s=120])", "find, walk to, mine and pick up blocks, until the inventory has gained count items or it stalls. Names work as a player would use them: iron_ore includes its deepslate form, coal means the blocks that drop it. Stone counts its cobblestone; with until_item only that item counts", args -> Pathing.collect(args));
        Api.act("respawn()", "press Respawn on the death screen", args -> WindowFns.respawn());
        Api.act("elytraFly()", "open a worn elytra in mid-air by pressing jump", args -> {
            Bot.readyForWorldInput();
            holdForTicks(options().keyJump, 2);
            Bot.waitTicks(2);
            return Bot.call(() -> J.obj("gliding", Bot.player().isFallFlying()));
        });
    }

    static Options options() {
        return Bot.mc().options;
    }

    static KeyMapping mapping(String control) {
        Options o = options();
        return switch (control) {
            case "forward" -> o.keyUp;
            case "back" -> o.keyDown;
            case "left" -> o.keyLeft;
            case "right" -> o.keyRight;
            case "jump" -> o.keyJump;
            case "sprint" -> o.keySprint;
            case "sneak" -> o.keyShift;
            case "attack" -> o.keyAttack;
            case "use" -> o.keyUse;
            default -> throw new ApiError("bad_argument", "unknown control '" + control + "'");
        };
    }

    static void clearControls() {
        Options o = options();
        for (KeyMapping key : new KeyMapping[] {o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keySprint, o.keyShift}) {
            VirtualInput.setMapping(key, false);
        }
    }

    static void selectHotbar(int slot) {
        if (slot < 0 || slot > 8) {
            throw new ApiError("bad_argument", "hotbar slots are 0-8");
        }
        Bot.readyForWorldInput();
        Bot.run(() -> VirtualInput.tapMapping(options().keyHotbarSlots[slot]));
        Bot.waitTicks(2);
    }

    private static void equipIfAsked(JsonObject args) {
        if (args.has("item")) {
            WindowFns.equip(J.str(args, "item"), "hand");
        }
    }

    static void tapUse() {
        Bot.run(() -> VirtualInput.tapMapping(options().keyUse));
    }

    /**
     * For keys the game reads as "is it down this tick" (sneak, jump). A tap only works for keys it counts as
     * clicks (attack, use, drop); pressed and released within one tick, these would never be seen.
     */
    static void holdForTicks(KeyMapping key, int ticks) {
        Bot.run(() -> VirtualInput.setMapping(key, true));
        try {
            Bot.waitTicks(ticks);
        } finally {
            Bot.run(() -> VirtualInput.setMapping(key, false));
        }
    }

    private static JsonObject activateAt(BlockPos pos) {
        Bot.readyForWorldInput();
        Bot.aimAtBlock(pos);
        tapUse();
        return WindowFns.afterClick();
    }

    private static JsonObject holdUseOn(BlockPos pos, double seconds) {
        Bot.readyForWorldInput();
        Bot.aimAtBlock(pos);
        Bot.run(() -> VirtualInput.setMapping(options().keyUse, true));
        try {
            long end = System.currentTimeMillis() + (long) (seconds * 1000);
            while (System.currentTimeMillis() < end) {
                Bot.requireControl();
                Bot.pause(50);
            }
        } finally {
            Bot.run(() -> VirtualInput.setMapping(options().keyUse, false));
        }
        return Bot.call(() -> J.obj("held_seconds", seconds, "crosshair", Bot.cursorText()));
    }

    private static void toss(String item, int count) {
        requireThrown(item, WindowFns.throwFromInventory(java.util.Map.of(item, count)));
    }

    private static void tossStack(String item) {
        requireThrown(item, WindowFns.throwFromInventory(java.util.Map.of(item, WindowFns.ONE_STACK)));
    }

    private static void requireThrown(String item, java.util.Map<String, Integer> thrown) {
        if (thrown.getOrDefault(item, 0) == 0) {
            throw new ApiError("not_found", "you carry no " + item, "inventory() lists what you have");
        }
    }

    // ---- digging ----

    private static JsonObject dig(JsonObject args) {
        BlockPos pos = Bot.pos(args);
        Bot.readyForWorldInput();
        BlockState before = Bot.call(() -> {
            BlockState state = Bot.level().getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                throw new ApiError("nothing_to_dig", "there is no solid block at " + pos.toShortString());
            }
            if (state.getDestroySpeed(Bot.level(), pos) < 0) {
                throw new ApiError("unbreakable", Bot.blockId(state) + " cannot be broken");
            }
            return state;
        });
        if (J.bool(args, "auto_tool", true)) {
            WindowFns.equipBestTool(before);
        }
        Bot.aimAtBlock(pos);
        double seconds = Bot.call(() -> {
            float perTick = before.getDestroyProgress(Bot.player(), Bot.level(), pos);
            return perTick <= 0 ? 60.0 : Math.ceil(1f / perTick) / 20.0;
        });
        long timeout = (long) (seconds * 1500) + 2000;
        Bot.run(() -> VirtualInput.setMapping(options().keyAttack, true));
        boolean broken;
        try {
            broken = Bot.waitUntil(() -> Bot.level().getBlockState(pos).getBlock() != before.getBlock(), timeout);
        } finally {
            Bot.run(() -> VirtualInput.setMapping(options().keyAttack, false));
        }
        if (!broken) {
            throw new ApiError("dig_timeout", "still " + Bot.blockId(before) + " after " + timeout / 1000 + "s",
                    "something may be stopping you: protection, or the aim moved");
        }
        Bot.waitTicks(3);
        return Bot.call(() -> {
            JsonObject out = J.obj("broke", Bot.blockId(before), "seconds", Math.round(seconds * 100) / 100.0);
            boolean drops = !before.requiresCorrectToolForDrops() || Bot.player().getMainHandItem().isCorrectToolForDrops(before);
            if (!drops) {
                out.addProperty("warning", "the held tool cannot harvest this block, so it probably dropped nothing");
            }
            return out;
        });
    }

    // ---- placing ----

    private static JsonObject placeBlock(JsonObject args) {
        BlockPos target = Bot.pos(args);
        equipIfAsked(args);
        Bot.readyForWorldInput();
        record Aim(BlockPos neighbour, Direction face, Vec3 point, boolean sneak) {}
        Aim aim = Bot.call(() -> {
            BlockState here = Bot.level().getBlockState(target);
            if (!here.canBeReplaced()) {
                throw new ApiError("occupied", Bot.blockId(here) + " is already at " + target.toShortString());
            }
            if (Bot.player().getBoundingBox().intersects(new net.minecraft.world.phys.AABB(target))) {
                throw new ApiError("occupied", "you are standing in " + target.toShortString(), "step away first");
            }
            Vec3 eye = Bot.player().getEyePosition();
            Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
            for (Direction side : order) {
                BlockPos neighbour = target.relative(side);
                BlockState state = Bot.level().getBlockState(neighbour);
                if (state.isAir() || state.canBeReplaced()) {
                    continue;
                }
                Direction face = side.getOpposite();
                Vec3 point = Vec3.atCenterOf(neighbour).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                if (eye.distanceTo(point) <= Bot.REACH + 0.5 && WorldFns.clearLine(eye, point.add(face.getStepX() * 0.01, face.getStepY() * 0.01, face.getStepZ() * 0.01))) {
                    // A right click on a table, chest, door or lever opens or flips it instead of placing; sneaking places.
                    boolean sneak = Bot.level().getBlockEntity(neighbour) != null || state.getMenuProvider(Bot.level(), neighbour) != null
                            || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)
                            || state.is(BlockTags.BUTTONS) || state.is(BlockTags.BEDS) || state.is(Blocks.LEVER);
                    return new Aim(neighbour, face, point, sneak);
                }
            }
            throw new ApiError("no_support", "no reachable visible block to place against at " + target.toShortString(),
                    "move closer or place a supporting block first");
        });
        Bot.lookAt(aim.point());
        Bot.run(() -> {
            if (aim.sneak()) {
                VirtualInput.setMapping(options().keyShift, true);
            }
        });
        if (aim.sneak()) {
            Bot.waitTicks(2);
        }
        tapUse();
        boolean placed;
        try {
            placed = Bot.waitUntil(() -> !Bot.level().getBlockState(target).canBeReplaced(), 1500);
        } finally {
            Bot.run(() -> VirtualInput.setMapping(options().keyShift, false));
        }
        if (!placed) {
            throw new ApiError("place_failed", "nothing appeared at " + target.toShortString() + "; held "
                    + Bot.call(() -> Bot.itemId(Bot.player().getMainHandItem()))
                    + (args.has("item") ? ", asked for " + J.str(args, "item") : ""), "is a placeable block in hand?");
        }
        return Bot.call(() -> WorldFns.blockJson(target));
    }

    /** Aims at a surface rather than a block, because water counts here, and reads back what appeared. */
    private static JsonObject placeEntity(JsonObject args) {
        BlockPos pos = Bot.pos(args);
        equipIfAsked(args);
        Bot.readyForWorldInput();
        Vec3 point = Vec3.atBottomCenterOf(pos).add(0, 1, 0);
        Bot.lookAt(point);
        int heldBefore = Bot.call(() -> Bot.player().getMainHandItem().getCount());
        Set<Integer> before = Bot.call(ActionFns::entityIdsAround);
        tapUse();
        boolean appeared = Bot.waitUntil(() -> !newEntityIds(before).isEmpty() || Bot.player().getMainHandItem().getCount() < heldBefore, 1500);
        if (!appeared) {
            throw new ApiError("place_failed", "nothing appeared; the game refuses when the spot has no room or is out of reach",
                    "a boat is wider than one block, so aim a full block clear of banks and walls, within about 4 blocks; to board it after, stay within 3");
        }
        Bot.waitTicks(4);
        return Bot.call(() -> {
            JsonArray created = new JsonArray();
            for (int id : newEntityIds(before)) {
                created.add(Bot.entityJson(Bot.level().getEntity(id)));
            }
            return J.obj("new_entities", created);
        });
    }

    private static Set<Integer> entityIdsAround() {
        Set<Integer> out = new HashSet<>();
        for (Entity entity : Bot.level().entitiesForRendering()) {
            out.add(entity.getId());
        }
        return out;
    }

    /** New since the snapshot and close by: far-off entities also appear as chunks load, and are not ours. */
    private static Set<Integer> newEntityIds(Set<Integer> before) {
        Set<Integer> out = entityIdsAround();
        out.removeAll(before);
        out.removeIf(id -> Bot.level().getEntity(id).distanceTo(Bot.player()) > 8);
        return out;
    }

    // ---- using items ----

    private static JsonObject consume(String item) {
        if (item != null) {
            WindowFns.equip(item, "hand");
        }
        Bot.readyForWorldInput();
        ItemStack before = Bot.call(() -> Bot.player().getMainHandItem().copy());
        if (before.isEmpty()) {
            throw new ApiError("nothing_held", "nothing in hand to consume");
        }
        Bot.run(() -> VirtualInput.setMapping(options().keyUse, true));
        boolean started;
        try {
            started = Bot.waitUntil(() -> Bot.player().isUsingItem(), 1000);
            if (started) {
                Bot.waitUntil(() -> !Bot.player().isUsingItem(), 8000);
            }
        } finally {
            Bot.run(() -> VirtualInput.setMapping(options().keyUse, false));
        }
        if (!started) {
            throw new ApiError("cannot_consume", Bot.itemId(before) + " cannot be consumed right now", "not hungry, or not food");
        }
        return Bot.call(() -> J.obj("food", Bot.player().getFoodData().getFoodLevel(),
                "health", Bot.player().getHealth(), "left_in_hand", Bot.player().getMainHandItem().getCount()));
    }

    private static JsonObject fish(JsonObject args) {
        Bot.readyForWorldInput();
        long timeout = J.integer(args, "timeout_s", 60) * 1000L;
        tapUse();
        if (!Bot.waitUntil(() -> Bot.player().fishing != null, 2000)) {
            throw new ApiError("cannot_fish", "no bobber appeared", "hold a fishing rod and face water");
        }
        int stacksBefore = Bot.call(ActionFns::itemTotal);
        boolean bite = Bot.waitUntil(() -> {
            FishingHook hook = Bot.player().fishing;
            return hook == null || ((FishingHookAccessor) hook).cogito$biting();
        }, timeout);
        boolean stillCast = Bot.call(() -> Bot.player().fishing != null);
        if (stillCast) {
            tapUse();
        }
        boolean caught = Bot.waitUntil(() -> itemTotal() > stacksBefore, 4000);
        return J.obj("bite", bite && stillCast, "caught", caught);
    }

    private static int itemTotal() {
        var inventory = Bot.player().getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            total += inventory.getItem(i).getCount();
        }
        return total;
    }
}
