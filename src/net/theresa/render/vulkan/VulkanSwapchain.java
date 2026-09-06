package net.theresa.render.vulkan;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkComponentMapping;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Window swapchain: image format, extent, present mode, swapchain images and
 * their views. Supports out-of-date/suboptimal handling through
 * {@link #acquire} returning -1 and {@link #present} returning true, after
 * which the caller calls {@link #recreate()}.
 */
public class VulkanSwapchain {

    private static final int EXTENT_UNDEFINED = 0xFFFFFFFF;

    // Acquire timeout per the renderer contract (UINT64_MAX).
    private static final long ACQUIRE_TIMEOUT = 0xFFFFFFFFL;

    private final VulkanContext ctx;
    private final long window;

    public long swapchain;
    public List<Long> images = new ArrayList<>();
    public List<Long> imageViews = new ArrayList<>();
    public int imageFormat;
    public int width;
    public int height;

    public VulkanSwapchain(VulkanContext ctx, long glfwWindow) {
        this.ctx = ctx;
        this.window = glfwWindow;
        create();
    }

    private void create() {
        VkPhysicalDevice physicalDevice = ctx.physicalDevice;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, ctx.surface, caps),
                    "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

            imageFormat = chooseSurfaceFormat(stack, physicalDevice);
            int presentMode = choosePresentMode(stack, physicalDevice);
            chooseExtent(stack, caps);

            // 4 images: enough slack that MAILBOX acquire never blocks on the
            // presentation engine (2-3 images throttle the render loop to the
            // display refresh — measured 30fps on a 60Hz panel)
            int imageCount = Math.max(caps.minImageCount() + 1, 4);
            if (caps.maxImageCount() > 0 && imageCount > caps.maxImageCount()) {
                imageCount = caps.maxImageCount();
            }
            System.out.printf("[VulkanSwapchain] images=%d (min=%d max=%d) presentMode=%d%n",
                    imageCount, caps.minImageCount(), caps.maxImageCount(), presentMode);

            boolean concurrent = ctx.graphicsFamily != ctx.presentFamily;
            VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(ctx.surface)
                    .minImageCount(imageCount)
                    .imageFormat(imageFormat)
                    .imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                    .imageExtent(VkExtent2D.calloc(stack).set(width, height))
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .imageSharingMode(concurrent ? VK_SHARING_MODE_CONCURRENT : VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(caps.currentTransform())
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(presentMode)
                    .clipped(true)
                    .oldSwapchain(NULL);
            if (concurrent) {
                info.pQueueFamilyIndices(stack.ints(ctx.graphicsFamily, ctx.presentFamily));
            }

            LongBuffer pSwapchain = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(ctx.device, info, null, pSwapchain), "vkCreateSwapchainKHR");
            swapchain = pSwapchain.get(0);

            createImagesAndViews(stack);
        }
    }

    private int chooseSurfaceFormat(MemoryStack stack, VkPhysicalDevice physicalDevice) {
        IntBuffer count = stack.mallocInt(1);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, ctx.surface, count, null),
                "vkGetPhysicalDeviceSurfaceFormatsKHR");
        int formatCount = count.get(0);
        if (formatCount == 0) {
            throw new IllegalStateException("Surface reports no supported formats");
        }
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(formatCount, stack);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, ctx.surface, count, formats),
                "vkGetPhysicalDeviceSurfaceFormatsKHR");

        int format = formats.get(0).format();
        for (int i = 0; i < formatCount; i++) {
            VkSurfaceFormatKHR candidate = formats.get(i);
            // UNORM, not SRGB: our shaders already work in sRGB-encoded values like the
            // GL fixed-function path did; an SRGB attachment would re-encode them
            // (double gamma -> washed, oversaturated pastel output)
            if (candidate.format() == VK_FORMAT_B8G8R8A8_UNORM
                    && candidate.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                return candidate.format();
            }
        }
        return format;
    }

    private int choosePresentMode(MemoryStack stack, VkPhysicalDevice physicalDevice) {
        IntBuffer count = stack.mallocInt(1);
        check(vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, ctx.surface, count, null),
                "vkGetPhysicalDeviceSurfacePresentModesKHR");
        int modeCount = count.get(0);
        IntBuffer modes = stack.mallocInt(Math.max(modeCount, 1));
        if (modeCount == 0) {
            return VK_PRESENT_MODE_FIFO_KHR;
        }
        check(vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, ctx.surface, count, modes),
                "vkGetPhysicalDeviceSurfacePresentModesKHR");
        for (int i = 0; i < modeCount; i++) {
            if (modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) {
                return VK_PRESENT_MODE_MAILBOX_KHR;
            }
        }
        return VK_PRESENT_MODE_FIFO_KHR;
    }

    private void chooseExtent(MemoryStack stack, VkSurfaceCapabilitiesKHR caps) {
        if (caps.currentExtent().width() != EXTENT_UNDEFINED) {
            width = caps.currentExtent().width();
            height = caps.currentExtent().height();
            return;
        }
        // The surface has no fixed size yet, so fall back to the framebuffer
        // size and clamp it into the supported range.
        IntBuffer w = stack.mallocInt(1);
        IntBuffer h = stack.mallocInt(1);
        GLFW.glfwGetFramebufferSize(window, w, h);
        width = clamp(w.get(0), caps.minImageExtent().width(), caps.maxImageExtent().width());
        height = clamp(h.get(0), caps.minImageExtent().height(), caps.maxImageExtent().height());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void createImagesAndViews(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(vkGetSwapchainImagesKHR(ctx.device, swapchain, count, null), "vkGetSwapchainImagesKHR");
        int imageCount = count.get(0);
        LongBuffer pImages = stack.mallocLong(Math.max(imageCount, 1));
        check(vkGetSwapchainImagesKHR(ctx.device, swapchain, count, pImages), "vkGetSwapchainImagesKHR");

        images = new ArrayList<>(imageCount);
        for (int i = 0; i < imageCount; i++) {
            images.add(pImages.get(i));
        }

        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(imageFormat)
                .components(VkComponentMapping.calloc(stack)
                        .r(VK_COMPONENT_SWIZZLE_IDENTITY)
                        .g(VK_COMPONENT_SWIZZLE_IDENTITY)
                        .b(VK_COMPONENT_SWIZZLE_IDENTITY)
                        .a(VK_COMPONENT_SWIZZLE_IDENTITY))
                .subresourceRange(VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1));

        imageViews = new ArrayList<>(imageCount);
        LongBuffer pView = stack.mallocLong(1);
        for (int i = 0; i < imageCount; i++) {
            viewInfo.image(pImages.get(i));
            check(vkCreateImageView(ctx.device, viewInfo, null, pView), "vkCreateImageView");
            imageViews.add(pView.get(0));
        }
    }

    /**
     * Destroys the current views and swapchain, then rebuilds everything from
     * the window's current framebuffer size.
     */
    public void recreate() {
        cleanup();
        create();
    }

    /**
     * Acquires the next swapchain image. Returns the image index, or -1 when
     * the swapchain is out of date and {@link #recreate()} is needed.
     * VK_SUBOPTIMAL_KHR still returns a usable index.
     */
    public int acquire(long imageAvailableSemaphore) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pImageIndex = stack.mallocInt(1);
            // semaphore only: associating a fence here too would make it illegal to
            // reuse that fence for the frame's vkQueueSubmit (VUID-vkQueueSubmit-fence-00064)
            int result = vkAcquireNextImageKHR(ctx.device, swapchain, ACQUIRE_TIMEOUT,
                    imageAvailableSemaphore, NULL, pImageIndex);
            if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                return -1;
            }
            if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
                check(result, "vkAcquireNextImageKHR");
            }
            return pImageIndex.get(0);
        }
    }

    /**
     * Presents the given image, waiting on waitSemaphore. Returns true when
     * the swapchain is out of date or suboptimal and {@link #recreate()} is
     * needed.
     */
    public boolean present(VkQueue presentQueue, long waitSemaphore, int imageIndex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPresentInfoKHR info = VkPresentInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(waitSemaphore))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swapchain))
                    .pImageIndices(stack.ints(imageIndex));
            int result = vkQueuePresentKHR(presentQueue, info);
            if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
                return true;
            }
            check(result, "vkQueuePresentKHR");
            return false;
        }
    }

    public void cleanup() {
        if (ctx.device != null) {
            for (long view : imageViews) {
                vkDestroyImageView(ctx.device, view, null);
            }
            if (swapchain != NULL) {
                vkDestroySwapchainKHR(ctx.device, swapchain, null);
                swapchain = NULL;
            }
        }
        imageViews = new ArrayList<>();
        images = new ArrayList<>();
    }

    private static void check(int vkResult, String what) {
        if (vkResult != VK_SUCCESS) {
            throw new IllegalStateException(what + " failed with VkResult " + vkResult);
        }
    }
}
