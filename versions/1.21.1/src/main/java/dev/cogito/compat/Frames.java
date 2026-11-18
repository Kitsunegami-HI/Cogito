package dev.cogito.compat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.function.Consumer;

/** Minecraft 1.21.1: reading the rendered frame, which the game reads back at once. */
public final class Frames {
    private Frames() {}

    /** Client thread. Captures the main render target. */
    public static void capture(Consumer<NativeImage> done) {
        done.accept(Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget()));
    }

    /** A pixel in the same packing {@link #setPixel} takes, so a filter can mix channels without caring which is which. */
    public static int pixel(NativeImage image, int x, int y) {
        return image.getPixelRGBA(x, y);
    }

    public static void setPixel(NativeImage image, int x, int y, int pixel) {
        image.setPixelRGBA(x, y, pixel);
    }
}
