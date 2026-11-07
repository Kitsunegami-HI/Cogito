package dev.cogito.bot;

import com.google.gson.JsonObject;
import dev.cogito.agent.Reflexes;
import dev.cogito.control.Control;
import dev.cogito.control.VirtualInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * What every API function shares. Worker threads call in here; anything touching the game goes through
 * {@link #call}, which runs it on the client thread and waits.
 */
public final class Bot {
    public static final double REACH = 4.5;

    private Bot() {}

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }

    // ---- threads ----

    public static <T> T call(Supplier<T> task) {
        if (mc().isSameThread()) {
            return task.get();
        }
        CompletableFuture<T> future = mc().submit(task);
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiError("interrupted", "stopped: control was taken back or the goal changed");
        } catch (TimeoutException e) {
            throw new ApiError("timeout", "the game did not respond within 15 seconds");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ApiError api) {
                throw api;
            }
            throw new ApiError("game_error", String.valueOf(e.getCause()));
        }
    }

    public static void run(Runnable task) {
        call(() -> {
            task.run();
            return null;
        });
    }

    /** Stops any walking in progress; for code outside this package. */
    public static void stopPathing() {
        Pathing.stop();
    }

    public static void pause(long millis) {
        Reflexes.checkOvertaken();
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiError("interrupted", "stopped: control was taken back or the goal changed");
        }
    }

    public static void waitTicks(int ticks) {
        pause(ticks * 50L);
    }

    /** Polls a condition on the client thread. Throws when control is taken back. */
    public static boolean waitUntil(Supplier<Boolean> condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            requireControl();
            boolean met = call(condition);
            // A reflex can make a condition true, for example by stopping a walk: that is not the action succeeding.
            Reflexes.checkOvertaken();
            if (met) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            pause(25);
        }
    }

    public static void requireControl() {
        if (!Control.isAi()) {
            throw new ApiError("human_in_control", "the person is in control; actions are blocked",
                    Control.isHumanLocked() ? "wait for the person to press the takeover key"
                            : "ask for control with take_control");
        }
    }

    // ---- world access ----

    public static LocalPlayer player() {
        LocalPlayer player = mc().player;
        if (player == null) {
            throw new ApiError("not_in_world", "not in a world yet");
        }
        return player;
    }

    public static ClientLevel level() {
        ClientLevel level = mc().level;
        if (level == null) {
            throw new ApiError("not_in_world", "not in a world yet");
        }
        return level;
    }

    public static Identifier id(String raw) {
        Identifier parsed = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
        if (parsed == null) {
            throw new ApiError("bad_argument", "'" + raw + "' is not a valid id");
        }
        return parsed;
    }

    public static Item item(String raw) {
        return BuiltInRegistries.ITEM.getOptional(id(raw))
                .orElseThrow(() -> new ApiError("bad_argument", "no item named '" + raw + "'",
                        "describeBlock or recipesAll show real ids"));
    }

    public static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /**
     * Client thread. A hotbar place to bring something from the main inventory to, without a fight over it. Baritone moves a
     * pickaxe it finds in the main inventory to place 0 at once, and a building block to place 8 when the hotbar has none,
     * so pushing a pickaxe out, or using those two places, gets undone while the window is still open: the hand ends up
     * holding the pickaxe again and every click after that goes wrong. So: an empty place in the middle, else one holding
     * something that matters least.
     */
    public static int hotbarPlaceToBorrow(net.minecraft.world.entity.player.Inventory inventory) {
        for (int place = 1; place <= 7; place++) {
            if (inventory.getItem(place).isEmpty()) {
                return place;
            }
        }
        for (int place = 7; place >= 1; place--) {
            ItemStack stack = inventory.getItem(place);
            if (!stack.isDamageableItem() && !stack.has(net.minecraft.core.component.DataComponents.FOOD)
                    && !stack.is(Items.COBBLESTONE) && !stack.is(Items.DIRT) && !stack.is(Items.NETHERRACK) && !stack.is(Items.STONE)) {
                return place;
            }
        }
        for (int place = 7; place >= 1; place--) {
            if (!inventory.getItem(place).is(net.minecraft.tags.ItemTags.PICKAXES)) {
                return place;
            }
        }
        return 8;
    }

    /** Client thread. Whether this is food that a furnace makes more of a meal, as raw meat is. */
    public static boolean cooksIntoBetterFood(ItemStack stack) {
        net.minecraft.world.food.FoodProperties food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
        if (food == null) {
            return false;
        }
        Item cooked = RecipeIndex.get().smeltsInto(stack.getItem());
        net.minecraft.world.food.FoodProperties better = cooked == null ? null : new ItemStack(cooked).get(net.minecraft.core.component.DataComponents.FOOD);
        return better != null && better.nutrition() > food.nutrition();
    }

    /**
     * An item id, a #tag, or an item with its potion as item#potion, such as minecraft:potion#minecraft:night_vision,
     * which is how itemKey names a bottle.
     */
    public static boolean matchesItem(ItemStack stack, String wanted) {
        if (stack.isEmpty()) {
            return false;
        }
        if (wanted.startsWith("#")) {
            return stack.is(TagKey.create(Registries.ITEM, id(wanted.substring(1))));
        }
        int hash = wanted.indexOf('#');
        if (hash > 0) {
            return stack.getItem() == item(wanted.substring(0, hash)) && potionIdOf(stack).equals(id(wanted.substring(hash + 1)).toString());
        }
        return stack.getItem() == item(wanted);
    }

    /** The item id, with the potion after a # for bottles, so that kinds of potion can be told apart. */
    public static String itemKey(ItemStack stack) {
        String potion = potionIdOf(stack);
        return potion.isEmpty() ? itemId(stack) : itemId(stack) + "#" + potion;
    }

    public static String potionIdOf(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null || contents.potion().isEmpty()) {
            return "";
        }
        return contents.potion().get().unwrapKey().map(key -> key.identifier().toString()).orElse("");
    }

    /** Block ids or #tags to the blocks they name. */
    public static Set<Block> blocks(List<String> names) {
        Set<Block> out = new LinkedHashSet<>();
        for (String name : names) {
            if (name.startsWith("#")) {
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, id(name.substring(1)));
                BuiltInRegistries.BLOCK.getTagOrEmpty(tag).forEach(holder -> out.add(holder.value()));
            } else {
                BuiltInRegistries.BLOCK.getOptional(id(name)).ifPresent(out::add);
            }
        }
        if (out.isEmpty()) {
            throw new ApiError("bad_argument", "none of " + names + " is a known block or #tag");
        }
        return out;
    }

    public static BlockPos pos(JsonObject args) {
        return new BlockPos(J.integer(args, "x"), J.integer(args, "y"), J.integer(args, "z"));
    }

    public static JsonObject stackJson(ItemStack stack) {
        JsonObject out = J.obj("id", itemId(stack), "count", stack.getCount());
        String name = stack.getHoverName().getString();
        out.addProperty("name", name);
        String potion = potionIdOf(stack);
        if (!potion.isEmpty()) {
            out.addProperty("potion", potion);
        }
        if (stack.isDamageableItem()) {
            out.addProperty("durability", stack.getMaxDamage() - stack.getDamageValue());
        }
        return out;
    }

    public static JsonObject entityJson(Entity entity) {
        LocalPlayer me = player();
        JsonObject out = J.obj("id", entity.getId(), "type",
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                "name", entity.getName().getString(),
                "pos", J.nums(entity.getX(), entity.getY(), entity.getZ()),
                "distance", Math.round(entity.distanceTo(me) * 10) / 10.0);
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            out.addProperty("health", Math.round(living.getHealth() * 10) / 10.0);
        }
        if (entity instanceof net.minecraft.world.entity.monster.Enemy) {
            out.addProperty("hostile", true);
        }
        if (entity instanceof net.minecraft.world.entity.item.ItemEntity item) {
            out.add("item", stackJson(item.getItem()));
        }
        return out;
    }

    // ---- aiming ----

    /** Worker thread. Turns the view smoothly, then lets the crosshair update. */
    public static void lookAt(Vec3 target) {
        requireControl();
        run(() -> {
            LocalPlayer player = player();
            Vec3 eye = player.getEyePosition();
            Vec3 delta = target.subtract(eye);
            double flat = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90f;
            float pitch = (float) -(Mth.atan2(delta.y, flat) * Mth.RAD_TO_DEG);
            VirtualInput.turnTo(yaw, pitch);
        });
        settleLook();
    }

    public static void look(float yaw, float pitch) {
        requireControl();
        run(() -> VirtualInput.turnTo(yaw, pitch));
        settleLook();
    }

    private static void settleLook() {
        if (!waitUntil(VirtualInput::lookIdle, 4000)) {
            throw new ApiError("timeout", "the view did not finish turning; is the window minimized?");
        }
        waitTicks(2);
    }

    /** Client thread. The point on a block a player would aim at, or null if none is in sight. */
    public static Vec3 visiblePoint(BlockPos pos) {
        return visiblePoint(player().getEyePosition(), pos);
    }

    /** The same from an eye somewhere else, for working out where to stand. */
    public static Vec3 visiblePoint(Vec3 eye, BlockPos pos) {
        LocalPlayer player = player();
        ClientLevel level = level();
        BlockState state = level.getBlockState(pos);
        // A water source is a block a bottle or bucket can be used on; the crosshair itself never lands on fluids.
        boolean fluid = state.getFluidState().isSource();
        VoxelShape shape = fluid ? state.getFluidState().getShape(level, pos) : state.getShape(level, pos, CollisionContext.of(player));
        ClipContext.Fluid fluids = fluid ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE;
        AABB box = shape.isEmpty() ? new AABB(0.25, 0.25, 0.25, 0.75, 0.75, 0.75) : shape.bounds();
        Vec3 min = new Vec3(pos.getX() + box.minX, pos.getY() + box.minY, pos.getZ() + box.minZ);
        Vec3 max = new Vec3(pos.getX() + box.maxX, pos.getY() + box.maxY, pos.getZ() + box.maxZ);
        Vec3 center = min.add(max).scale(0.5);
        List<Vec3> points = new ArrayList<>();
        points.add(center);
        for (Direction face : Direction.values()) {
            points.add(new Vec3(
                    face.getStepX() == 0 ? center.x : face.getStepX() > 0 ? max.x - 0.02 : min.x + 0.02,
                    face.getStepY() == 0 ? center.y : face.getStepY() > 0 ? max.y - 0.02 : min.y + 0.02,
                    face.getStepZ() == 0 ? center.z : face.getStepZ() > 0 ? max.z - 0.02 : min.z + 0.02));
        }
        for (Vec3 point : points) {
            BlockHitResult hit = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, fluids, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                return point;
            }
        }
        return null;
    }

    /** Worker thread. Aims the crosshair at a block and proves it landed there. */
    public static void aimAtBlock(BlockPos pos) {
        Vec3 point = call(() -> {
            double distance = player().getEyePosition().distanceTo(Vec3.atCenterOf(pos));
            if (distance > REACH + 1.0) {
                throw new ApiError("out_of_reach", "block " + pos.toShortString() + " is " + Math.round(distance)
                        + " blocks away; reach is about " + REACH, "goto({x,y,z,range:3}) first");
            }
            Vec3 visible = visiblePoint(pos);
            if (visible == null) {
                throw new ApiError("no_line_of_sight", "something blocks the view of " + pos.toShortString(),
                        "move to where it is visible, or dig what is in the way");
            }
            return visible;
        });
        lookAt(point);
        // The game's crosshair does not land on fluids, so for water the look itself has to do: items that work on water raytrace it themselves.
        boolean fluid = call(() -> level().getBlockState(pos).getFluidState().isSource());
        if (!fluid && !call(() -> mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(pos))) {
            String landed = call(Bot::cursorText);
            throw new ApiError("aim_missed", "the crosshair landed on " + landed + ", not " + pos.toShortString(),
                    "move closer or clear what is in the way");
        }
    }

    /** Worker thread. Aims at one side of a block, for what lands on the side it is used on, such as fire. */
    public static void aimAtFace(BlockPos pos, String name) {
        Direction face = null;
        for (Direction direction : Direction.values()) {
            if (direction.getSerializedName().equalsIgnoreCase(name)) {
                face = direction;
            }
        }
        if (face == null) {
            throw new ApiError("bad_argument", "no face called " + name, "up, down, north, south, east or west");
        }
        Vec3 point = Vec3.atCenterOf(pos).add(face.getStepX() * 0.49, face.getStepY() * 0.49, face.getStepZ() * 0.49);
        lookAt(point);
        Direction wanted = face;
        if (!call(() -> mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(pos) && hit.getDirection() == wanted)) {
            String landed = call(Bot::cursorText);
            throw new ApiError("aim_missed", "the crosshair landed on " + landed + ", not the " + name + " face of " + pos.toShortString(),
                    "stand within reach on that side of it, with that face in view");
        }
    }

    public static String cursorText() {
        HitResult hit = mc().hitResult;
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            return blockId(level().getBlockState(block.getBlockPos())) + " at " + block.getBlockPos().toShortString()
                    + ", " + block.getDirection().getSerializedName() + " face";
        }
        if (hit instanceof EntityHitResult entity) {
            return "entity " + entity.getEntity().getName().getString();
        }
        return "nothing";
    }

    /** Worker thread. Aims at an entity, in reach and in sight. */
    public static void aimAtEntity(Entity entity) {
        lookAt(call(() -> entity.getBoundingBox().getCenter()));
        if (!call(() -> mc().hitResult instanceof EntityHitResult hit && hit.getEntity() == entity)) {
            throw new ApiError("aim_missed", "the crosshair did not land on " + entity.getName().getString()
                    + "; it is on " + call(Bot::cursorText), "move closer; entity reach is about 3 blocks");
        }
    }

    public static Entity entityById(int id) {
        Entity entity = level().getEntity(id);
        if (entity == null) {
            throw new ApiError("not_found", "no entity with id " + id + " is loaded", "entities() lists current ids");
        }
        return entity;
    }

    /** World input needs no open screen and a grabbed mouse, as for a player. */
    public static void readyForWorldInput() {
        requireControl();
        boolean grabbed = call(() -> {
            if (mc().screen != null) {
                throw new ApiError("window_open", "a window is open: " + mc().screen.getClass().getSimpleName(),
                        "closeWindow() first");
            }
            if (mc().mouseHandler.isMouseGrabbed()) {
                return true;
            }
            mc().mouseHandler.grabMouse();
            return false;
        });
        if (!grabbed) {
            // Grabbing delays attacks by one tick, exactly as clicking into the game does.
            waitTicks(3);
        }
    }
}
