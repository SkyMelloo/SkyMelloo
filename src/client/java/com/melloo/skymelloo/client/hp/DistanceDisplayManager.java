package com.melloo.skymelloo.client.hp;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;

/** Appends a "(12m)" distance suffix to Highlighting'd entities' nametags. */
public final class DistanceDisplayManager {
	private DistanceDisplayManager() {
	}

	public static Component apply(Entity entity, Component original) {
		if (!SkyMellooConfig.HANDLER.instance().highlightShowDistance) {
			return original;
		}
		var player = Minecraft.getInstance().player;
		if (player == null || player == entity) {
			return original;
		}
		int distance = Math.round(player.distanceTo(entity));
		MutableComponent result = original.copy();
		result.append(Component.literal(" (" + distance + "m)").withStyle(ChatFormatting.GRAY));
		return result;
	}
}
