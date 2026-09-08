package com.melloo.skymelloo.client.combat;

import com.melloo.skymelloo.client.highlight.HighlightManager;
import com.melloo.skymelloo.client.social.WhitelistManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// Reacts to players killed for real (flash highlight only); the kill counter/announcement lives
// in MagicMissileManager instead. Only reacts once per victim per lobby/world.
public final class PlayerKillTracker {
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyMelloo/PlayerKillTracker");
	private static final Set<UUID> killedThisSession = new HashSet<>();

	private PlayerKillTracker() {
	}

	public static void resetSession() {
		killedThisSession.clear();
	}

	public static void onPlayerDied(Player victim, Entity killer) {
		if (!WhitelistManager.isAllowed()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || killer != client.player || victim == client.player) {
			return;
		}
		if (HighlightManager.isNpc(victim)) {
			return;
		}

		boolean firstTimeThisSession = killedThisSession.add(victim.getUUID());
		if (firstTimeThisSession) {
			client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F));
		}

		try {
			HighlightManager.flashKillHighlight(victim.getUUID());
		} catch (Exception e) {
			LOGGER.error("Kill-flash cosmetic failed", e);
		}
	}
}
