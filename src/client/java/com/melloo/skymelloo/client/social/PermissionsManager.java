package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.api.ModAuthManager;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.skymelloo.client.util.DebugLog;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin-configurable per-feature permission gating is gone entirely - every feature is available
 * to everyone now that the mod is open source, no admin grant needed. {@link #has(String)} always
 * returns {@code true} except for {@code "cosmetics"}, which keeps a genuinely separate, still-real
 * requirement: the account must be linked to a sky.melloo.me website account (Discord login +
 * "/skymelloo verify") so the server knows who's who when broadcasting cosmetics to nearby players
 * - that's a technical necessity for the feature to work at all, not an admin-configurable
 * restriction, so it stays. {@link #isAccountLinked()} still comes from a real server fetch (the
 * endpoint that used to also return per-feature permissions now just reports link status).
 */
public final class PermissionsManager {
	private static final int PERIODIC_RECHECK_TICKS = 600; // 30s at 20 ticks/s

	private static volatile boolean fetchStarted = false;
	private static volatile Map<String, Boolean> permissions = new HashMap<>();
	private static int periodicTicks = 0;
	private static boolean accountLinkedHintShown = false;

	private PermissionsManager() {
	}

	public static boolean has(String key) {
		if ("cosmetics".equals(key)) {
			return isAccountLinked();
		}
		return true;
	}

	public static boolean isAccountLinked() {
		return Boolean.TRUE.equals(permissions.get("accountLinked"));
	}

	/** Bypasses the once-per-join gate and re-fetches right now - used when opening the settings menu, alongside {@link WhitelistManager#forceRecheck}. */
	public static void forceRefetch(Minecraft client) {
		fetchStarted = false;
		fetchIfNeeded(client);
	}

	public static void fetchIfNeeded(Minecraft client) {
		if (fetchStarted || client.player == null || !WhitelistManager.isAllowed()) {
			return;
		}
		fetchStarted = true;
		performFetch(client, false);
	}

	/** Call every tick - only actually re-fetches every {@link #PERIODIC_RECHECK_TICKS}, so a fresh account-link (e.g. just ran /skymelloo verify) is picked up without reconnecting. */
	public static void tickPeriodicRecheck(Minecraft client) {
		if (!fetchStarted || client.player == null || !WhitelistManager.isAllowed()) {
			return;
		}
		periodicTicks++;
		if (periodicTicks < PERIODIC_RECHECK_TICKS) {
			return;
		}
		periodicTicks = 0;
		performFetch(client, true);
	}

	private static void performFetch(Minecraft client, boolean announceChanges) {
		DebugLog.log(DebugLog.Category.PERMISSIONS, "Fetching account-link status...");
		ModAuthManager.getIdentity(client).thenCompose(SkyMellooApiClient::fetchPermissions).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
			if (error == null && result != null) {
				permissions = result;
				DebugLog.log(DebugLog.Category.PERMISSIONS, "Account linked: " + isAccountLinked());
				// Cosmetics silently not showing up with no explanation reads as a bug rather than a
				// missing account link - one clear explanation per session, not repeated on every 30s
				// recheck.
				if (announceChanges && !isAccountLinked() && !accountLinkedHintShown && client.player != null) {
					accountLinkedHintShown = true;
					client.player.sendSystemMessage(ChatUtil.prefixed(
							"§dCosmetics need a linked sky.melloo.me account - log in with Discord at §fsky.melloo.me/account §dand run §f/skymelloo verify <code>§d to use them."));
				}
			} else {
				DebugLog.log(DebugLog.Category.PERMISSIONS, "Account-link fetch failed" + (error != null ? " (" + error.getMessage() + ")" : "") + ".");
			}
		}));
	}
}
