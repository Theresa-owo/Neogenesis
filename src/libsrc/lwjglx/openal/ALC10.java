package libsrc.lwjglx.openal;

import java.nio.IntBuffer;

public class ALC10 {
	static ALCcontext alcContext;
	public static final int ALC_FREQUENCY = 4103;
	public static final int ALC_REFRESH = 4104;
	public static final int ALC_SYNC = 4105;
	public static final int ALC_NO_ERROR = 0;
	public static final int ALC_DEFAULT_DEVICE_SPECIFIER = 4100;
	public static final int ALC_DEVICE_SPECIFIER = 4101;

	public ALC10() {
	}

	public static int alcGetError(ALCdevice var0) {
		return var0 == null ? org.lwjgl.openal.ALC10.alcGetError(AL.alcDevice.device) : org.lwjgl.openal.ALC10.alcGetError(var0.device);
	}

	public static String alcGetString(ALCdevice var0, int var1) {
		return var0 == null ? org.lwjgl.openal.ALC10.alcGetString(AL.alcDevice.device, var1) : org.lwjgl.openal.ALC10.alcGetString(var0.device, var1);
	}

	public static boolean alcIsExtensionPresent(ALCdevice var0, String var1) {
		return var0 == null ? org.lwjgl.openal.ALC10.alcIsExtensionPresent(AL.alcDevice.device, var1) : org.lwjgl.openal.ALC10.alcIsExtensionPresent(var0.device, var1);
	}

	public static ALCcontext alcCreateContext(ALCdevice var0, IntBuffer var1) {
		long var2 = org.lwjgl.openal.ALC10.alcCreateContext(var0.device, var1);
		alcContext = new ALCcontext(var2);
		return alcContext;
	}

	public static ALCcontext alcGetCurrentContext() {
		return alcContext;
	}

	public static ALCdevice alcGetContextsDevice(ALCcontext var0) {
		return AL.alcDevice;
	}

	public static void alcGetInteger(ALCdevice var0, int var1, IntBuffer var2) {
		org.lwjgl.openal.ALC10.alcGetIntegerv(var0.device, var1, var2);
	}
}
