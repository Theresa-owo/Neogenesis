package net.theresa.render.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Instance, window surface, physical device and logical device for the Vulkan
 * backend. Graphics and present queue families may or may not be the same;
 * both are resolved here and used when creating the swapchain.
 */
public class VulkanContext {

    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    public VkInstance instance;
    public long surface;
    public VkPhysicalDevice physicalDevice;
    public VkDevice device;
    public VkQueue graphicsQueue;
    public VkQueue presentQueue;
    public int graphicsFamily = -1;
    public int presentFamily = -1;

    public VulkanContext(long glfwWindow) {
        if (!GLFWVulkan.glfwVulkanSupported()) {
            throw new IllegalStateException("Vulkan is not supported: GLFW reports no Vulkan loader or missing window surface extensions");
        }
        // No explicit VK.create(): LWJGL 3.4 initialises the VK context lazily (and
        // GLFW's loader probing may already have done it), a second create() throws.

        try (MemoryStack stack = MemoryStack.stackPush()) {
            createInstance(stack);
            createSurface(glfwWindow, stack);
            pickPhysicalDevice(stack);
            createLogicalDevice(stack);
        }
    }

    private void createInstance(MemoryStack stack) {
        PointerBuffer requiredExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
        if (requiredExtensions == null) {
            throw new IllegalStateException("Vulkan is not supported: GLFW could not list the required instance extensions");
        }

        // Only request the validation layer when the loader actually has it,
        // so machines without the Vulkan SDK layer installed still run.
        PointerBuffer layers = hasLayer(stack, VALIDATION_LAYER)
                ? stack.pointers(stack.UTF8(VALIDATION_LAYER))
                : null;

        VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("Neogenesis"))
                .apiVersion(VK_MAKE_VERSION(1, 1, 0));

        // Request VK_EXT_debug_utils too when the loader offers it, so the debug
        // messenger's function pointer exists on the instance capabilities.
        PointerBuffer extensions = requiredExtensions;
        if (VulkanDebug.hasInstanceExtension(org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
            extensions = stack.mallocPointer(requiredExtensions.remaining() + 1);
            extensions.put(requiredExtensions);
            extensions.put(stack.UTF8(org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
            extensions.flip();
        }

        VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(app)
                .ppEnabledLayerNames(layers)
                .ppEnabledExtensionNames(extensions);

        PointerBuffer pInstance = stack.mallocPointer(1);
        check(vkCreateInstance(info, null, pInstance), "vkCreateInstance");
        instance = new VkInstance(pInstance.get(0), info);
    }

    private static boolean hasLayer(MemoryStack stack, String layerName) {
        IntBuffer count = stack.mallocInt(1);
        int err = vkEnumerateInstanceLayerProperties(count, null);
        if (err != VK_SUCCESS || count.get(0) == 0) {
            return false;
        }
        VkLayerProperties.Buffer available = VkLayerProperties.calloc(count.get(0), stack);
        err = vkEnumerateInstanceLayerProperties(count, available);
        if (err != VK_SUCCESS) {
            return false;
        }
        for (int i = 0; i < available.capacity(); i++) {
            if (layerName.equals(available.get(i).layerNameString())) {
                return true;
            }
        }
        return false;
    }

    private void createSurface(long glfwWindow, MemoryStack stack) {
        LongBuffer pSurface = stack.mallocLong(1);
        int err = GLFWVulkan.glfwCreateWindowSurface(instance, glfwWindow, null, pSurface);
        if (err != VK_SUCCESS) {
            throw new IllegalStateException("glfwCreateWindowSurface failed with VkResult " + err);
        }
        surface = pSurface.get(0);
    }

    private void pickPhysicalDevice(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices");
        int deviceCount = count.get(0);
        if (deviceCount == 0) {
            throw new IllegalStateException("Vulkan is not supported: no physical devices found");
        }
        PointerBuffer devices = stack.mallocPointer(deviceCount);
        check(vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");

        int bestScore = -1;
        for (int i = 0; i < deviceCount; i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
            int[] families = findQueueFamilies(stack, candidate);
            if (families == null) {
                continue;
            }
            int score = deviceScore(stack, candidate, families);
            if (score > bestScore) {
                bestScore = score;
                physicalDevice = candidate;
                graphicsFamily = families[0];
                presentFamily = families[1];
            }
        }
        if (physicalDevice == null) {
            throw new IllegalStateException("Vulkan is not supported: no physical device with a graphics + present capable queue for the window surface");
        }
    }

    private int[] findQueueFamilies(MemoryStack stack, VkPhysicalDevice device) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        int familyCount = count.get(0);
        if (familyCount == 0) {
            return null;
        }
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(familyCount, stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);

        int graphics = -1;
        int present = -1;
        for (int i = 0; i < familyCount; i++) {
            if (graphics < 0 && (families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                graphics = i;
            }
            IntBuffer supported = stack.mallocInt(1);
            check(vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, supported), "vkGetPhysicalDeviceSurfaceSupportKHR");
            if (present < 0 && supported.get(0) == VK_TRUE) {
                present = i;
            }
        }
        return graphics >= 0 && present >= 0 ? new int[]{graphics, present} : null;
    }

    private static int deviceScore(MemoryStack stack, VkPhysicalDevice device, int[] families) {
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
        vkGetPhysicalDeviceProperties(device, props);

        int score = 0;
        if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
            score += 1000;
        } else if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) {
            score += 100;
        }
        if (families[0] == families[1]) {
            score += 10;
        }
        return score;
    }

    private void createLogicalDevice(MemoryStack stack) {
        FloatBuffer priorities = stack.floats(1.0f);
        boolean sameFamily = graphicsFamily == presentFamily;
        VkDeviceQueueCreateInfo.Buffer queueInfos = VkDeviceQueueCreateInfo.calloc(sameFamily ? 1 : 2, stack);
        queueInfos.get(0)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(graphicsFamily)
                .pQueuePriorities(priorities);
        if (!sameFamily) {
            queueInfos.get(1)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(presentFamily)
                    .pQueuePriorities(priorities);
        }

        PointerBuffer extensions = stack.pointers(stack.UTF8(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME));

        VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueInfos)
                .pEnabledFeatures(VkPhysicalDeviceFeatures.calloc(stack))
                .ppEnabledExtensionNames(extensions);

        PointerBuffer pDevice = stack.mallocPointer(1);
        check(vkCreateDevice(physicalDevice, info, null, pDevice), "vkCreateDevice");
        device = new VkDevice(pDevice.get(0), physicalDevice, info);

        PointerBuffer pQueue = stack.mallocPointer(1);
        vkGetDeviceQueue(device, graphicsFamily, 0, pQueue);
        graphicsQueue = new VkQueue(pQueue.get(0), device);
        vkGetDeviceQueue(device, presentFamily, 0, pQueue);
        presentQueue = new VkQueue(pQueue.get(0), device);
    }

    /**
     * Index of the first memory type matching typeBits with all of
     * memoryPropertyFlags set.
     */
    public int memoryTypeIndex(int typeBits, int memoryPropertyFlags) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, props);
            for (int i = 0; i < props.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) != 0
                        && (props.memoryTypes(i).propertyFlags() & memoryPropertyFlags) == memoryPropertyFlags) {
                    return i;
                }
            }
        }
        throw new IllegalStateException("No memory type for bits 0x" + Integer.toHexString(typeBits)
                + " with properties 0x" + Integer.toHexString(memoryPropertyFlags));
    }

    public void waitIdle() {
        check(vkDeviceWaitIdle(device), "vkDeviceWaitIdle");
    }

    public void cleanup() {
        if (device != null) {
            vkDestroyDevice(device, null);
            device = null;
        }
        if (instance != null) {
            if (surface != NULL) {
                vkDestroySurfaceKHR(instance, surface, null);
                surface = NULL;
            }
            vkDestroyInstance(instance, null);
            instance = null;
        }
    }

    public static void check(int vkResult, String what) {
        if (vkResult != VK_SUCCESS) {
            throw new IllegalStateException(what + " failed with VkResult " + vkResult);
        }
    }
}
