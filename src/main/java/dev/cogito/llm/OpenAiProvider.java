package dev.cogito.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.completions.CompletionUsage;
import com.openai.models.models.Model;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Chat Completions through the OpenAI SDK: OpenAI itself, and every server that speaks the same API (relays, local
 * servers, other makers' compatible endpoints). Those differ in what they accept, so the optional parts of a request
 * are dropped one by one when a server refuses them.
 */
final class OpenAiProvider implements Provider {
    private static final Map<String, String[]> OPTIONS = new LinkedHashMap<>();
    /** Fields some servers use to hand back the model's reasoning; it has to go back with the reply. */
    private static final List<String> REASONING_FIELDS = List.of("reasoning_content", "reasoning");

    static {
        OPTIONS.put("format", new String[] {"response_format", "json_schema"});
        OPTIONS.put("effort", new String[] {"reasoning_effort", "reasoning"});
        OPTIONS.put("max_completion_tokens", new String[] {"max_completion_tokens"});
    }

    private final OpenAIClient client;
    private final Rejected rejected = new Rejected();

    OpenAiProvider(LlmSettings.Profile profile, String key) {
        client = OpenAIOkHttpClient.builder()
                // A local server needs no key, but the SDK wants one to send.
                .apiKey(key.isEmpty() ? "none" : key)
                .baseUrl(profile.baseUrl().isEmpty() ? LlmSettings.Kind.OPENAI.officialUrl : profile.baseUrl())
                .build();
    }

    @Override
    public Conversation start(Chat.Request request) {
        return new Talk(request);
    }

    @Override
    public List<String> models() {
        try {
            return client.models().list().data().stream().map(Model::id).toList();
        } catch (OpenAIServiceException e) {
            throw new LlmException("the server answered " + e.statusCode() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private final class Talk implements Conversation {
        private final Chat.Request request;
        private final List<ChatCompletionFunctionTool> tools = new ArrayList<>();
        private final List<ChatCompletionMessageParam> messages = new ArrayList<>();

        Talk(Chat.Request request) {
            this.request = request;
            for (Chat.Tool tool : request.tools()) {
                FunctionParameters.Builder parameters = FunctionParameters.builder();
                AnthropicProvider.map(tool.schema()).forEach((key, value) -> parameters.putAdditionalProperty(key, JsonValue.from(value)));
                tools.add(ChatCompletionFunctionTool.builder().function(FunctionDefinition.builder()
                        .name(tool.name()).description(tool.description()).parameters(parameters.build()).build()).build());
            }
            String system = request.system();
            if (request.jsonSchema() != null) {
                // Said in words as well, for servers that take the API's answer format and ignore it.
                system += "\n\nYour final answer, after any tool calls, is one JSON object and nothing else, matching this JSON schema:\n"
                        + request.jsonSchema();
            }
            messages.add(ChatCompletionMessageParam.ofSystem(ChatCompletionSystemMessageParam.builder().content(system).build()));
        }

        @Override
        public void user(List<Chat.Part> parts) {
            ChatCompletionUserMessageParam.Builder message = ChatCompletionUserMessageParam.builder();
            if (parts.size() == 1 && parts.getFirst() instanceof Chat.Text text) {
                // Plain text, which every server understands.
                message.content(text.text());
            } else {
                List<ChatCompletionContentPart> content = new ArrayList<>();
                for (Chat.Part part : parts) {
                    switch (part) {
                        case Chat.Text text -> content.add(ChatCompletionContentPart.ofText(
                                ChatCompletionContentPartText.builder().text(text.text()).build()));
                        case Chat.Image image -> content.add(ChatCompletionContentPart.ofImageUrl(ChatCompletionContentPartImage.builder()
                                .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                                        .url("data:image/png;base64," + Base64.getEncoder().encodeToString(image.frame().png()))
                                        .build())
                                .build()));
                    }
                }
                message.contentOfArrayOfContentParts(content);
            }
            messages.add(ChatCompletionMessageParam.ofUser(message.build()));
        }

        @Override
        public Chat.Reply send() {
            for (int attempt = 0; ; attempt++) {
                Set<String> sent = new HashSet<>();
                ChatCompletionCreateParams params = params(sent);
                try {
                    return reply(client.chat().completions().create(params));
                } catch (OpenAIServiceException e) {
                    boolean refused = e.statusCode() == 400 || e.statusCode() == 422;
                    if (refused && attempt < 3 && rejected.drop(request.model(), e.getMessage(), OPTIONS, sent)) {
                        continue;
                    }
                    throw new LlmException("the server answered " + e.statusCode() + ": " + e.getMessage(), e);
                }
            }
        }

        @Override
        public void results(List<Chat.Result> results, List<Chat.Part> after) {
            List<Chat.Part> extra = new ArrayList<>();
            for (Chat.Result result : results) {
                messages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                        .toolCallId(result.call().id()).content(result.text()).build()));
                if (result.image() != null) {
                    // A tool message carries text only, so its picture follows in the next user message.
                    extra.add(new Chat.Text("The picture " + result.call().name() + " returned:"));
                    extra.add(new Chat.Image(result.image()));
                }
            }
            extra.addAll(after);
            if (!extra.isEmpty()) {
                user(extra);
            }
        }

        private ChatCompletionCreateParams params(Set<String> sent) {
            String model = request.model();
            ChatCompletionCreateParams.Builder params = ChatCompletionCreateParams.builder().model(model).messages(messages);
            tools.forEach(params::addTool);
            if (rejected.has(model, "max_completion_tokens")) {
                params.putAdditionalBodyProperty("max_tokens", JsonValue.from(request.maxTokens()));
            } else {
                params.maxCompletionTokens(request.maxTokens());
                sent.add("max_completion_tokens");
            }
            if (!request.effort().equals("default") && !rejected.has(model, "effort")) {
                params.reasoningEffort(switch (request.effort()) {
                    case "low" -> ReasoningEffort.LOW;
                    case "high" -> ReasoningEffort.HIGH;
                    case "xhigh" -> ReasoningEffort.XHIGH;
                    case "max" -> ReasoningEffort.MAX;
                    default -> ReasoningEffort.MEDIUM;
                });
                sent.add("effort");
            }
            if (request.jsonSchema() != null && !rejected.has(model, "format")) {
                ResponseFormatJsonSchema.JsonSchema.Schema.Builder schema = ResponseFormatJsonSchema.JsonSchema.Schema.builder();
                AnthropicProvider.map(request.jsonSchema()).forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
                params.responseFormat(ResponseFormatJsonSchema.builder().jsonSchema(ResponseFormatJsonSchema.JsonSchema.builder()
                        .name("answer").strict(true).schema(schema.build()).build()).build());
                sent.add("format");
            }
            return params.build();
        }

        private Chat.Reply reply(ChatCompletion completion) {
            if (completion.choices().isEmpty()) {
                throw new LlmException("the server sent a reply with no choices", false);
            }
            ChatCompletion.Choice choice = completion.choices().getFirst();
            ChatCompletionMessage message = choice.message();
            ChatCompletionAssistantMessageParam param = message.toParam();
            String thinking = "";
            Map<String, JsonValue> extra = message._additionalProperties();
            for (String field : REASONING_FIELDS) {
                JsonValue value = extra.get(field);
                Optional<String> text = value == null ? Optional.empty() : value.asString();
                if (text.isPresent() && !text.get().isBlank()) {
                    thinking = text.get();
                    param = param.toBuilder().putAdditionalProperty(field, value).build();
                    break;
                }
            }
            messages.add(ChatCompletionMessageParam.ofAssistant(param));

            List<Chat.Call> calls = new ArrayList<>();
            for (ChatCompletionMessageToolCall toolCall : message.toolCalls().orElse(List.of())) {
                Optional<ChatCompletionMessageFunctionToolCall> function = toolCall.function();
                function.ifPresent(f -> calls.add(new Chat.Call(f.id(), f.function().name(), arguments(f.function().arguments()))));
            }
            String finish = choice._finishReason().asKnown().map(Object::toString).orElse("none");
            Chat.Stop stop = message.refusal().isPresent() || finish.equals("content_filter") ? Chat.Stop.REFUSAL
                    : finish.equals("length") ? Chat.Stop.MAX_TOKENS
                    : !calls.isEmpty() ? Chat.Stop.TOOL_USE : Chat.Stop.END;
            Chat.Usage usage = completion.usage().map(OpenAiProvider::usage).orElse(Chat.Usage.NONE);
            return new Chat.Reply(message.content().orElse(""), thinking, calls, stop,
                    message.refusal().orElse(finish), usage);
        }
    }

    private static Chat.Usage usage(CompletionUsage usage) {
        long cached = usage.promptTokensDetails().flatMap(CompletionUsage.PromptTokensDetails::cachedTokens).orElse(0L);
        return new Chat.Usage(Math.max(0, usage.promptTokens() - cached), cached, 0, usage.completionTokens());
    }

    /** A call's arguments arrive as JSON text, which a model can get wrong; then the tool gets nothing and says what is missing. */
    private static JsonObject arguments(String text) {
        try {
            JsonElement parsed = JsonParser.parseString(text == null || text.isBlank() ? "{}" : text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (JsonSyntaxException e) {
            return new JsonObject();
        }
    }
}
