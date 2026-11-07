package dev.cogito.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import dev.cogito.Config;
import dev.cogito.control.VirtualInput;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookSignScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** The rest of the Mineflayer surface: less often needed, but a complete client should have it. */
final class ExtraFns {
    private static final int MAX_BOOK_PAGES = 100;
    private static final int TAB_LIMIT = 50;

    private ExtraFns() {}

    static void register() {
        Api.act("writeBook(pages,[title])", "open a writable book from the inventory, type its pages (a list of strings), press Done, or sign it if a title is given", ExtraFns::writeBook);
        Api.act("setCommandBlock(x,y,z,command,[mode=impulse],[track_output=true],[conditional=false],[always_active=false])", "program a command block; mode impulse, repeat or chain. Needs allowCommands, and creative with operator rights on the server", ExtraFns::setCommandBlock);
        Api.read("tabComplete(text)", "what pressing Tab would offer at the end of this chat or /command text", ExtraFns::tabComplete);
        Api.act("creativeSetSlot(slot,item,[count=1])", "creative mode: put any item in an inventory slot (hotbar 36-44, main 9-35, armor 5-8, off-hand 45)", ExtraFns::creativeSetSlot);
        Api.act("creativeFly(on)", "creative mode: start or stop flying with a double tap of jump", args -> creativeFly(J.bool(args, "on", true)));
        Api.act("creativeFlyTo(x,y,z,[timeout_s=30])", "creative mode: fly in a straight line to a point", ExtraFns::creativeFlyTo);
        Api.act("setSettings([view_distance],[main_hand],[chat],[chat_colors])", "change client settings for now; main_hand left or right; chat enabled, commands or hidden", ExtraFns::setSettings);
        Api.read("getExplosionDamages(x,y,z,power,[entity_id],[raw=false])", "the damage an explosion of this power at a point would do to you or an entity, through what stands in between and armor; raw leaves armor out. Power: creeper 3, TNT 4, bed in the nether 5", ExtraFns::explosionDamages);
        Api.act("acceptResourcePack()", "press Yes on a server's resource pack prompt", args -> answerPackPrompt(CommonComponents.GUI_YES, CommonComponents.GUI_PROCEED));
        Api.act("denyResourcePack()", "press No on a server's resource pack prompt; if the pack is required this disconnects", args -> answerPackPrompt(CommonComponents.GUI_NO, CommonComponents.GUI_DISCONNECT));
    }

    // ---- books ----

    private static JsonElement writeBook(JsonObject args) {
        List<String> pages = J.strings(args, "pages");
        String title = J.str(args, "title", null);
        if (pages.isEmpty() || pages.size() > MAX_BOOK_PAGES) {
            throw new ApiError("bad_argument", "a book takes 1 to " + MAX_BOOK_PAGES + " pages");
        }
        if (Bot.call(() -> !(Bot.mc().screen instanceof BookEditScreen))) {
            WindowFns.equip("minecraft:writable_book", "hand");
            Bot.readyForWorldInput();
            // Sneaking uses the item even when the crosshair is on a table or chest, which would open instead.
            Options o = ActionFns.options();
            Bot.run(() -> VirtualInput.setMapping(o.keyShift, true));
            try {
                Bot.waitTicks(3);
                Bot.run(() -> VirtualInput.tapMapping(o.keyUse));
                if (!Bot.waitUntil(() -> Bot.mc().screen instanceof BookEditScreen, 1500)) {
                    throw new ApiError("no_window", "the book did not open", "hold a book and quill");
                }
            } finally {
                Bot.run(() -> VirtualInput.setMapping(o.keyShift, false));
            }
        }
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) {
                Bot.run(() -> VirtualInput.keyTap(GLFW.GLFW_KEY_PAGE_DOWN));
                Bot.pause(40);
            }
            ScreenTool.type(pages.get(i));
        }
        if (title == null) {
            WindowFns.pressWidget(CommonComponents.GUI_DONE);
        } else {
            WindowFns.pressWidget(Component.translatable("book.signButton"));
            if (!Bot.waitUntil(() -> Bot.mc().screen instanceof BookSignScreen, 1500)) {
                throw new ApiError("no_window", "the signing page did not open");
            }
            ScreenTool.type(title);
            Bot.pause(60);
            WindowFns.pressWidget(Component.translatable("book.finalizeButton"));
        }
        Bot.waitUntil(() -> Bot.mc().screen == null, 2000);
        Bot.waitTicks(4);
        return Bot.call(() -> {
            ItemStack held = Bot.player().getMainHandItem();
            JsonObject out = J.obj("held", Bot.itemId(held));
            var written = held.get(DataComponents.WRITTEN_BOOK_CONTENT);
            var draft = held.get(DataComponents.WRITABLE_BOOK_CONTENT);
            out.addProperty("pages", written != null ? written.pages().size() : draft != null ? draft.pages().size() : 0);
            if (written != null) {
                out.addProperty("title", written.title().raw());
            }
            return out;
        });
    }

    // ---- commands ----

    private static JsonElement setCommandBlock(JsonObject args) {
        if (!Config.get().allowCommands) {
            throw new ApiError("not_allowed", "programming command blocks is turned off for the AI", "allowCommands in config.json");
        }
        BlockPos pos = Bot.pos(args);
        CommandBlockEntity.Mode mode = switch (J.str(args, "mode", "impulse")) {
            case "impulse" -> CommandBlockEntity.Mode.REDSTONE;
            case "repeat" -> CommandBlockEntity.Mode.AUTO;
            case "chain" -> CommandBlockEntity.Mode.SEQUENCE;
            default -> throw new ApiError("bad_argument", "mode must be impulse, repeat or chain");
        };
        Bot.run(() -> Bot.player().connection.send(new ServerboundSetCommandBlockPacket(pos, J.str(args, "command"), mode,
                J.bool(args, "track_output", true), J.bool(args, "conditional", false), J.bool(args, "always_active", false))));
        return J.obj("sent", true, "note", "the server ignores this without creative mode and operator rights, and the client's copy of the block does not show the command; power the block to try it");
    }

    private static JsonElement tabComplete(JsonObject args) {
        String text = J.str(args, "text");
        JsonArray out = new JsonArray();
        if (!text.startsWith("/")) {
            String last = text.substring(text.lastIndexOf(' ') + 1).toLowerCase(Locale.ROOT);
            Bot.call(() -> {
                for (PlayerInfo info : Bot.player().connection.getOnlinePlayers()) {
                    if (info.getProfile().name().toLowerCase(Locale.ROOT).startsWith(last)) {
                        out.add(info.getProfile().name());
                    }
                }
                return null;
            });
            return J.obj("matches", out);
        }
        CompletableFuture<Suggestions> pending = Bot.call(() -> {
            var connection = Bot.player().connection;
            StringReader reader = new StringReader(text);
            reader.skip();
            var parsed = connection.getCommands().parse(reader, connection.getSuggestionsProvider());
            return connection.getCommands().getCompletionSuggestions(parsed);
        });
        Suggestions suggestions;
        try {
            suggestions = pending.get(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiError("interrupted", "stopped while waiting for suggestions");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new ApiError("timeout", "the server did not offer suggestions in time");
        }
        for (Suggestion suggestion : suggestions.getList()) {
            if (out.size() < TAB_LIMIT) {
                out.add(suggestion.getText());
            }
        }
        return J.obj("matches", out, "from_column", suggestions.getRange().getStart());
    }

    // ---- creative mode ----

    private static void requireCreative() {
        if (Bot.call(() -> !Bot.player().getAbilities().instabuild)) {
            throw new ApiError("not_creative", "this only works in creative mode");
        }
    }

    private static JsonElement creativeSetSlot(JsonObject args) {
        requireCreative();
        int slot = J.integer(args, "slot");
        int count = J.integer(args, "count", 1);
        if (slot < 1 || slot > 45) {
            throw new ApiError("bad_argument", "inventory slots are 1-45");
        }
        ItemStack stack = Bot.call(() -> new ItemStack(Bot.item(J.str(args, "item")), count));
        Bot.run(() -> {
            // The server takes it that the client already shows this and does not send it back, as with the creative screen.
            Bot.player().inventoryMenu.getSlot(slot).setByPlayer(stack);
            Bot.mc().gameMode.handleCreativeModeItemAdd(stack, slot);
        });
        Bot.waitTicks(2);
        return Bot.call(() -> Bot.stackJson(Bot.player().inventoryMenu.getSlot(slot).getItem()));
    }

    private static JsonElement creativeFly(boolean on) {
        requireCreative();
        Bot.readyForWorldInput();
        if (Bot.call(() -> Bot.player().getAbilities().flying) != on) {
            ActionFns.holdForTicks(ActionFns.options().keyJump, 1);
            Bot.waitTicks(1);
            ActionFns.holdForTicks(ActionFns.options().keyJump, 1);
            Bot.waitUntil(() -> Bot.player().getAbilities().flying == on, 1000);
        }
        return Bot.call(() -> J.obj("flying", Bot.player().getAbilities().flying));
    }

    private static JsonElement creativeFlyTo(JsonObject args) {
        creativeFly(true);
        Vec3 target = new Vec3(J.number(args, "x"), J.number(args, "y"), J.number(args, "z"));
        long deadline = System.currentTimeMillis() + J.integer(args, "timeout_s", 30) * 1000L;
        Options o = ActionFns.options();
        try {
            while (true) {
                Bot.requireControl();
                Vec3 at = Bot.call(() -> Bot.player().position());
                Vec3 delta = target.subtract(at);
                double flat = Math.hypot(delta.x, delta.z);
                if (flat < 0.7 && Math.abs(delta.y) < 0.7) {
                    return J.obj("arrived", true, "pos", J.nums(at.x, at.y, at.z));
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new ApiError("timeout", "still " + Math.round(delta.length()) + " blocks away");
                }
                Bot.lookAt(Bot.call(() -> new Vec3(target.x, Bot.player().getEyeY(), target.z)));
                // Short bursts near the target, because a flying player keeps sliding after the key is released.
                boolean near = flat < 3;
                Bot.run(() -> {
                    VirtualInput.setMapping(o.keyUp, flat >= 0.7);
                    VirtualInput.setMapping(o.keyJump, delta.y > 0.5);
                    VirtualInput.setMapping(o.keyShift, delta.y < -0.5);
                });
                Bot.pause(near ? 60 : 200);
                if (near) {
                    Bot.run(ActionFns::clearControls);
                    Bot.pause(200);
                }
            }
        } finally {
            Bot.run(ActionFns::clearControls);
        }
    }

    // ---- settings ----

    private static JsonElement setSettings(JsonObject args) {
        Bot.run(() -> {
            Options o = ActionFns.options();
            if (args.has("view_distance")) {
                o.renderDistance().set(J.integer(args, "view_distance"));
            }
            if (args.has("main_hand")) {
                o.mainHand().set(switch (J.str(args, "main_hand")) {
                    case "left" -> HumanoidArm.LEFT;
                    case "right" -> HumanoidArm.RIGHT;
                    default -> throw new ApiError("bad_argument", "main_hand must be left or right");
                });
            }
            if (args.has("chat")) {
                o.chatVisibility().set(switch (J.str(args, "chat")) {
                    case "enabled" -> ChatVisiblity.FULL;
                    case "commands" -> ChatVisiblity.SYSTEM;
                    case "hidden" -> ChatVisiblity.HIDDEN;
                    default -> throw new ApiError("bad_argument", "chat must be enabled, commands or hidden");
                });
            }
            if (args.has("chat_colors")) {
                o.chatColors().set(J.bool(args, "chat_colors", true));
            }
            o.broadcastOptions();
        });
        return Bot.call(() -> {
            Options o = ActionFns.options();
            return J.obj("view_distance", o.renderDistance().get(), "main_hand", o.mainHand().get().name().toLowerCase(Locale.ROOT),
                    "chat", o.chatVisibility().get().name().toLowerCase(Locale.ROOT), "chat_colors", o.chatColors().get());
        });
    }

    // ---- reckoning ----

    /** The game's own formula, so a plan can ask "will this kill me" before standing there. Protection enchantments are left out. */
    private static JsonElement explosionDamages(JsonObject args) {
        Vec3 center = new Vec3(J.number(args, "x"), J.number(args, "y"), J.number(args, "z"));
        double power = J.number(args, "power");
        return Bot.call(() -> {
            LocalPlayer me = Bot.player();
            Entity entity = args.has("entity_id") ? Bot.entityById(J.integer(args, "entity_id")) : me;
            double reach = power * 2;
            double distance = Math.sqrt(entity.distanceToSqr(center)) / reach;
            if (distance > 1.0) {
                return J.obj("damage", 0, "note", "out of range of the blast");
            }
            float seen = ServerExplosion.getSeenPercent(center, entity);
            double strength = (1.0 - distance) * seen;
            float damage = (float) ((strength * strength + strength) / 2.0 * 7.0 * reach + 1.0);
            boolean raw = J.bool(args, "raw", false);
            if (entity instanceof net.minecraft.world.entity.player.Player) {
                Difficulty difficulty = Bot.level().getDifficulty();
                damage = switch (difficulty) {
                    case PEACEFUL -> 0f;
                    case EASY -> Math.min(damage / 2f + 1f, damage);
                    case NORMAL -> damage;
                    case HARD -> damage * 1.5f;
                };
            }
            if (!raw && entity instanceof LivingEntity living) {
                damage = CombatRules.getDamageAfterAbsorb(living, damage, Bot.level().damageSources().generic(), living.getArmorValue(),
                        (float) living.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
            }
            return J.obj("damage", Math.round(damage * 10) / 10.0, "exposure", Math.round(seen * 100) / 100.0,
                    "kills", entity instanceof LivingEntity living && damage >= living.getHealth());
        });
    }

    // ---- resource packs ----

    private static JsonElement answerPackPrompt(Component... labels) {
        if (Bot.call(() -> !(Bot.mc().screen instanceof ConfirmScreen) || !Bot.mc().screen.getClass().getSimpleName().contains("PackConfirm"))) {
            throw new ApiError("no_window", "no resource pack prompt is showing");
        }
        return J.obj("pressed", WindowFns.pressWidget(labels));
    }
}
