package com.melloo.skymelloo.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// Real line-of-sight check via a block raycast from the player's eyes, gating chest/item
// highlighting so they never show through solid blocks. Fails open if there's no player/level.
public final class VisibilityUtil {
	private VisibilityUtil() {
	}

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
		// Only visible if what was hit is close enough to be the target itself, not a blocking block.
		return hit.getLocation().distanceToSqr(targetPos) < 1.0;
	}
}
