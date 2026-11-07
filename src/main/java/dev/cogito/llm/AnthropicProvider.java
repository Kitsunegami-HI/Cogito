package dev.cogito.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.models.ModelInfo;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cogito.vision.FrameGrabber;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Claude, through the Anthropic SDK: adaptive thinking, effort, structured answers and a cached prompt. */
final class AnthropicProvider implements Provider {
    private static final Gson GSON = new Gson();
    private static final Map<String, String[]> OPTIONS = new LinkedHashMap<>();

    static {
        OPTIONS.put("format", new String[] {"output_config.format", "output_format", "json_schema", "structured output"});
        OPTIONS.put("effort", new String[] {"effort"});
        OPTIONS.put("thinking", new String[] {"thinking"});
    }

    private final AnthropicClient client;
    private final Rejected rejected = new Rejected();

    AnthropicProvider(LlmSettings.Profile profile, String key) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(key);
        if (!profile.baseUrl().isEmpty()) {
            builder.baseUrl(profile.baseUrl());
        }
        client = builder.build();
    }

    @Override
    public Conversation start(Chat.Request request) {
        return new Talk(request);
    }

    @Override
    public List<String> models() {
        try {
            return client.models().list().data().stream().map(ModelInfo::id).toList();
        } catch (AnthropicServiceException e) {
            throw new LlmException("Anthropic answered " + e.statusCode() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private final class Talk implements Conversation {
        private final Chat.Request request;
        private final List<Tool> tools = new ArrayList<>();
        private final List<MessageParam> messages = new ArrayList<>();

        Talk(Chat.Request request) {
            this.request = request;
            for (Chat.Tool tool : request.tools()) {
                tools.add(tool(tool));
            }
        }

        @Override
        public void user(List<Chat.Part> parts) {
            messages.add(MessageParam.ofUserOfContentBlockParams(blocks(parts)));
        }

        @Override
        public Chat.Reply send() {
            for (int attempt = 0; ; attempt++) {
                Set<String> sent = new HashSet<>();
                MessageCreateParams params = params(sent);
                try {
                    Message response = client.messages().create(params);
                    messages.add(response.toParam());
                    return reply(response);
                } catch (BadRequestException e) {
                    if (attempt < 3 && rejected.drop(request.model(), e.getMessage(), OPTIONS, sent)) {
                        continue;
                    }
                    throw new LlmException("Anthropic refused the request: " + e.getMessage(), e);
                } catch (AnthropicServiceException e) {
                    throw new LlmException("Anthropic answered " + e.statusCode() + ": " + e.getMessage(), e);
                }
            }
        }

        @Override
        public void results(List<Chat.Result> results, List<Chat.Part> after) {
            List<ContentBlockParam> blocks = new ArrayList<>();
            for (Chat.Result result : results) {
                ToolResultBlockParam.Builder block = ToolResultBlockParam.builder().toolUseId(result.call().id()).isError(result.isError());
                if (result.image() == null) {
                    block.content(result.text());
                } else {
                    block.contentOfBlocks(List.of(
                            ToolResultBlockParam.Content.Block.ofText(result.text()),
                            ToolResultBlockParam.Content.Block.ofImage(image(result.image()))));
                }
                blocks.add(ContentBlockParam.ofToolResult(block.build()));
            }
            blocks.addAll(blocks(after));
            messages.add(MessageParam.ofUserOfContentBlockParams(blocks));
        }

        private MessageCreateParams params(Set<String> sent) {
            String model = request.model();
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(request.maxTokens())
                    .system(request.system())
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .messages(messages);
            tools.forEach(params::addTool);
            boolean thinks = !request.effort().equals("default");
            if (thinks && !rejected.has(model, "thinking")) {
                params.thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build());
                sent.add("thinking");
            }
            OutputConfig.Builder output = OutputConfig.builder();
            boolean anyOutput = false;
            if (thinks && !rejected.has(model, "effort")) {
                output.effort(effort(request.effort()));
                sent.add("effort");
                anyOutput = true;
            }
            if (request.jsonSchema() != null && !rejected.has(model, "format")) {
                JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
                map(request.jsonSchema()).forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
                output.format(JsonOutputFormat.builder().schema(schema.build()).build());
                sent.add("format");
                anyOutput = true;
            }
            if (anyOutput) {
                params.outputConfig(output.build());
            }
            return params.build();
        }

        private Chat.Reply reply(Message response) {
            StringBuilder text = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            List<Chat.Call> calls = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                block.text().ifPresent(t -> text.append(t.text()));
                block.thinking().ifPresent(t -> thinking.append(t.thinking()));
                block.toolUse().ifPresent(use -> calls.add(call(use)));
            }
            StopReason reason = response.stopReason().orElse(StopReason.END_TURN);
            Chat.Stop stop = StopReason.REFUSAL.equals(reason) ? Chat.Stop.REFUSAL
                    : StopReason.MAX_TOKENS.equals(reason) ? Chat.Stop.MAX_TOKENS
                    : !calls.isEmpty() ? Chat.Stop.TOOL_USE : Chat.Stop.END;
            Usage usage = response.usage();
            return new Chat.Reply(text.toString(), thinking.toString(), calls, stop,
                    stop == Chat.Stop.REFUSAL ? String.valueOf(response.stopDetails()) : reason.toString(),
                    new Chat.Usage(usage.inputTokens(), usage.cacheReadInputTokens().orElse(0L),
                            usage.cacheCreationInputTokens().orElse(0L), usage.outputTokens()));
        }
    }

    private static Chat.Call call(ToolUseBlock use) {
        return new Chat.Call(use.id(), use.name(), toJson(use._input()));
    }

    private static Tool tool(Chat.Tool tool) {
        Map<String, Object> schema = map(tool.schema());
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            properties.forEach((key, value) -> props.putAdditionalProperty((String) key, JsonValue.from(value)));
        }
        Tool.InputSchema.Builder input = Tool.InputSchema.builder().properties(props.build());
        if (schema.get("required") instanceof List<?> required) {
            input.required(required.stream().map(String::valueOf).toList());
        }
        return Tool.builder().name(tool.name()).description(tool.description()).inputSchema(input.build()).build();
    }

    private static List<ContentBlockParam> blocks(List<Chat.Part> parts) {
        List<ContentBlockParam> out = new ArrayList<>();
        for (Chat.Part part : parts) {
            switch (part) {
                case Chat.Text text -> out.add(ContentBlockParam.ofText(TextBlockParam.builder().text(text.text()).build()));
                case Chat.Image image -> out.add(ContentBlockParam.ofImage(image(image.frame())));
            }
        }
        return out;
    }

    private static ImageBlockParam image(FrameGrabber.Frame frame) {
        return ImageBlockParam.builder().source(Base64ImageSource.builder()
                .mediaType(Base64ImageSource.MediaType.IMAGE_PNG)
                .data(Base64.getEncoder().encodeToString(frame.png()))
                .build()).build();
    }

    private static OutputConfig.Effort effort(String name) {
        return switch (name) {
            case "low" -> OutputConfig.Effort.LOW;
            case "high" -> OutputConfig.Effort.HIGH;
            case "xhigh" -> OutputConfig.Effort.XHIGH;
            case "max" -> OutputConfig.Effort.MAX;
            default -> OutputConfig.Effort.MEDIUM;
        };
    }

    /** A tool input as Gson JSON. Parsed, never string-matched, since the model may escape text differently. */
    private static JsonObject toJson(JsonValue value) {
        try {
            JsonElement parsed = JsonParser.parseString(ObjectMappers.jsonMapper().writeValueAsString(value));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(String json) {
        return GSON.fromJson(json, Map.class);
    }
}
