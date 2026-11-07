package dev.cogito.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cogito.Config;
import dev.cogito.bot.Bot;
import dev.cogito.bot.J;
import dev.cogito.control.VirtualInput;
import dev.cogito.ui.Overlay;
import dev.cogito.vision.FrameGrabber;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The executor as a device-level policy: it sees frames and answers with key, button and mouse state at 20 Hz.
 * It has no functions and reports nothing about success; the Verifier decides when the step is done, and the
 * person's takeover key works exactly as it does for the LLM executor.
 */
final class VlaExecutor implements Executor {
    /** One 50 ms device state. Keys and buttons are GLFW codes, held for the whole frame. */
    record DeviceFrame(Set<Integer> keys, Set<Integer> buttons, double mouseDx, double mouseDy, double scrollY) {
        static DeviceFrame idle() {
            return new DeviceFrame(Set.of(), Set.of(), 0, 0, 0);
        }
    }

    /** A policy answers an observation with the next frames to play. */
    interface DevicePolicy {
        List<DeviceFrame> act(String instruction, FrameGrabber.Frame image, String state) throws Exception;
    }

    private static final int FRAME_MILLIS = 50;
    private static final int OBSERVATION_EDGE = 640;
    private static final long STEP_TIMEOUT_MILLIS = 120_000;

    private final DevicePolicy policy;

    VlaExecutor(DevicePolicy policy) {
        this.policy = policy;
    }

    static VlaExecutor http(String endpoint) {
        return new VlaExecutor(new HttpPolicy(endpoint));
    }

    static VlaExecutor scripted() {
        return new VlaExecutor(new ScriptedPolicy());
    }

    @Override
    public StepResult run(Context context) throws InterruptedException {
        long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MILLIS;
        Bot.readyForWorldInput();
        Overlay.setQuiet("vla", true);
        try {
            while (System.currentTimeMillis() < deadline) {
                if (context.verify().get().met() && !context.step().doneWhen().isEmpty()) {
                    return new StepResult(Status.DONE, "verified by the game", null);
                }
                FrameGrabber.Frame image = FrameGrabber.grab(OBSERVATION_EDGE, null);
                List<DeviceFrame> frames;
                try {
                    frames = policy.act(context.step().instruction(), image, context.state());
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    return new StepResult(Status.FAILED, "the device policy failed: " + e, null);
                }
                if (frames.isEmpty()) {
                    return new StepResult(Status.FAILED, "the device policy sent no frames", null);
                }
                for (DeviceFrame frame : frames) {
                    Bot.requireControl();
                    // A reflex has the body for now; these frames were decided before it fired, so they are dropped.
                    if (!Reflexes.isActive()) {
                        Bot.run(() -> VirtualInput.applyFrame(frame.keys(), frame.buttons(), frame.mouseDx(), frame.mouseDy(), frame.scrollY()));
                    }
                    Thread.sleep(FRAME_MILLIS);
                    if (context.verify().get().met() && !context.step().doneWhen().isEmpty()) {
                        return new StepResult(Status.DONE, "verified by the game", null);
                    }
                }
            }
            return new StepResult(Status.FAILED, "the step timed out; last check: " + context.verify().get().detail(), null);
        } finally {
            Overlay.setQuiet("vla", false);
            if (dev.cogito.control.Control.isAi()) {
                Bot.run(() -> VirtualInput.applyFrame(Set.of(), Set.of(), 0, 0, 0));
            }
        }
    }

    /**
     * POSTs {instruction, state, frame_png_base64} and expects
     * {"frames":[{"keys":[87],"buttons":[],"dx":0,"dy":0,"scroll":0}, ...]}.
     */
    private static final class HttpPolicy implements DevicePolicy {
        private static final Gson GSON = new Gson();
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final String endpoint;

        HttpPolicy(String endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public List<DeviceFrame> act(String instruction, FrameGrabber.Frame image, String state) throws Exception {
            JsonObject body = J.obj("instruction", instruction, "state", state,
                    "frame_png_base64", Base64.getEncoder().encodeToString(image.png()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            JsonArray frames = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("frames");
            List<DeviceFrame> out = new ArrayList<>();
            for (JsonElement element : frames) {
                JsonObject frame = element.getAsJsonObject();
                out.add(new DeviceFrame(ints(frame, "keys"), ints(frame, "buttons"), J.number(frame, "dx", 0),
                        J.number(frame, "dy", 0), J.number(frame, "scroll", 0)));
            }
            return out;
        }

        private static Set<Integer> ints(JsonObject frame, String key) {
            Set<Integer> out = new HashSet<>();
            if (frame.has(key)) {
                frame.getAsJsonArray(key).forEach(e -> out.add(e.getAsInt()));
            }
            return out;
        }
    }

    /** Stands in for a real model when testing the plumbing: walks forward and looks slowly around. */
    private static final class ScriptedPolicy implements DevicePolicy {
        @Override
        public List<DeviceFrame> act(String instruction, FrameGrabber.Frame image, String state) {
            List<DeviceFrame> out = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                out.add(new DeviceFrame(Set.of(GLFW.GLFW_KEY_W), Set.of(), 8, 0, 0));
            }
            return out;
        }
    }

    /** Chooses the policy named in the settings. */
    static VlaExecutor fromConfig(String kind) {
        return kind.equals("scripted") ? scripted() : http(Config.get().vlaEndpoint);
    }
}
