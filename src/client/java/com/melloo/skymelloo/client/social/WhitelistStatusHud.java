package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.util.ChatUtil;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/**
 * Persistent top-left HUD showing whether the mod has checked in with sky.melloo.me, and the
 * sky.melloo.me ping (not the Minecraft server ping - that's not this mod's concern, and the
 * vanilla tab list/F3 already show it) - always visible (not just briefly on connect) so an
 * offline state is never something you have to notice a one-off title for. No longer reflects
 * whitelist status (whitelist gating removed entirely) - just connection health and,
 * if applicable, an Admin badge. Position is configurable via the HUD layout editor (default J).
 */
public final class WhitelistStatusHud implements HudElement {
	public enum State {
		CONNECTING, CONNECTED, ERROR
	}

	public static final WhitelistStatusHud INSTANCE = new WhitelistStatusHud();

	private static volatile State state = State.CONNECTING;

	private WhitelistStatusHud() {
	}

	public static void setState(State newState) {
		boolean changed = newState != state;
		state = newState;
		if (!changed) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		if (newState == State.ERROR) {
			// A clickable link, not just a plain HUD line - the persistent HUD text can't be clicked,
			// and "connection failed" alone gives no indication of whether this is a known outage
			// (check the status page) or something local (firewall, DNS, the game itself). Fires once
			// per transition INTO error, not every failed retry.
			Component link = Component.literal("sky.melloo.me/status").setStyle(Style.EMPTY
					.withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create("https://sky.melloo.me/status")))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to open")))
					.withUnderlined(true));
			client.player.sendSystemMessage(ChatUtil.prefixed(Component.literal(
					"§cCouldn't reach sky.melloo.me - our authentication servers may be down. Check ").append(link)));
		}
	}

	private static int pingColor(int pingMs) {
		if (pingMs < 0) {
			return 0xFF888888;
		}
		if (pingMs <= 100) {
			return 0xFF55FF55;
		}
		if (pingMs <= 250) {
			return 0xFFFFAA00;
		}
		return 0xFFFF5555;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, DeltaTracker deltaTracker) {
		String statusText;
		int statusColor;
		switch (state) {
			case CONNECTING -> {
				statusText = "Connecting to sky.melloo.me...";
				statusColor = 0xFFFFCC00;
			}
			case CONNECTED -> {
				statusText = WhitelistManager.isAdmin() ? "Connected to sky.melloo.me (Admin)" : "Connected to sky.melloo.me";
				statusColor = 0xFF55FF55;
			}
			default -> {
				statusText = "Connection failed - see sky.melloo.me/status";
				statusColor = 0xFFFF8800;
			}
		}

		Minecraft client = Minecraft.getInstance();
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		int x = config.hudStatusX;
		int y = config.hudStatusY;

		int smPing = SkyMellooPingMonitor.getLastPingMs();
		String pingLine = "sky.melloo.me " + (smPing >= 0 ? smPing + "ms" : "--");

		int lineWidth = Math.max(client.font.width(statusText), client.font.width(pingLine)) + 18;
		gg.fill(x - 4, y - 3, x + lineWidth, y + 23, 0x99101018);

		gg.fill(x, y + 1, x + 6, y + 7, statusColor);
		gg.text(client.font, statusText, x + 10, y, statusColor);

		int pingY = y + 12;
		gg.fill(x, pingY + 1, x + 6, pingY + 7, pingColor(smPing));
		gg.text(client.font, pingLine, x + 10, pingY, pingColor(smPing));
	}
}
