package libsrc.lwjglx.opengl;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.GL_TRUE;
import static org.lwjgl.system.MemoryStack.stackPush;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import net.theresa.neogenesis.ClientMain;
import net.theresa.neogenesis.modules.rawinput.RawInput;
import net.theresa.neogenesis.utils.types.Tuple2;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import libsrc.lwjglx.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFWWindowFocusCallback;
import org.lwjgl.glfw.GLFWWindowIconifyCallback;
import org.lwjgl.glfw.GLFWWindowPosCallback;
import org.lwjgl.glfw.GLFWWindowRefreshCallback;
import org.lwjgl.glfw.*;
import libsrc.lwjglx.LWJGLException;
import libsrc.lwjglx.Sys;
import libsrc.lwjglx.input.Keyboard;
import libsrc.lwjglx.input.Mouse;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;

import net.theresa.neogenesis.utils.Builder;

import javax.imageio.ImageIO;

public class Display {

	private static String windowTitle = "Game";

	private static GLCapabilities context;

	private static boolean displayCreated = false;
	private static boolean displayFocused = false;
	private static boolean displayVisible = true;
	private static boolean displayDirty = false;
	private static boolean displayResizable = false;

	private static DisplayMode mode = new DisplayMode(640, 480);
	private static DisplayMode desktopDisplayMode = new DisplayMode(640, 480);

	private static int latestEventKey = 0;

	private static int displayX = 0;
	private static int displayY = 0;

	private static boolean displayResized = false;
	private static int displayWidth = 0;
	private static int displayHeight = 0;
	private static int displayFramebufferWidth = 0;
	private static int displayFramebufferHeight = 0;

	private static boolean latestResized = false;
	private static int latestWidth = 0;
	private static int latestHeight = 0;
	private static boolean fullscreen = false;
	private static final int[] lastWindowPos = {0, 0};

	private static final GLFWVidMode GLFWvidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());

	private static boolean isRawInput = false;

	static {
		Sys.initialize(); // init using dummy sys method

		int monitorWidth = GLFWvidmode.width();
		int monitorHeight = GLFWvidmode.height();
		int monitorBitPerPixel = GLFWvidmode.redBits() + GLFWvidmode.greenBits() + GLFWvidmode.blueBits();
		int monitorRefreshRate = GLFWvidmode.refreshRate();

		desktopDisplayMode = new DisplayMode(monitorWidth, monitorHeight, monitorBitPerPixel, monitorRefreshRate);
	}

	public static void toggleRawInput(boolean rawInput)
	{
		if (rawInput ^ isRawInput)
		{
			setRawInput(rawInput);
		}
	}

	public static void setRawInput(boolean rawInput)
	{
		isRawInput = rawInput;
		if (org.lwjgl.glfw.GLFW.glfwRawMouseMotionSupported())
		{
			org.lwjgl.glfw.GLFW.glfwSetInputMode(Window.handle, GLFW_RAW_MOUSE_MOTION, rawInput ? 1 : 0);
		}
		else
		{
			Minecraft.logger.warn("RawInput is not supported!");
		}
	}

	public static void create(PixelFormat pixel_format, Drawable shared_drawable) throws LWJGLException {
		//System.out.println("TODO: Implement Display.create(PixelFormat, Drawable)"); // TODO
		create();
	}

	public static void create(PixelFormat pixel_format, ContextAttribs attribs) throws LWJGLException {
		//System.out.println("TODO: Implement Display.create(PixelFormat, ContextAttribs)"); // TODO
		create();
	}

	public static void create(PixelFormat pixel_format) throws LWJGLException {
		//System.out.println("TODO: Implement Display.create(PixelFormat)"); // TODO
		create();
	}
	public static void create() {
		if (!displayCreated) {
            GLFW.glfwGetPrimaryMonitor();
            GLFW.glfwDefaultWindowHints();
			GLFW.glfwWindowHint(131076, 0);
			GLFW.glfwWindowHint(131075, displayResizable ? 1 : 0);
			GLFW.glfwWindowHint(139271, 1);
			Display.Window.handle = GLFW.glfwCreateWindow(mode.getWidth(), mode.getHeight(), windowTitle, 0L, 0L);
			if (Display.Window.handle == 0L) {
				throw new IllegalStateException("Failed to create Display window");
			} else {
				Display.Window.keyCallback = new GLFWKeyCallback() {
					public void invoke(long window, int key, int scancode, int action, int mods) {
						Display.latestEventKey = key;
						if (action == 0 || action == 1) {
							Keyboard.addKeyEvent(key, action == 1, false);
						}

						if (action == 2 && Keyboard.enableRepeat) {
							Keyboard.addKeyEvent(key, true, true);
						}
					}
				};
				Display.Window.charCallback = new GLFWCharCallback() {
					public void invoke(long var1, int var3) {
						Keyboard.addCharEvent(Display.latestEventKey, (char)var3);
					}
				};
				Display.Window.cursorPosCallback = new GLFWCursorPosCallback() {
					public void invoke(long var1, double var3, double var5) {
						if (Mouse.cursorMode == 2) {
							//double var7 = (double)(Integer)ModManager.modtog.getSetting("ingamecursor_dpi") / (double)1000.0F;
							double var9 = (double)Display.displayWidth / (double)2.0F;
							double var11 = (double)Display.displayHeight / (double)2.0F ;
							double var13 = (double)Display.displayWidth / (double)2.0F - var9;
							double var15 = (double)Display.displayWidth / (double)2.0F + var9;
							double var17 = (double)Display.displayHeight / (double)2.0F - var11;
							double var19 = (double)Display.displayHeight / (double)2.0F + var11;
							boolean var21 = var3 < var13 || var3 > var15 || var5 < var17 || var5 > var19;
							if (var3 < var13) {
								var3 = var13;
							}

							if (var3 > var15) {
								var3 = var15;
							}

							if (var5 < var17) {
								var5 = var17;
							}

							if (var5 > var19) {
								var5 = var19;
							}

							if (var21) {
								GLFW.glfwSetCursorPos(Display.Window.handle, var3, var5);
							}

							double var22 = (var3 - (double)Display.displayWidth / (double)2.0F) + (double)Display.displayWidth / (double)2.0F;
							double var24 = (var5 - (double)Display.displayHeight / (double)2.0F) + (double)Display.displayHeight / (double)2.0F;
							Mouse.addMoveEvent(var22, var24);
						} else {
							Mouse.addMoveEvent(var3, var5);
						}

					}
				};
				Display.Window.mouseButtonCallback = new GLFWMouseButtonCallback() {
					public void invoke(long var1, int var3, int var4, int var5) {
						Mouse.addButtonEvent(var3, var4 == 1);
					}
				};
				Display.Window.windowFocusCallback = new GLFWWindowFocusCallback() {
					public void close() {
						super.close();
					}

					public void callback(long var1, long var3) {
						super.callback(var1, var3);
					}

					public void invoke(long var1, boolean var3) {
						Display.displayFocused = var3;
					}
				};
				Display.Window.windowIconifyCallback = new GLFWWindowIconifyCallback() {
					public void close() {
						super.close();
					}

					public void callback(long var1, long var3) {
						super.callback(var1, var3);
					}

					public void invoke(long var1, boolean var3) {
						Display.displayVisible = !var3;
					}
				};
				Display.Window.windowSizeCallback = new GLFWWindowSizeCallback() {
					public void invoke(long var1, int var3, int var4) {
						Display.latestResized = true;
						Display.latestWidth = var3;
						Display.latestHeight = var4;
					}
				};
				Display.Window.windowPosCallback = new GLFWWindowPosCallback() {
					public void invoke(long var1, int var3, int var4) {
						Display.displayX = var3;
						Display.displayY = var4;
					}
				};
				Display.Window.windowRefreshCallback = new GLFWWindowRefreshCallback() {
					public void invoke(long var1) {
						Display.displayDirty = true;
					}
				};
				Display.Window.framebufferSizeCallback = new GLFWFramebufferSizeCallback() {
					public void invoke(long var1, int var3, int var4) {
						Display.displayFramebufferWidth = var3;
						Display.displayFramebufferHeight = var4;
					}
				};
				Display.Window.scrollCallback = new GLFWScrollCallback() {
					public void invoke(long var1, double var3, double var5) {
						Mouse.addScrollEvent(var5);
					}
				};
				Display.Window.setCallbacks(Window.handle);
				displayWidth = mode.getWidth();
				displayHeight = mode.getHeight();
				IntBuffer var7 = BufferUtils.createIntBuffer(1);
				IntBuffer var8 = BufferUtils.createIntBuffer(1);
				GLFW.glfwGetFramebufferSize(Display.Window.handle, var7, var8);
				displayFramebufferWidth = var7.get(0);
				displayFramebufferHeight = var8.get(0);
				double var3 = getWidth();
				double var4 = getHeight();
				GLFW.glfwSetWindowPos(Display.Window.handle, ((int) ((var3 - modeWidth()) / 2)), (int) ((var4 - modeHeight()) / 2));
				displayX = (int) ((var3 - mode.getWidth()) / 2);
				displayY = (int) ((var4 - mode.getHeight()) / 2);
				GLFW.glfwMakeContextCurrent(Display.Window.handle);
				GL.createCapabilities();
				GLFW.glfwSwapInterval(1);
				GLFW.glfwShowWindow(Display.Window.handle);
				displayCreated = true;
			}
		}
	}
	public static int getHeight(long handle) {
		try (MemoryStack stack = stackPush()) {
			IntBuffer heightBuffer = stack.mallocInt(1);
			GLFW.glfwGetWindowSize(handle, null, heightBuffer);
			return heightBuffer.get(0);
		}
	}

	public static int getWidth(long handle) {
		try (MemoryStack stack = stackPush()) {
			IntBuffer widthBuffer = stack.mallocInt(1);
			GLFW.glfwGetWindowSize(handle, widthBuffer, null);
			return widthBuffer.get(0);
		}
	}
	public static int modeWidth() {
		return mode.getWidth();
	}
	public static int modeHeight() {
		return mode.getHeight();
	}
/*
	public static void create() throws LWJGLException {

		int monitorWidth = GLFWvidmode.width();
		int monitorHeight = GLFWvidmode.height();
		int monitorBitPerPixel = GLFWvidmode.redBits() + GLFWvidmode.greenBits() + GLFWvidmode.blueBits();
		int monitorRefreshRate = GLFWvidmode.refreshRate();

		desktopDisplayMode = new DisplayMode(monitorWidth, monitorHeight, monitorBitPerPixel, monitorRefreshRate);

		glfwDefaultWindowHints();
		glfwWindowHint(GLFW_VISIBLE, GL_FALSE);
		glfwWindowHint(GLFW_RESIZABLE, displayResizable ? GL_TRUE : GL_FALSE);
		glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GL_TRUE);


		Window.handle = glfwCreateWindow(mode.getWidth(), mode.getHeight(), windowTitle, NULL, NULL);
		if ( Window.handle == 0L )
			throw new IllegalStateException("Failed to create Display window");


		Window.keyCallback = new GLFWKeyCallback() {
			@Override
			public void invoke(long window, int key, int scancode, int action, int mods) {
				latestEventKey = key;

				if (action == GLFW_RELEASE || action == GLFW.GLFW_PRESS) {
					Keyboard.addKeyEvent(key, action == GLFW.GLFW_PRESS ? true : false);
				}
			}
		};

		Window.charCallback = new GLFWCharCallback() {
			@Override
			public void invoke(long window, int codepoint) {
				Keyboard.addCharEvent(latestEventKey, (char)codepoint);
			}
		};

		Window.cursorPosCallback = new GLFWCursorPosCallback() {
			@Override
			public void invoke(long window, double xpos, double ypos) {
				Mouse.addMoveEvent(xpos, ypos);
			}
		};

		Window.mouseButtonCallback = new GLFWMouseButtonCallback() {
			@Override
			public void invoke(long window, int button, int action, int mods) {
				Mouse.addButtonEvent(button, action == GLFW.GLFW_PRESS ? true : false);
			}
		};

		Window.windowFocusCallback = new GLFWWindowFocusCallback() {
			@Override
			public void invoke(long window, boolean focused) {
				displayFocused = (focused ? 1 : 0) == GL11.GL_TRUE;
			}
		};

		Window.windowIconifyCallback = new GLFWWindowIconifyCallback() {
			@Override
			public void invoke(long window, boolean iconified) {
				displayVisible = (iconified ? 1 : 0) == GL11.GL_FALSE;
			}
		};

		Window.windowSizeCallback = new GLFWWindowSizeCallback() {
			@Override
			public void invoke(long window, int width, int height) {
				latestResized = true;
				latestWidth = width;
				latestHeight = height;
			}
		};

		Window.windowPosCallback = new GLFWWindowPosCallback() {
			@Override
			public void invoke(long window, int xpos, int ypos) {
				displayX = xpos;
				displayY = ypos;
			}
		};

		Window.windowRefreshCallback = new GLFWWindowRefreshCallback() {
			@Override
			public void invoke(long window) {
				displayDirty = true;
			}
		};

		Window.framebufferSizeCallback = new GLFWFramebufferSizeCallback() {
			@Override
			public void invoke(long window, int width, int height) {
				displayFramebufferWidth = width;
				displayFramebufferHeight = height;
			}
		};

		Window.setCallbacks(Window.handle);

		displayWidth = mode.getWidth();
		displayHeight = mode.getHeight();

		IntBuffer fbw = BufferUtils.createIntBuffer(1);
		IntBuffer fbh = BufferUtils.createIntBuffer(1);
		GLFW.glfwGetFramebufferSize(Window.handle, fbw, fbh);
		displayFramebufferWidth = fbw.get(0);
		displayFramebufferHeight = fbh.get(0);

		glfwSetWindowPos(
			Window.handle,
			(monitorWidth - mode.getWidth()) / 2,
			(monitorHeight - mode.getHeight()) / 2
		);

		displayX = (monitorWidth - mode.getWidth()) / 2;
		displayY = (monitorHeight - mode.getHeight()) / 2;

		glfwMakeContextCurrent(Window.handle);
		context = GL.createCapabilities();

		glfwSwapInterval(1);
		glfwShowWindow(Window.handle);

		displayCreated = true;
	}
	*/

	public static boolean isCreated() {
		return displayCreated;
	}

	public static boolean isActive() {
		return displayFocused;
	}

	public static boolean isVisible() {
		return displayVisible;
	}

	public static GLCapabilities getContext() {
		return context;
	}

	public static void setLocation(int new_x, int new_y) {
		System.out.println("TODO: Implement Display.setLocation(int, int)");
	}

	public static void setVSyncEnabled(boolean sync) {
		GLFW.glfwSwapInterval(sync ? 1 : 0);
	}

	public static long getWindow() {
		return Window.handle;
	}

	public static void update() {
		update(true);
	}

	public static void update(boolean processMessages) {
		try {
			swapBuffers();
			displayDirty = false;
		}
		catch (LWJGLException e) {
			throw new RuntimeException(e);
		}

		if (processMessages) processMessages();
	}

	public static void processMessages() {
		glfwPollEvents();
		Keyboard.poll();
		Mouse.poll();

		if (latestResized) {
			latestResized = false;
			displayResized = true;
			displayWidth = latestWidth;
			displayHeight = latestHeight;
		}
		else {
			displayResized = false;
		}
	}

	public static void swapBuffers() throws LWJGLException {
		glfwSwapBuffers(Window.handle);
	}

	public static void destroy() {
		Window.releaseCallbacks();
		glfwDestroyWindow(Window.handle);

		/*try {
			glfwTerminate();
		} catch (Throwable t) {
			t.printStackTrace();
		}*/
		displayCreated = false;
	}

	public static void setDisplayMode(DisplayMode dm) throws LWJGLException {
		mode = dm;
	}

	public static DisplayMode getDisplayMode() {
		return mode;
	}

	public static DisplayMode[] getAvailableDisplayModes() throws LWJGLException {
		IntBuffer count = BufferUtils.createIntBuffer(1);

		DisplayMode[] displayModes = new DisplayMode[count.get(0)];

		for (int i = 0; i < count.get(0); i++) {

			int w = GLFWvidmode.width();
			int h = GLFWvidmode.height();
			int b = GLFWvidmode.redBits() + GLFWvidmode.greenBits()
					+ GLFWvidmode.blueBits();
			int r = GLFWvidmode.refreshRate();

			displayModes[i] = new DisplayMode(w, h, b, r);
		}

		return displayModes;
	}

	public static DisplayMode getDesktopDisplayMode() {
		return desktopDisplayMode;
	}

	public static boolean wasResized() {
		return displayResized;
	}

	public static int getX() {
		return displayX;
	}

	public static int getY() {
		return displayY;
	}

	public static int getWidth() {
		return displayWidth;
	}

	public static int getHeight() {
		return displayHeight;
	}

	public static int getFramebufferWidth() {
		return displayFramebufferWidth;
	}

	public static int getFramebufferHeight() {
		return displayFramebufferHeight;
	}

	public static void setTitle(String title) {
		windowTitle = title;
	}

	public static boolean isCloseRequested() {
		return (glfwWindowShouldClose(Window.handle) ? 1 : 0) == GL_TRUE;
	}

	public static boolean isDirty() {
		return displayDirty;
	}

	public static void setInitialBackground(float red, float green, float blue) {
		// TODO
		System.out.println("TODO: Implement Display.setInitialBackground(float, float, float)");
	}

	private static byte[] readImage(String var0) throws IOException {
		//System.out.println(var0);
		byte[] var17;
		try (InputStream var1 = ClientMain.class.getResourceAsStream(var0)) {
            BufferedImage var3;
			var3 = ImageIO.read(var1);
            int[] var4 = var3.getRGB(0, 0, var3.getWidth(), var3.getHeight(), (int[])null, 0, var3.getWidth());
			byte[] var5 = new byte[var4.length * 4];

			for(int var6 = 0; var6 < var4.length; ++var6) {
				var5[var6 << 2] = (byte)(var4[var6] >> 16 & 255);
				var5[var6 << 2 | 1] = (byte)(var4[var6] >> 8 & 255);
				var5[var6 << 2 | 2] = (byte)(var4[var6] & 255);
				var5[var6 << 2 | 3] = (byte)(var4[var6] >> 24 & 255);
			}

			var17 = var5;
		}

		return var17;
	}

	public static void setIcon(Tuple2<byte[], Integer>[] icons) {
		GLFWImage.Buffer var1 = GLFWImage.malloc(icons.length);
		List<GLFWImage> var2 = Builder.arrayList(new GLFWImage[0]);

		for(Tuple2 var6 : icons) {
			ByteBuffer var7 = ByteBuffer.allocateDirect(((byte[]) var6.getA()).length);
			var7.put((byte[])var6.getA());
			var7.flip();
			GLFWImage var8 = GLFWImage.malloc();
			var8.set((Integer) var6.getB(), (Integer) var6.getB(), var7);
			var1.put(var8);
			var2.add(var8);
		}

		var1.flip();
		GLFW.glfwSetWindowIcon(Display.Window.handle, var1);
		var1.free();
		var2.forEach(Struct::free);
	}


	public static void setIcon(List<InputStream> streams) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			GLFWImage.Buffer icons = GLFWImage.malloc(streams.size());

			for (InputStream stream : streams) {
				BufferedImage image = ImageIO.read(stream);

				int width = image.getWidth();
				int height = image.getHeight();
				int[] pixels = new int[width * height];
				image.getRGB(0, 0, width, height, pixels, 0, width);

				ByteBuffer buffer = MemoryUtil.memAlloc(pixels.length * 4);
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						int pixel = pixels[y * width + x];

						buffer.put((byte) ((pixel >> 16) & 0xFF));  // Red
						buffer.put((byte) ((pixel >> 8) & 0xFF));   // Green
						buffer.put((byte) (pixel & 0xFF));          // Blue
						buffer.put((byte) ((pixel >> 24) & 0xFF));  // Alpha
					}
				}
				buffer.flip();

				GLFWImage icon = GLFWImage.malloc(stack);
				icon.set(width, height, buffer);

				icons.put(icon);
			}

			icons.rewind();
			GLFW.glfwSetWindowIcon(Window.handle, icons);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	public static ByteBuffer readStream(InputStream stream) throws IOException {
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		byte[] buffer = new byte[1024];
		int bytesRead;
		while ((bytesRead = stream.read(buffer)) != -1) {
			byteArrayOutputStream.write(buffer, 0, bytesRead);
		}
		byte[] byteArray = byteArrayOutputStream.toByteArray();
		ByteBuffer byteBuffer = ByteBuffer.allocateDirect(byteArray.length);
		byteBuffer.put(byteArray);
		byteBuffer.flip();
		return byteBuffer;
	}

	private static ByteBuffer convertToByteBuffer(BufferedImage image) {
		int[] pixels = new int[image.getWidth() * image.getHeight()];
		image.getRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());

		ByteBuffer buffer = ByteBuffer.allocateDirect(image.getWidth() * image.getHeight() * 4);

		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int pixel = pixels[y * image.getWidth() + x];
				buffer.put((byte) ((pixel >> 16) & 0xFF)); // Red
				buffer.put((byte) ((pixel >> 8) & 0xFF));  // Green
				buffer.put((byte) (pixel & 0xFF));         // Blue
				buffer.put((byte) ((pixel >> 24) & 0xFF)); // Alpha
			}
		}

		buffer.flip();
		return buffer;
	}
	public static void setIcon() {
		try {
			System.out.println();
			setIcon(new Tuple2[]{
					new Tuple2(readImage("/neogenesis/icons/icon_16x.png"), 16),
					new Tuple2(readImage("/neogenesis/icons/icon_32x.png"), 32),
					new Tuple2(readImage("/neogenesis/icons/icon_64x.png"), 64),
					new Tuple2(readImage("/neogenesis/icons/icon_128x.png"), 128),
					new Tuple2(readImage("/neogenesis/icons/icon_256x.png"), 256)});
		} catch (Exception var2) {
			var2.printStackTrace();
		}
	}

	public static void setResizable(boolean resizable) {
		displayResizable = resizable;
		// TODO
	}

	public static boolean isResizable() {
		return displayResizable;
	}

	public static void setDisplayModeAndFullscreen(DisplayMode mode) throws LWJGLException {
		// TODO
		System.out.println("TODO: Implement Display.setDisplayModeAndFullscreen(DisplayMode)");
	}

	public static void setFullscreen(boolean fullscreen) {
		setDisplayModeAndFullscreenInternal(fullscreen);
	}

	private static void setDisplayModeAndFullscreenInternal(boolean fullscreen) {
		synchronized (GlobalLock.lock) {
			boolean wasFullscreen = Display.fullscreen;
			Display.fullscreen = fullscreen;

			if (wasFullscreen != Display.fullscreen) {
				if (!isCreated()) {
					return;
				}

				GLFW.glfwSetWindowShouldClose(Window.handle, false);
				GLFWVidMode vidmode = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());

                if (Display.fullscreen) {
                    int[] xpos = new int[1];
                    int[] ypos = new int[1];
                    GLFW.glfwGetWindowPos(Window.handle, xpos, ypos);
                    lastWindowPos[0] = xpos[0];
                    lastWindowPos[1] = ypos[0];

                    if (vidmode != null) {
                        // Borderless fullscreen instead of exclusive: glfwSetWindowMonitor with a
                        // monitor handle forces a physical display-mode switch, which shows a black
                        // screen for seconds on every toggle/focus change. A borderless window is
                        // composited by DWM and switches instantly.
                        long monitor = GLFW.glfwGetPrimaryMonitor();
                        int[] monx = new int[1];
                        int[] mony = new int[1];
                        GLFW.glfwGetMonitorPos(monitor, monx, mony);
                        GLFW.glfwSetWindowAttrib(Window.handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
                        GLFW.glfwSetWindowMonitor(Window.handle, MemoryUtil.NULL, monx[0], mony[0], vidmode.width(), vidmode.height(), GLFW.GLFW_DONT_CARE);
                    }
                } else {
                    GLFW.glfwSetWindowAttrib(Window.handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
                    GLFW.glfwSetWindowMonitor(Window.handle, MemoryUtil.NULL, lastWindowPos[0], lastWindowPos[1], modeWidth(), modeHeight(), GLFW.GLFW_DONT_CARE);
                }

				GLFW.glfwMakeContextCurrent(Window.handle);
				GL.createCapabilities();
				GLFW.glfwShowWindow(Window.handle);

				//makeCurrentAndSetSwapInterval();
			}
		}
	}
	private static void makeCurrentAndSetSwapInterval() {
		GLFW.glfwSwapInterval(1); // Enable v-sync
	}

	public static boolean isFullscreen() {
		return fullscreen;
	}

	public static void setParent(java.awt.Canvas parent) throws LWJGLException {
		// Do nothing as set parent not supported
	}

	public static void releaseContext() throws LWJGLException {
		glfwMakeContextCurrent(0);
	}

	public static void makeCurrent() throws LWJGLException {
		glfwMakeContextCurrent(Window.handle);
	}

	public static java.lang.String getAdapter() {
		// TODO
		return "GeNotSupportedAdapter";
	}

	public static java.lang.String getVersion() {
		// TODO
		return "1.0 NOT SUPPORTED";
	}

	/**
	 * An accurate sync method that will attempt to run at a constant frame rate.
	 * It should be called once every frame.
	 *
	 * @param fps - the desired frame rate, in frames per second
	 */
	public static void sync(int fps) {
		Sync.sync(fps);
	}

	public static Drawable getDrawable() {
		return null;
	}

	static DisplayImplementation getImplementation() {
		return null;
	}

	public static class Window {
		public static long handle;

		static GLFWKeyCallback keyCallback;
		static GLFWCharCallback charCallback;
		static GLFWCursorPosCallback cursorPosCallback;
		static GLFWMouseButtonCallback mouseButtonCallback;
		static GLFWWindowFocusCallback windowFocusCallback;
		static GLFWWindowIconifyCallback windowIconifyCallback;
		static GLFWWindowSizeCallback windowSizeCallback;
		static GLFWWindowPosCallback windowPosCallback;
		static GLFWWindowRefreshCallback windowRefreshCallback;
		static GLFWFramebufferSizeCallback framebufferSizeCallback;
		static GLFWScrollCallback scrollCallback;

		public static void setCallbacks(long handle) {
			GLFW.glfwSetKeyCallback(handle, keyCallback);
			GLFW.glfwSetCharCallback(handle, charCallback);
			GLFW.glfwSetCursorPosCallback(handle, cursorPosCallback);
			GLFW.glfwSetMouseButtonCallback(handle, mouseButtonCallback);
			GLFW.glfwSetWindowFocusCallback(handle, windowFocusCallback);
			GLFW.glfwSetWindowIconifyCallback(handle, windowIconifyCallback);
			GLFW.glfwSetWindowSizeCallback(handle, windowSizeCallback);
			GLFW.glfwSetWindowPosCallback(handle, windowPosCallback);
			GLFW.glfwSetWindowRefreshCallback(handle, windowRefreshCallback);
			GLFW.glfwSetFramebufferSizeCallback(handle, framebufferSizeCallback);
			GLFW.glfwSetScrollCallback(handle, scrollCallback);
		}

		public static void releaseCallbacks() {
			if (keyCallback != null) keyCallback.free();
			if (charCallback != null) charCallback.free();
			if (cursorPosCallback != null) cursorPosCallback.free();
			if (mouseButtonCallback != null) mouseButtonCallback.free();
			if (windowFocusCallback != null) windowFocusCallback.free();
			if (windowIconifyCallback != null) windowIconifyCallback.free();
			if (windowSizeCallback != null) windowSizeCallback.free();
			if (windowPosCallback != null) windowPosCallback.free();
			if (windowRefreshCallback != null) windowRefreshCallback.free();
			if (framebufferSizeCallback != null) framebufferSizeCallback.free();
		}
	}

}
