package com.melloo.skymelloo.client.fishing;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.mixin.FishingHookAccessor;
import com.melloo.skymelloo.client.social.PermissionsManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;

/**
 * Watches the local player's own fishing bobber and alerts on a bite.
 * No auto-actions are taken (no auto-reeling/auto-clicking) - it only notifies.
 */
public final class FishingHelper {
	private static FishingHook trackedHook;
	private static boolean wasBiting = false;
	private static int idleTickCounter = 0;
	private static int biteTickCounter = 0;

	private FishingHelper() {
	}

	/** Whether this entity is the bobber currently being watched - used by EspManager to glow it. */
	public static boolean isTracked(Entity entity) {
		return entity == trackedHook;
	}

	/** Whether the rod is currently cast out (used to gate the fishing shooting-gallery minigame). */
	public static boolean isFishing() {
		return trackedHook != null && !trackedHook.isRemoved();
	}

	public static boolean isBiting() {
		return trackedHook != null && !trackedHook.isRemoved() && ((FishingHookAccessor) trackedHook).skymelloo$isBiting();
	}

	public static void tick(Minecraft client) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.fishingHelperEnabled || !PermissionsManager.has("fishingHelper") || client.player == null || client.level == null) {
			trackedHook = null;
			wasBiting = false;
			return;
		}

		if (trackedHook == null || trackedHook.isRemoved()) {
			trackedHook = findOwnHook(client);
			wasBiting = false;
		}
		if (trackedHook == null) {
			return;
		}

		boolean biting = ((FishingHookAccessor) trackedHook).skymelloo$isBiting();
		if (biting) {
			biteTickCounter++;
			if (!wasBiting) {
				showBiteTitle(client);
				if (config.fishingHelperSound) {
					client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.2F));
				}
				spawnBiteBurst(client, trackedHook, true);
				biteTickCounter = 0;
			} else if (biteTickCounter % 4 == 0) {
				// Keep pulsing while the bite is still active, so it feels urgent instead of a single blip.
				spawnBiteBurst(client, trackedHook, false);
				if (config.fishingHelperSound && biteTickCounter % 8 == 0) {
					client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.6F));
				}
			}
			idleTickCounter = 0;
		} else {
			biteTickCounter = 0;
			idleTickCounter++;
			if (idleTickCounter % 25 == 0) {
				spawnIdleRipple(client, trackedHook);
			}
		}
		wasBiting = biting;
	}

	/** Big client-side title (same mechanism vanilla uses for server-sent titles) so a bite is impossible to miss instead of just another chat line. */
	private static void showBiteTitle(Minecraft client) {
		if (client.gui == null) {
			return;
		}
		client.gui.setTimes(2, 20, 8);
		client.gui.setTitle(Component.literal("Bite!").withStyle(s -> s.withColor(0x55CCFF)));
		client.gui.setSubtitle(Component.literal("Reel it in!").withStyle(s -> s.withColor(0xAAAAAA)));
	}

	private static void spawnBiteBurst(Minecraft client, FishingHook hook, boolean big) {
		RandomSource random = hook.getRandom();
		int count = big ? 24 : 8;
		for (int i = 0; i < count; i++) {
			double ox = (random.nextDouble() - 0.5) * (big ? 0.9 : 0.5);
			double oy = random.nextDouble() * (big ? 0.7 : 0.3);
			double oz = (random.nextDouble() - 0.5) * (big ? 0.9 : 0.5);
			client.level.addParticle(ParticleTypes.HAPPY_VILLAGER, hook.getX() + ox, hook.getY() + oy, hook.getZ() + oz, 0, 0.05, 0);
		}
		if (big) {
			for (int i = 0; i < 8; i++) {
				double ox = (random.nextDouble() - 0.5) * 0.6;
				double oz = (random.nextDouble() - 0.5) * 0.6;
				client.level.addParticle(ParticleTypes.SPLASH, hook.getX() + ox, hook.getY(), hook.getZ() + oz, 0, 0.15, 0);
			}
		}
	}

	/** Subtle ambient ripple around the bobber while waiting, so fishing doesn't feel dead until the bite. */
	private static void spawnIdleRipple(Minecraft client, FishingHook hook) {
		RandomSource random = hook.getRandom();
		for (int i = 0; i < 3; i++) {
			double ox = (random.nextDouble() - 0.5) * 0.4;
			double oz = (random.nextDouble() - 0.5) * 0.4;
			client.level.addParticle(ParticleTypes.BUBBLE_POP, hook.getX() + ox, hook.getY(), hook.getZ() + oz, 0, 0.02, 0);
		}
	}

	private static FishingHook findOwnHook(Minecraft client) {
		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity instanceof FishingHook hook && hook.getPlayerOwner() == client.player) {
				return hook;
			}
		}
		return null;
	}
}
