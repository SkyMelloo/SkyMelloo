package com.melloo.skymelloo.client.social;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;

// Prefixes a nearby user's custom status text onto their nametag. Deliberately not using the
// vanilla scoreText line, since Hypixel already drives that with its own scoreboard objective.
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
