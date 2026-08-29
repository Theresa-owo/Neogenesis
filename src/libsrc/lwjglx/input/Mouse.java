package libsrc.lwjglx.input;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import org.lwjgl.glfw.GLFW;
import libsrc.lwjglx.LWJGLException;
import libsrc.lwjglx.Sys;
import libsrc.lwjglx.opengl.Display;

public class Mouse {

	public static int cursorMode = 0;
	private static boolean grabbed = false;
	private static int lastX = 0;
	private static int lastY = 0;
	private static int latestX = 0;
	private static int latestY = 0;
	private static int x = 0;
	private static int y = 0;
	private static final EventQueue queue = new EventQueue(8388608);
	private static final int[] buttonEvents;
	private static final boolean[] buttonEventStates;
	private static final int[] wheelEvents;
	private static final int[] xEvents;
	private static final int[] yEvents;
	private static final int[] lastxEvents;
	private static final int[] lastyEvents;
	private static final long[] nanoTimeEvents;
	private static boolean clipPostionToDisplay;


	public static void addMoveEvent(double var0, double var2) {
		latestX = (int)var0;
		latestY = Display.getHeight() - (int)var2;
		lastxEvents[queue.getNextPos()] = xEvents[queue.getNextPos()];
		lastyEvents[queue.getNextPos()] = yEvents[queue.getNextPos()];
		xEvents[queue.getNextPos()] = latestX;
		yEvents[queue.getNextPos()] = latestY;
		buttonEvents[queue.getNextPos()] = -1;
		buttonEventStates[queue.getNextPos()] = false;
		nanoTimeEvents[queue.getNextPos()] = Sys.getNanoTime();
		wheelEvents[queue.getNextPos()] = 0;
		queue.add();
	}

	public static void addScrollEvent(double var0) {
		lastxEvents[queue.getNextPos()] = xEvents[queue.getNextPos()];
		lastyEvents[queue.getNextPos()] = yEvents[queue.getNextPos()];
		xEvents[queue.getNextPos()] = latestX;
		yEvents[queue.getNextPos()] = latestY;
		buttonEvents[queue.getNextPos()] = -1;
		buttonEventStates[queue.getNextPos()] = false;
		nanoTimeEvents[queue.getNextPos()] = Sys.getNanoTime();
		wheelEvents[queue.getNextPos()] = (int)((double)120.0F * var0);
		queue.add();
	}

	public static void addButtonEvent(int var0, boolean var1) {
		lastxEvents[queue.getNextPos()] = xEvents[queue.getNextPos()];
		lastyEvents[queue.getNextPos()] = yEvents[queue.getNextPos()];
		xEvents[queue.getNextPos()] = latestX;
		yEvents[queue.getNextPos()] = latestY;
		buttonEvents[queue.getNextPos()] = var0;
		buttonEventStates[queue.getNextPos()] = var1;
		nanoTimeEvents[queue.getNextPos()] = Sys.getNanoTime();
		wheelEvents[queue.getNextPos()] = 0;
		queue.add();
	}

	public static void poll() {
		lastX = x;
		lastY = y;
		if (!grabbed && clipPostionToDisplay) {
			if (latestX < 0) {
				latestX = 0;
			}

			if (latestY < 0) {
				latestY = 0;
			}

			if (latestX > Display.getWidth() - 1) {
				latestX = Display.getWidth() - 1;
			}

			if (latestY > Display.getHeight() - 1) {
				latestY = Display.getHeight() - 1;
			}
		}

		x = latestX;
		y = latestY;
	}

	public static int getReleasedCursorMode() {
		Minecraft var3 = Minecraft.getMinecraft();
		boolean var4 = var3.theWorld != null;
		//boolean var5 = var3.currentScreen instanceof ClientScreen && ((ClientScreen) var3.currentScreen).getWindow() instanceof IngameMenu;
		boolean var6 = var3.currentScreen instanceof GuiChat;
		boolean var7 = var4 && !var6;// && !var5
		return var7 ? 1 : 0;
	}
	public static void create() throws LWJGLException {
	}

	public static boolean isCreated() {
		return Display.isCreated();
	}

	public static void setGrabbed(boolean var0) {
		if (var0) {
			cursorMode = 1;
			GLFW.glfwSetInputMode(Display.getWindow(), 208897, 212995);
		} else {
			cursorMode = getReleasedCursorMode();
			GLFW.glfwSetInputMode(Display.getWindow(), 208897, cursorMode == 2 ? 212995 : 212993);
		}

		GLFW.glfwSetCursorPos(Display.getWindow(), (double)Display.getWidth() / (double)2.0F, (double)Display.getHeight() / (double)2.0F);
		latestX = lastX = x = Display.getWidth() / 2;
		latestY = lastY = y = Display.getHeight() / 2;
		if (!var0) {
			addMoveEvent((double)Display.getWidth() / (double)2.0F, (double)Display.getHeight() / (double)2.0F);
		}

		grabbed = var0;
	}

	public static boolean isGrabbed() {
		return grabbed;
	}

	public static boolean isButtonDown(int var0) {
		return GLFW.glfwGetMouseButton(Display.getWindow(), var0) == 1;
	}

	public static boolean next() {
		return queue.next();
	}

	public static int getEventX() {
		return xEvents[queue.getCurrentPos()];
	}

	public static int getEventY() {
		return yEvents[queue.getCurrentPos()];
	}

	public static int getEventDX() {
		return xEvents[queue.getCurrentPos()] - lastxEvents[queue.getCurrentPos()];
	}

	public static int getEventDY() {
		return yEvents[queue.getCurrentPos()] - lastyEvents[queue.getCurrentPos()];
	}

	public static long getEventNanoseconds() {
		return nanoTimeEvents[queue.getCurrentPos()];
	}

	public static int getEventButton() {
		return buttonEvents[queue.getCurrentPos()];
	}

	public static boolean getEventButtonState() {
		return buttonEventStates[queue.getCurrentPos()];
	}

	public static int getEventDWheel() {
		return wheelEvents[queue.getCurrentPos()];
	}

	public static int getX() {
		return x;
	}

	public static int getY() {
		return y;
	}

	public static int getDX() {
		return x - lastX;
	}

	public static int getDY() {
		return y - lastY;
	}

	public static int getDWheel() {
		return wheelEvents[queue.getCurrentPos()];
	}

	public static int getButtonCount() {
		return 8;
	}

	public static void setClipMouseCoordinatesToWindow(boolean var0) {
		clipPostionToDisplay = var0;
	}

	public static void setCursorPosition(int var0, int var1) {
		GLFW.glfwSetCursorPos(Display.getWindow(), (double)var0, (double)var1);
	}

	public static Cursor setNativeCursor(Cursor var0) throws LWJGLException {
		return null;
	}

	public static void destroy() {
	}

	static {
		buttonEvents = new int[queue.getMaxEvents()];
		buttonEventStates = new boolean[queue.getMaxEvents()];
		xEvents = new int[queue.getMaxEvents()];
		yEvents = new int[queue.getMaxEvents()];
		lastxEvents = new int[queue.getMaxEvents()];
		lastyEvents = new int[queue.getMaxEvents()];
		nanoTimeEvents = new long[queue.getMaxEvents()];
		wheelEvents = new int[queue.getMaxEvents()];
		clipPostionToDisplay = true;
	}
}
