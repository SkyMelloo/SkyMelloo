package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.api.ModAuthManager;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.skymelloo.client.util.DebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// isAllowed() always returns true - the mod is open source, no whitelist gate anymore. Kept only
// so existing call sites don't need editing. isAdmin() is separate: real admin-link status.
public final class WhitelistManager {
	private static final int PERIODIC_RECHECK_TICKS = 600; // 30s at 20 ticks/s

	private static volatile boolean checkStarted = false;
	private static volatile boolean admin = false;
	private static volatile String roleLabel = null;
	private static int periodicTicks = 0;

	private WhitelistManager() {
	}

	public static boolean isAllowed() {
		return true;
	}

	public static boolean isAdmin() {
		return admin;
	}

	public static String getRoleLabel() {
		return roleLabel;
	}

	// Real role label if known, else a generic "Admin" fallback; null when not admin at all.
	public static String getAdminBadgeText() {
		if (!admin) {
			return null;
		}
		return roleLabel != null ? roleLabel : "Admin";
	}

	// Bypasses the once-per-join gate, used when opening the settings menu.
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
		// Connection health is MellooEssentials' ConnectionStatusHud's job; this only updates admin status.
		ModAuthManager.getIdentity(client).thenCompose(SkyMellooApiClient::checkIsAdmin).whenComplete((status, error) -> Minecraft.getInstance().execute(() -> {
			if (error != null) {
				DebugLog.log(DebugLog.Category.PERMISSIONS, "Admin-link check failed: " + error.getMessage());
				return;
			}
			admin = status.isAdmin();
			roleLabel = status.roleLabel();
			DebugLog.log(DebugLog.Category.PERMISSIONS, "Admin-linked: " + admin + " (role: " + roleLabel + ")");
			if (announceChanges && admin && !wasAdmin && client.player != null) {
				client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.chat.whitelist.linked_as_admin")));
			}
		}));
	}
}
