package com.melloo.skymelloo.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Real line-of-sight check via a block raycast from the player's eyes - used to gate chest and
 * item highlighting so they only ever show when actually visible, never through solid blocks.
 */
public final class VisibilityUtil {
	private VisibilityUtil() {
	}

	/** Whether nothing solid blocks the straight line from the player's eyes to {@code targetPos}. Fails open (returns true) if there's no player/level to raycast against at all. */
	public static boolean hasLineOfSight(Vec3 targetPos) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null) {
			return true;
		}
		Vec3 eyePos = client.player.getEyePosition(1.0F);
		ClipContext context = new ClipContext(eyePos, targetPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player);
		HitResult hit = client.level.clip(context);
		if (hit.getType() == HitResult.Type.MISS) {
			return true;
		}
		// Hit something - only actually visible if that something IS the target itself (or close
		// enough to it), not a different block sitting in between eye and target.
		return hit.getLocation().distanceToSqr(targetPos) < 1.0;
	}
}
