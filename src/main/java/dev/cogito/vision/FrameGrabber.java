package dev.cogito.vision;

import com.mojang.blaze3d.platform.NativeImage;
import dev.cogito.bot.ApiError;
import net.minecraft.client.Minecraft;
import dev.cogito.compat.Frames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Captures what is on the game's framebuffer, including shaders, resource packs and the overlay. */
public final class FrameGrabber {
    /** A PNG and the pixel size it was encoded at. */
    public record Frame(byte[] png, int width, int height) {}

    private FrameGrabber() {}

    /** Size a full screenshot has for a given maximum edge. Coordinates the model sends use this space. */
    public static int[] shotSize(int maxEdge) {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        double scale = Math.min(1.0, (double) maxEdge / Math.max(w, h));
        return new int[] {Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale))};
    }

    /** Worker thread. Grabs the whole frame, or a region given as fractions (x0, y0, x1, y1) of it. */
    public static Frame grab(int maxEdge, double[] region) {
        CompletableFuture<NativeImage> pending = new CompletableFuture<>();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                Frames.capture(pending::complete);
            } catch (RuntimeException e) {
                pending.completeExceptionally(e);
            }
        });
        try (NativeImage full = pending.get(10, TimeUnit.SECONDS)) {
            return encode(full, maxEdge, region);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiError("interrupted", "stopped while capturing the screen");
        } catch (TimeoutException | ExecutionException | IOException e) {
            throw new ApiError("capture_failed", "could not capture the screen: " + e,
                    "the window may be minimized");
        }
    }

    private static Frame encode(NativeImage full, int maxEdge, double[] region) throws IOException {
        int x0 = 0;
        int y0 = 0;
        int cw = full.getWidth();
        int ch = full.getHeight();
        if (region != null) {
            x0 = clamp((int) (region[0] * full.getWidth()), 0, full.getWidth() - 1);
            y0 = clamp((int) (region[1] * full.getHeight()), 0, full.getHeight() - 1);
            cw = clamp((int) (region[2] * full.getWidth()) - x0, 1, full.getWidth() - x0);
            ch = clamp((int) (region[3] * full.getHeight()) - y0, 1, full.getHeight() - y0);
        }
        double scale = Math.min(1.0, (double) maxEdge / Math.max(cw, ch));
        int w = Math.max(1, (int) Math.round(cw * scale));
        int h = Math.max(1, (int) Math.round(ch * scale));
        try (NativeImage out = new NativeImage(w, h, false)) {
            // Box filter: readable GUI text survives the downscale.
            for (int y = 0; y < h; y++) {
                int sy0 = y0 + y * ch / h;
                int sy1 = Math.max(sy0 + 1, y0 + (y + 1) * ch / h);
                for (int x = 0; x < w; x++) {
                    int sx0 = x0 + x * cw / w;
                    int sx1 = Math.max(sx0 + 1, x0 + (x + 1) * cw / w);
                    long r = 0;
                    long g = 0;
                    long b = 0;
                    int n = 0;
                    for (int sy = sy0; sy < sy1; sy++) {
                        for (int sx = sx0; sx < sx1; sx++) {
                            int packed = Frames.pixel(full, sx, sy);
                            r += packed & 0xFF;
                            g += (packed >> 8) & 0xFF;
                            b += (packed >> 16) & 0xFF;
                            n++;
                        }
                    }
                    Frames.setPixel(out, x, y, 0xFF000000 | (int) (b / n) << 16 | (int) (g / n) << 8 | (int) (r / n));
                }
            }
            Path temp = Files.createTempFile("cogito-frame", ".png");
            try {
                out.writeToFile(temp);
                return new Frame(Files.readAllBytes(temp), w, h);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
