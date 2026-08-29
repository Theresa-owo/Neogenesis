package libsrc.lwjglx.openal;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
import libsrc.lwjglx.LWJGLException;
import libsrc.lwjglx.Sys;

public class AL {
	static ALCdevice alcDevice;
	private static boolean created = false;

	public AL() {
	}

	public static void create() throws LWJGLException {
		if (!created) {
			long var0 = ALC10.alcOpenDevice((ByteBuffer)null);
			ALCCapabilities var2 = ALC.createCapabilities(var0);
			IntBuffer var3 = BufferUtils.createIntBuffer(16);
			var3.put(4103);
			var3.put(192000);
			var3.put(4104);
			var3.put(60);
			var3.put(4105);
			var3.put(0);
			var3.put(0);
			var3.flip();
			long var4 = ALC10.alcCreateContext(var0, var3);
			ALC10.alcMakeContextCurrent(var4);
			org.lwjgl.openal.AL.createCapabilities(var2);
			alcDevice = new ALCdevice(var4);
			created = true;
		}

	}

	public static boolean isCreated() {
		return created;
	}

	public static void destroy() {
		org.lwjgl.openal.AL.setCurrentProcess((ALCapabilities)null);
		alcDevice = null;
		created = false;
	}

	public static ALCdevice getDevice() {
		return alcDevice;
	}

	static {
		Sys.initialize();
	}
}