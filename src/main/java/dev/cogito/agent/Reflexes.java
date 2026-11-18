package dev.cogito.agent;

import dev.cogito.compat.Mc;
import dev.cogito.Config;
import dev.cogito.bot.ApiError;
import dev.cogito.bot.Bot;
import dev.cogito.control.Control;
import dev.cogito.control.VirtualInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import dev.cogito.compat.Click;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The body's own safety, with no model in the loop: a model turn takes seconds, and a creeper does not wait.
 * Runs on the client thread each tick, while the AI has control and whether or not a goal is running. When a
 * reflex starts, whatever action is in progress stops with an error the executor can read, and new actions wait
 * until the reflex ends. It never starts a fight with another player or anything neutral that has not attacked.
 */
public final class Reflexes {
    private enum Kind {
        NONE, ESCAPE_LAVA, SURFACE, FLEE, FIGHT, EAT
    }

    private static final int FOOD_LOW = 6;
    private static final float RAW_FOOD_BELOW_HEALTH = 8;
    private static final int HEALTH_LOW = 6;
    private static final int HEALTH_LOW_UNARMED = 12;
    /** Attack bonus of a wooden sword; every pickaxe up to iron is below it. */
    private static final double DECENT_WEAPON = 3.0;
    public static final String HAZARD_AHEAD = "lava, fire or a drop of more than 3 blocks just ahead. If you mean to go there, because a spell, potion or"
            + " effect carries you over it (status lists your effects), hold forward with confirm_hazard:true";
    private static final int ENGAGE_RANGE = 5;
    private static final int CROWD = 4;
    private static final int CROWD_RANGE = 7;
    private static final int CHASE_RANGE = 16;
    private static final double MELEE_REACH = 3.0;
    private static final int FIGHT_TICKS = 400;
    private static final int FLEE_TICKS = 160;
    private static final int IDLE_LOOK_TICKS = 40;
    private static final int IDLE_WINDOW_TICKS = 200;
    private static final int EAT_COOLDOWN_TICKS = 40;
    private static final float LEVEL_PITCH = 20f;
    private static int idleLookTicks;
    private static int idleWindowTicks;
    private static int eatCooldownUntil;
    private static final int ESCAPE_TICKS = 300;
    private static final int EAT_TICKS = 100;
    private static final int RESPAWN_DELAY_TICKS = 30;
    private static final int LEAVE_ALONE_TICKS = 200;
    /** Worth too much, or harmful, to eat without thinking about it. */
    private static final Set<Item> NOT_FOR_REFLEX = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.PUFFERFISH,
            Items.POISONOUS_POTATO, Items.CHICKEN, Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT, Items.GOLDEN_APPLE,
            Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_CARROT);

    private static final ThreadLocal<Long> ACTION_EPOCH = new ThreadLocal<>();

    private static volatile Kind active = Kind.NONE;
    private static boolean hazardConfirmed;
    /** Goes up each time a reflex takes the body; an action that started before that has been overtaken. */
    private static volatile long epoch;
    private static volatile String doing = "";

    private static int clock;
    private static int ticksInReflex;
    private static LivingEntity target;
    private static LivingEntity ignored;
    private static int ignoreUntil;
    private static int fleeCooldownUntil;
    private static boolean weaponChosen;
    private static int slotBeforeEating = -1;
    private static boolean deathHandled;
    private static int respawnAt;

    private Reflexes() {}

    // ---- what the rest of the agent sees ----

    public static boolean isActive() {
        return active != Kind.NONE;
    }

    /** What the body is doing on its own right now, or empty. */
    public static String doing() {
        return doing;
    }

    /** Which reflex is running: escape_lava, surface, flee, fight or eat. Null when none is. */
    public static String kind() {
        return active == Kind.NONE ? null : active.name().toLowerCase(Locale.ROOT);
    }

    /** Client thread. What the running reflex is aimed at, or null. */
    public static Entity target() {
        return target;
    }

    /** Worker thread. Waits out a running reflex, then marks an action's start. Pass the result to endAction. */
    public static Long beginAction() {
        Long outer = ACTION_EPOCH.get();
        // An action that a reflex has overtaken must not start anything new, such as opening a window the reflex then
        // cannot eat through. It finds out here, at once, instead of at its next wait.
        if (outer != null) {
            checkOvertaken();
        }
        if (outer == null) {
            while (isActive()) {
                Bot.requireControl();
                Bot.pause(50);
            }
            ACTION_EPOCH.set(epoch);
        }
        return outer;
    }

    public static void endAction(Long outer) {
        if (outer == null) {
            ACTION_EPOCH.remove();
        }
    }

    /**
     * For a skill that was overtaken and means to carry on: waits for the body to be free, a respawn included, then
     * counts as having started now. A model-driven action never does this, because a model should look before going on.
     */
    public static void rejoin() {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && (isActive() || Bot.call(Reflexes::bodyIsDown))) {
            Bot.requireControl();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiError("interrupted", "stopped: control was taken back or the goal changed");
            }
        }
        ACTION_EPOCH.set(epoch);
    }

    private static boolean bodyIsDown() {
        Minecraft mc = Minecraft.getInstance();
        return mc.screen instanceof DeathScreen || (mc.player != null && mc.player.isDeadOrDying());
    }

    /** Called from every wait in an action: if a reflex took the body meanwhile, the action is over. */
    public static void checkOvertaken() {
        Long started = ACTION_EPOCH.get();
        if (started != null && started != epoch) {
            String what = doing;
            throw new ApiError("interrupted_by_reflex", "this action stopped because the body reacted to danger"
                    + (what.isEmpty() ? "" : ": " + what), "look at the state and events, then carry on");
        }
    }

    // ---- each tick ----

    /** Client thread. */
    public static void tick() {
        clock++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!Config.get().reflexes || !Control.isAi() || player == null || mc.isPaused()) {
            stopIfActive();
            deathHandled = false;
            return;
        }
        if (mc.screen instanceof DeathScreen || player.isDeadOrDying()) {
            handleDeath(mc, player);
            return;
        }
        deathHandled = false;
        if (player.getAbilities().invulnerable || player.isSpectator() || player.isSleeping() || player.isPassenger() || player.isFallFlying()) {
            stopIfActive();
            return;
        }
        levelTheViewWhenIdle(player);
        Kind wanted = choose(mc, player);
        if (wanted != active) {
            LivingEntity chosen = target;
            stopIfActive();
            if (wanted != Kind.NONE) {
                target = chosen;
                begin(wanted);
            }
        }
        if (active == Kind.NONE) {
            stopAtTheEdge(player);
            return;
        }
        ticksInReflex++;
        switch (active) {
            case ESCAPE_LAVA -> escapeTo(mc, player, findStandable(player));
            case SURFACE -> escapeTo(mc, player, findAir(player));
            case FLEE -> flee(mc, player);
            case FIGHT -> fight(mc, player);
            case EAT -> eat(mc, player);
            default -> {
            }
        }
    }

    /**
     * Walking by holding the forward control, nothing looks where the feet are going, and in the Nether that has ended in lava.
     * Going there may still be what the model wants, with a spell or potion that carries it over, so this asks instead of
     * forbidding: it stops once and says so, and the model holds forward again with its confirmation. The path finder has
     * its own eyes and does not use this control.
     */
    private static void stopAtTheEdge(LocalPlayer player) {
        if (!options().keyUp.isDown()) {
            hazardConfirmed = false;
        } else if (!hazardConfirmed && !isSafeToward(player, player.getViewVector(1f))) {
            VirtualInput.setMapping(options().keyUp, false);
            VirtualInput.setMapping(options().keySprint, false);
            GameEvents.add("reflex", "Stopped walking forward: " + HAZARD_AHEAD);
        }
    }

    /** Client thread. Whether walking on the way the body faces leads into lava, fire or a long drop. */
    public static boolean hazardAhead() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && !isSafeToward(player, player.getViewVector(1f));
    }

    /** Client thread. Says that this walk forward is meant to go on over whatever is ahead, until forward is let go. */
    public static void confirmHazard(boolean confirmed) {
        hazardConfirmed = confirmed;
    }

    /**
     * A player left staring at the sky or the ground after the last thing it did is an odd sight for whoever watches
     * through its eyes. Once nothing has moved the view for a couple of seconds, it comes back to level.
     */
    private static void levelTheViewWhenIdle(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        boolean idle = active == Kind.NONE && AgentLoop.get().isIdle() && !dev.cogito.bot.Api.bodyBusy()
                && VirtualInput.lookIdle() && !dev.cogito.bot.Api.walking();
        if (!idle) {
            idleLookTicks = 0;
            idleWindowTicks = 0;
            return;
        }
        // A window left open by something that was interrupted; long enough that a caller still using it is not cut off.
        if (mc.screen instanceof AbstractContainerScreen<?>) {
            idleLookTicks = 0;
            if (++idleWindowTicks >= IDLE_WINDOW_TICKS) {
                idleWindowTicks = 0;
                mc.setScreen(null);
            }
            return;
        }
        if (mc.screen != null) {
            idleLookTicks = 0;
            return;
        }
        if (++idleLookTicks >= IDLE_LOOK_TICKS) {
            idleLookTicks = 0;
            if (Math.abs(player.getXRot()) > LEVEL_PITCH) {
                VirtualInput.turnTo(player.getYRot(), 0);
            }
        }
    }

    /** Most urgent first. What was started keeps going unless something above it appeared. */
    private static Kind choose(Minecraft mc, LocalPlayer player) {
        // Fire resistance makes lava a place like any other. So does walking on its surface, which is not being in it, though
        // the block at the feet is lava then: only isInLava tells the two apart.
        boolean burns = !player.hasEffect(MobEffects.FIRE_RESISTANCE);
        if (burns && (player.isInLava() || player.level().getBlockState(player.blockPosition()).is(BlockTags.FIRE))) {
            return Kind.ESCAPE_LAVA;
        }
        // Bobbing up out of the lava is not being out of it: anything else now would let the body sink back in.
        if (burns && active == Kind.ESCAPE_LAVA && !player.onGround() && ticksInReflex < ESCAPE_TICKS) {
            return Kind.ESCAPE_LAVA;
        }
        // A magma block burns whoever stands on it, slowly enough for a fight on top of one to go on until death.
        if (burns && player.onGround() && isMagma(player.level().getBlockState(player.getOnPos())) && (active != Kind.ESCAPE_LAVA || ticksInReflex < ESCAPE_TICKS)) {
            return Kind.ESCAPE_LAVA;
        }
        if (player.isUnderWater() && (player.getAirSupply() < 100 || active == Kind.SURFACE)) {
            return Kind.SURFACE;
        }
        LivingEntity threat = threat(player);
        // Running ends at a distance, not as soon as it is out of sight or a few steps behind: a zombie is back in the second
        // and a half that eating takes, and the two would go on taking turns with neither done.
        if (threat == null && active == Kind.FLEE && ticksInReflex < FLEE_TICKS && target != null && target.isAlive()
                && player.distanceTo(target) <= CHASE_RANGE) {
            return Kind.FLEE;
        }
        if (threat != null) {
            // A pickaxe loses a straight fight with a zombie, so with nothing better in hand it is time to go much sooner.
            boolean weak = player.getHealth() <= (bestWeaponDamage(player) < DECENT_WEAPON ? HEALTH_LOW_UNARMED : HEALTH_LOW);
            boolean hopeless = threat instanceof Warden || threat.getType() == EntityType.IRON_GOLEM || threat instanceof WitherBoss;
            boolean fuseLit = threat instanceof Creeper creeper && creeper.getSwelling(0f) > 0f;
            // One at a time a fight is won; a crowd only wears the body down, and it stops it from getting anywhere.
            boolean crowded = crowd(player).size() >= CROWD;
            if ((weak || hopeless || fuseLit || crowded) && clock >= fleeCooldownUntil && (active != Kind.FLEE || ticksInReflex < FLEE_TICKS)) {
                target = threat;
                return Kind.FLEE;
            }
            if (active == Kind.FLEE) {
                fleeCooldownUntil = clock + 100;
            }
            if (!hopeless && (active != Kind.FIGHT || ticksInReflex < FIGHT_TICKS)) {
                target = threat;
                return Kind.FIGHT;
            }
            if (active == Kind.FIGHT) {
                // A long fight that goes nowhere means it cannot be reached. Leave it alone for a while.
                ignored = threat;
                ignoreUntil = clock + LEAVE_ALONE_TICKS;
            }
            return Kind.NONE;
        }
        if (active == Kind.EAT) {
            if (ticksInReflex < EAT_TICKS && (player.isUsingItem() || ticksInReflex < 10)) {
                return Kind.EAT;
            }
            // Whether it ate or could not: a pause before the next try, so that a try that gets nowhere does not take
            // the body back every few ticks.
            eatCooldownUntil = clock + EAT_COOLDOWN_TICKS;
            return Kind.NONE;
        }
        if (mc.screen == null && clock >= eatCooldownUntil && wantsFood(player) && foodSlot(player) >= 0) {
            return Kind.EAT;
        }
        return Kind.NONE;
    }

    private static void begin(Kind kind) {
        active = kind;
        epoch++;
        ticksInReflex = 0;
        weaponChosen = false;
        slotBeforeEating = -1;
        doing = describe(kind);
        Bot.stopPathing();
        VirtualInput.releaseAll();
        GameEvents.add("reflex", doing);
    }

    private static void stopIfActive() {
        if (active == Kind.NONE) {
            return;
        }
        Kind kind = active;
        active = Kind.NONE;
        String was = doing;
        doing = "";
        target = null;
        if (Control.isAi()) {
            VirtualInput.releaseAll();
            if (kind == Kind.EAT && slotBeforeEating >= 0) {
                VirtualInput.tapMapping(options().keyHotbarSlots[slotBeforeEating]);
            }
        }
        GameEvents.add("reflex", "Stopped " + was);
    }

    private static String describe(Kind kind) {
        return switch (kind) {
            case ESCAPE_LAVA -> "getting out of lava or fire";
            case SURFACE -> "swimming up for air";
            case FLEE -> "running from " + nameOf(target);
            case FIGHT -> "fighting " + nameOf(target);
            case EAT -> "eating";
            default -> "";
        };
    }

    private static String nameOf(Entity entity) {
        return entity == null ? "something" : entity.getName().getString();
    }

    // ---- danger ----

    /** Something to deal with now: whatever hit us recently, or a monster close by and in sight. */
    private static LivingEntity threat(LocalPlayer player) {
        DamageSource source = player.getLastDamageSource();
        if (source != null && source.getEntity() instanceof LivingEntity attacker && attacker.isAlive() && !(attacker instanceof Player)
                && !isIgnored(attacker) && player.distanceTo(attacker) <= CHASE_RANGE && player.hasLineOfSight(attacker)) {
            return attacker;
        }
        if (target != null && target.isAlive() && !isIgnored(target) && player.distanceTo(target) <= ENGAGE_RANGE + 3 && player.hasLineOfSight(target)) {
            return target;
        }
        LivingEntity best = null;
        double bestDistance = ENGAGE_RANGE;
        List<LivingEntity> near = level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(ENGAGE_RANGE));
        for (LivingEntity entity : near) {
            if (!(entity instanceof Enemy) || entity instanceof NeutralMob || entity instanceof EnderDragon || !entity.isAlive() || isIgnored(entity)) {
                continue;
            }
            double distance = player.distanceTo(entity);
            if (distance < bestDistance && player.hasLineOfSight(entity)) {
                best = entity;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The monsters close enough to matter at once. */
    private static List<LivingEntity> crowd(LocalPlayer player) {
        return level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(CROWD_RANGE), entity ->
                entity instanceof Enemy && !(entity instanceof NeutralMob) && !(entity instanceof EnderDragon)
                        && entity.isAlive() && player.hasLineOfSight(entity));
    }

    private static boolean isIgnored(LivingEntity entity) {
        return entity == ignored && clock < ignoreUntil;
    }

    private static boolean readyToAct(Minecraft mc) {
        if (mc.screen != null) {
            VirtualInput.keyTap(GLFW.GLFW_KEY_ESCAPE);
            return false;
        }
        if (!mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.grabMouse();
            return false;
        }
        return true;
    }

    private static void fight(Minecraft mc, LocalPlayer player) {
        LivingEntity enemy = target;
        if (enemy == null || !enemy.isAlive() || !readyToAct(mc)) {
            return;
        }
        if (!weaponChosen) {
            weaponChosen = true;
            int weapon = bestWeaponSlot(player);
            if (weapon >= Inventory.getSelectionSize()) {
                // A better one deep in the pack comes to the hand: what obtain made goes wherever there was room.
                int place = Bot.hotbarPlaceToBorrow(player.getInventory());
                Mc.click(player.inventoryMenu.containerId, weapon, place, Click.SWAP, player);
                weapon = place;
            }
            if (weapon >= 0 && weapon != Mc.selectedSlot(player.getInventory())) {
                VirtualInput.tapMapping(options().keyHotbarSlots[weapon]);
            }
        }
        aimAt(player, enemy.getBoundingBox().getCenter());
        double distance = player.distanceTo(enemy);
        boolean close = distance <= MELEE_REACH;
        boolean safe = isSafeToward(player, enemy.position().subtract(player.position()));
        VirtualInput.setMapping(options().keyUp, !close && safe);
        VirtualInput.setMapping(options().keySprint, distance > 5 && safe);
        VirtualInput.setMapping(options().keyJump, !close && player.horizontalCollision && player.onGround());
        if (close && player.getAttackStrengthScale(0.5f) >= 0.95f
                && mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == enemy) {
            VirtualInput.tapMapping(options().keyAttack);
        }
    }

    private static void flee(Minecraft mc, LocalPlayer player) {
        LivingEntity from = target;
        if (from == null || !readyToAct(mc)) {
            return;
        }
        // Away from all of them when there are several.
        Vec3 middle = from.position();
        List<LivingEntity> crowd = crowd(player);
        if (crowd.size() >= 2) {
            middle = Vec3.ZERO;
            for (LivingEntity entity : crowd) {
                middle = middle.add(entity.position());
            }
            middle = middle.scale(1.0 / crowd.size());
        }
        Vec3 straightAway = player.position().subtract(middle).multiply(1, 0, 1);
        if (straightAway.lengthSqr() < 0.01) {
            straightAway = new Vec3(1, 0, 0);
        }
        // Straight away if that is safe, otherwise the nearest safe direction to it.
        Vec3 away = null;
        for (int angle : new int[] {0, 45, -45, 90, -90, 135, -135, 180}) {
            Vec3 direction = straightAway.yRot(angle * Mth.DEG_TO_RAD);
            if (isSafeToward(player, direction)) {
                away = direction;
                break;
            }
        }
        if (away == null) {
            // Cornered against lava or a drop. Running on would be worse than anything it could do to us here.
            fight(mc, player);
            return;
        }
        aimAt(player, player.getEyePosition().add(away.normalize().scale(10)));
        VirtualInput.setMapping(options().keyUp, true);
        VirtualInput.setMapping(options().keySprint, true);
        VirtualInput.setMapping(options().keyJump, player.horizontalCollision || player.isInWater());
    }

    private static void escapeTo(Minecraft mc, LocalPlayer player, BlockPos place) {
        if (!readyToAct(mc)) {
            return;
        }
        // Jumping is how to rise through a fluid. On land it would only bring the same floor back.
        VirtualInput.setMapping(options().keyJump, player.isInLava() || player.isInWater() || player.horizontalCollision || place == null);
        if (place != null && ticksInReflex <= ESCAPE_TICKS) {
            aimAt(player, Vec3.atCenterOf(place));
            VirtualInput.setMapping(options().keyUp, true);
            VirtualInput.setMapping(options().keySprint, true);
        }
    }

    /** The nearest air just above water, for a player out of breath. */
    private static BlockPos findAir(LocalPlayer player) {
        BlockPos at = player.blockPosition();
        ClientLevel level = level();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(at.offset(-8, -2, -8), at.offset(8, 10, 8))) {
            if (level.getBlockState(pos).isAir() && !level.getBlockState(pos.below()).getFluidState().isEmpty()) {
                double distance = pos.distSqr(at);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    /** The nearest place to stand: solid ground that does not burn, room for a body, no lava or fire. */
    private static BlockPos findStandable(LocalPlayer player) {
        BlockPos at = player.blockPosition();
        ClientLevel level = level();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(at.offset(-7, -2, -7), at.offset(7, 4, 7))) {
            BlockState floor = level.getBlockState(pos.below());
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir() && Mc.solidRender(floor, level, pos.below()) && !isMagma(floor)) {
                double distance = pos.distSqr(at);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    /** No lava or fire over the next two steps that way, as a running body carries on after it turns, and something to stand on. */
    private static boolean isSafeToward(LocalPlayer player, Vec3 direction) {
        Vec3 flat = direction.multiply(1, 0, 1);
        if (flat.lengthSqr() < 1e-4) {
            return true;
        }
        Vec3 way = flat.normalize();
        return isSafeAt(player.position().add(way.scale(1.2))) && isSafeAt(player.position().add(way.scale(2.4)));
    }

    /** No lava or fire at this point, and something to stand on within three blocks. */
    private static boolean isSafeAt(Vec3 point) {
        ClientLevel level = level();
        BlockPos ahead = BlockPos.containing(point);
        if (isHot(level.getBlockState(ahead)) || isHot(level.getBlockState(ahead.above()))) {
            return false;
        }
        for (int down = 1; down <= 3; down++) {
            BlockState below = level.getBlockState(ahead.below(down));
            if (isHot(below) || isMagma(below)) {
                return false;
            }
            if (!below.isAir()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHot(BlockState state) {
        return state.is(BlockTags.FIRE) || state.getFluidState().is(FluidTags.LAVA);
    }

    private static boolean isMagma(BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK);
    }

    // ---- food ----

    private static boolean wantsFood(LocalPlayer player) {
        int food = player.getFoodData().getFoodLevel();
        if (food <= FOOD_LOW) {
            return true;
        }
        if (food >= 18 || player.getHealth() > player.getMaxHealth() - 8) {
            return false;
        }
        // Wanted for healing, not out of hunger. Raw meat feeds a third of what it would cooked, so it waits for the furnace unless badly hurt.
        int slot = foodSlot(player);
        return slot < 0 || !Bot.cooksIntoBetterFood(player.getInventory().getItem(slot)) || player.getHealth() <= RAW_FOOD_BELOW_HEALTH;
    }

    /** The best ordinary food carried anywhere in the inventory, or -1. */
    private static int foodSlot(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int best = -1;
        float bestValue = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food != null && !NOT_FOR_REFLEX.contains(stack.getItem()) && food.nutrition() + food.saturation() > bestValue) {
                best = i;
                bestValue = food.nutrition() + food.saturation();
            }
        }
        return best;
    }

    private static void eat(Minecraft mc, LocalPlayer player) {
        if (ticksInReflex > 1) {
            VirtualInput.setMapping(options().keyUse, true);
            return;
        }
        // A window an overtaken action opened in the same tick: nothing can be eaten through it.
        if (mc.screen != null) {
            mc.setScreen(null);
            return;
        }
        if (!mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.grabMouse();
        }
        int slot = foodSlot(player);
        if (slot < 0) {
            return;
        }
        slotBeforeEating = Mc.selectedSlot(player.getInventory());
        if (slot < Inventory.getSelectionSize()) {
            VirtualInput.tapMapping(options().keyHotbarSlots[slot]);
        } else {
            // What opening the inventory and pressing a number key over the slot sends, without the moment of blindness.
            int place = Bot.hotbarPlaceToBorrow(player.getInventory());
            Mc.click(player.inventoryMenu.containerId, slot, place, Click.SWAP, player);
            VirtualInput.tapMapping(options().keyHotbarSlots[place]);
        }
    }

    private static int bestWeaponSlot(LocalPlayer player) {
        int best = -1;
        double bestDamage = 1.0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            double damage = attackBonus(player.getInventory().getItem(i));
            if (damage > bestDamage) {
                bestDamage = damage;
                best = i;
            }
        }
        return best;
    }

    private static double bestWeaponDamage(LocalPlayer player) {
        double best = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            best = Math.max(best, attackBonus(player.getInventory().getItem(i)));
        }
        return best;
    }

    private static double attackBonus(ItemStack stack) {
        double[] damage = {0};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.is(Attributes.ATTACK_DAMAGE)) {
                damage[0] += modifier.amount();
            }
        });
        return damage[0];
    }

    // ---- death ----

    private static void handleDeath(Minecraft mc, LocalPlayer player) {
        if (!deathHandled) {
            deathHandled = true;
            active = Kind.NONE;
            doing = "";
            target = null;
            epoch++;
            Bot.stopPathing();
            VirtualInput.releaseAll();
            respawnAt = clock + RESPAWN_DELAY_TICKS;
        }
        if (player.level().getLevelData().isHardcore() || clock < respawnAt || !(mc.screen instanceof DeathScreen)) {
            return;
        }
        // The real button through the virtual mouse, as a person would press it.
        String label = Component.translatable("deathScreen.respawn").getString();
        for (GuiEventListener child : mc.screen.children()) {
            if (child instanceof AbstractWidget widget && widget.active && widget.getMessage().getString().equals(label)) {
                double perGuiPixel = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
                VirtualInput.mouseTo((widget.getX() + widget.getWidth() / 2.0) * perGuiPixel, (widget.getY() + widget.getHeight() / 2.0) * perGuiPixel);
                VirtualInput.mouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, true);
                VirtualInput.mouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, false);
                GameEvents.add("reflex", "Pressed Respawn");
                respawnAt = clock + RESPAWN_DELAY_TICKS;
                return;
            }
        }
    }

    // ---- shared ----

    private static void aimAt(LocalPlayer player, Vec3 point) {
        Vec3 delta = point.subtract(player.getEyePosition());
        double flat = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        VirtualInput.turnTo((float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90f, (float) -(Mth.atan2(delta.y, flat) * Mth.RAD_TO_DEG));
    }

    private static ClientLevel level() {
        return Minecraft.getInstance().level;
    }

    private static Options options() {
        return Minecraft.getInstance().options;
    }
}
