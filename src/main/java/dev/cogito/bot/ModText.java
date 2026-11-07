package dev.cogito.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.locale.Language;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What mods say about their own content. Mods keep tutorials, tooltips and guide text in their language files,
 * so this one search works for any mod, needs no support from it, and reads the player's language.
 */
public final class ModText {
    private static final Pattern ITEM_ID = Pattern.compile("\\b([a-z0-9_.-]+):([a-z0-9_/.-]+)\\b");
    private static final Pattern EXPLANATORY = Pattern.compile("ponder|tooltip|desc|summary|condition|behaviou?r|guide|tutorial|hint|help|info|manual|tip\\b");
    private static final int NOTES_PER_THING = 10;
    private static final int THINGS_IN_CONTEXT = 6;

    private ModText() {}

    private record Hit(String key, String text) {}

    private static List<Hit> search(String rawTerm) {
        String term = rawTerm.contains(":") ? rawTerm.substring(rawTerm.indexOf(':') + 1) : rawTerm;
        String needle = term.toLowerCase(Locale.ROOT);
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, String> entry : Language.getInstance().getLanguageData().entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).contains(needle)) {
                hits.add(new Hit(entry.getKey(), entry.getValue()));
            }
        }
        // Explanations first, in the order their authors numbered them; plain names and the rest after.
        hits.sort(Comparator.<Hit>comparingInt(hit -> EXPLANATORY.matcher(hit.key()).find() ? 0 : 1).thenComparing(Hit::key));
        return hits;
    }

    /** The modText function. */
    static JsonObject lookup(String term, int limit) {
        List<Hit> hits = search(term);
        JsonArray lines = new JsonArray();
        for (Hit hit : hits.subList(0, Math.min(limit, hits.size()))) {
            lines.add(J.obj("key", hit.key(), "text", hit.text()));
        }
        return J.obj("term", term, "total", hits.size(), "lines", lines,
                "note", hits.isEmpty() ? "nothing in the language files; try a shorter word, or the screen tool" : null);
    }

    /** How many explanatory lines exist for a block or item, so a description can point at them. */
    static int explanationCount(String id) {
        return (int) search(id).stream().filter(hit -> EXPLANATORY.matcher(hit.key()).find()).count();
    }

    /**
     * Client thread. Notes on the modded things in play: named in the goal, or carried. Planning and execution
     * both start from this, so neither has to guess how a modded item behaves when its mod already says.
     */
    public static JsonObject notesInPlay(String goal) {
        Set<String> ids = new LinkedHashSet<>();
        if (goal != null) {
            Matcher matcher = ITEM_ID.matcher(goal);
            while (matcher.find()) {
                if (!matcher.group(1).equals("minecraft")) {
                    ids.add(matcher.group());
                }
            }
        }
        Inventory inventory = Bot.player().getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            String id = Bot.itemId(inventory.getItem(i));
            if (!inventory.getItem(i).isEmpty() && !id.startsWith("minecraft:")) {
                ids.add(id);
            }
        }
        JsonObject out = new JsonObject();
        int things = 0;
        for (String id : ids) {
            if (things >= THINGS_IN_CONTEXT) {
                break;
            }
            JsonArray notes = new JsonArray();
            for (Hit hit : search(id)) {
                if (notes.size() < NOTES_PER_THING && EXPLANATORY.matcher(hit.key()).find()) {
                    notes.add(hit.text());
                }
            }
            if (!notes.isEmpty()) {
                out.add(id, notes);
                things++;
            }
        }
        return out;
    }
}
