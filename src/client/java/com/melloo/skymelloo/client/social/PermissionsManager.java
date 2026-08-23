package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.api.ModAuthManager;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.util.DebugLog;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;

/**
 * Every feature is available to everyone - {@link #has(String)} always returns {@code true}.
 * {@link #isAccountLinked()} is separate, still required by CloudSyncManager.
 */
public final class PermissionsManager {
	private static final int PERIODIC_RECHECK_TICKS = 600; // 30s at 20 ticks/s

	private static volatile boolean fetchStarted = false;
	private static volatile Map<String, Boolean> permissions = new HashMap<>();
	private static int periodicTicks = 0;

	private PermissionsManager() {
	}

	public static boolean has(String key) {
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
		performFetch(client);
	}

	/** Call every tick - only actually re-fetches every {@link #PERIODIC_RECHECK_TICKS}, so a fresh account-link (e.g. just ran /skymelloo link) is picked up without reconnecting. */
	public static void tickPeriodicRecheck(Minecraft client) {
		if (!fetchStarted || client.player == null || !WhitelistManager.isAllowed()) {
			return;
		}
		periodicTicks++;
		if (periodicTicks < PERIODIC_RECHECK_TICKS) {
			return;
		}
		periodicTicks = 0;
		performFetch(client);
	}

	private static void performFetch(Minecraft client) {
		DebugLog.log(DebugLog.Category.PERMISSIONS, "Fetching account-link status...");
		ModAuthManager.getIdentity(client).thenCompose(SkyMellooApiClient::fetchPermissions).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
			if (error == null && result != null) {
				permissions = result;
				DebugLog.log(DebugLog.Category.PERMISSIONS, "Account linked: " + isAccountLinked());
			} else {
				DebugLog.log(DebugLog.Category.PERMISSIONS, "Account-link fetch failed" + (error != null ? " (" + error.getMessage() + ")" : "") + ".");
			}
		}));
	}
}
