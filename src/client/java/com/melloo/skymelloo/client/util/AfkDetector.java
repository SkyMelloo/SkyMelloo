package com.melloo.skymelloo.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Whether the local player has been genuinely untouched (no movement, no camera look) for 5+
 * minutes - used to report an "AFK" status alongside plain online/offline presence (see
 * ModPresenceManager), so other SkyMelloo users and the website can tell "online and actually
 * playing" apart from "online but stepped away".
 * <p>
 * Deliberately NOT hooked into raw keyboard/mouse GLFW callbacks (which would need a couple of new
 * Mixins on KeyboardHandler/MouseHandler) - polling the player's own position/look angle each tick is
 * a simpler, lower-risk proxy that needs no new injection points at all, and is accurate for the
 * overwhelming majority of real "stepped away from the keyboard" cases. It can't detect someone
 * clicking a stationary target with the camera perfectly still (a real but rare edge case) - not
 * worth a full input-hook for what's ultimately just a "5 min idle" heuristic, not a security check.
 */
public final class AfkDetector {
	private static final long AFK_THRESHOLD_TICKS = 5L * 60 * 20; // 5 min at 20 ticks/s
	private static final double POSITION_EPSILON = 0.001;
	private static final float ANGLE_EPSILON = 0.01F;

	private static boolean initialized = false;
	private static double lastX, lastY, lastZ;
	private static float lastYaw, lastPitch;
	private static long idleTicks = 0;

	private AfkDetector() {
	}

	public static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) {
			initialized = false;
			idleTicks = 0;
			return;
		}
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		float yaw = player.getYRot();
		float pitch = player.getXRot();
		if (!initialized) {
			initialized = true;
			lastX = x;
			lastY = y;
			lastZ = z;
			lastYaw = yaw;
			lastPitch = pitch;
			idleTicks = 0;
			return;
		}
		boolean moved = Math.abs(x - lastX) > POSITION_EPSILON
				|| Math.abs(y - lastY) > POSITION_EPSILON
				|| Math.abs(z - lastZ) > POSITION_EPSILON
				|| Math.abs(yaw - lastYaw) > ANGLE_EPSILON
				|| Math.abs(pitch - lastPitch) > ANGLE_EPSILON;
		lastX = x;
		lastY = y;
		lastZ = z;
		lastYaw = yaw;
		lastPitch = pitch;
		idleTicks = moved ? 0 : idleTicks + 1;
	}

	public static boolean isAfk() {
		return idleTicks >= AFK_THRESHOLD_TICKS;
	}
}
