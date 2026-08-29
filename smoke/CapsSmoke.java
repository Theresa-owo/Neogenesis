import libsrc.lwjglx.opengl.GLContext;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

public class CapsSmoke {
    public static void main(String[] args) throws Exception {
        org.lwjgl.glfw.GLFW.glfwInit();
        libsrc.lwjglx.opengl.Display.create();
        GLCapabilities lwjgl = GL.getCapabilities();
        System.out.println("[LWJGL3 caps] OpenGL13=" + lwjgl.OpenGL13 + " OpenGL31=" + lwjgl.OpenGL31
                + " ARB_copy_buffer=" + lwjgl.GL_ARB_copy_buffer + " ARB_vbo=" + lwjgl.GL_ARB_vertex_buffer_object);
        libsrc.lwjglx.opengl.ContextCapabilities caps = GLContext.getCapabilities();
        System.out.println("[lwjglx caps]  OpenGL13=" + caps.OpenGL13 + " OpenGL31=" + caps.OpenGL31
                + " ARB_copy_buffer=" + caps.GL_ARB_copy_buffer);
        System.exit(0);
    }
}
