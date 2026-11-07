package dev.cogito.bot;

import dev.cogito.mixin.BlockItemAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Puts a blueprint up one block at a time, from the bottom layer to the top, as Mindcraft's build goal and a person both
 * do. Each click is asked about first: from this eye, on this face, what would come out? The items' own placement code
 * answers, so stairs, doors, sideways logs and top slabs need no table of block kinds. What can be done from where the
 * player stands is done first, nearest first, and only then is there a walk, to a spot where the next block comes out
 * right. Baritone does the walking, and puts down scaffolding when a spot has nothing to stand on.
 */
final class BlueprintBuilder {
    /** What decides how a block sits. The rest, such as how a pane connects, follows from its neighbours. */
    private static final Set<String> SHAPING = Set.of("facing", "axis", "half", "face", "attachment", "orientation", "hanging");
    private static final int MAX_WALKS_PER_CELL = 4;
    private static final int MAX_TRIES_PER_CELL = 3;
    private static final int MAX_WALK_CELLS = 20_000;
    private static final int MAX_DROP = 3;
    /** Beyond this the first thing is to go over there; a closer look at where to stand comes after. */
    private static final int FAR_AWAY = 12;
    private static final int SPOT_RADIUS = 3;
    private static final int LOWEST_SPOT = -3;
    /** Two up, because the stairs along the eaves of a roof face inward, and that is only seen from up on the roof. */
    private static final int HIGHEST_SPOT = 2;
    private static final long WALK_MILLIS = 30_000;
    private static final long APPEAR_MILLIS = 1500;
    private static final double REACH_MARGIN = 0.2;
    private static final double AIM_PAST = 0.05;

    private enum Outcome { FINISHED, PROGRESS, STUCK }

    private record Cell(BlockPos pos, BlockState want) {
        boolean clearing() {
            return want.isAir();
        }
    }

    private record Shot(BlockPos against, Direction face, Vec3 point) {}

    /** One thing to do from where the player stands. A null shot means dig. */
    private record Step(Cell cell, Shot shot) {}

    private final Blueprint blueprint;
    private final BlockPos origin;
    private final long deadline;
    private final Consumer<String> say;
    /** Placed as well as could be and still not as planned, such as a standing torch for a wall torch. Digging those up again would not help. */
    private final Set<BlockPos> leftTurned = new HashSet<>();

    BlueprintBuilder(Blueprint blueprint, BlockPos origin, long deadline, Consumer<String> say) {
        this.blueprint = blueprint;
        this.origin = origin;
        this.deadline = deadline;
        this.say = say;
    }

    static boolean same(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        for (Property<?> property : first.getProperties()) {
            boolean shaping = SHAPING.contains(property.getName()) || (property.getName().equals("type") && first.getBlock() instanceof SlabBlock);
            if (shaping && first.getValue(property) != second.getValue(property)) {
                return false;
            }
        }
        return true;
    }

    void buildAll() {
        for (int y = 0; y < blueprint.height && !outOfTime(); y++) {
            buildLayer(y);
        }
    }

    private boolean outOfTime() {
        return System.currentTimeMillis() >= deadline;
    }

    /**
     * A block with nothing to attach to the right way yet may have it once the rest of the layer stands, so passes go on
     * while they get somewhere. Only when nothing more comes out as planned does one block go in as it will, rather the
     * wrong way round than a hole, and that may in turn give the next block what it needs.
     */
    private void buildLayer(int y) {
        boolean asItComes = false;
        while (!outOfTime()) {
            Outcome outcome = pass(y, !asItComes);
            if (outcome == Outcome.FINISHED) {
                return;
            }
            if (outcome == Outcome.PROGRESS) {
                asItComes = false;
            } else if (asItComes) {
                return;
            } else {
                asItComes = true;
            }
        }
    }

    private Outcome pass(int y, boolean exact) {
        Set<BlockPos> skip = new HashSet<>();
        Map<BlockPos, Set<BlockPos>> spotsTried = new HashMap<>();
        Map<BlockPos, Integer> tries = new HashMap<>();
        boolean didSomething = false;
        while (!outOfTime()) {
            Bot.requireControl();
            List<Cell> left = Bot.call(() -> cellsLeft(y, skip));
            if (left.isEmpty()) {
                return skip.isEmpty() ? Outcome.FINISHED : didSomething ? Outcome.PROGRESS : Outcome.STUCK;
            }
            Step step = Bot.call(() -> stepFromHere(left, exact));
            if (step == null) {
                walkTowards(left, skip, spotsTried, exact);
                continue;
            }
            BlockPos pos = step.cell().pos();
            if (tries.merge(pos, 1, Integer::sum) > MAX_TRIES_PER_CELL) {
                skip.add(pos);
                continue;
            }
            didSomething |= perform(step, skip);
            if (didSomething && !exact) {
                return Outcome.PROGRESS;
            }
        }
        return Outcome.PROGRESS;
    }

    /** Client thread. What is still undone in a layer, nearest first. */
    private List<Cell> cellsLeft(int y, Set<BlockPos> skip) {
        ClientLevel level = Bot.level();
        Vec3 me = Bot.player().position();
        List<Cell> left = new ArrayList<>();
        for (int z = 0; z < blueprint.length; z++) {
            for (int x = 0; x < blueprint.width; x++) {
                BlockState want = blueprint.at(x, y, z);
                if (want == null) {
                    continue;
                }
                BlockPos pos = origin.offset(x, y, z);
                BlockState here = level.getBlockState(pos);
                if (!want.isAir() && Blueprint.costOf(want) == null) {
                    // The top of a door comes with its bottom half. All it needs is the room, which scaffolding may have taken.
                    if (Blueprint.placeable(want) && !here.canBeReplaced() && here.getBlock() != want.getBlock() && !skip.contains(pos)) {
                        left.add(new Cell(pos, Blocks.AIR.defaultBlockState()));
                    }
                    continue;
                }
                // A block in place but turned the wrong way stays: digging it up only helps if it can then be placed right,
                // and it would not have ended up wrong if that were easy. buildStatus reports it.
                boolean done = want.isAir() ? here.isAir() || !here.getFluidState().isEmpty() : here.getBlock() == want.getBlock() || leftTurned.contains(pos);
                if (!done && !skip.contains(pos) && (want.isAir() || carrying(want))) {
                    left.add(new Cell(pos, want));
                }
            }
        }
        left.sort(Comparator.comparingDouble(cell -> cell.pos().distToCenterSqr(me)));
        return left;
    }

    private static boolean carrying(BlockState want) {
        Item item = want.getBlock().asItem();
        return Obtain.carried().getOrDefault(item, 0) > 0;
    }

    /** Client thread. The nearest thing that needs no walking. */
    private Step stepFromHere(List<Cell> left, boolean exact) {
        LocalPlayer player = Bot.player();
        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange() + 1;
        for (Cell cell : left) {
            if (eye.distanceTo(Vec3.atCenterOf(cell.pos())) > reach) {
                break;
            }
            if (needsDigging(cell)) {
                if (canDigFromHere(cell.pos())) {
                    return new Step(cell, null);
                }
                continue;
            }
            if (bodyInTheWay(cell)) {
                continue;
            }
            Shot shot = shot(eye, cell.pos(), cell.want(), exact);
            if (shot != null) {
                return new Step(cell, shot);
            }
        }
        return null;
    }

    /** Client thread. Grass and flowers could be placed over, but they catch the crosshair on its way to the block behind, so they go first. */
    private static boolean needsDigging(Cell cell) {
        ClientLevel level = Bot.level();
        BlockState here = level.getBlockState(cell.pos());
        return cell.clearing() || !here.canBeReplaced() || !here.getShape(level, cell.pos()).isEmpty();
    }

    /** Client thread. Not the block underfoot: a person steps off first. */
    private static boolean canDigFromHere(BlockPos pos) {
        LocalPlayer player = Bot.player();
        if (player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > Bot.REACH || standingOn(player, pos)) {
            return false;
        }
        return Bot.visiblePoint(pos) != null;
    }

    private static boolean standingOn(LocalPlayer player, BlockPos pos) {
        AABB below = player.getBoundingBox().move(0, -0.2, 0);
        return below.intersects(new AABB(pos));
    }

    /** Client thread. */
    private static boolean bodyInTheWay(Cell cell) {
        AABB space = new AABB(cell.pos());
        if (cell.want().hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            space = space.expandTowards(0, 1, 0);
        }
        return Bot.player().getBoundingBox().intersects(space);
    }

    /** Worker thread. True if the world changed. */
    private boolean perform(Step step, Set<BlockPos> skip) {
        Cell cell = step.cell();
        try {
            if (step.shot() == null) {
                Skills.call("dig", J.obj("x", cell.pos().getX(), "y", cell.pos().getY(), "z", cell.pos().getZ()));
                return true;
            }
            return click(cell, step.shot());
        } catch (ApiError e) {
            if (Skills.isControlFailure(e)) {
                throw e;
            }
            skip.add(cell.pos());
            say.accept("could not do " + cell.pos().toShortString() + ": " + e.getMessage());
            return false;
        }
    }

    private boolean click(Cell cell, Shot shot) {
        WindowFns.equip(Bot.itemId(new ItemStack(cell.want().getBlock().asItem())), "hand");
        Bot.lookAt(shot.point());
        boolean onIt = Bot.call(() -> Bot.mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(shot.against()) && hit.getDirection() == shot.face());
        if (!onIt) {
            return false;
        }
        ActionFns.tapUse();
        boolean appeared = Bot.waitUntil(() -> !Bot.level().getBlockState(cell.pos()).canBeReplaced(), APPEAR_MILLIS);
        if (!appeared) {
            return false;
        }
        if (Bot.call(() -> !same(Bot.level().getBlockState(cell.pos()), cell.want()))) {
            leftTurned.add(cell.pos());
        }
        return true;
    }

    /** The nearest cell that some spot can serve gets the walk. A cell far away first needs the player over there at all. */
    private void walkTowards(List<Cell> left, Set<BlockPos> skip, Map<BlockPos, Set<BlockPos>> spotsTried, boolean exact) {
        Cell nearest = left.get(0);
        // The cell's own position in its tried list stands for this walk, which then happens once.
        if (Bot.call(() -> nearest.pos().distToCenterSqr(Bot.player().position())) > FAR_AWAY * FAR_AWAY
                && spotsTried.computeIfAbsent(nearest.pos(), key -> new HashSet<>()).add(nearest.pos())) {
            BaritoneBridge.walkNear(nearest.pos(), SPOT_RADIUS, WALK_MILLIS);
            return;
        }
        for (Cell cell : left) {
            Set<BlockPos> tried = spotsTried.computeIfAbsent(cell.pos(), key -> new HashSet<>());
            BlockPos spot = tried.size() >= MAX_WALKS_PER_CELL ? null : Bot.call(() -> spotFor(cell, exact, tried));
            if (spot != null) {
                tried.add(spot);
                BaritoneBridge.walkToCell(spot, WALK_MILLIS);
                return;
            }
            skip.add(cell.pos());
        }
    }

    /**
     * Client thread. In order: spots that can be walked to, shortest walk first; then spots Baritone would have to scaffold
     * to; then spots with ground that no walk reaches, such as the inside of a room that has been closed, which are the
     * nearest in a straight line and the last that should be tried.
     */
    private static BlockPos spotFor(Cell cell, boolean exact, Set<BlockPos> tried) {
        ClientLevel level = Bot.level();
        LocalPlayer player = Bot.player();
        BlockPos target = cell.pos();
        boolean digging = needsDigging(cell);
        Map<BlockPos, Integer> onFoot = walkableFrom(level, player.blockPosition());
        List<BlockPos> walkable = new ArrayList<>();
        List<BlockPos> inTheAir = new ArrayList<>();
        List<BlockPos> shutOff = new ArrayList<>();
        for (int dx = -SPOT_RADIUS; dx <= SPOT_RADIUS; dx++) {
            for (int dz = -SPOT_RADIUS; dz <= SPOT_RADIUS; dz++) {
                for (int dy = LOWEST_SPOT; dy <= HIGHEST_SPOT; dy++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    boolean inTheWay = dx == 0 && dz == 0 && dy >= -1 && dy <= 1;
                    if (inTheWay || tried.contains(feet) || !passable(level, feet) || !passable(level, feet.above())) {
                        continue;
                    }
                    (onFoot.containsKey(feet) ? walkable : passable(level, feet.below()) ? inTheAir : shutOff).add(feet);
                }
            }
        }
        Comparator<BlockPos> nearest = Comparator.comparingDouble(feet -> feet.distToCenterSqr(player.position()));
        walkable.sort(Comparator.comparingInt(onFoot::get));
        inTheAir.sort(nearest);
        shutOff.sort(nearest);
        walkable.addAll(inTheAir);
        walkable.addAll(shutOff);
        for (BlockPos feet : walkable) {
            Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + player.getEyeHeight(), feet.getZ() + 0.5);
            boolean good = digging ? canSeeToDig(eye, target, feet) : shot(eye, target, cell.want(), exact) != null;
            if (good) {
                return feet;
            }
        }
        return null;
    }

    /** Client thread. How many steps each spot around here is from the player, with no digging and no scaffolding. Doors open. */
    private static Map<BlockPos, Integer> walkableFrom(ClientLevel level, BlockPos start) {
        Map<BlockPos, Integer> steps = new HashMap<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        steps.put(start, 0);
        queue.add(start);
        while (!queue.isEmpty() && steps.size() < MAX_WALK_CELLS) {
            BlockPos from = queue.remove();
            int next = steps.get(from) + 1;
            boolean headRoom = walkThrough(level, from.above(2));
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos beside = from.relative(direction);
                BlockPos landing = null;
                if (headRoom && canStand(level, beside.above())) {
                    landing = beside.above();
                } else if (walkThrough(level, beside) && walkThrough(level, beside.above())) {
                    for (int drop = 0; drop <= MAX_DROP && landing == null; drop++) {
                        if (canStand(level, beside.below(drop))) {
                            landing = beside.below(drop);
                        }
                    }
                }
                if (landing != null && steps.putIfAbsent(landing, next) == null) {
                    queue.add(landing);
                }
            }
        }
        return steps;
    }

    private static boolean canStand(ClientLevel level, BlockPos feet) {
        return walkThrough(level, feet) && walkThrough(level, feet.above()) && !passable(level, feet.below());
    }

    private static boolean walkThrough(ClientLevel level, BlockPos pos) {
        return passable(level, pos) || level.getBlockState(pos).is(BlockTags.DOORS);
    }

    private static boolean canSeeToDig(Vec3 eye, BlockPos target, BlockPos feet) {
        return !feet.below().equals(target) && eye.distanceTo(Vec3.atCenterOf(target)) <= Bot.REACH && Bot.visiblePoint(eye, target) != null;
    }

    private static boolean passable(ClientLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Client thread. The same question the game asks when the click lands. The player's view is turned for the answer and
     * back, all within one call on the client thread, so nothing sees it.
     */
    private static Shot shot(Vec3 eye, BlockPos target, BlockState want, boolean exact) {
        LocalPlayer player = Bot.player();
        ClientLevel level = Bot.level();
        if (!(want.getBlock().asItem() instanceof BlockItem item)) {
            return null;
        }
        ItemStack stack = new ItemStack(item);
        double reach = player.blockInteractionRange() - REACH_MARGIN;
        float yawBefore = player.getYRot();
        float pitchBefore = player.getXRot();
        try {
            for (Direction side : Direction.values()) {
                BlockPos against = target.relative(side);
                BlockState there = level.getBlockState(against);
                VoxelShape shape = there.getShape(level, against);
                if (there.canBeReplaced() || shape.isEmpty() || clickWouldUseIt(level, against, there)) {
                    continue;
                }
                Direction face = side.getOpposite();
                for (Vec3 point : pointsOn(against, shape.bounds(), face)) {
                    if (eye.distanceTo(point) > reach) {
                        continue;
                    }
                    Vec3 delta = point.subtract(eye);
                    Vec3 past = point.add(delta.normalize().scale(AIM_PAST));
                    BlockHitResult hit = level.clip(new ClipContext(eye, past, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                    if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(against) || hit.getDirection() != face) {
                        continue;
                    }
                    double flat = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
                    player.setYRot((float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90f);
                    player.setXRot((float) -(Mth.atan2(delta.y, flat) * Mth.RAD_TO_DEG));
                    BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
                    BlockState result = comesOut(item, context, want, target);
                    if (result != null && (!exact || same(result, want))) {
                        return new Shot(against, face, point);
                    }
                }
            }
            return null;
        } finally {
            player.setYRot(yawBefore);
            player.setXRot(pitchBefore);
        }
    }

    private static BlockState comesOut(BlockItem item, BlockPlaceContext context, BlockState want, BlockPos target) {
        if (!context.canPlace()) {
            return null;
        }
        BlockState result = ((BlockItemAccessor) item).cogito$placementState(context);
        // The item refuses while the player's body is in the cell, which says nothing about the spot being considered.
        if (result == null && Bot.player().getBoundingBox().intersects(new AABB(target))) {
            return want.getBlock().getStateForPlacement(context);
        }
        return result;
    }

    /** A right click on these opens or flips them instead of placing anything. */
    private static boolean clickWouldUseIt(ClientLevel level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(pos) != null || state.getMenuProvider(level, pos) != null
                || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.BUTTONS);
    }

    /** The middle of a face, with a few more where it matters which part is clicked: high or low on a side, for top and bottom halves. */
    private static List<Vec3> pointsOn(BlockPos pos, AABB box, Direction face) {
        double x0 = pos.getX() + box.minX;
        double x1 = pos.getX() + box.maxX;
        double y0 = pos.getY() + box.minY;
        double y1 = pos.getY() + box.maxY;
        double z0 = pos.getZ() + box.minZ;
        double z1 = pos.getZ() + box.maxZ;
        double midX = (x0 + x1) / 2;
        double midZ = (z0 + z1) / 2;
        List<Vec3> points = new ArrayList<>();
        switch (face.getAxis()) {
            case Y -> {
                double y = face == Direction.UP ? y1 : y0;
                points.add(new Vec3(midX, y, midZ));
                points.add(new Vec3(Mth.lerp(0.1, x0, x1), y, midZ));
                points.add(new Vec3(Mth.lerp(0.9, x0, x1), y, midZ));
                points.add(new Vec3(midX, y, Mth.lerp(0.1, z0, z1)));
                points.add(new Vec3(midX, y, Mth.lerp(0.9, z0, z1)));
            }
            case X -> {
                double x = face == Direction.EAST ? x1 : x0;
                points.add(new Vec3(x, Mth.lerp(0.25, y0, y1), midZ));
                points.add(new Vec3(x, Mth.lerp(0.75, y0, y1), midZ));
            }
            case Z -> {
                double z = face == Direction.SOUTH ? z1 : z0;
                points.add(new Vec3(midX, Mth.lerp(0.25, y0, y1), z));
                points.add(new Vec3(midX, Mth.lerp(0.75, y0, y1), z));
            }
        }
        return points;
    }
}
