package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.SkyMellooClient;
import com.melloo.skymelloo.client.api.ModAuthManager;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.party.PartyTracker;
import com.melloo.skymelloo.client.util.ChatUtil;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// "Dungeon Info": posts a stat summary (and optionally auto-kicks) when someone joins your Party Finder group.
public final class PartyJoinWatcher {
	private static final Pattern FORMAT_CODE = Pattern.compile("§[0-9A-FK-ORa-fk-or]");
	private static final Pattern JOINED_DUNGEON_GROUP = Pattern.compile(
			"Party Finder\\s*>.*?([A-Za-z0-9_]{1,16}) joined the dungeon group!(?:\\s*\\(([A-Za-z]+) Level (\\d+)\\))?"
	);
	private static final int SELF_JOIN_CHECK_DELAY_TICKS = 40;

	// Catacombs Skill required per floor (index 0 = Floor I); Entrance needs Combat Skill instead, see below.
	private static final int[] CATACOMBS_LEVEL_PER_FLOOR = {1, 3, 5, 9, 14, 19, 24};
	private static final int ENTRANCE_COMBAT_REQUIREMENT = 15;

	private static int tickCounter = 0;
	private static int pendingSelfJoinCheckTick = -1;

	private PartyJoinWatcher() {
	}

	public static void init() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			// dungeonAutoKickEnabled only picks auto-kick vs. a manual [Kick] warning - it doesn't disable the check itself.
			String stripped = FORMAT_CODE.matcher(message.getString()).replaceAll("");
			for (String line : stripped.split("\n")) {
				String trimmed = line.trim();
				Matcher matcher = JOINED_DUNGEON_GROUP.matcher(trimmed);
				if (matcher.find()) {
					String joinedName = matcher.group(1);
					Minecraft client = Minecraft.getInstance();
					if (client.player != null && joinedName.equalsIgnoreCase(client.player.getGameProfile().name())) {
						// That's you joining someone else's already-existing party, not someone
						// joining yours - request a refresh and check the existing members shortly.
						PartyTracker.requestRefreshNow();
						pendingSelfJoinCheckTick = tickCounter + SELF_JOIN_CHECK_DELAY_TICKS;
						continue;
					}
					String dungeonClass = matcher.group(2);
					String dungeonLevel = matcher.group(3);
					lookupAndAnnounce(joinedName, dungeonClass, dungeonLevel);
				}
			}
		});
	}

	public static void tick(Minecraft client) {
		tickCounter++;
		if (pendingSelfJoinCheckTick >= 0 && tickCounter >= pendingSelfJoinCheckTick) {
			pendingSelfJoinCheckTick = -1;
			SkyMellooClient.checkPartyAccessoryPowerAuto();
		}
	}

	public static void lookupAndAnnounce(String username) {
		lookupAndAnnounce(username, null, null);
	}

	public static void lookupAndAnnounce(String username, String dungeonClass, String dungeonLevel) {
		ModAuthManager.getIdentity(Minecraft.getInstance()).thenAccept(identity ->
		SkyMellooApiClient.fetchSummary(username, identity).whenComplete((summary, summaryError) ->
				SkyMellooApiClient.fetchAccessoryPower(username, identity).whenComplete((ap, apError) ->
						Minecraft.getInstance().execute(() -> {
							Minecraft client = Minecraft.getInstance();
							if (client.player == null) {
								return;
							}
							if (summaryError != null) {
								client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.chat.party_join.stats_load_failed", username, summaryError.getMessage())));
								return;
							}
							SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
							boolean apAvailable = apError == null && ap.accessoryPower() >= 0;

							if (config.partyJoinStatsEnabled) {
								String rendered = renderTemplate(config.dungeonInfoMessageTemplate, username, summary, apAvailable ? ap.accessoryPower() : -1);
								String finderSuffix = dungeonClass != null ? Component.translatable("skymelloo.chat.party_join.finder_suffix", dungeonClass, dungeonLevel).getString() : "";
								DungeonRunTracker.sendDungeonMessage(client, rendered + finderSuffix, config.dungeonInfoMessageDelivery);
							}

							maybeAutoKick(client, username, summary, apAvailable ? ap.accessoryPower() : -1, config);
							maybeAutoKickMax(client, username, summary, apAvailable ? ap.accessoryPower() : -1, config);
							maybeAutoKickForFloor(client, username, summary, config);
							maybeAutoKickForFloorMax(client, username, summary, config);
							maybeAutoKickForFloorCompletion(client, username, summary, config);
							maybeAutoKickForFloorCompletionMax(client, username, summary, config);
						})
				)
		)).exceptionally(error -> {
			Minecraft.getInstance().execute(() -> {
				Minecraft client = Minecraft.getInstance();
				if (client.player != null) {
					client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.chat.party_join.stats_load_failed", username, ChatUtil.friendlyError(error))));
				}
			});
			return null;
		});
	}

	// If below threshold, auto-kicks when dungeonAutoKickEnabled, else posts a manual [Kick] warning.
	private static void maybeAutoKick(Minecraft client, String username, SkyMellooApiClient.SummaryResult summary, int ap, SkyMellooConfig config) {
		// Anything other than "LEVEL" means AP - covers both the current "AP" value and "MP" left
		// over from configs saved before the Hypixel stat rename.
		boolean useAp = !"LEVEL".equalsIgnoreCase(config.dungeonAutoKickStat);
		int value = useAp ? ap : summary.skyblockLevel();
		if (useAp && ap < 0) {
			// No AP data available for this player - can't safely judge them, so don't act blind.
			return;
		}
		if (value >= config.dungeonAutoKickThreshold) {
			return;
		}
		String statLabel = useAp ? "AP" : "Level";
		// Only the party leader can /party kick - the button is hidden for anyone else so it can't silently fail.
		boolean isLeader = com.melloo.skymelloo.client.party.PartyTracker.isLocalPlayerLeader();
		if (config.dungeonAutoKickEnabled && isLeader) {
			com.melloo.mellooessentials.client.party.PartyKickQueue.queueKick(username);
			String text = config.dungeonAutoKickMessageTemplate
					.replace("{player}", username)
					.replace("{stat}", statLabel)
					.replace("{value}", String.valueOf(value))
					.replace("{threshold}", String.valueOf(config.dungeonAutoKickThreshold));
			DungeonRunTracker.sendDungeonMessage(client, text, config.dungeonAutoKickDelivery);
			return;
		}
		MutableComponent warning = Component.translatable("skymelloo.chat.party_join.low_stat_warning",
				statLabel, username, value, config.dungeonAutoKickThreshold);
		if (isLeader) {
			MutableComponent kickButton = Component.translatable("skymelloo.chat.party_join.kick_button").withStyle(style -> style
					.withColor(ChatFormatting.RED)
					.withBold(true)
					.withClickEvent(new ClickEvent.RunCommand("/party kick " + username))
					.withHoverEvent(new HoverEvent.ShowText(Component.translatable("skymelloo.chat.party_join.kick_button_hover", username))));
			warning = warning.append(kickButton);
		}
		client.player.sendSystemMessage(ChatUtil.prefixed(warning));
	}

	// Checks qualifyingFloor against the threshold; also called directly by PartyHudManager.
	public static void maybeAutoKickForFloor(Minecraft client, String username, SkyMellooApiClient.SummaryResult summary, SkyMellooConfig config) {
		int value = qualifyingFloor(summary.catacombsLevel(), summary.skillLevels().getOrDefault("combat", 0));
		if (!config.dungeonFloorKickEnabled || value >= config.dungeonFloorKickThreshold) {
			return;
		}
		if (!com.melloo.skymelloo.client.party.PartyTracker.isLocalPlayerLeader()) {
			return;
		}
		com.melloo.mellooessentials.client.party.PartyKickQueue.queueKick(username);
		String text = config.dungeonFloorKickMessageTemplate
				.replace("{player}", username)
				.replace("{value}", String.valueOf(value))
				.replace("{threshold}", String.valueOf(config.dungeonFloorKickThreshold));
		DungeonRunTracker.sendDungeonMessage(client, text, config.dungeonFloorKickDelivery);
	}

	// Opposite of maybeAutoKick - for carry parties, kicks a joiner already OVER the threshold.
	private static void maybeAutoKickMax(Minecraft client, String username, SkyMellooApiClient.SummaryResult summary, int ap, SkyMellooConfig config) {
		if (!config.dungeonAutoKickMaxEnabled) {
			return;
		}
		// Anything other than "LEVEL" means AP - covers both the current "AP" value and "MP" left
		// over from configs saved before the Hypixel stat rename.
		boolean useAp = !"LEVEL".equalsIgnoreCase(config.dungeonAutoKickMaxStat);
		int value = useAp ? ap : summary.skyblockLevel();
		if (useAp && ap < 0) {
			// No AP data available for this player - can't safely judge them, so don't act blind.
			return;
		}
		if (value <= config.dungeonAutoKickMaxThreshold) {
			return;
		}
		if (!com.melloo.skymelloo.client.party.PartyTracker.isLocalPlayerLeader()) {
			return;
		}
		String statLabel = useAp ? "AP" : "Level";
		com.melloo.mellooessentials.client.party.PartyKickQueue.queueKick(username);
		String text = config.dungeonAutoKickMaxMessageTemplate
				.replace("{player}", username)
				.replace("{stat}", statLabel)
				.replace("{value}", String.valueOf(value))
				.replace("{threshold}", String.valueOf(config.dungeonAutoKickMaxThreshold));
		DungeonRunTracker.sendDungeonMessage(client, text, config.dungeonAutoKickMaxDelivery);
	}

	// Opposite of maybeAutoKickForFloor - kicks a member already eligible past the threshold; also called by PartyHudManager.
	public static void maybeAutoKickForFloorMax(Minecraft client, String username, SkyMellooApiClient.SummaryResult summary, SkyMellooConfig config) {
		int value = qualifyingFloor(summary.catacombsLevel(), summary.skillLevels().getOrDefault("combat", 0));
		if (!config.dungeonFloorKickMaxEnabled || value <= config.dungeonFloorKickMaxThreshold) {
			return;
		}
		if (!com.melloo.skymelloo.client.party.PartyTracker.isLocalPlayerLeader()) {
			return;
		}
		com.melloo.mellooessentials.client.party.PartyKickQueue.queueKick(username);
		String text = config.dungeonFloorKickMaxMessageTemplate
				.replace("{player}", username)
				.replace("{value}", String.valueOf(value))
				.replace("{threshold}", String.valueOf(config.dungeonFloorKickMaxThreshold));
		DungeonRunTracker.sendDungeonMessage(client, text, config.dungeonFloorKickMaxDelivery);
	}

	// Checks actual Hypixel floor-completion record, not the level-requirement check above; also called by PartyHudManager.
	public static void maybeAutoKickForFloorCompletion(Minecraft client, String username, SkyMellooApiClient.SummaryResult summary, SkyMellooConfig config) {
		int value = summary.highestFloor();
		if (!config.dungeonFloorCompletionKickEnabled || value >= config.dungeonFloorCompletionKickThreshold) {
			return;
		}
		if (!com.melloo.skymelloo.client.party.PartyTracker.isLocalPlayerLeader()) {
			return;
		}
		com.melloo.mellooessentials.client.party.PartyKickQueue.queueKick(username);
		String text = config.dungeonFloorCompletionKickMessageTemplate
				.replace("{player}", username)
				.replace("{value}", String.valueOf(value))
				.replace("{threshold}", String.valueOf(config.dungeonFloorCompletionKickThreshold));
		DungeonRunTracker.sendDungeonMessage(client, text, config.dungeonFloorCompletionKickDelivery);
	}

	// Opposite of maybeAutoKickForFloorCompletion - kicks a member already past the completion threshold.
	public static void maybeAutoKickForFloorCompletionMax(Minecraft client, String username, SkyMellooApiClient.SummaryResult summary, SkyMellooConfig config) {
		int value = summary.highestFloor();
		if (!config.dungeonFloorCompletionKickMaxEnabled || value <= config.dungeonFloorCompletionKickMaxThreshold) {
			return;
		}
		if (!com.melloo.skymelloo.client.party.PartyTracker.isLocalPlayerLeader()) {
			return;
		}
		com.melloo.mellooessentials.client.party.PartyKickQueue.queueKick(username);
		String text = config.dungeonFloorCompletionKickMaxMessageTemplate
				.replace("{player}", username)
				.replace("{value}", String.valueOf(value))
				.replace("{threshold}", String.valueOf(config.dungeonFloorCompletionKickMaxThreshold));
		DungeonRunTracker.sendDungeonMessage(client, text, config.dungeonFloorCompletionKickMaxDelivery);
	}

	// Minimum Catacombs Skill level required for floor (1-7, clamped).
	public static int requiredCatacombsLevel(int floor) {
		int index = Math.max(1, Math.min(floor, CATACOMBS_LEVEL_PER_FLOOR.length)) - 1;
		return CATACOMBS_LEVEL_PER_FLOOR[index];
	}

	// Highest floor eligible by level requirement, not actual completion history. -1 if below the
	// Entrance requirement, 0 if Entrance-only, else the highest floor (1-7) qualified for.
	public static int qualifyingFloor(int catacombsLevel, int combatLevel) {
		if (combatLevel < ENTRANCE_COMBAT_REQUIREMENT) {
			return -1;
		}
		int floor = 0;
		for (int i = 0; i < CATACOMBS_LEVEL_PER_FLOOR.length; i++) {
			if (catacombsLevel < CATACOMBS_LEVEL_PER_FLOOR[i]) {
				break;
			}
			floor = i + 1;
		}
		return floor;
	}

	// Substitutes template placeholders like {username}/{ap}/{level}/{cata}/{class} (plus legacy {mp}/{mptier}/{mpscore}).
	private static String renderTemplate(String template, String username, SkyMellooApiClient.SummaryResult summary, int ap) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		ZoneId zone;
		try {
			zone = ZoneId.of(config.dungeonInfoTimezone);
		} catch (Exception e) {
			zone = ZoneId.systemDefault();
		}
		ZonedDateTime now = ZonedDateTime.now(zone);
		String apText = config.dungeonInfoShowMp && ap >= 0 ? String.valueOf(ap) : "-";
		DungeonReadiness.StatsBreakdown stats = DungeonReadiness.statsBreakdown(summary, ap, config.dungeonTargetFloor);
		DungeonReadiness.ExperienceBreakdown experience = DungeonReadiness.experienceBreakdown(summary);
		int readiness = DungeonReadiness.combinedScore(summary, ap, config.dungeonTargetFloor);
		return template
				.replace("{username}", username)
				.replace("{ap}", apText)
				.replace("{mp}", apText)
				.replace("{level}", String.valueOf(summary.skyblockLevel()))
				.replace("{cata}", String.valueOf(summary.catacombsLevel()))
				.replace("{class}", summary.selectedClass() != null ? summary.selectedClass() : "-")
				.replace("{skillavg}", String.format("%.1f", summary.averageSkillLevel()))
				.replace("{networth}", formatAmount(summary.netWorth()))
				.replace("{rank}", summary.rankLabel() != null ? summary.rankLabel() : "-")
				.replace("{guild}", summary.guildName() != null ? summary.guildName() : "-")
				.replace("{maxfloor}", String.valueOf(summary.highestFloor()))
				.replace("{qualfloor}", String.valueOf(qualifyingFloor(summary.catacombsLevel(), summary.skillLevels().getOrDefault("combat", 0))))
				.replace("{statscore}", String.valueOf(stats.total()))
				.replace("{expscore}", String.valueOf(experience.total()))
				.replace("{readiness}", String.valueOf(readiness))
				.replace("{skillsscore}", String.valueOf(stats.skills().total()))
				.replace("{apscore}", String.valueOf(stats.ap()))
				.replace("{mpscore}", String.valueOf(stats.ap()))
				.replace("{farmingpoints}", String.valueOf(stats.skills().farming()))
				.replace("{miningpoints}", String.valueOf(stats.skills().mining()))
				.replace("{combatpoints}", String.valueOf(stats.skills().combat()))
				.replace("{foragingpoints}", String.valueOf(stats.skills().foraging()))
				.replace("{fishingpoints}", String.valueOf(stats.skills().fishing()))
				.replace("{enchantingpoints}", String.valueOf(stats.skills().enchanting()))
				.replace("{alchemypoints}", String.valueOf(stats.skills().alchemy()))
				.replace("{tamingpoints}", String.valueOf(stats.skills().taming()))
				.replace("{classpoints}", String.valueOf(stats.skills().classLevel()))
				.replace("{sblevelpoints}", String.valueOf(stats.skills().skyblockLevel()))
				.replace("{floorpoints}", String.valueOf(experience.highestFloor()))
				.replace("{completionspoints}", String.valueOf(experience.completions()))
				.replace("{aptier}", DungeonReadiness.accessoryPowerTierLabel(ap, config.dungeonTargetFloor))
				.replace("{mptier}", DungeonReadiness.accessoryPowerTierLabel(ap, config.dungeonTargetFloor))
				.replace("{catatier}", DungeonReadiness.catacombsLevelTierLabel(summary.catacombsLevel(), config.dungeonTargetFloor))
				.replace("{time}", now.format(DateTimeFormatter.ofPattern("HH:mm")))
				.replace("{date}", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
	}

	private static String formatAmount(double amount) {
		if (amount >= 1_000_000_000) {
			return String.format("%.2fB", amount / 1_000_000_000);
		}
		if (amount >= 1_000_000) {
			return String.format("%.2fM", amount / 1_000_000);
		}
		if (amount >= 1_000) {
			return String.format("%.1fK", amount / 1_000);
		}
		return String.format("%.0f", amount);
	}
}
