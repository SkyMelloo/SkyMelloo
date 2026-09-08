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

// Logs a chronological "what actually hit me" recap for the local player, dumped in chat on death.
// Source comes from the damage-event packet; amount is inferred by diffing health each tick.
public final class DeathRecapManager {
	private record RecapEntry(String sourceLabel, float damage) {
	}

	private static final List<RecapEntry> recentDamage = new ArrayList<>();
	private static final int MAX_ENTRIES = 8;
	private static String lastDamageSourceLabel = "Unknown";
	private static float lastHealth = -1;

	private DeathRecapManager() {
	}

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
		// No entity - environmental damage; the raw damage-type key is readable enough on its own.
		return source.getMsgId();
	}

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
			// leaderOnlyForRelay=false: a death recap is personal, not a shared fact restricted to the leader.
			DungeonRunTracker.sendDungeonMessage(client, text, config.deathRecapPartyAnnounceDelivery, false);
		}
		if (!config.deathRecapEnabled || recentDamage.isEmpty()) {
			recentDamage.clear();
			return;
		}
		client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.chat.death_recap.header")));
		for (RecapEntry entry : recentDamage) {
			client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.chat.death_recap.entry",
					String.format("%.1f", entry.damage()), entry.sourceLabel())));
		}
		recentDamage.clear();
	}

	// "Bonzo (18.2), lava (4.0)" - grouped by source and summed, top 3 contributors.
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
