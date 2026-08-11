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

/**
 * "Dungeon Info": when someone joins your Dungeon Party Finder group (NOT a regular party
 * invite/join), looks up their SkyBlock stats via sky.melloo.me/api and posts a customizable
 * summary in chat, so you can spot undergeared members before starting - and optionally
 * auto-kicks them if a chosen stat is below a threshold.
 * <p>
 * Verified against a live Hypixel screenshot: "Party Finder &gt; &lt;name&gt; joined the dungeon
 * group! (&lt;Class&gt; Level &lt;n&gt;)" - names can have a leading rank/cosmetic icon glued
 * directly to them with no space, so the username is matched from the end (right before
 * "joined the dungeon group!") rather than anchored to the start of the line.
 * <p>
 * If the joining name is YOUR OWN, it means you just joined an existing party via the finder
 * (rather than someone joining a party you made) - in that case, request a fresh party info
 * packet and check everyone already in it a couple seconds later, once HypixelModAPI has replied.
 */
public final class PartyJoinWatcher {
	private static final Pattern FORMAT_CODE = Pattern.compile("§[0-9A-FK-ORa-fk-or]");
	private static final Pattern JOINED_DUNGEON_GROUP = Pattern.compile(
			"Party Finder\\s*>.*?([A-Za-z0-9_]{1,16}) joined the dungeon group!(?:\\s*\\(([A-Za-z]+) Level (\\d+)\\))?"
	);
	private static final int SELF_JOIN_CHECK_DELAY_TICKS = 40;

	// Verified directly from the real in-game Catacombs floor-selection tooltips, not guessed:
	// Entrance needs Combat Skill 15; Floors I-VII each need Catacombs
	// Skill 1/3/5/9/14/19/24 respectively (index 0 = Floor I's requirement). Master Mode
	// requirements aren't verified/included here.
	private static final int[] CATACOMBS_LEVEL_PER_FLOOR = {1, 3, 5, 9, 14, 19, 24};
	private static final int ENTRANCE_COMBAT_REQUIREMENT = 15;

	private static int tickCounter = 0;
	private static int pendingSelfJoinCheckTick = -1;

	private PartyJoinWatcher() {
	}

	public static void init() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			// dungeonAutoKickEnabled only decides whether the low-stat check below kicks
			// automatically or just posts a warning with a manual [Kick] button, it's no longer a
			// full on/off switch for the check itself.
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
			SkyMellooClient.checkPartyMagicalPowerAuto();
		}
	}

	public static void lookupAndAnnounce(String username) {
		lookupAndAnnounce(username, null, null);
	}

	public static void lookupAndAnnounce(String username, String dungeonClass, String dungeonLevel) {
		ModAuthManager.getIdentity(Minecraft.getInstance()).thenAccept(identity ->
		SkyMellooApiClient.fetchSummary(username, identity).whenComplete((summary, summaryError) ->
				SkyMellooApiClient.fetchMagicalPower(username, identity).whenComplete((mp, mpError) ->
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
							boolean mpAvailable = mpError == null && mp.magicalPower() >= 0;

							if (config.partyJoinStatsEnabled) {
								String rendered = renderTemplate(config.dungeonInfoMessageTemplate, username, summary, mpAvailable ? mp.magicalPower() : -1);
								String finderSuffix = dungeonClass != null ? Component.translatable("skymelloo.chat.party_join.finder_suffix", dungeonClass, dungeonLevel).getString() : "";
								DungeonRunTracker.sendDungeonMessage(client, rendered + finderSuffix, config.dungeonInfoMessageDelivery);
							}

							maybeAutoKick(client, username, summary, mpAvailable ? mp.magicalPower() : -1, config);
							maybeAutoKickMax(client, username, summary, mpAvailable ? mp.magicalPower() : -1, config);
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

	/**
	 * Checks the joining player against the configured stat/threshold - if they're below it and
	 * {@link SkyMellooConfig#dungeonAutoKickEnabled} is on, kicks them automatically like before;
	 * if it's off, posts the same warning but with a manual, clickable [Kick] button instead of
	 * acting on its own, so the check is never fully silent either way.
	 */
	private static void maybeAutoKick(Minecraft client, String username, SkyMellooApiClient.SummaryResult summary, int mp, SkyMellooConfig config) {
		boolean useMp = "MP".equalsIgnoreCase(config.dungeonAutoKickStat);
		int value = useMp ? mp : summary.skyblockLevel();
		if (useMp && mp < 0) {
			// No MP data available for this player - can't safely judge them, so don't act blind.
			return;
		}
		if (value >= config.dungeonAutoKickThreshold) {
			return;
		}
		String statLabel = useMp ? "MP" : "Level";
		// Only the actual party LEADER can /party kick at all on Hypixel - anyone else's attempt just
		// fails server-side. Auto-kicking only happens if leader; the button is only even shown if
		// leader too, rather than offering something that would silently fail when clicked.
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

	/**
	 * Checks {@link #qualifyingFloor} (real level REQUIREMENTS - Catacombs/Combat Skill - not just
	 * "have they ever completed floor N before") against {@link SkyMellooConfig#dungeonFloorKickThreshold}.
	 * Independent toggle/threshold from the MP/Level check above - a party can reasonably want both,
	 * either, or neither.
	 */
	/** Public - also called by {@link com.melloo.skymelloo.client.party.PartyHudManager} once it independently resolves a tracked party member's floor stat, not just on the join-message path. */
	public static void maybeAutoKickForFloor(Minecraft client, String username, SkyMellooApiClient.SummaryResult summary, SkyMellooConfig config) {
		int value = qualifyingFloor(summary.catacombsLevel(), summary.skillLevels().getOrDefault("combat", 0));
		if (!config.dungeonFloorKickEnabled || value >= config.dungeonFloorKickThreshold) {
			return;
		}
		// Only the actual party LEADER can /party kick at all on Hypixel - skip silently rather than
		// send a command that would just fail server-side for anyone else.
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

	/**
	 * The opposite of {@link #maybeAutoKick} - for carry parties, where a joiner whose stat is already
	 * OVER the threshold doesn't need carrying and may be taking a slot from someone who does.
	 * Independent toggle/stat/threshold from the min check, so a party can run either, both, or neither.
	 */
	private static void maybeAutoKickMax(Minecraft client, String username, SkyMellooApiClient.SummaryResult summary, int mp, SkyMellooConfig config) {
		if (!config.dungeonAutoKickMaxEnabled) {
			return;
		}
		boolean useMp = "MP".equalsIgnoreCase(config.dungeonAutoKickMaxStat);
		int value = useMp ? mp : summary.skyblockLevel();
		if (useMp && mp < 0) {
			// No MP data available for this player - can't safely judge them, so don't act blind.
			return;
		}
		if (value <= config.dungeonAutoKickMaxThreshold) {
			return;
		}
		if (!com.melloo.skymelloo.client.party.PartyTracker.isLocalPlayerLeader()) {
			return;
		}
		String statLabel = useMp ? "MP" : "Level";
		com.melloo.mellooessentials.client.party.PartyKickQueue.queueKick(username);
		String text = config.dungeonAutoKickMaxMessageTemplate
				.replace("{player}", username)
				.replace("{stat}", statLabel)
				.replace("{value}", String.valueOf(value))
				.replace("{threshold}", String.valueOf(config.dungeonAutoKickMaxThreshold));
		DungeonRunTracker.sendDungeonMessage(client, text, config.dungeonAutoKickMaxDelivery);
	}

	/**
	 * The opposite of {@link #maybeAutoKickForFloor} - for carry parties, where a member already
	 * level-eligible for a floor higher than the threshold doesn't need to be carried through it.
	 */
	/** Public - also called by {@link com.melloo.skymelloo.client.party.PartyHudManager}, same as {@link #maybeAutoKickForFloor}. */
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

	/**
	 * Checks {@link SkyMellooApiClient.SummaryResult#highestFloor()} (real Hypixel completion record,
	 * NOT the level-requirement "eligible" check above) against
	 * {@link SkyMellooConfig#dungeonFloorCompletionKickThreshold} - not just the min/max Catacombs
	 * level requirement above, but the highest floor actually completed and unlocked.
	 * Independent toggle/threshold from every other check here.
	 */
	/** Public - also called by {@link com.melloo.skymelloo.client.party.PartyHudManager}, same as {@link #maybeAutoKickForFloor}. */
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

	/** The opposite of {@link #maybeAutoKickForFloorCompletion} - for carry parties, where a member who's already completed a floor higher than the threshold doesn't need to take a carry slot. */
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

	/** Verified minimum Catacombs Skill level required for {@code floor} (1-7, clamped) - see {@link #CATACOMBS_LEVEL_PER_FLOOR}. */
	public static int requiredCatacombsLevel(int floor) {
		int index = Math.max(1, Math.min(floor, CATACOMBS_LEVEL_PER_FLOOR.length)) - 1;
		return CATACOMBS_LEVEL_PER_FLOOR[index];
	}

	/**
	 * Highest floor this player is currently ELIGIBLE for right now, purely from their Catacombs/
	 * Combat skill levels ({@link #CATACOMBS_LEVEL_PER_FLOOR}/{@link #ENTRANCE_COMBAT_REQUIREMENT}) -
	 * distinct from {@link SkyMellooApiClient.SummaryResult#highestFloor()} (highest floor they've
	 * ever actually COMPLETED before). A player can be level-eligible for a floor they've personally
	 * never cleared, or - much less commonly - have completed a floor in the past under different
	 * circumstances without currently meeting the requirement (not really possible for level
	 * requirements specifically, since Catacombs levels don't decrease, but kept as two separate
	 * numbers since they answer two different questions: "have they done this" vs. "can they queue
	 * for this at all").
	 *
	 * @return -1 if they don't even meet the Entrance's Combat Skill requirement, 0 if they meet
	 * Entrance but not yet Floor I, otherwise the highest floor (1-7) they qualify for.
	 */
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

	/** Substitutes {username} {mp} {level} {cata} {class} {skillavg} {networth} {rank} {guild} {maxfloor} {qualfloor} {time} {date} in the Dungeon Info message template. */
	private static String renderTemplate(String template, String username, SkyMellooApiClient.SummaryResult summary, int mp) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		ZoneId zone;
		try {
			zone = ZoneId.of(config.dungeonInfoTimezone);
		} catch (Exception e) {
			zone = ZoneId.systemDefault();
		}
		ZonedDateTime now = ZonedDateTime.now(zone);
		String mpText = config.dungeonInfoShowMp && mp >= 0 ? String.valueOf(mp) : "-";
		DungeonReadiness.StatsBreakdown stats = DungeonReadiness.statsBreakdown(summary, mp, config.dungeonTargetFloor);
		DungeonReadiness.ExperienceBreakdown experience = DungeonReadiness.experienceBreakdown(summary);
		int readiness = DungeonReadiness.combinedScore(summary, mp, config.dungeonTargetFloor);
		return template
				.replace("{username}", username)
				.replace("{mp}", mpText)
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
				.replace("{mpscore}", String.valueOf(stats.mp()))
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
				.replace("{mptier}", DungeonReadiness.magicalPowerTierLabel(mp, config.dungeonTargetFloor))
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
