package dev.cogito.compat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.function.Consumer;

/** Minecraft 26.1: reading the rendered frame, which the GPU hands back later. */
public final class Frames {
    private Frames() {}

    /** Client thread. Captures the main render target; the image arrives when the GPU has it. */
    public static void capture(Consumer<NativeImage> done) {
        Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(), done);
    }

    /** A pixel in the same packing {@link #setPixel} takes, so a filter can mix channels without caring which is which. */
    public static int pixel(NativeImage image, int x, int y) {
        return image.getPixel(x, y);
    }

    public static void setPixel(NativeImage image, int x, int y, int pixel) {
        image.setPixel(x, y, pixel);
    }
}
