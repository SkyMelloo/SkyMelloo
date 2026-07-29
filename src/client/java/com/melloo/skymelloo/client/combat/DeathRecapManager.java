package com.melloo.skymelloo.client.combat;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.social.DungeonRunTracker;
import com.melloo.skymelloo.client.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Logs a short chronological "what actually hit me" recap for the LOCAL player, dumped in chat the
 * moment they die - real damage-source entities (not a proximity guess), sourced from
 * {@link net.minecraft.network.protocol.game.ClientboundDamageEventPacket} (see
 * {@link com.melloo.skymelloo.client.mixin.DamageEventMixin}), which the server sends for every hit
 * (not just the killing blow) specifically so the client can play the right hurt sound/animation -
 * repurposed here to build a real combat log instead of guessing at the nearest hostile.
 * <p>
 * The damage event packet carries the SOURCE but not the amount - amount is inferred separately by
 * diffing the local player's own health every tick ({@link #tick}) and pairing it with whichever
 * source label was most recently seen, since both land within the same tick in practice.
 */
public final class DeathRecapManager {
	private record RecapEntry(String sourceLabel, float damage) {
	}

	private static final List<RecapEntry> recentDamage = new ArrayList<>();
	private static final int MAX_ENTRIES = 8;
	private static String lastDamageSourceLabel = "Unknown";
	private static float lastHealth = -1;

	private DeathRecapManager() {
	}

	/** Called from {@link com.melloo.skymelloo.client.mixin.DamageEventMixin} for every damage-event packet - only ever cares about the LOCAL player's own. */
	public static void onDamageEvent(Entity damaged, DamageSource source) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || damaged != client.player) {
			return;
		}
		lastDamageSourceLabel = describeSource(source);
	}

	private static String describeSource(DamageSource source) {
		Entity attacker = source.getEntity();
		if (attacker != null) {
			Component name = attacker.getCustomName() != null ? attacker.getCustomName() : attacker.getName();
			return name.getString();
		}
		// No entity - environmental damage (fall, lava, magic, etc.). Mojang's own internal damage-type
		// key is readable enough on its own without a translation table (e.g. "fall", "lava", "inFire").
		return source.getMsgId();
	}

	/** Called every client tick - diffs the local player's health to catch amount+timing, paired with whatever source was last seen via {@link #onDamageEvent}. */
	public static void tick(Minecraft client) {
		if (client.player == null) {
			lastHealth = -1;
			return;
		}
		float current = client.player.getHealth();
		if (lastHealth < 0) {
			lastHealth = current;
			return;
		}
		if (current < lastHealth - 0.01f) {
			recentDamage.add(new RecapEntry(lastDamageSourceLabel, lastHealth - current));
			if (recentDamage.size() > MAX_ENTRIES) {
				recentDamage.remove(0);
			}
		}
		lastHealth = current;
	}

	/** Called from {@link com.melloo.skymelloo.client.mixin.PlayerKillMixin} when the LOCAL player specifically dies. */
	public static void onLocalPlayerDied() {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			recentDamage.clear();
			return;
		}
		if (config.deathRecapPartyAnnounceEnabled && !recentDamage.isEmpty()) {
			String text = config.deathRecapPartyAnnounceTemplate
					.replace("{player}", client.player.getGameProfile().name())
					.replace("{cause}", summarizeCause());
			// leaderOnlyForRelay=false: a death recap is inherently personal (only the player who died
			// generates it), never a duplicated shared fact, so it must NOT be restricted to just the
			// party leader for the "PARTY SM" delivery option - see sendDungeonMessage's own doc comment.
			DungeonRunTracker.sendDungeonMessage(client, text, config.deathRecapPartyAnnounceDelivery, false);
		}
		if (!config.deathRecapEnabled || recentDamage.isEmpty()) {
			recentDamage.clear();
			return;
		}
		client.player.sendSystemMessage(ChatUtil.prefixed("§6=== Death Recap ==="));
		for (RecapEntry entry : recentDamage) {
			client.player.sendSystemMessage(ChatUtil.prefixed("§c-" + String.format("%.1f", entry.damage()) + " HP §7from §f" + entry.sourceLabel()));
		}
		recentDamage.clear();
	}

	/** "Bonzo (18.2), lava (4.0)" - damage grouped by source and summed (the recap can have several entries from the same attacker), biggest contributor first, capped to the top 3 so a long fight doesn't turn into an unreadable chat line. */
	private static String summarizeCause() {
		Map<String, Float> totalBySource = new LinkedHashMap<>();
		for (RecapEntry entry : recentDamage) {
			totalBySource.merge(entry.sourceLabel(), entry.damage(), Float::sum);
		}
		return totalBySource.entrySet().stream()
				.sorted(Map.Entry.<String, Float>comparingByValue().reversed())
				.limit(3)
				.map(e -> e.getKey() + " (" + String.format("%.1f", e.getValue()) + ")")
				.collect(Collectors.joining(", "));
	}
}
