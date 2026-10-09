package com.cinemamod.mcef.vulkan.mixin;

import com.cinemamod.mcef.MCEFRenderer;
import com.cinemamod.mcef.vulkan.render.VulkanBackend;
import com.cinemamod.mcef.vulkan.render.VulkanPaintTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Rectangle;
import java.nio.ByteBuffer;

@Mixin(targets = "com.cinemamod.mcef.MCEFBrowser", remap = false)
public abstract class MixinMCEFBrowser {
    @Shadow
    @Final
    private MCEFRenderer renderer;
    @Shadow
    protected volatile Rectangle popupSize;
    @Shadow
    protected volatile boolean showPopup;

    /** @reason Replace only the final bitmap upload, after MCEF has copied and dispatched CEF's frame safely. */
    @Inject(
            method = "onPaintRenderThread_MCEF(Z[Ljava/awt/Rectangle;Ljava/nio/ByteBuffer;IILjava/awt/Rectangle;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void before_onPaintRenderThread_MCEF(boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer,
                                                 int width, int height, Rectangle popupRect,
                                                 boolean showPopupSnapshot, CallbackInfo info) {
        if (VulkanBackend.isActive()) {
            info.cancel();
            ((VulkanPaintTarget) renderer).paintFrame_MCEF(
                    popup, dirtyRects, buffer, width, height, popupRect, showPopupSnapshot
            );
        }
    }

    /** @reason Restore a hidden popup even if Chromium does not immediately repaint the underlying view. */
    @Inject(method = "onPopupShow(Lorg/cef/browser/CefBrowser;Z)V", at = @At("TAIL"))
    private void after_onPopupShow_MCEF(CallbackInfo info) {
        queuePopupState_MCEF();
    }

    @Inject(method = "onPopupSize(Lorg/cef/browser/CefBrowser;Ljava/awt/Rectangle;)V", at = @At("TAIL"))
    private void after_onPopupSize_MCEF(CallbackInfo info) {
        queuePopupState_MCEF();
    }

    @Unique
    private void queuePopupState_MCEF() {
        Rectangle currentBounds = popupSize;
        Rectangle bounds = currentBounds == null ? null : new Rectangle(currentBounds);
        boolean visible = showPopup;
        Runnable update = () -> {
            if (VulkanBackend.isActive()) {
                ((VulkanPaintTarget) renderer).updatePopup_MCEF(bounds, visible);
            }
        };
        if (RenderSystem.isOnRenderThread()) {
            update.run();
        } else {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.isRunning()) {
                minecraft.execute(update);
            }
        }
    }
}
