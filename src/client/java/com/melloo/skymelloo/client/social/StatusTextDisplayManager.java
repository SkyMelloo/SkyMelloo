package com.melloo.skymelloo.client.social;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;

/**
 * Prefixes a nearby SkyMelloo user's custom status text onto their nametag, from
 * {@link ModPresenceManager}. Deliberately NOT using {@code EntityRenderState.scoreText} (the
 * vanilla "below name" scoreboard line, which renders as a genuinely separate line above the
 * name tag) - Hypixel already drives that field with its own server-side scoreboard objective, and
 * overwriting it here would either get silently clobbered by the server or break Hypixel's own
 * display. A same-line prefix is the safe option that can never collide with server-driven state.
 */
public final class StatusTextDisplayManager {
	private StatusTextDisplayManager() {
	}

	public static Component apply(Player player, Component original) {
		String status = ModPresenceManager.getStatusText(player.getUUID());
		if (status.isBlank()) {
			return original;
		}
		MutableComponent result = Component.literal("[" + status + "] ").withStyle(ChatFormatting.AQUA)
				.append(original.copy());
		return result;
	}
}
