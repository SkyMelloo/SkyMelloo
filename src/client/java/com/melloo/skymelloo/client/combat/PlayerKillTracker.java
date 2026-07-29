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

/**
 * Reacts to players you've personally killed for real (flash highlight) - the actual "kill"
 * counter/announcement lives in {@link com.melloo.skymelloo.client.cosmetics.MagicMissileManager}
 * instead, since that's the feature this permission is really about. Only reacts once per victim
 * per lobby/world (cleared on reconnect).
 */
public final class PlayerKillTracker {
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyMelloo/PlayerKillTracker");
	private static final Set<UUID> killedThisSession = new HashSet<>();

	private PlayerKillTracker() {
	}

	public static void resetSession() {
		killedThisSession.clear();
	}

	/** Called from {@link com.melloo.skymelloo.client.mixin.PlayerKillMixin} whenever any player entity dies. */
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

		// Counting/announcing "kills" now lives in MagicMissileManager (missile hits, since that's
		// the closest thing SkyMelloo has to a real kill - no real combat damage/packets happen here).
		// This real-death path still triggers the flash highlight + death double reactions below.
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
