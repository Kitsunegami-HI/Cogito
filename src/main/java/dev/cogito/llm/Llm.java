package dev.cogito.llm;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * The one way the agent reaches a model: which provider and model each of its layers uses comes from
 * {@link LlmSettings}, and every reply is counted here, with what it is estimated to cost, for the spending limits.
 */
public final class Llm {
    /** The two layers that talk to a model. */
    public enum Role { PLANNER, EXECUTOR }

    private static final Map<String, Provider> PROVIDERS = new HashMap<>();
    private static int providersVersion = -1;

    private static final AtomicLong inputTokens = new AtomicLong();
    private static final AtomicLong cacheReadTokens = new AtomicLong();
    private static final AtomicLong cacheWriteTokens = new AtomicLong();
    private static final AtomicLong outputTokens = new AtomicLong();
    private static final AtomicLong requests = new AtomicLong();
    private static final DoubleAdder cost = new DoubleAdder();

    private Llm() {}

    /** Starts a conversation with the model the settings give this role; fails with a setup error when it cannot be used. */
    public static Conversation start(Role role, String system, List<Chat.Tool> tools, long maxTokens, String jsonSchema) {
        LlmSettings.Settings settings = LlmSettings.get();
        LlmSettings.Role chosen = role(settings, role);
        LlmSettings.Profile profile = settings.profile(chosen.profile());
        String problem = problem(role, chosen, profile);
        if (problem != null) {
            throw new LlmException(problem, true);
        }
        Conversation inner = provider(profile).start(new Chat.Request(chosen.model(), chosen.effort(), system, tools, maxTokens, jsonSchema));
        return new Counted(inner, profile, chosen.model());
    }

    /** Why this role cannot reach a model right now, in words for the person; null when it can. */
    public static String problem(Role role) {
        LlmSettings.Settings settings = LlmSettings.get();
        LlmSettings.Role chosen = role(settings, role);
        return problem(role, chosen, settings.profile(chosen.profile()));
    }

    /** Provider and model of a role, for traces and events, such as "Anthropic/claude-haiku-5-5". */
    public static String describe(Role role) {
        LlmSettings.Settings settings = LlmSettings.get();
        LlmSettings.Role chosen = role(settings, role);
        LlmSettings.Profile profile = settings.profile(chosen.profile());
        return (profile == null ? chosen.profile() : profile.name()) + "/" + chosen.model();
    }

    public static LlmSettings.Role role(Role role) {
        return role(LlmSettings.get(), role);
    }

    private static LlmSettings.Role role(LlmSettings.Settings settings, Role role) {
        return role == Role.PLANNER ? settings.planner() : settings.executor();
    }

    private static String problem(Role role, LlmSettings.Role chosen, LlmSettings.Profile profile) {
        String layer = role == Role.PLANNER ? "planner" : "executor";
        if (profile == null) {
            return "The " + layer + " uses a model provider that no longer exists (" + chosen.profile() + "). Choose one in the Cogito settings.";
        }
        if (chosen.model().isEmpty()) {
            return "No model is chosen for the " + layer + ". Choose one in the Cogito settings.";
        }
        LlmSettings.Key key = LlmSettings.key(profile);
        if (key.value().isEmpty() && !profile.keyOptional()) {
            return "The model provider " + profile.name() + " has no API key. Enter one in the Cogito settings"
                    + (key.variable() == null ? "." : ", or set the environment variable " + key.variable() + ".");
        }
        return null;
    }

    private static synchronized Provider provider(LlmSettings.Profile profile) {
        if (providersVersion != LlmSettings.version()) {
            // The settings changed: build every client again from them.
            PROVIDERS.values().forEach(Llm::closeQuietly);
            PROVIDERS.clear();
            providersVersion = LlmSettings.version();
        }
        return PROVIDERS.computeIfAbsent(profile.id(), id -> create(profile));
    }

    private static Provider create(LlmSettings.Profile profile) {
        String key = LlmSettings.key(profile).value();
        return switch (profile.kind()) {
            case ANTHROPIC -> new AnthropicProvider(profile, key);
            case OPENAI -> new OpenAiProvider(profile, key);
            case GEMINI -> new GeminiProvider(profile, key);
        };
    }

    private static void closeQuietly(Provider provider) {
        try {
            provider.close();
        } catch (RuntimeException ignored) {
            // A client that cannot close is dropped all the same.
        }
    }

    /** What a connection test found: the models the key can use, and a model's one-word answer when one was named. */
    public record Test(List<String> models, String modelsError, String reply, String replyError, Chat.Usage usage) {}

    /**
     * Tries a provider as the settings screen holds it, saved or not: lists its models, and asks {@code model} for one
     * word when it is not empty. Blocks on the network, so the screen calls it off the render thread. The answer is
     * counted like any other.
     */
    public static Test test(LlmSettings.Profile profile, String model) {
        if (LlmSettings.key(profile).value().isEmpty() && !profile.keyOptional()) {
            throw new LlmException("No API key: enter one, or name an environment variable that holds it.", true);
        }
        try (Provider provider = create(profile)) {
            List<String> models = List.of();
            String modelsError = null;
            try {
                models = provider.models();
            } catch (RuntimeException e) {
                modelsError = message(e);
            }
            String reply = null, replyError = null;
            Chat.Usage usage = Chat.Usage.NONE;
            if (model != null && !model.isBlank()) {
                try {
                    Conversation chat = new Counted(provider.start(new Chat.Request(model.strip(), "default",
                            "You are checking that a connection works.", List.of(), 2048, null)), profile, model.strip());
                    chat.user(List.of(new Chat.Text("Reply with the single word OK.")));
                    Chat.Reply answer = chat.send();
                    reply = answer.text().isBlank() ? "(" + answer.stop() + ", no text)" : answer.text().strip();
                    usage = answer.usage();
                } catch (RuntimeException e) {
                    replyError = message(e);
                }
            }
            return new Test(models, modelsError, reply, replyError, usage);
        }
    }

    /** An error in a few words: an SDK puts the whole response body in its message. */
    public static String message(Throwable e) {
        String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        text = text.replaceAll("\\s+", " ").strip();
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    public static double estimatedCostUsd() {
        return cost.sum();
    }

    public static JsonObject usageJson() {
        JsonObject out = new JsonObject();
        out.addProperty("requests", requests.get());
        out.addProperty("input_tokens", inputTokens.get());
        out.addProperty("cache_read_tokens", cacheReadTokens.get());
        out.addProperty("cache_write_tokens", cacheWriteTokens.get());
        out.addProperty("output_tokens", outputTokens.get());
        out.addProperty("estimated_cost_usd", Math.round(estimatedCostUsd() * 10000) / 10000.0);
        return out;
    }

    /**
     * US dollars per million tokens: input, cache read, cache write, output. The provider's own prices when they are
     * set; Claude's from this table; anything else is priced high on purpose, so that a spending limit still stops a
     * goal rather than letting it run unpriced. A free local server can be given 0.
     */
    static double[] rates(LlmSettings.Profile profile, String model) {
        if (profile.inputPrice() >= 0 && profile.outputPrice() >= 0) {
            double in = profile.inputPrice();
            return new double[] {in, in * 0.1, profile.kind() == LlmSettings.Kind.ANTHROPIC ? in * 1.25 : in, profile.outputPrice()};
        }
        if (profile.kind() == LlmSettings.Kind.ANTHROPIC) {
            if (model.contains("haiku")) {
                return new double[] {0.10, 0.01, 0.125, 0.50};
            }
            if (model.contains("opus")) {
                return new double[] {4, 0.20, 5, 20};
            }
            if (model.contains("fable") || model.contains("mythos")) {
                return new double[] {10, 0.25, 12.5, 50};
            }
        }
        return new double[] {2, 0.20, 2.5, 10};
    }

    /** Counts every reply, and leaves pictures out for a provider set up without them. */
    private record Counted(Conversation inner, LlmSettings.Profile profile, String model) implements Conversation {
        @Override
        public void user(List<Chat.Part> parts) {
            inner.user(parts(parts));
        }

        @Override
        public Chat.Reply send() {
            Chat.Reply reply = inner.send();
            Chat.Usage usage = reply.usage();
            requests.incrementAndGet();
            inputTokens.addAndGet(usage.input());
            cacheReadTokens.addAndGet(usage.cacheRead());
            cacheWriteTokens.addAndGet(usage.cacheWrite());
            outputTokens.addAndGet(usage.output());
            double[] rate = rates(profile, model);
            cost.add((usage.input() * rate[0] + usage.cacheRead() * rate[1] + usage.cacheWrite() * rate[2] + usage.output() * rate[3]) / 1_000_000.0);
            return reply;
        }

        @Override
        public void results(List<Chat.Result> results, List<Chat.Part> after) {
            if (profile.images()) {
                inner.results(results, after);
                return;
            }
            List<Chat.Result> plain = new ArrayList<>();
            for (Chat.Result result : results) {
                plain.add(result.image() == null ? result
                        : new Chat.Result(result.call(), result.text() + "\n" + NO_PICTURE, null, result.isError()));
            }
            inner.results(plain, parts(after));
        }

        private List<Chat.Part> parts(List<Chat.Part> parts) {
            if (profile.images()) {
                return parts;
            }
            List<Chat.Part> out = new ArrayList<>();
            for (Chat.Part part : parts) {
                out.add(part instanceof Chat.Image ? new Chat.Text(NO_PICTURE) : part);
            }
            return out;
        }
    }

    private static final String NO_PICTURE = "(A picture of the screen was taken here, but this model is set up without pictures.)";
}
