package net.theresa.render;

import net.theresa.render.vulkan.VulkanRenderer;

/**
 * Owns the active renderer backend and its lifecycle.
 *
 * The backend is selected with -Dneogenesis.renderer=vulkan (default: opengl).
 * While the Vulkan backend is a work in progress, any initialization failure
 * falls back to OpenGL so the game always stays playable.
 */
public final class RenderSystem {

    public enum Backend {
        OPENGL, VULKAN
    }

    private static Backend backend = Backend.OPENGL;
    private static VulkanRenderer vulkanRenderer;
    private static boolean initialised;

    private RenderSystem() {
    }

    public static Backend getBackend() {
        return backend;
    }

    public static boolean isVulkan() {
        return backend == Backend.VULKAN;
    }

    /**
     * Called once after the GLFW window exists. Must be on the client thread.
     */
    public static void init(long window, int width, int height) {
        if (initialised) {
            return;
        }
        initialised = true;

        String requested = System.getProperty("neogenesis.renderer", "opengl");
        if ("vulkan".equalsIgnoreCase(requested)) {
            try {
                vulkanRenderer = new VulkanRenderer();
                vulkanRenderer.init(window, width, height);
                backend = Backend.VULKAN;
                System.out.println("[RenderSystem] Vulkan backend active");
                return;
            } catch (Throwable t) {
                if (libsrc.lwjglx.opengl.Display.isVulkanCompanion()) {
                    // the window was created NO_API for Vulkan presentation; GL cannot take over now
                    throw new RuntimeException(
                            "Vulkan renderer init failed and the window is in Vulkan companion mode. "
                                    + "Remove -Dneogenesis.renderer=vulkan to play on OpenGL.", t);
                }
                System.err.println("[RenderSystem] Vulkan init failed, falling back to OpenGL: " + t);
                t.printStackTrace();
                vulkanRenderer = null;
            }
        }
        backend = Backend.OPENGL;
        System.out.println("[RenderSystem] OpenGL backend active");
    }

    /**
     * Renders one frame on the active backend. In Vulkan mode this replaces the
     * entire GL render path; in OpenGL mode it is a no-op (the vanilla loop runs).
     */
    public static void frame() {
        if (backend == Backend.VULKAN && vulkanRenderer != null) {
            vulkanRenderer.frame();
        }
    }

    public static void onResize(int width, int height) {
        if (backend == Backend.VULKAN && vulkanRenderer != null) {
            vulkanRenderer.onResize(width, height);
        }
    }

    public static void shutdown() {
        if (vulkanRenderer != null) {
            try {
                vulkanRenderer.cleanup();
            } catch (Throwable t) {
                System.err.println("[RenderSystem] Vulkan cleanup failed: " + t);
            }
            vulkanRenderer = null;
        }
        backend = Backend.OPENGL;
        initialised = false;
    }
}
