package dev.cogito.llm;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.AutomaticFunctionCallingConfig;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.ListModelsConfig;
import com.google.genai.types.Model;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.ThinkingLevel;
import com.google.genai.types.Tool;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini through Google's Gen AI SDK. The model's own turns go back exactly as they came, because their function
 * calls carry thought signatures that Gemini checks.
 */
final class GeminiProvider implements Provider {
    private static final Gson GSON = new Gson();
    private static final Map<String, String[]> OPTIONS = new LinkedHashMap<>();
    private static final Pattern GENERATION = Pattern.compile("gemini-(\\d+)");
    private static final AtomicLong CALL_IDS = new AtomicLong();

    static {
        OPTIONS.put("format", new String[] {"mime type", "mime_type", "response_schema", "response_json_schema", "responsejsonschema"});
        OPTIONS.put("thinking", new String[] {"thinking"});
    }

    private final Client client;
    private final Rejected rejected = new Rejected();

    GeminiProvider(LlmSettings.Profile profile, String key) {
        HttpOptions.Builder http = HttpOptions.builder().timeout(600_000);
        if (!profile.baseUrl().isEmpty()) {
            http.baseUrl(profile.baseUrl());
        }
        client = Client.builder().apiKey(key).vertexAI(false).httpOptions(http.build()).build();
    }

    @Override
    public Conversation start(Chat.Request request) {
        return new Talk(request);
    }

    @Override
    public List<String> models() {
        try {
            List<String> out = new ArrayList<>();
            for (Model model : client.models.list(ListModelsConfig.builder().pageSize(200).build()).page()) {
                if (model.supportedActions().orElse(List.of()).contains("generateContent")) {
                    model.name().ifPresent(name -> out.add(name.startsWith("models/") ? name.substring(7) : name));
                }
            }
            return out;
        } catch (ApiException e) {
            throw new LlmException("Gemini answered " + e.code() + ": " + e.message(), e);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private final class Talk implements Conversation {
        private final Chat.Request request;
        private final String system;
        private final Tool tool;
        private final List<Content> contents = new ArrayList<>();
        /** Ids made up here because the model gave none; they are not sent back. */
        private final Set<String> madeUp = new HashSet<>();

        Talk(Chat.Request request) {
            this.request = request;
            List<FunctionDeclaration> declarations = new ArrayList<>();
            for (Chat.Tool t : request.tools()) {
                declarations.add(FunctionDeclaration.builder().name(t.name()).description(t.description())
                        .parametersJsonSchema(AnthropicProvider.map(t.schema())).build());
            }
            tool = declarations.isEmpty() ? null : Tool.builder().functionDeclarations(declarations).build();
            system = request.jsonSchema() == null ? request.system() : request.system()
                    + "\n\nYour final answer, after any tool calls, is one JSON object and nothing else, matching this JSON schema:\n"
                    + request.jsonSchema();
        }

        @Override
        public void user(List<Chat.Part> parts) {
            contents.add(Content.builder().role("user").parts(parts(parts)).build());
        }

        @Override
        public Chat.Reply send() {
            for (int attempt = 0; ; attempt++) {
                Set<String> sent = new HashSet<>();
                GenerateContentConfig config = config(sent);
                try {
                    return reply(client.models.generateContent(request.model(), contents, config));
                } catch (ApiException e) {
                    if (e.code() == 400 && attempt < 3 && rejected.drop(request.model(), e.message(), OPTIONS, sent)) {
                        continue;
                    }
                    throw new LlmException("Gemini answered " + e.code() + ": " + e.message(), e);
                }
            }
        }

        @Override
        public void results(List<Chat.Result> results, List<Chat.Part> after) {
            List<Part> out = new ArrayList<>();
            for (Chat.Result result : results) {
                FunctionResponse.Builder response = FunctionResponse.builder().name(result.call().name()).response(response(result));
                if (!madeUp.contains(result.call().id())) {
                    response.id(result.call().id());
                }
                out.add(Part.builder().functionResponse(response.build()).build());
            }
            for (Chat.Result result : results) {
                if (result.image() != null) {
                    out.add(Part.fromText("The picture " + result.call().name() + " returned:"));
                    out.add(Part.fromBytes(result.image().png(), "image/png"));
                }
            }
            out.addAll(parts(after));
            contents.add(Content.builder().role("user").parts(out).build());
        }

        private GenerateContentConfig config(Set<String> sent) {
            String model = request.model();
            GenerateContentConfig.Builder config = GenerateContentConfig.builder()
                    .systemInstruction(Content.fromParts(Part.fromText(system)))
                    .maxOutputTokens((int) Math.min(Integer.MAX_VALUE, request.maxTokens()))
                    .automaticFunctionCalling(AutomaticFunctionCallingConfig.builder().disable(true).build());
            if (tool != null) {
                config.tools(tool);
            }
            if (!rejected.has(model, "thinking")) {
                config.thinkingConfig(thinking(model, request.effort()));
                sent.add("thinking");
            }
            if (request.jsonSchema() != null && !rejected.has(model, "format")) {
                config.responseMimeType("application/json").responseJsonSchema(AnthropicProvider.map(request.jsonSchema()));
                sent.add("format");
            }
            return config.build();
        }

        private Chat.Reply reply(GenerateContentResponse response) {
            Chat.Usage usage = response.usageMetadata().map(GeminiProvider::usage).orElse(Chat.Usage.NONE);
            List<Candidate> candidates = response.candidates().orElse(List.of());
            if (candidates.isEmpty()) {
                String why = response.promptFeedback().map(String::valueOf).orElse("no answer");
                return new Chat.Reply("", "", List.of(), Chat.Stop.REFUSAL, why, usage);
            }
            Candidate candidate = candidates.getFirst();
            Content content = candidate.content().orElse(null);
            List<Part> parts = content == null ? List.of() : content.parts().orElse(List.of());
            if (!parts.isEmpty()) {
                contents.add(content.toBuilder().role("model").build());
            }
            StringBuilder text = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            List<Chat.Call> calls = new ArrayList<>();
            for (Part part : parts) {
                if (part.functionCall().isPresent()) {
                    calls.add(call(part.functionCall().get()));
                } else if (part.text().isPresent()) {
                    (part.thought().orElse(false) ? thinking : text).append(part.text().get());
                }
            }
            FinishReason.Known finish = candidate.finishReason().map(FinishReason::knownEnum).orElse(FinishReason.Known.STOP);
            Chat.Stop stop = switch (finish) {
                case MAX_TOKENS -> Chat.Stop.MAX_TOKENS;
                case SAFETY, PROHIBITED_CONTENT, BLOCKLIST, SPII, RECITATION, IMAGE_SAFETY, IMAGE_PROHIBITED_CONTENT -> Chat.Stop.REFUSAL;
                default -> calls.isEmpty() ? Chat.Stop.END : Chat.Stop.TOOL_USE;
            };
            return new Chat.Reply(text.toString(), thinking.toString(), calls, stop,
                    candidate.finishMessage().orElse(finish.toString()), usage);
        }

        private Chat.Call call(FunctionCall call) {
            String id = call.id().orElse(null);
            if (id == null || id.isEmpty()) {
                id = "gemini-call-" + CALL_IDS.incrementAndGet();
                madeUp.add(id);
            }
            JsonElement args = GSON.toJsonTree(call.args().orElse(Map.of()));
            return new Chat.Call(id, call.name().orElse(""), args.isJsonObject() ? args.getAsJsonObject() : new JsonObject());
        }
    }

    /** Gemini 3 and later take a thinking level; earlier ones a token budget. Thought summaries come back either way. */
    private static ThinkingConfig thinking(String model, String effort) {
        ThinkingConfig.Builder thinking = ThinkingConfig.builder().includeThoughts(true);
        if (effort.equals("default")) {
            return thinking.build();
        }
        Matcher m = GENERATION.matcher(model);
        if (m.find() && Integer.parseInt(m.group(1)) >= 3) {
            thinking.thinkingLevel(switch (effort) {
                case "low" -> ThinkingLevel.Known.LOW;
                case "medium" -> ThinkingLevel.Known.MEDIUM;
                default -> ThinkingLevel.Known.HIGH;
            });
        } else {
            thinking.thinkingBudget(switch (effort) {
                case "low" -> 1024;
                case "medium" -> 4096;
                case "high" -> 16384;
                default -> -1;
            });
        }
        return thinking.build();
    }

    /** A tool's answer as the object Gemini wants: its JSON under "output", or under "error" when it failed. */
    private static Map<String, Object> response(Chat.Result result) {
        Object value;
        try {
            JsonElement parsed = JsonParser.parseString(result.text());
            value = parsed.isJsonObject() || parsed.isJsonArray() ? GSON.fromJson(parsed, Object.class) : result.text();
        } catch (JsonSyntaxException e) {
            value = result.text();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(result.isError() ? "error" : "output", value);
        return out;
    }

    private static List<Part> parts(List<Chat.Part> parts) {
        List<Part> out = new ArrayList<>();
        for (Chat.Part part : parts) {
            switch (part) {
                case Chat.Text text -> out.add(Part.fromText(text.text()));
                case Chat.Image image -> out.add(Part.fromBytes(image.frame().png(), "image/png"));
            }
        }
        return out;
    }

    private static Chat.Usage usage(GenerateContentResponseUsageMetadata usage) {
        long prompt = usage.promptTokenCount().orElse(0);
        long cached = usage.cachedContentTokenCount().orElse(0);
        long output = usage.candidatesTokenCount().orElse(0) + (long) usage.thoughtsTokenCount().orElse(0);
        return new Chat.Usage(Math.max(0, prompt - cached), cached, 0, output);
    }
}
