package com.cinemamod.mcef.vulkan.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;

/** Select the actual device, not GlTexture: VulkanMod's VkGpuTexture extends GlTexture. */
public final class VulkanBackend {
    private VulkanBackend() {
    }

    /** Only call after device initialization, on Minecraft's render thread. */
    public static boolean isActive() {
        return FabricLoader.getInstance().isModLoaded("vulkanmod")
                && "Vulkan".equals(RenderSystem.getDevice().getBackendName());
    }
}
