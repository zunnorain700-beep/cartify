package com.cartify.app;

import android.graphics.Bitmap;

/**
 * Cheap artwork blur for the player backdrop.
 *
 * A full-resolution Gaussian blur is far too slow on the UI thread, so the source is
 * downscaled to a thumbnail, box-blurred twice (which is a close approximation of a
 * Gaussian), then upscaled. The upscale itself does most of the smoothing, so the
 * result looks like a frosted-glass wash behind the artwork.
 */
public final class Blur {

    /** Width the blurred wash is rendered at; the view stretches it with centerCrop. */
    private static final int OUTPUT_WIDTH = 480;

    private Blur() {
    }

    public static Bitmap of(Bitmap src, int radius) {
        return of(src, radius, 0.62f);
    }

    /**
     * @param brightness 1 keeps the wash as bright as the artwork itself (the player wants a
     *                   translucent mirror of the cover); below 1 pulls it down so text on top
     *                   of it stays legible.
     */
    public static Bitmap of(Bitmap src, int radius, float brightness) {
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return null;

        int smallW = 48;
        int smallH = Math.max(1, Math.round(smallW * (float) src.getHeight() / src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, smallW, smallH, true);

        int[] pixels = new int[smallW * smallH];
        small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH);
        // Two passes of a small box blur read as a soft gaussian at this size.
        int r = Math.max(1, radius);
        boxBlur(pixels, smallW, smallH, r);
        boxBlur(pixels, smallW, smallH, r);
        if (brightness < 0.999f) darken(pixels, brightness);
        small.setPixels(pixels, 0, smallW, 0, 0, smallW, smallH);

        int outH = Math.max(1, Math.round(OUTPUT_WIDTH * (float) src.getHeight() / src.getWidth()));
        Bitmap out = Bitmap.createScaledBitmap(small, OUTPUT_WIDTH, outH, true);
        if (out != small) small.recycle();
        return out;
    }

    /** Pulls the wash down so white text and controls stay readable on top of it. */
    private static void darken(int[] pixels, float factor) {
        float blue = Math.min(1f, factor * 1.06f);
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int a = (c >>> 24) & 0xFF;
            int r = (int) (((c >> 16) & 0xFF) * factor);
            int g = (int) (((c >> 8) & 0xFF) * factor);
            int b = (int) ((c & 0xFF) * blue);
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static void boxBlur(int[] pixels, int w, int h, int radius) {
        int[] temp = new int[pixels.length];
        blurRows(pixels, temp, w, h, radius);
        blurColumns(temp, pixels, w, h, radius);
    }

    private static void blurRows(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0, red = 0, green = 0, blue = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[row + clamp(i, w)];
                a += (c >>> 24) & 0xFF;
                red += (c >> 16) & 0xFF;
                green += (c >> 8) & 0xFF;
                blue += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = ((a / div) << 24) | ((red / div) << 16)
                        | ((green / div) << 8) | (blue / div);
                int cOut = in[row + clamp(x - r, w)];
                int cIn = in[row + clamp(x + r + 1, w)];
                a += ((cIn >>> 24) & 0xFF) - ((cOut >>> 24) & 0xFF);
                red += ((cIn >> 16) & 0xFF) - ((cOut >> 16) & 0xFF);
                green += ((cIn >> 8) & 0xFF) - ((cOut >> 8) & 0xFF);
                blue += (cIn & 0xFF) - (cOut & 0xFF);
            }
        }
    }

    private static void blurColumns(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int a = 0, red = 0, green = 0, blue = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[clamp(i, h) * w + x];
                a += (c >>> 24) & 0xFF;
                red += (c >> 16) & 0xFF;
                green += (c >> 8) & 0xFF;
                blue += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((a / div) << 24) | ((red / div) << 16)
                        | ((green / div) << 8) | (blue / div);
                int cOut = in[clamp(y - r, h) * w + x];
                int cIn = in[clamp(y + r + 1, h) * w + x];
                a += ((cIn >>> 24) & 0xFF) - ((cOut >>> 24) & 0xFF);
                red += ((cIn >> 16) & 0xFF) - ((cOut >> 16) & 0xFF);
                green += ((cIn >> 8) & 0xFF) - ((cOut >> 8) & 0xFF);
                blue += (cIn & 0xFF) - (cOut & 0xFF);
            }
        }
    }

    private static int clamp(int value, int size) {
        return value < 0 ? 0 : (value >= size ? size - 1 : value);
    }
}
