package com.cinemamod.mcef.vulkan.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrowserFrameCompositorTest {
    private final BrowserFrameCompositor frame = new BrowserFrameCompositor();

    @Test
    void swapsBgraWithoutFlippingRowsOrMutatingTheSource() {
        ByteBuffer source = bgra(0, 0, 255, 255, 255, 0, 0, 255, 0, 255, 0, 255, 255, 255, 255, 255);
        int position = source.position();
        int limit = source.limit();
        assertEquals(List.of(new Rectangle(0, 0, 2, 2)), paint(source, 2, 2));
        assertArrayEquals(bytes(255, 0, 0, 255, 0, 0, 255, 255, 0, 255, 0, 255, 255, 255, 255, 255), readAll());
        assertEquals(position, source.position());
        assertEquals(limit, source.limit());
    }

    @Test
    void honorsBufferPositionLimitAndReadOnlySlices() {
        ByteBuffer source = bgra(99, 99, 99, 99, 5, 10, 20, 255, 88, 88, 88, 88);
        source.position(4).limit(8);
        ByteBuffer readOnly = source.asReadOnlyBuffer();
        paint(readOnly, 1, 1);
        assertArrayEquals(bytes(20, 10, 5, 255), readAll());
        assertEquals(4, readOnly.position());
        assertEquals(8, readOnly.limit());
    }

    @Test
    void firstPaintUsesTheFullSnapshotEvenForOneDirtyPixel() {
        ByteBuffer source = solid(3, 2, 5, 10, 20, 255);
        assertEquals(List.of(new Rectangle(0, 0, 3, 2)),
                frame.paintView(source, 3, 2, new Rectangle[]{new Rectangle(1, 1, 1, 1)}));
        assertArrayEquals(bytes(20, 10, 5, 255), pixel(2, 0));
    }

    @Test
    void dirtyRectsUseFullFrameRowStrideAndSourceOffsets() {
        paint(solid(4, 3, 0, 0, 0, 255), 4, 3);
        ByteBuffer source = solid(4, 3, 0, 0, 0, 255);
        putPixel(source, 4, 1, 1, 10, 20, 30, 255);
        putPixel(source, 4, 2, 1, 40, 50, 60, 255);
        putPixel(source, 4, 0, 0, 255, 255, 255, 255);
        Rectangle dirty = new Rectangle(1, 1, 2, 1);
        assertEquals(List.of(dirty), frame.paintView(source, 4, 3, new Rectangle[]{dirty}));
        assertArrayEquals(bytes(30, 20, 10, 255, 60, 50, 40, 255), read(dirty));
        assertArrayEquals(bytes(0, 0, 0, 255), pixel(0, 0));
        assertArrayEquals(bytes(0, 0, 0, 255), pixel(1, 2));
    }

    @Test
    void clipsDirtyRectsAndIgnoresNullEmptyAndOverflowingRects() {
        paint(solid(2, 2, 0, 0, 0, 255), 2, 2);
        List<Rectangle> dirty = frame.paintView(solid(2, 2, 1, 2, 3, 255), 2, 2, new Rectangle[]{
                null, new Rectangle(0, 0, 0, 1), new Rectangle(1, 1, -1, 1),
                new Rectangle(Integer.MAX_VALUE - 1, 0, 10, 1), new Rectangle(-1, -1, 2, 2)
        });
        assertEquals(List.of(new Rectangle(0, 0, 1, 1)), dirty);
        assertArrayEquals(bytes(3, 2, 1, 255), pixel(0, 0));
        assertArrayEquals(bytes(0, 0, 0, 255), pixel(1, 1));
    }

    @Test
    void resizeUsesFullFrameRatherThanOldDimensionsOrDirtyRects() {
        paint(solid(3, 3, 1, 2, 3, 255), 3, 3);
        assertEquals(List.of(new Rectangle(0, 0, 2, 1)),
                frame.paintView(solid(2, 1, 4, 5, 6, 255), 2, 1, new Rectangle[]{new Rectangle(1, 0, 1, 1)}));
        assertEquals(2, frame.getWidth());
        assertEquals(1, frame.getHeight());
        assertArrayEquals(bytes(6, 5, 4, 255, 6, 5, 4, 255), readAll());
    }

    @Test
    void absentDirtyRectsLeaveAnExistingViewUnchanged() {
        paint(solid(1, 1, 1, 2, 3, 255), 1, 1);
        assertTrue(frame.paintView(solid(1, 1, 4, 5, 6, 255), 1, 1, null).isEmpty());
        assertArrayEquals(bytes(3, 2, 1, 255), readAll());
    }

    @Test
    void rejectsTruncatedFramesWithoutDestroyingTheLastFrame() {
        paint(solid(1, 1, 1, 2, 3, 255), 1, 1);
        ByteBuffer truncated = solid(2, 2, 4, 5, 6, 255);
        truncated.limit(15);
        assertTrue(paint(truncated, 2, 2).isEmpty());
        assertEquals(1, frame.getWidth());
        assertArrayEquals(bytes(3, 2, 1, 255), readAll());
        assertTrue(paint(null, 1, 1).isEmpty());
    }

    @Test
    void packedSubImageClipsDestinationAndAdjustsItsOwnSourceOffset() {
        paint(solid(2, 2, 0, 0, 0, 255), 2, 2);
        ByteBuffer packed = bgra(1, 2, 3, 255, 4, 5, 6, 255, 7, 8, 9, 255, 10, 11, 12, 255);
        assertEquals(List.of(new Rectangle(0, 1, 1, 1)), frame.paintPackedViewRect(packed, -1, 1, 2, 2));
        assertArrayEquals(bytes(6, 5, 4, 255), pixel(0, 1));
        assertArrayEquals(bytes(0, 0, 0, 255), pixel(1, 1));
        assertTrue(frame.paintPackedViewRect(packed, Integer.MAX_VALUE, 0, 2, 2).isEmpty());
    }

    @Test
    void popupCanArriveBeforeTheFirstViewFrame() {
        frame.setPopupState(new Rectangle(1, 0, 1, 1), true);
        assertTrue(paintPopup(solid(1, 1, 0, 0, 255, 255), 1, 1).isEmpty());
        paint(solid(2, 1, 255, 0, 0, 255), 2, 1);
        assertArrayEquals(bytes(0, 0, 255, 255, 255, 0, 0, 255), readAll());
    }

    @Test
    void popupClipsNegativeOriginUsingPopupSourceCoordinates() {
        paint(solid(2, 2, 0, 0, 0, 255), 2, 2);
        frame.setPopupState(new Rectangle(-1, -1, 2, 2), true);
        ByteBuffer popup = bgra(0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255, 10, 20, 30, 255);
        assertEquals(List.of(new Rectangle(0, 0, 1, 1)), paintPopup(popup, 2, 2));
        assertArrayEquals(bytes(30, 20, 10, 255), pixel(0, 0));
        assertArrayEquals(bytes(0, 0, 0, 255), pixel(1, 1));
    }

    @Test
    void popupClipsRightAndBottomEdges() {
        paint(solid(2, 2, 0, 0, 0, 255), 2, 2);
        frame.setPopupState(new Rectangle(1, 1, 3, 3), true);
        assertEquals(List.of(new Rectangle(1, 1, 1, 1)), paintPopup(solid(3, 3, 5, 10, 20, 255), 3, 3));
        assertArrayEquals(bytes(20, 10, 5, 255), pixel(1, 1));
    }

    @Test
    void popupDirtyRectsUseThePopupStrideNotTheViewStride() {
        paint(solid(4, 3, 0, 0, 0, 255), 4, 3);
        frame.setPopupState(new Rectangle(1, 0, 2, 2), true);
        paintPopup(solid(2, 2, 0, 0, 255, 255), 2, 2);
        ByteBuffer updated = solid(2, 2, 0, 255, 0, 255);
        putPixel(updated, 2, 1, 1, 255, 0, 0, 255);
        assertEquals(List.of(new Rectangle(2, 1, 1, 1)), frame.paintPopup(updated, 2, 2,
                new Rectangle[]{new Rectangle(1, 1, 1, 1)}));
        assertArrayEquals(bytes(0, 0, 255, 255), pixel(2, 1));
        assertArrayEquals(bytes(255, 0, 0, 255), pixel(1, 0));
    }

    @Test
    void hidingPopupRestoresTheLatestUnderlyingViewWithoutANewPaint() {
        paint(solid(2, 1, 255, 0, 0, 255), 2, 1);
        Rectangle bounds = new Rectangle(0, 0, 1, 1);
        frame.setPopupState(bounds, true);
        paintPopup(solid(1, 1, 0, 0, 255, 255), 1, 1);
        frame.paintView(solid(2, 1, 0, 255, 0, 255), 2, 1, new Rectangle[]{bounds});
        assertArrayEquals(bytes(255, 0, 0, 255), pixel(0, 0));
        assertEquals(List.of(bounds), frame.setPopupState(bounds, false));
        assertArrayEquals(bytes(0, 255, 0, 255), pixel(0, 0));
        assertArrayEquals(bytes(0, 0, 255, 255), pixel(1, 0));
    }

    @Test
    void movingPopupRestoresOldLocationAndPaintsNewLocation() {
        paint(solid(3, 1, 255, 0, 0, 255), 3, 1);
        frame.setPopupState(new Rectangle(0, 0, 1, 1), true);
        paintPopup(solid(1, 1, 0, 0, 255, 255), 1, 1);
        assertEquals(List.of(new Rectangle(0, 0, 1, 1), new Rectangle(2, 0, 1, 1)),
                frame.setPopupState(new Rectangle(2, 0, 1, 1), true));
        assertArrayEquals(bytes(0, 0, 255, 255), pixel(0, 0));
        assertArrayEquals(bytes(255, 0, 0, 255), pixel(2, 0));
    }

    @Test
    void popupResizeDiscardsOldPixelsUntilTheNewBitmapArrives() {
        paint(solid(3, 1, 255, 0, 0, 255), 3, 1);
        frame.setPopupState(new Rectangle(0, 0, 1, 1), true);
        paintPopup(solid(1, 1, 0, 0, 255, 255), 1, 1);
        assertEquals(List.of(new Rectangle(0, 0, 1, 1)), frame.setPopupState(new Rectangle(0, 0, 2, 1), true));
        assertArrayEquals(bytes(0, 0, 255, 255), pixel(0, 0));
        paintPopup(solid(2, 1, 0, 255, 0, 255), 2, 1);
        assertArrayEquals(bytes(0, 255, 0, 255), pixel(1, 0));
    }

    @Test
    void hidingAndReopeningDoesNotResurrectOldPopupPixels() {
        paint(solid(1, 1, 255, 0, 0, 255), 1, 1);
        Rectangle bounds = new Rectangle(0, 0, 1, 1);
        frame.setPopupState(bounds, true);
        paintPopup(solid(1, 1, 0, 0, 255, 255), 1, 1);
        frame.setPopupState(bounds, false);
        assertTrue(paintPopup(solid(1, 1, 0, 255, 0, 255), 1, 1).isEmpty());
        frame.setPopupState(bounds, true);
        assertArrayEquals(bytes(0, 0, 255, 255), pixel(0, 0));
    }

    @Test
    void invalidPopupBoundsRestoreTheView() {
        paint(solid(1, 1, 255, 0, 0, 255), 1, 1);
        frame.setPopupState(new Rectangle(0, 0, 1, 1), true);
        paintPopup(solid(1, 1, 0, 0, 255, 255), 1, 1);
        assertEquals(List.of(new Rectangle(0, 0, 1, 1)), frame.setPopupState(new Rectangle(0, 0, 0, 1), true));
        assertArrayEquals(bytes(0, 0, 255, 255), readAll());
    }

    @Test
    void popupOutsideTheViewAndHugeCoordinatesDoNotOverflow() {
        paint(solid(1, 1, 255, 0, 0, 255), 1, 1);
        frame.setPopupState(new Rectangle(Integer.MAX_VALUE, Integer.MIN_VALUE, 2, 2), true);
        assertTrue(paintPopup(solid(2, 2, 0, 0, 255, 255), 2, 2).isEmpty());
        assertArrayEquals(bytes(0, 0, 255, 255), readAll());
    }

    @Test
    void unpremultipliesTransparentCefPixelsForTheStandardGuiPipeline() {
        paint(bgra(0, 0, 128, 128, 32, 64, 96, 128, 50, 60, 70, 0), 3, 1);
        assertArrayEquals(bytes(255, 0, 0, 128, 191, 128, 64, 128, 0, 0, 0, 0), readAll());
    }

    @Test
    void compositesPremultipliedPopupOverOpaqueView() {
        paint(solid(1, 1, 255, 0, 0, 255), 1, 1);
        frame.setPopupState(new Rectangle(0, 0, 1, 1), true);
        paintPopup(bgra(0, 0, 128, 128), 1, 1);
        assertArrayEquals(bytes(128, 0, 127, 255), readAll());
    }

    @Test
    void compositesBeforeUnpremultiplyingBothTransparentLayers() {
        paint(bgra(0, 0, 128, 128), 1, 1);
        frame.setPopupState(new Rectangle(0, 0, 1, 1), true);
        paintPopup(bgra(128, 0, 0, 128), 1, 1);
        assertArrayEquals(bytes(85, 0, 170, 192), readAll());
    }

    @Test
    void transparentPopupDoesNotEraseTheView() {
        paint(bgra(0, 0, 255, 255), 1, 1);
        frame.setPopupState(new Rectangle(0, 0, 1, 1), true);
        paintPopup(bgra(0, 0, 0, 0), 1, 1);
        assertArrayEquals(bytes(255, 0, 0, 255), readAll());
    }

    @Test
    void uploadWritesOnlyToTheRequestedDestinationBufferWindow() {
        paint(bgra(5, 10, 20, 255), 1, 1);
        ByteBuffer destination = ByteBuffer.allocate(12);
        destination.position(4).limit(8);
        frame.writeRgba(new Rectangle(0, 0, 1, 1), destination);
        assertEquals(8, destination.position());
        assertEquals(8, destination.limit());
        assertArrayEquals(bytes(0, 0, 0, 0, 20, 10, 5, 255, 0, 0, 0, 0), destination.array());
    }

    @Test
    void rejectsOutOfBoundsUploadsAndShortDestinationBuffers() {
        paint(solid(1, 1, 0, 0, 0, 255), 1, 1);
        assertThrows(IllegalArgumentException.class, () -> frame.writeRgba(new Rectangle(-1, 0, 1, 1), ByteBuffer.allocate(4)));
        assertThrows(IllegalArgumentException.class, () -> frame.writeRgba(new Rectangle(0, 0, 2, 1), ByteBuffer.allocate(8)));
        assertThrows(IllegalArgumentException.class, () -> frame.writeRgba(new Rectangle(0, 0, 0, 1), ByteBuffer.allocate(0)));
        assertThrows(IllegalArgumentException.class, () -> frame.writeRgba(new Rectangle(0, 0, 1, 1), ByteBuffer.allocate(3)));
    }

    @Test
    void popupBoundsAreDefensivelyCopied() {
        paint(solid(2, 1, 0, 0, 0, 255), 2, 1);
        Rectangle bounds = new Rectangle(0, 0, 1, 1);
        frame.setPopupState(bounds, true);
        bounds.x = 1;
        paintPopup(bgra(0, 0, 255, 255), 1, 1);
        assertArrayEquals(bytes(255, 0, 0, 255), pixel(0, 0));
        assertArrayEquals(bytes(0, 0, 0, 255), pixel(1, 0));
    }

    @Test
    void separateBrowsersDoNotShareFrameOrPopupState() {
        paint(bgra(0, 0, 255, 255), 1, 1);
        BrowserFrameCompositor other = new BrowserFrameCompositor();
        other.paintView(bgra(255, 0, 0, 255), 1, 1, new Rectangle[]{new Rectangle(0, 0, 1, 1)});
        ByteBuffer otherRgba = ByteBuffer.allocate(4);
        other.writeRgba(new Rectangle(0, 0, 1, 1), otherRgba);
        assertArrayEquals(bytes(0, 0, 255, 255), otherRgba.array());
        assertArrayEquals(bytes(255, 0, 0, 255), readAll());
    }

    @Test
    void clearDropsViewAndPopupState() {
        paint(bgra(255, 0, 0, 255), 1, 1);
        frame.setPopupState(new Rectangle(0, 0, 1, 1), true);
        paintPopup(bgra(0, 0, 255, 255), 1, 1);
        frame.clear();
        assertFalse(frame.hasView());
        assertEquals(0, frame.getWidth());
        assertEquals(0, frame.getHeight());
        assertTrue(frame.paintPackedViewRect(bgra(0, 0, 255, 255), 0, 0, 1, 1).isEmpty());
        paint(bgra(0, 255, 0, 255), 1, 1);
        assertArrayEquals(bytes(0, 255, 0, 255), readAll());
    }

    @ParameterizedTest
    @CsvSource({"0,1,0", "1,0,0", "-1,1,0", "1,-1,0", "2147483647,2147483647,0",
            "2147483647,1,0", "536870911,1,2147483644", "2,3,24"})
    void validatesByteCountsWithoutIntegerOrLongOverflow(int width, int height, int expected) {
        assertEquals(expected, BrowserFrameCompositor.requiredBytes(width, height));
    }

    private List<Rectangle> paint(ByteBuffer source, int width, int height) {
        return frame.paintView(source, width, height, new Rectangle[]{new Rectangle(0, 0, width, height)});
    }

    private List<Rectangle> paintPopup(ByteBuffer source, int width, int height) {
        return frame.paintPopup(source, width, height, new Rectangle[]{new Rectangle(0, 0, width, height)});
    }

    private byte[] readAll() {
        return read(new Rectangle(0, 0, frame.getWidth(), frame.getHeight()));
    }

    private byte[] pixel(int x, int y) {
        return read(new Rectangle(x, y, 1, 1));
    }

    private byte[] read(Rectangle region) {
        ByteBuffer destination = ByteBuffer.allocate(BrowserFrameCompositor.requiredBytes(region.width, region.height));
        frame.writeRgba(region, destination);
        return destination.array();
    }

    private static ByteBuffer bgra(int... values) {
        return ByteBuffer.wrap(bytes(values));
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    private static ByteBuffer solid(int width, int height, int blue, int green, int red, int alpha) {
        ByteBuffer buffer = ByteBuffer.allocate(BrowserFrameCompositor.requiredBytes(width, height));
        for (int i = 0; i < width * height; i++) {
            buffer.put(bytes(blue, green, red, alpha));
        }
        return buffer.flip();
    }

    private static void putPixel(ByteBuffer buffer, int width, int x, int y, int blue, int green, int red, int alpha) {
        int offset = (y * width + x) * 4;
        buffer.put(offset, (byte) blue).put(offset + 1, (byte) green)
                .put(offset + 2, (byte) red).put(offset + 3, (byte) alpha);
    }
}
