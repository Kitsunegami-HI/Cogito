package dev.cogito.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cogito.memory.MemoryStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;

import java.util.ArrayDeque;

/**
 * Recent things that happened to the player. Text from other players or the world is marked untrusted,
 * so the agent treats it as data and never as an instruction.
 */
public final class GameEvents {
    private record Entry(long seq, long time, String kind, String text, boolean untrusted) {}

    private static final int MAX = 200;
    private static final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private static long nextSeq = 1;
    private static float lastHealth = -1;
    private static boolean wasDead;

    private GameEvents() {}

    public static synchronized void add(String kind, String text) {
        push(kind, text, false);
    }

    public static synchronized void addUntrusted(String kind, String text) {
        push(kind, text, true);
    }

    private static void push(String kind, String text, boolean untrusted) {
        entries.addLast(new Entry(nextSeq++, System.currentTimeMillis(), kind, text, untrusted));
        while (entries.size() > MAX) {
            entries.removeFirst();
        }
    }

    public static synchronized long lastSeq() {
        return nextSeq - 1;
    }

    public static synchronized JsonArray since(long seq, int limit) {
        JsonArray out = new JsonArray();
        for (Entry e : entries) {
            if (e.seq() > seq) {
                JsonObject row = new JsonObject();
                row.addProperty("seq", e.seq());
                row.addProperty("kind", e.kind());
                row.addProperty("text", e.text());
                if (e.untrusted()) {
                    row.addProperty("untrusted", true);
                }
                out.add(row);
            }
        }
        while (out.size() > limit) {
            out.remove(0);
        }
        return out;
    }

    /** Client thread, once per tick. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            lastHealth = -1;
            return;
        }
        float health = player.getHealth();
        if (lastHealth >= 0 && health < lastHealth - 0.01f) {
            DamageSource source = player.getLastDamageSource();
            String from = source == null ? "" : source.getEntity() != null
                    ? " from " + source.getEntity().getName().getString() : " from " + source.getMsgId();
            add("damage", "Lost " + round(lastHealth - health) + " health" + from + ", now " + round(health) + "/"
                    + round(player.getMaxHealth()));
        }
        lastHealth = health;
        boolean dead = mc.screen instanceof DeathScreen;
        if (dead && !wasDead) {
            BlockPos at = player.blockPosition();
            String dimension = player.level().dimension().identifier().toString();
            DamageSource source = player.getLastDamageSource();
            String cause = source == null ? "unknown cause" : source.getLocalizedDeathMessage(player).getString();
            add("death", "Died at " + at.toShortString() + " in " + dimension + ": " + cause
                    + ". Whatever is missing from the inventory after respawning lies there for about 5 minutes.");
            MemoryStore.notePlace("last_death", at, dimension, cause);
        }
        wasDead = dead;
    }

    private static String round(float value) {
        return String.format("%.1f", value);
    }
}
