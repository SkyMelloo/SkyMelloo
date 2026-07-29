package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.api.ModAuthManager;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.skymelloo.client.util.DebugLog;
import net.minecraft.client.Minecraft;

/**
 * The admin-managed whitelist gate that used to make the ENTIRE mod inert for anyone not
 * explicitly approved is gone - SkyMelloo works fully for everyone now, whitelisted or not, now that the
 * mod is open source. {@link #isAllowed()} always returns {@code true} - kept only so the ~60
 * existing call sites across the mod (every feature system's own entry point) don't each need an
 * individual edit; none of them actually gate on anything real anymore. {@link #isAdmin()} is a
 * genuinely separate, still-real concept - whether this account is verified-linked to the actual
 * sky.melloo.me owner/admin/developer team (via /skymelloo verify) - used for staff highlighting
 * and admin-only debug/settings visibility, nothing to do with whitelist status.
 */
public final class WhitelistManager {
	private static final int PERIODIC_RECHECK_TICKS = 600; // 30s at 20 ticks/s

	private static volatile boolean checkStarted = false;
	private static volatile boolean admin = false;
	private static int periodicTicks = 0;

	private WhitelistManager() {
	}

	public static boolean isAllowed() {
		return true;
	}

	/** Whether this account is verified-linked to the admin website account (via /skymelloo verify). */
	public static boolean isAdmin() {
		return admin;
	}

	/** Bypasses the once-per-join gate and re-checks right now - used when opening the settings menu, so a fresh admin-link made moments ago (e.g. via /skymelloo verify) shows up without needing to reconnect. */
	public static void forceRecheck(Minecraft client) {
		checkStarted = false;
		checkOnce(client);
	}

	public static void checkOnce(Minecraft client) {
		if (checkStarted || client.player == null) {
			return;
		}
		checkStarted = true;
		performCheck(client, false);
	}

	/** Call every tick - only actually re-checks every {@link #PERIODIC_RECHECK_TICKS}, and announces in chat if admin status changed since the last check. */
	public static void tickPeriodicRecheck(Minecraft client) {
		if (!checkStarted || client.player == null) {
			return;
		}
		periodicTicks++;
		if (periodicTicks < PERIODIC_RECHECK_TICKS) {
			return;
		}
		periodicTicks = 0;
		performCheck(client, true);
	}

	private static void performCheck(Minecraft client, boolean announceChanges) {
		boolean wasAdmin = admin;
		DebugLog.log(DebugLog.Category.PERMISSIONS, "Checking admin-link status...");
		ModAuthManager.getIdentity(client).thenCompose(SkyMellooApiClient::checkIsAdmin).whenComplete((isAdmin, error) -> Minecraft.getInstance().execute(() -> {
			if (error != null) {
				WhitelistStatusHud.setState(WhitelistStatusHud.State.ERROR);
				DebugLog.log(DebugLog.Category.PERMISSIONS, "Admin-link check failed: " + error.getMessage());
				return;
			}
			admin = isAdmin;
			WhitelistStatusHud.setState(WhitelistStatusHud.State.CONNECTED);
			DebugLog.log(DebugLog.Category.PERMISSIONS, "Admin-linked: " + admin);
			if (announceChanges && admin && !wasAdmin && client.player != null) {
				client.player.sendSystemMessage(ChatUtil.prefixed("§bYour account is now linked as admin."));
			}
		}));
	}
}
