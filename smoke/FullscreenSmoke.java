import libsrc.lwjglx.opengl.Display;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

/** Exercises the borderless-fullscreen path in lwjglx Display. */
public class FullscreenSmoke {
    public static void main(String[] args) throws Exception {
        GLFW.glfwInit();
        Display.create();
        long h = Display.Window.handle;
        System.out.println("[1/4] window created: " + Display.getWidth() + "x" + Display.getHeight());

        GLFWVidMode vidmode = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());
        Display.setFullscreen(true);
        for (int i = 0; i < 40 && Display.getHeight() != vidmode.height(); i++) { Thread.sleep(50); Display.update(); }
        boolean fsSize = Display.getWidth() == vidmode.width() && Display.getHeight() == vidmode.height();
        boolean undecorated = GLFW.glfwGetWindowAttrib(h, GLFW.GLFW_DECORATED) == 0;
        System.out.println("[2/4] fullscreen: size=" + Display.getWidth() + "x" + Display.getHeight()
                + " (monitor " + vidmode.width() + "x" + vidmode.height() + ") borderless=" + undecorated);
        if (!fsSize || !undecorated) throw new IllegalStateException("fullscreen switch failed");

        Thread.sleep(500);
        Display.setFullscreen(false);
        for (int i = 0; i < 40 && Display.getHeight() == vidmode.height(); i++) { Thread.sleep(50); Display.update(); }
        boolean decorated = GLFW.glfwGetWindowAttrib(h, GLFW.GLFW_DECORATED) == 1;
        System.out.println("[3/4] windowed: size=" + Display.getWidth() + "x" + Display.getHeight()
                + " decorated=" + decorated);
        if (!decorated) throw new IllegalStateException("windowed restore failed");

        Display.destroy();
        System.out.println("[4/4] SMOKE OK");
        System.exit(0);
    }
}
