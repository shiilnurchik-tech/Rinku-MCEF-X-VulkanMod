package com.cinemamod.mcef.vulkan.render;

import java.awt.Rectangle;
import java.nio.ByteBuffer;

/** Render-thread-only bridge implemented on MCEFRenderer by the add-on. */
public interface VulkanPaintTarget {
    void paintFrame_MCEF(
            boolean popup,
            Rectangle[] dirtyRects,
            ByteBuffer bgra,
            int width,
            int height,
            Rectangle popupBounds,
            boolean showPopup
    );

    void updatePopup_MCEF(Rectangle popupBounds, boolean showPopup);
}
