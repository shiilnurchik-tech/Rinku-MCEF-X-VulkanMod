package com.cinemamod.mcef.vulkan.render;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * CPU-side cache of completed CEF bitmaps, not a web renderer. CEF supplies top-left-origin,
 * premultiplied BGRA. Keep the view separate from the popup so hiding or moving a popup can
 * restore the last view without requesting a new Chromium frame.
 *
 * All state belongs to one browser and is accessed exclusively on the render thread.
 */
public final class BrowserFrameCompositor {
    private byte[] viewPixels;
    private int width;
    private int height;
    private byte[] popupPixels;
    private int popupWidth;
    private int popupHeight;
    private Rectangle popupBounds;
    private boolean showPopup;

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean hasView() {
        return viewPixels != null;
    }

    /** A CEF dirty rectangle refers to rows in the full bitmap, not a packed rectangle. */
    public List<Rectangle> paintView(ByteBuffer bgra, int sourceWidth, int sourceHeight, Rectangle[] dirtyRects) {
        int size = requiredBytes(sourceWidth, sourceHeight);
        if (!hasBytes(bgra, size)) {
            return List.of();
        }

        List<Rectangle> dirty = new ArrayList<>();
        if (viewPixels == null || width != sourceWidth || height != sourceHeight) {
            byte[] pixels = new byte[size];
            Rectangle full = new Rectangle(0, 0, sourceWidth, sourceHeight);
            copyBgra(bgra, sourceWidth, pixels, sourceWidth, full, 0, 0);
            viewPixels = pixels;
            width = sourceWidth;
            height = sourceHeight;
            dirty.add(full);
        } else if (dirtyRects != null) {
            for (Rectangle rectangle : dirtyRects) {
                Rectangle clipped = clip(rectangle, width, height);
                if (clipped != null) {
                    copyBgra(bgra, sourceWidth, viewPixels, width, clipped, clipped.x, clipped.y);
                    dirty.add(clipped);
                }
            }
        }
        return dirty;
    }

    /** Compatibility entry point for an explicitly packed BGRA sub-image. */
    public List<Rectangle> paintPackedViewRect(ByteBuffer bgra, int x, int y, int copyWidth, int copyHeight) {
        if (!hasView() || !hasBytes(bgra, requiredBytes(copyWidth, copyHeight))) {
            return List.of();
        }
        Rectangle clipped = clip(x, y, copyWidth, copyHeight, width, height);
        if (clipped == null) {
            return List.of();
        }
        Rectangle source = new Rectangle(
                (int) ((long) clipped.x - x), (int) ((long) clipped.y - y), clipped.width, clipped.height
        );
        copyBgra(bgra, copyWidth, viewPixels, width, source, clipped.x, clipped.y);
        return List.of(clipped);
    }

    public List<Rectangle> setPopupState(Rectangle bounds, boolean visible) {
        Rectangle next = bounds != null && bounds.width > 0 && bounds.height > 0 ? new Rectangle(bounds) : null;
        boolean nextVisible = visible && next != null;
        if (Objects.equals(popupBounds, next) && showPopup == nextVisible) {
            return List.of();
        }

        List<Rectangle> dirty = new ArrayList<>();
        addWholePopup(dirty);
        boolean sizeChanged = popupBounds == null || next == null
                || popupBounds.width != next.width || popupBounds.height != next.height;
        popupBounds = next;
        showPopup = nextVisible;
        if (!showPopup || sizeChanged) {
            clearPopupPixels();
        }
        addWholePopup(dirty);
        return dirty;
    }

    public List<Rectangle> paintPopup(ByteBuffer bgra, int sourceWidth, int sourceHeight, Rectangle[] dirtyRects) {
        int size = requiredBytes(sourceWidth, sourceHeight);
        if (!showPopup || popupBounds == null || !hasBytes(bgra, size)) {
            return List.of();
        }

        List<Rectangle> dirty = new ArrayList<>();
        if (popupPixels == null || popupWidth != sourceWidth || popupHeight != sourceHeight) {
            addWholePopup(dirty);
            byte[] pixels = new byte[size];
            copyBgra(bgra, sourceWidth, pixels, sourceWidth,
                    new Rectangle(0, 0, sourceWidth, sourceHeight), 0, 0);
            popupPixels = pixels;
            popupWidth = sourceWidth;
            popupHeight = sourceHeight;
            addWholePopup(dirty);
        } else if (dirtyRects != null) {
            for (Rectangle rectangle : dirtyRects) {
                Rectangle clipped = clip(rectangle, Math.min(popupWidth, popupBounds.width),
                        Math.min(popupHeight, popupBounds.height));
                if (clipped != null) {
                    copyBgra(bgra, sourceWidth, popupPixels, popupWidth, clipped, clipped.x, clipped.y);
                    addPopupRegion(dirty, clipped);
                }
            }
        }
        return dirty;
    }

    /**
     * Pack a destination rectangle for CommandEncoder.writeToTexture. Minecraft's standard
     * textured GUI pipeline expects straight RGBA, so composite in premultiplied space first
     * and unpremultiply once at the upload boundary. No Y flip is needed.
     */
    public void writeRgba(Rectangle region, ByteBuffer destination) {
        if (!hasView() || region == null || !region.equals(clip(region, width, height))) {
            throw new IllegalArgumentException("Upload rectangle is outside the browser view");
        }
        int size = requiredBytes(region.width, region.height);
        if (destination.remaining() < size) {
            throw new IllegalArgumentException("Upload buffer is too small");
        }

        for (int y = region.y; y < region.y + region.height; y++) {
            for (int x = region.x; x < region.x + region.width; x++) {
                int offset = (y * width + x) * 4;
                int red = viewPixels[offset] & 255;
                int green = viewPixels[offset + 1] & 255;
                int blue = viewPixels[offset + 2] & 255;
                int alpha = viewPixels[offset + 3] & 255;

                if (showPopup && popupPixels != null) {
                    long popupX = (long) x - popupBounds.x;
                    long popupY = (long) y - popupBounds.y;
                    if (popupX >= 0 && popupY >= 0
                            && popupX < Math.min(popupWidth, popupBounds.width)
                            && popupY < Math.min(popupHeight, popupBounds.height)) {
                        int popupOffset = ((int) popupY * popupWidth + (int) popupX) * 4;
                        int popupAlpha = popupPixels[popupOffset + 3] & 255;
                        int inverseAlpha = 255 - popupAlpha;
                        red = (popupPixels[popupOffset] & 255) + scale(red, inverseAlpha);
                        green = (popupPixels[popupOffset + 1] & 255) + scale(green, inverseAlpha);
                        blue = (popupPixels[popupOffset + 2] & 255) + scale(blue, inverseAlpha);
                        alpha = popupAlpha + scale(alpha, inverseAlpha);
                    }
                }

                destination.put((byte) unpremultiply(red, alpha));
                destination.put((byte) unpremultiply(green, alpha));
                destination.put((byte) unpremultiply(blue, alpha));
                destination.put((byte) alpha);
            }
        }
    }

    public void clear() {
        viewPixels = null;
        width = 0;
        height = 0;
        clearPopupPixels();
        popupBounds = null;
        showPopup = false;
    }

    public static int requiredBytes(int width, int height) {
        if (width <= 0 || height <= 0) {
            return 0;
        }
        long pixels = (long) width * height;
        return pixels > Integer.MAX_VALUE / 4L ? 0 : (int) (pixels * 4);
    }

    private static boolean hasBytes(ByteBuffer source, int size) {
        return source != null && size > 0 && source.remaining() >= size;
    }

    private static void copyBgra(ByteBuffer source, int sourceWidth, byte[] destination, int destinationWidth,
                                 Rectangle sourceRect, int destinationX, int destinationY) {
        int base = source.position();
        for (int row = 0; row < sourceRect.height; row++) {
            int src = base + ((sourceRect.y + row) * sourceWidth + sourceRect.x) * 4;
            int dst = ((destinationY + row) * destinationWidth + destinationX) * 4;
            for (int column = 0; column < sourceRect.width; column++, src += 4, dst += 4) {
                destination[dst] = source.get(src + 2);
                destination[dst + 1] = source.get(src + 1);
                destination[dst + 2] = source.get(src);
                destination[dst + 3] = source.get(src + 3);
            }
        }
    }

    private void addWholePopup(List<Rectangle> dirty) {
        if (showPopup && popupPixels != null) {
            addPopupRegion(dirty, new Rectangle(0, 0, Math.min(popupWidth, popupBounds.width),
                    Math.min(popupHeight, popupBounds.height)));
        }
    }

    private void addPopupRegion(List<Rectangle> dirty, Rectangle source) {
        Rectangle clipped = clip((long) popupBounds.x + source.x, (long) popupBounds.y + source.y,
                source.width, source.height, width, height);
        if (clipped != null) {
            dirty.add(clipped);
        }
    }

    private void clearPopupPixels() {
        popupPixels = null;
        popupWidth = 0;
        popupHeight = 0;
    }

    private static Rectangle clip(Rectangle rectangle, int width, int height) {
        return rectangle == null ? null : clip(rectangle.x, rectangle.y, rectangle.width, rectangle.height, width, height);
    }

    private static Rectangle clip(long x, long y, int copyWidth, int copyHeight, int width, int height) {
        if (copyWidth <= 0 || copyHeight <= 0 || width <= 0 || height <= 0) {
            return null;
        }
        long left = Math.max(0, x);
        long top = Math.max(0, y);
        long right = Math.min(width, x + copyWidth);
        long bottom = Math.min(height, y + copyHeight);
        return right <= left || bottom <= top ? null
                : new Rectangle((int) left, (int) top, (int) (right - left), (int) (bottom - top));
    }

    private static int scale(int value, int alpha) {
        return (value * alpha + 127) / 255;
    }

    private static int unpremultiply(int value, int alpha) {
        if (alpha == 0) {
            return 0;
        }
        return alpha == 255 ? Math.min(255, value) : Math.min(255, (value * 255 + alpha / 2) / alpha);
    }
}
