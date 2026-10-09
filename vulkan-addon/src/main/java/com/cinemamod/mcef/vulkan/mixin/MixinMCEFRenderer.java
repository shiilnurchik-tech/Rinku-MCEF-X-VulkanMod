package com.cinemamod.mcef.vulkan.mixin;

import com.cinemamod.mcef.MCEFDirectTexture;
import com.cinemamod.mcef.MCEFRenderer;
import com.cinemamod.mcef.vulkan.render.BrowserFrameCompositor;
import com.cinemamod.mcef.vulkan.render.VulkanBackend;
import com.cinemamod.mcef.vulkan.render.VulkanPaintTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

@Mixin(value = MCEFRenderer.class, remap = false)
public abstract class MixinMCEFRenderer implements VulkanPaintTarget {
    @Unique
    private static final Logger LOGGER_MCEF = LoggerFactory.getLogger("MCEF-Vulkan");
    @Unique
    private static boolean BACKEND_LOGGED_MCEF;
    @Unique
    private final BrowserFrameCompositor frame_MCEF = new BrowserFrameCompositor();
    @Unique
    private ByteBuffer uploadBuffer_MCEF;
    @Unique
    private boolean closed_MCEF;
    @Unique
    private boolean ownsVulkanTexture_MCEF;

    @Shadow
    private GpuTexture texture;
    @Shadow
    private int textureWidth;
    @Shadow
    private int textureHeight;
    @Shadow
    private MCEFDirectTexture directTexture;

    @Shadow
    private void syncDirectTextureViewIfNeeded() {
        throw new AssertionError();
    }

    /** @reason VulkanMod textures are also GlTexture instances; never enter MCEF's native GL upload branch. */
    @Inject(method = "onPaint(Ljava/nio/ByteBuffer;II)V", at = @At("HEAD"), cancellable = true)
    private void before_onPaint_MCEF(ByteBuffer bgra, int width, int height, CallbackInfo info) {
        if (!VulkanBackend.isActive()) {
            return;
        }
        info.cancel();
        RenderSystem.assertOnRenderThread();
        if (!closed_MCEF) {
            uploadDirty_MCEF(frame_MCEF.paintView(bgra, width, height,
                    new Rectangle[]{new Rectangle(0, 0, width, height)}));
        }
    }

    /** @reason This overload accepts packed rectangles on the Vulkan path, with no GL pixel-store state. */
    @Inject(method = "onPaint(Ljava/nio/ByteBuffer;IIII)V", at = @At("HEAD"), cancellable = true)
    private void before_onPaintRectangle_MCEF(ByteBuffer bgra, int x, int y, int width, int height, CallbackInfo info) {
        if (!VulkanBackend.isActive()) {
            return;
        }
        info.cancel();
        RenderSystem.assertOnRenderThread();
        if (!closed_MCEF) {
            uploadDirty_MCEF(frame_MCEF.paintPackedViewRect(bgra, x, y, width, height));
        }
    }

    @Unique
    @Override
    public void paintFrame_MCEF(boolean popup, Rectangle[] dirtyRects, ByteBuffer bgra, int width, int height,
                                Rectangle popupBounds, boolean showPopup) {
        RenderSystem.assertOnRenderThread();
        if (closed_MCEF || !VulkanBackend.isActive()) {
            return;
        }
        List<Rectangle> dirty = new ArrayList<>();
        if (popup) {
            dirty.addAll(frame_MCEF.setPopupState(popupBounds, showPopup));
            dirty.addAll(frame_MCEF.paintPopup(bgra, width, height, dirtyRects));
        } else {
            dirty.addAll(frame_MCEF.paintView(bgra, width, height, dirtyRects));
            dirty.addAll(frame_MCEF.setPopupState(popupBounds, showPopup));
        }
        uploadDirty_MCEF(dirty);
    }

    @Unique
    @Override
    public void updatePopup_MCEF(Rectangle popupBounds, boolean showPopup) {
        RenderSystem.assertOnRenderThread();
        if (!closed_MCEF && VulkanBackend.isActive()) {
            uploadDirty_MCEF(frame_MCEF.setPopupState(popupBounds, showPopup));
        }
    }

    /** @reason A VulkanMod compatibility handle is not an actual OpenGL texture name. */
    @Inject(method = "getTextureID", at = @At("HEAD"), cancellable = true)
    private void before_getTextureID_MCEF(CallbackInfoReturnable<Integer> info) {
        if (ownsVulkanTexture_MCEF) {
            info.setReturnValue(0);
        }
    }

    /** @reason The legacy dirty-rectangle API implies GL_UNPACK state; the add-on has its own stride-aware path. */
    @Inject(method = "supportsDirtyRectUpload", at = @At("HEAD"), cancellable = true)
    private void before_supportsDirtyRectUpload_MCEF(CallbackInfoReturnable<Boolean> info) {
        if (ownsVulkanTexture_MCEF) {
            info.setReturnValue(false);
        }
    }

    /** @reason Close the texture view before its texture, and prevent queued CEF paints from recreating it. */
    @Inject(method = "cleanup", at = @At("HEAD"))
    private void before_cleanup_MCEF(CallbackInfo info) {
        closed_MCEF = true;
        frame_MCEF.clear();
        if (uploadBuffer_MCEF != null) {
            MemoryUtil.memFree(uploadBuffer_MCEF);
            uploadBuffer_MCEF = null;
        }
        if (ownsVulkanTexture_MCEF && directTexture != null) {
            directTexture.bindTexture(null, 0, 0);
        }
        // MCEF retains ownership of the texture and TextureManager registration.
    }

    @Inject(method = "initialize", at = @At("HEAD"), cancellable = true)
    private void before_initialize_MCEF(CallbackInfo info) {
        if (closed_MCEF) {
            info.cancel();
        }
    }

    @Unique
    private void uploadDirty_MCEF(List<Rectangle> dirty) {
        if (!frame_MCEF.hasView()) {
            return;
        }
        if (ensureTexture_MCEF()) {
            dirty = List.of(new Rectangle(0, 0, textureWidth, textureHeight));
        }
        syncDirectTextureViewIfNeeded();
        for (Rectangle region : dirty) {
            int size = BrowserFrameCompositor.requiredBytes(region.width, region.height);
            if (uploadBuffer_MCEF == null || uploadBuffer_MCEF.capacity() < size) {
                ByteBuffer replacement = MemoryUtil.memAlloc(size);
                MemoryUtil.memFree(uploadBuffer_MCEF);
                uploadBuffer_MCEF = replacement;
            }
            uploadBuffer_MCEF.clear();
            uploadBuffer_MCEF.limit(size);
            frame_MCEF.writeRgba(region, uploadBuffer_MCEF);
            uploadBuffer_MCEF.flip();

            // VkCommandEncoder copies this packed RGBA region into VulkanMod's staging buffer
            // before returning. Reusing/freeing our CPU upload buffer afterwards is safe.
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                    texture, uploadBuffer_MCEF, NativeImage.Format.RGBA,
                    0, 0, region.x, region.y, region.width, region.height
            );
        }
    }

    @Unique
    private boolean ensureTexture_MCEF() {
        int width = frame_MCEF.getWidth();
        int height = frame_MCEF.getHeight();
        if (texture != null && !texture.isClosed() && textureWidth == width && textureHeight == height) {
            return false;
        }
        int maxSize = RenderSystem.getDevice().getMaxTextureSize();
        if (width > maxSize || height > maxSize) {
            throw new IllegalArgumentException("MCEF frame exceeds the GPU texture limit: " + width + "x" + height);
        }
        GpuTexture replacement = RenderSystem.getDevice().createTexture(
                "MCEF Vulkan Browser " + width + "x" + height,
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                TextureFormat.RGBA8, width, height, 1, 1
        );
        boolean adopted = false;
        try {
            if (directTexture != null) {
                directTexture.bindTexture(null, 0, 0);
            }
            if (texture != null) {
                texture.close();
            }
            texture = replacement;
            textureWidth = width;
            textureHeight = height;
            ownsVulkanTexture_MCEF = true;
            adopted = true;
        } finally {
            if (!adopted) {
                replacement.close();
            }
        }
        if (!BACKEND_LOGGED_MCEF) {
            LOGGER_MCEF.info("MCEF Vulkan bridge active: completed Chromium frames -> VulkanMod GPU textures");
            BACKEND_LOGGED_MCEF = true;
        }
        return true;
    }
}
