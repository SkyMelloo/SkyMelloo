package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.util.ChatUtil;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

/**
 * Confirms in chat when {@link com.melloo.skymelloo.client.mixin.ResourcePackAutoDeclineMixin}
 * actually declined Hypixel's resource pack - delayed a few seconds so it lands after the initial
 * post-join chat spam instead of getting buried underneath it and going unnoticed.
 * <p>
 * Only ever announced ONCE per game launch, deliberately not reset on every join: the mixin itself
 * correctly re-declines on every actual reconnect (each one is its own connection), but Hypixel's
 * own internal server-hops (dungeon floor entry, "/server X") also fire the same join event this
 * would otherwise reset on - re-announcing "resource pack declined" on every single floor
 * transition would just be spam for something that hasn't changed.
 */
public final class ResourcePackStatus {
	// ~8s - comfortably after the usual post-join chat spam settles down.
	private static final int ANNOUNCE_DELAY_TICKS = 160;

	private static volatile boolean declinedThisConnection = false;
	private static boolean alreadyAnnouncedThisLaunch = false;
	private static int announceDelayTicks = -1;

	private ResourcePackStatus() {
	}

	public static void init() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (!alreadyAnnouncedThisLaunch) {
				announceDelayTicks = ANNOUNCE_DELAY_TICKS;
			}
		});
	}

	/** Called by the mixin the moment it actually flips a Hypixel connection's resource pack status to disabled. */
	public static void markDeclined() {
		declinedThisConnection = true;
	}

	public static void tick(Minecraft client) {
		if (announceDelayTicks < 0) {
			return;
		}
		announceDelayTicks--;
		if (announceDelayTicks == 0) {
			if (declinedThisConnection && client.player != null) {
				client.player.sendSystemMessage(ChatUtil.prefixed("§7Hypixel resource pack auto-declined for this connection."));
			}
			alreadyAnnouncedThisLaunch = true;
		}
	}
}
