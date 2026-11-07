package dev.cogito.llm;

import dev.cogito.CogitoMod;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional parts of a request that a model or server may refuse: thinking settings, a structured answer format.
 * A provider drops the one a refusal names and asks again, and remembers it for that model, so a model that cannot
 * think, or a server that only claims an API, still works with what it has.
 */
final class Rejected {
    private final Map<String, Set<String>> byModel = new ConcurrentHashMap<>();

    boolean has(String model, String option) {
        return byModel.getOrDefault(model, Set.of()).contains(option);
    }

    /**
     * Drops the first option, among those this request sent, whose keywords appear in the refusal. Returns whether one
     * was dropped; if none was, asking again would be refused again.
     */
    boolean drop(String model, String refusal, Map<String, String[]> keywords, Set<String> sent) {
        String text = refusal == null ? "" : refusal.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String[]> option : keywords.entrySet()) {
            if (!sent.contains(option.getKey()) || has(model, option.getKey())) {
                continue;
            }
            for (String word : option.getValue()) {
                if (text.contains(word)) {
                    byModel.computeIfAbsent(model, m -> ConcurrentHashMap.newKeySet()).add(option.getKey());
                    CogitoMod.LOG.info("{} refused {}; asking again without it", model, option.getKey());
                    return true;
                }
            }
        }
        return false;
    }
}
