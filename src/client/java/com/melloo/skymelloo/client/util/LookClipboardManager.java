package com.melloo.skymelloo.client.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

// Ctrl+X saves the current look direction, Ctrl+F turns the player to face it again via a smoothed
// lerp (not an instant snap, to avoid looking like an aim-bot on Hypixel). One slot, never persisted.
public final class LookClipboardManager {
	private static final float TURN_SPEED = 0.35f; // fraction of remaining angle closed per tick
	private static final float SNAP_THRESHOLD = 0.5f; // degrees - close enough to just finish exactly

	private static Float savedYaw;
	private static Float savedPitch;

	private static boolean turning;
	private static float targetYaw;
	private static float targetPitch;

	private LookClipboardManager() {
	}

	public static void save(LocalPlayer player) {
		savedYaw = player.getYRot();
		savedPitch = player.getXRot();
		turning = false; // a fresh save cancels any turn already in progress toward the old target
	}

	// Returns false if nothing has been saved yet.
	public static boolean startRestore(LocalPlayer player) {
		if (savedYaw == null || savedPitch == null) {
			return false;
		}
		targetYaw = savedYaw;
		targetPitch = savedPitch;
		turning = true;
		return true;
	}

	public static void tick(LocalPlayer player) {
		if (!turning) {
			return;
		}
		float yawDelta = Mth.wrapDegrees(targetYaw - player.getYRot());
		float pitchDelta = targetPitch - player.getXRot();
		if (Math.abs(yawDelta) < SNAP_THRESHOLD && Math.abs(pitchDelta) < SNAP_THRESHOLD) {
			player.setYRot(targetYaw);
			player.setXRot(targetPitch);
			turning = false;
			return;
		}
		player.setYRot(player.getYRot() + yawDelta * TURN_SPEED);
		player.setXRot(Mth.clamp(player.getXRot() + pitchDelta * TURN_SPEED, -90f, 90f));
	}
}
