package net.theresa.render.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXTI;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkLayerProperties;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional Vulkan instance hardening: KHRONOS validation layer + EXT_debug_utils messaging.
 *
 * Everything here degrades gracefully: when the layer/extension is not present the
 * desired*() helpers simply omit it and {@link #setup} returns 0, so the renderer
 * must treat 0 as "no messenger" rather than a failure.
 */
public final class VulkanDebug {

    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    // The Java callback object backs the native function pointer, so it must stay
    // strongly reachable for as long as the messenger exists.
    private static VkDebugUtilsMessengerCallbackEXTI callback;
    private static boolean validationRequested;

    private VulkanDebug() {
    }

    /**
     * Returns the instance layers we want, filtered by what the ICD actually exposes.
     */
    public static List<String> desiredInstanceLayers() {
        List<String> layers = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            if (VK10.vkEnumerateInstanceLayerProperties(count, null) != VK10.VK_SUCCESS) {
                return layers;
            }
            int available = (int) count.get(0);
            if (available == 0) {
                return layers;
            }
            VkLayerProperties.Buffer props = VkLayerProperties.calloc(available, stack);
            if (VK10.vkEnumerateInstanceLayerProperties(count, props) != VK10.VK_SUCCESS) {
                return layers;
            }
            for (int i = 0; i < props.remaining(); i++) {
                if (VALIDATION_LAYER.equals(props.get(i).layerNameString())) {
                    layers.add(VALIDATION_LAYER);
                    break;
                }
            }
        }
        validationRequested = !layers.isEmpty();
        return layers;
    }

    /**
     * Returns the GLFW-required extensions plus VK_EXT_debug_utils when the loader exposes it.
     */
    public static List<String> desiredInstanceExtensions(List<String> glfwExtensions) {
        List<String> extensions = new ArrayList<>(glfwExtensions);
        if (hasInstanceExtension(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME) && !extensions.contains(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
            extensions.add(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        }
        return extensions;
    }

    /**
     * True once {@link #desiredInstanceLayers} actually found the validation layer.
     */
    public static boolean isValidationActive() {
        return validationRequested;
    }

    /**
     * Installs a debug messenger reporting WARNING and ERROR messages. Returns the
     * messenger handle, or 0 when VK_EXT_debug_utils is unavailable (or creation fails),
     * which callers must accept silently.
     */
    public static long setup(VkInstance instance) {
        if (instance == null || !hasInstanceExtension(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
            return 0;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            callback = (messageSeverity, messageTypes, pCallbackData, userData) ->
                VulkanDebug.onDebugMessage(messageSeverity, messageTypes,
                    VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData));
            VkDebugUtilsMessengerCreateInfoEXT info = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
            info.sType$Default();
            info.messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT);
            info.messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT);
            info.pfnUserCallback(callback);

            LongBuffer messenger = stack.mallocLong(1);
            int err = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, info, null, messenger);
            if (err != VK10.VK_SUCCESS) {
                System.err.println("[Vulkan] Failed to create debug messenger, error " + err);
                callback = null;
                return 0;
            }
            return messenger.get(0);
        }
    }

    /**
     * Tears the messenger down; a no-op when {@code messenger == 0} (debug disabled).
     */
    public static void destroy(VkInstance instance, long messenger) {
        if (messenger == 0 || instance == null) {
            return;
        }
        EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
        callback = null;
    }

    private static int onDebugMessage(int messageSeverity, int messageTypes, VkDebugUtilsMessengerCallbackDataEXT data) {
        System.err.println("[Vulkan " + severityName(messageSeverity) + "] " + data.pMessageString());
        return VK10.VK_FALSE;
    }

    private static String severityName(int messageSeverity) {
        if ((messageSeverity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
            return "error";
        }
        if ((messageSeverity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
            return "warning";
        }
        if ((messageSeverity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) != 0) {
            return "info";
        }
        return "verbose";
    }

    public static boolean hasInstanceExtension(String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            if (VK10.vkEnumerateInstanceExtensionProperties((CharSequence) null, count, null) != VK10.VK_SUCCESS) {
                return false;
            }
            int available = (int) count.get(0);
            if (available == 0) {
                return false;
            }
            VkExtensionProperties.Buffer props = VkExtensionProperties.calloc(available, stack);
            if (VK10.vkEnumerateInstanceExtensionProperties((CharSequence) null, count, props) != VK10.VK_SUCCESS) {
                return false;
            }
            for (int i = 0; i < props.remaining(); i++) {
                if (name.equals(props.get(i).extensionNameString())) {
                    return true;
                }
            }
            return false;
        }
    }
}
