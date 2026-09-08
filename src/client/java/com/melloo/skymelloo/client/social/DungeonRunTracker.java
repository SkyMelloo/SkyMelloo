package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.party.PartyTracker;
import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.skymelloo.client.util.DebugLog;
import com.melloo.skymelloo.client.util.TickDelay;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Tracks a dungeon run's key events for an end-of-run report and a live score estimate.
// Boss room entry is position-based since Hypixel has no reliable attributed chat event for it.
public final class DungeonRunTracker {
	private static final Pattern FORMAT_CODE = Pattern.compile("§[0-9A-FK-ORa-fk-or]");
	private static final Pattern DEATH_PATTERN = Pattern.compile("^\\s*☠\\s+(\\S+)\\s+.+became a ghost\\.$");
	// Generic prefix-only match, no hardcoded continuation phrase - covers every puzzle type.
	// No leading ^ - a chat-icon mod can prepend text (e.g. "[Name head]") before the name.
	private static final Pattern PUZZLE_SOLVED_PATTERN = Pattern.compile("PUZZLE SOLVED! .*?([A-Za-z0-9_]{2,16}) ");
	private static final Pattern PUZZLE_FAIL_PATTERN = Pattern.compile("PUZZLE FAIL! .*?([A-Za-z0-9_]{2,16}) ");
	private static final Pattern CRYPT_PATTERN = Pattern.compile("found a Wither Essence! Everyone gains an extra essence!");
	// No leading ^ - same chat-icon-prefix reason as above.
	private static final Pattern DOOR_OPENED_PATTERN = Pattern.compile("([A-Za-z0-9_]{1,16}) opened a (WITHER|BLOOD) door!$");
	// Unlike the Wither Door, the Blood Door's message is a plain nameless broadcast.
	private static final Pattern BLOOD_DOOR_OPENED_PATTERN = Pattern.compile("The BLOOD DOOR has been opened!$");
	// Two forms: with name for yourself, "A Wither Key was picked up!" (no name) for a teammate.
	// A floor can have several Wither Doors, each its own key-then-open cycle - see witherDoorKeys.
	private static final Pattern WITHER_KEY_OBTAINED_PATTERN = Pattern.compile("has obtained Wither Key!$");
	private static final Pattern WITHER_KEY_PICKED_GENERIC_PATTERN = Pattern.compile("A Wither Key was picked up!$");
	// Captures the opener's name (".*?" skips the rank tag) for the end-of-run report.
	private static final Pattern BLOOD_KEY_OBTAINED_PATTERN = Pattern.compile("^.*?([A-Za-z0-9_]{2,16}) has obtained Blood Key!$");
	private static final Pattern READY_PATTERN = Pattern.compile("([A-Za-z0-9_]{1,16}) is now ready!$");
	// Excludes The Watcher (Blood Room miniboss) - it sends repeated "[BOSS] The Watcher: ..." lines.
	private static final Pattern BOSS_CHAT_PATTERN = Pattern.compile("\\[BOSS] (?!The Watcher:)([^:]+):");
	// The Watcher's fight-start line - the real boss-room portal only spawns once this fight ends,
	// so this just arms the portal scan below rather than claiming the portal exists yet.
	private static final Pattern WATCHER_PATTERN = Pattern.compile("\\[BOSS] The Watcher: ");
	// The Watcher's win line - marks rooms/secrets as no longer improvable for the score formula.
	private static final Pattern WATCHER_CLEARED_PATTERN = Pattern.compile("\\[BOSS] The Watcher: You have proven yourself\\. You may pass\\.$");
	// Community mods (e.g. Skytils) broadcast one of these on a Mimic/Prince kill; Hypixel doesn't.
	private static final Pattern MIMIC_PATTERN = Pattern.compile(".*?(?:Mimic dead!?|Mimic Killed!|\\$SKYTILS-DUNGEON-SCORE-MIMIC\\$)$");
	private static final Pattern PRINCE_PATTERN = Pattern.compile(".*?(?:Prince dead!?|Prince Killed!)$");
	private static final String PRINCE_KILL_MESSAGE = "A Prince falls. +1 Bonus Score";
	private static final Pattern MIMIC_FLOORS_PATTERN = Pattern.compile("[FM][67]");
	private static final Pattern DUNGEON_COMPLETE_PATTERN = Pattern.compile("\\s*(?:Master Mode )?The Catacombs - (?:Floor [IVX]{1,4}|Entrance)\\s*");
	// Tail end of Hypixel's completion sequence, ~2s after the floor header line above.
	private static final Pattern REQUEUE_PATTERN = Pattern.compile("Click HERE to re-queue into .+!");
	private static final String DUNGEON_START_MESSAGE = "Starting in 1 second.";
	// Matches any server transfer, not just leaving a dungeon.
	private static final Pattern SENDING_TO_SERVER_PATTERN = Pattern.compile("^Sending to server ");

	private static final Pattern FLOOR_PATTERN = Pattern.compile("The Catacombs \\(([A-Za-z0-9]+)\\)");
	private static final Pattern CLEARED_PATTERN = Pattern.compile("Cleared: ([\\d.]+)%");
	private static final Pattern TIME_ELAPSED_PATTERN = Pattern.compile("Time Elapsed: ((?:\\d+[dhms] ?)+)");
	private static final Pattern TIME_TOKEN = Pattern.compile("(\\d+)([dhms])");

	// Tab-list patterns for dungeon stats, ported from DungeonScore.java.
	private static final Pattern COMPLETED_ROOMS_PATTERN = Pattern.compile(" *Completed Rooms: (?<rooms>\\d+)");
	private static final Pattern SECRETS_PATTERN = Pattern.compile("Secrets Found: (?<secper>\\d+\\.?\\d*)%");
	private static final Pattern PUZZLE_COUNT_PATTERN = Pattern.compile("Puzzles: \\((?<count>\\d+)\\)");
	private static final Pattern PUZZLE_STATE_PATTERN = Pattern.compile(".+?(?=:): \\[(?<state>.)](?: \\(\\w*\\))?");
	private static final Pattern CRYPTS_PATTERN = Pattern.compile("Crypts: (?<crypts>\\d+)");

	/** Per-floor Explore secrets-% target and Speed time cap (seconds) - ported verbatim from DungeonScore.java's FloorRequirement enum. */
	private enum FloorRequirement {
		E(30, 1200), F1(30, 600), F2(40, 600), F3(50, 600), F4(60, 720), F5(70, 600), F6(85, 720), F7(100, 840),
		M1(100, 480), M2(100, 480), M3(100, 480), M4(100, 480), M5(100, 480), M6(100, 600), M7(100, 840),
		NONE(0, 0);

		final int percentage;
		final int timeLimit;

		FloorRequirement(int percentage, int timeLimit) {
			this.percentage = percentage;
			this.timeLimit = timeLimit;
		}
	}

	private static FloorRequirement currentFloorRequirement() {
		if (floor == null) {
			return FloorRequirement.NONE;
		}
		try {
			return FloorRequirement.valueOf(floor.toUpperCase());
		} catch (IllegalArgumentException e) {
			return FloorRequirement.NONE;
		}
	}

	// Radius periodically scanned around the local player for the boss room's Nether Portal blocks.
	private static final int PORTAL_SCAN_RADIUS_XZ = 20;
	private static final int PORTAL_SCAN_RADIUS_Y = 10;
	private static final int PORTAL_SCAN_INTERVAL_TICKS = 20;
	// "Near the boss portal" for the Party HUD indicator - generous on purpose, informational only.
	private static final double NEAR_PORTAL_DISTANCE_SQ = 12.0 * 12.0;
	// "Actually at the portal" for entry detection - tighter than NEAR_PORTAL_DISTANCE_SQ above.
	private static final double PORTAL_ENTER_DISTANCE_SQ = 1.0 * 1.0;

	private static boolean runActive = false;
	// "completed" | "wiped" | "left" | null - for the website's run-history end-reason display.
	private static String lastRunEndReason = null;
	private static boolean bossRoomEntered = false;
	private static int puzzlesSolved = 0;
	private static int puzzlesFailed = 0;
	private static int cryptsFound = 0;
	private static final Map<String, Integer> deaths = new LinkedHashMap<>();
	/** One death this run, shown as an "X" marker on the live/replay map. Position is a
	 * best-effort snapshot at the moment the death message is seen - null if not currently visible. */
	public record DeathMarker(String username, Double mapX, Double mapY, int deathNumber, long atMillis) {
	}
	private static final List<DeathMarker> deathMarkers = new java.util.ArrayList<>();
	private static final Map<String, Integer> doorsOpened = new LinkedHashMap<>();
	private static final Set<String> readyPlayers = new LinkedHashSet<>();
	private static boolean selfReadyReminderSent = false;
	private static final List<PuzzleResult> puzzleOutcomes = new java.util.ArrayList<>();
	private static final Set<BlockPos> portalBlocks = new HashSet<>();
	private static final Set<String> playersEnteredBossRoom = new LinkedHashSet<>();
	private static int portalScanCooldown = 0;
	// Set on The Watcher's first chat line - arms the portal scan in trackBossRoomEntry().
	private static boolean watcherEncountered = false;
	// Set on the Watcher's farewell line - rooms/secrets are no longer improvable after this.
	private static boolean bloodRoomCompleted = false;
	// True once Hypixel's completion sequence finished and finishRun() sent the report, but
	// runActive/the HUD stay up until the player actually leaves (see SENDING_TO_SERVER_PATTERN).
	private static boolean runReportSentAwaitingLeave = false;
	private static boolean leaveEndScheduled = false;
	private static boolean witherDoorOpened = false;
	private static boolean bloodDoorOpened = false;
	// Who obtained the Blood Key this run, or null if nobody has (yet). For the end-of-run report.
	private static String bloodKeyPlayer = null;
	// Wall-clock timestamps (not elapsedSeconds, which freezes during the boss fight) bracketing
	// the boss fight, for the report's "time in boss room" line. 0 = not set yet.
	private static long bossRoomEnteredMillis = 0;
	private static long bossRoomClearedMillis = 0;
	// One entry per Wither Key obtained this run, in pickup order - true once that door's opened.
	private static final List<Boolean> witherDoorKeys = new java.util.ArrayList<>();
	// Wall-clock timestamp each witherDoorKeys entry was opened (0 = not yet), same index/order.
	private static final List<Long> witherDoorOpenedMillis = new java.util.ArrayList<>();
	private static long bloodDoorOpenedMillis = 0;
	private static long watcherEncounteredMillis = 0;
	private static long bloodRoomCompletedMillis = 0;
	private static boolean mimicKilled = false;
	private static boolean princeKilled = false;
	// Score (and who) at the moment someone FIRST entered the boss room under 300 - null if nobody
	// entered early. For the end-of-run report.
	private static Integer earlyBossRoomEntryScore = null;
	private static String earlyBossRoomEntryPlayer = null;
	// Snapshotted from PartyTracker at run start, not kept in sync afterward - someone leaving the
	// party mid-run should still get tracked for the rest of THIS run.
	private static Set<UUID> runRoster = new LinkedHashSet<>();

	private static String floor = null;
	private static double clearedPercent = 0;
	private static int elapsedSeconds = 0;
	// Guards the scoreboard-based auto-start: without this, the tick right after finishRun() would
	// see runActive=false + floor still non-null and restart tracking for a run that just ended.
	private static boolean sawNoDungeonSinceLastRun = true;
	private static long lastGateLogMillis = 0;
	private static long lastRunStartMillis = 0;
	// Consecutive ticks the sidebar has read "no floor" - never used once the boss room is entered.
	private static int floorNullTicks = 0;
	private static final int FLOOR_NULL_END_RUN_TICKS = 400; // 20s
	private static final int FLOOR_NULL_SETTLE_TICKS = 5;

	// Last-logged raw score inputs - re-logged only on change, see logScoreInputsIfChanged().
	private static int loggedCompletedRooms = -1;
	private static double loggedSecretsPercentage = -1;
	private static int loggedCrypts = -1;
	private static int loggedPuzzleCount = -1;
	private static int loggedScoreTotal = Integer.MIN_VALUE;

	private static boolean splusImpossibleAnnounced = false;
	private static boolean splusBackAnnounced = false;
	private static boolean secretsPaceWarningAnnounced = false;
	private static final java.util.Set<Integer> firedTimeLimitCheckpoints = new java.util.HashSet<>();
	private static int lastCountdownSecondShown = -1;
	private static boolean timeLimitReachedHandled = false;
	// Set once the "The Catacombs - Floor X" completion header is seen, cleared once the report
	// actually fires (on the later "Click HERE to re-queue" line) - see the chat listener below.
	private static boolean dungeonCompleteDetected = false;
	private static boolean bossRoomCleared = false;
	// Fires at most once per run, the moment cleared% first hits 100 - see maybeAnnounceRoomsDiscovered().
	private static boolean roomsDiscoveredAnnounced = false;
	// Independent of bossRoomCleared/bloodRoomCompleted - the party can finish a floor around a
	// dead/ghosted player, so those can stay true even after a personal death.
	private static boolean localPlayerDied = false;

	/** A puzzle's lifecycle: found via map color but unresolved, or resolved via chat. */
	public enum PuzzleOutcome {
		PENDING, SOLVED, FAILED
	}

	public record PuzzleResult(PuzzleOutcome outcome, String player, String detail) {
	}

	/** Snapshot for the Score HUD's "Final Result" panel, since the live tab list can already be gone by the time finishRun() runs. */
	public record FinalResult(String floor, int displayedScore, String displayedGrade, ScoreEstimate estimate,
							   double clearedPercent, double secretsPercentage, int crypts, int puzzlesSolved,
							   int puzzlesFailed, int deathsTotal, List<PuzzleResult> puzzleOutcomes) {
	}

	private static FinalResult lastFinalResult = null;
	private static long finalResultShownAtMillis = 0;

	// Cross-run session totals - deliberately never reset by startRun()/finishRun() (only ever reset
	// by the client actually restarting, which is what "this session" means here), see getSessionStats().
	private static int sessionRunsCompleted = 0;
	private static int sessionTotalScore = 0;
	private static int sessionTotalDeaths = 0;
	private static int sessionTotalSeconds = 0;
	private static int sessionSPlusRuns = 0;

	public record SessionStats(int runsCompleted, double averageScore, int splusRuns, int totalDeaths, int totalSeconds) {
	}

	/** Cross-run totals for this whole client session (since launch, not per-run) - see {@code /sm session}. */
	public static SessionStats getSessionStats() {
		double average = sessionRunsCompleted > 0 ? (double) sessionTotalScore / sessionRunsCompleted : 0;
		return new SessionStats(sessionRunsCompleted, average, sessionSPlusRuns, sessionTotalDeaths, sessionTotalSeconds);
	}

	private DungeonRunTracker() {
	}

	public static void init() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			String colorless = FORMAT_CODE.matcher(message.getString()).replaceAll("").trim();

			// Checked ahead of the toggle gate below - ending a stuck run shouldn't depend on it.
			if (runActive && !leaveEndScheduled && SENDING_TO_SERVER_PATTERN.matcher(colorless).find()) {
				leaveEndScheduled = true;
				// Hypixel doesn't always send the re-queue line - if the floor's already confirmed
				// complete but no report sent yet, send it here instead.
				if (dungeonCompleteDetected && !runReportSentAwaitingLeave) {
					dungeonCompleteDetected = false;
					finishRun(SkyMellooConfig.HANDLER.instance());
					runReportSentAwaitingLeave = true;
				}
				boolean reportAlreadySent = runReportSentAwaitingLeave;
				DebugLog.log(DebugLog.Category.DUNGEON, "Leaving this server instance (\"" + colorless + "\") while still marked active - keeping run/HUD visible for 20 more seconds before actually ending tracking"
						+ (reportAlreadySent ? " (report already sent)." : " (no report, run wasn't fully completed)."));
				lastRunEndReason = reportAlreadySent ? "completed" : (isEntirePartyDead() ? "wiped" : "left");
				TickDelay.schedule(FLOOR_NULL_END_RUN_TICKS, () -> {
					runActive = false;
					resetBossRoomDisplayFlags();
					runReportSentAwaitingLeave = false;
					leaveEndScheduled = false;
				});
			}

			SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
			boolean anyToggleOn = config.dungeonRunReportEnabled || config.dungeonBossRoomAnnounceEnabled || config.dungeonDeathKickEnabled || config.dungeonScoreHudEnabled || config.dungeonSelfReadyReminderEnabled;
			if (!anyToggleOn) {
				// Throttled to once per 30s - this gate runs on every chat line.
				long now = System.currentTimeMillis();
				if (now - lastGateLogMillis > 30_000) {
					lastGateLogMillis = now;
					DebugLog.log(DebugLog.Category.DUNGEON, "Chat listener gated off (no relevant toggle on)");
				}
				return;
			}

			if (colorless.equals(DUNGEON_START_MESSAGE)) {
				// Also catches a new run while runActive was stuck true from one that never ended.
				if (!runActive || System.currentTimeMillis() - lastRunStartMillis > 3000) {
					startRun();
				}
				return;
			}

			// Ready-up happens before the countdown, so this must precede the runActive gate below.
			Matcher readyMatcher = READY_PATTERN.matcher(colorless);
			if (readyMatcher.find()) {
				readyPlayers.add(readyMatcher.group(1));
				DebugLog.log(DebugLog.Category.DUNGEON, readyMatcher.group(1) + " is ready.");
				maybeSendSelfReadyReminder();
				return;
			}

			if (!runActive) {
				return;
			}

			Matcher deathMatcher = DEATH_PATTERN.matcher(colorless);
			if (deathMatcher.find()) {
				handleDeath(deathMatcher.group(1));
				return;
			}

			if (!watcherEncountered && WATCHER_PATTERN.matcher(colorless).find()) {
				watcherEncountered = true;
				watcherEncounteredMillis = System.currentTimeMillis();
				DebugLog.log(DebugLog.Category.DUNGEON, "The Watcher encountered - arming boss room portal scan");
				return;
			}

			if (!bloodRoomCompleted && WATCHER_CLEARED_PATTERN.matcher(colorless).find()) {
				bloodRoomCompleted = true;
				bloodRoomCompletedMillis = System.currentTimeMillis();
				DebugLog.log(DebugLog.Category.DUNGEON, "Blood Room completed");
				maybeSendPreBossScoreWarning(Minecraft.getInstance());
				return;
			}

			if (!bloodDoorOpened && BLOOD_DOOR_OPENED_PATTERN.matcher(colorless).find()) {
				bloodDoorOpened = true;
				bloodDoorOpenedMillis = System.currentTimeMillis();
				DebugLog.log(DebugLog.Category.DUNGEON, "Blood Door opened");
				return;
			}

			if (bloodKeyPlayer == null) {
				Matcher bloodKeyMatcher = BLOOD_KEY_OBTAINED_PATTERN.matcher(colorless);
				if (bloodKeyMatcher.matches()) {
					bloodKeyPlayer = bloodKeyMatcher.group(1);
					DebugLog.log(DebugLog.Category.DUNGEON, "Blood Key obtained by " + bloodKeyPlayer);
					return;
				}
			}

			if (!mimicKilled && MIMIC_PATTERN.matcher(colorless).matches()) {
				mimicKilled = true;
				return;
			}

			if (!princeKilled && (PRINCE_PATTERN.matcher(colorless).matches() || colorless.equals(PRINCE_KILL_MESSAGE))) {
				princeKilled = true;
				return;
			}

			if (WITHER_KEY_OBTAINED_PATTERN.matcher(colorless).find() || WITHER_KEY_PICKED_GENERIC_PATTERN.matcher(colorless).find()) {
				witherDoorKeys.add(false);
				witherDoorOpenedMillis.add(0L);
				DebugLog.log(DebugLog.Category.DUNGEON, "Wither Key obtained (" + witherDoorKeys.size() + " total this run)");
				return;
			}

			Matcher doorMatcher = DOOR_OPENED_PATTERN.matcher(colorless);
			if (doorMatcher.find()) {
				doorsOpened.merge(doorMatcher.group(1), 1, Integer::sum);
				if ("WITHER".equals(doorMatcher.group(2))) {
					witherDoorOpened = true;
					int pendingIndex = witherDoorKeys.indexOf(false);
					if (pendingIndex >= 0) {
						witherDoorKeys.set(pendingIndex, true);
						witherDoorOpenedMillis.set(pendingIndex, System.currentTimeMillis());
					}
				} else if ("BLOOD".equals(doorMatcher.group(2))) {
					bloodDoorOpened = true;
					bloodDoorOpenedMillis = System.currentTimeMillis();
				}
				DebugLog.log(DebugLog.Category.DUNGEON, "Door opened by " + doorMatcher.group(1) + " (" + doorMatcher.group(2) + ")");
				return;
			}

			Matcher puzzleFailMatcher = PUZZLE_FAIL_PATTERN.matcher(colorless);
			if (puzzleFailMatcher.find()) {
				String failPlayer = puzzleFailMatcher.group(1);
				String failDetail = extractPuzzleReason(colorless, failPlayer);
				// A retried puzzle resends "PUZZLE FAIL!" - don't double-penalize the same failure.
				boolean isRetryOfSameFail = isSameAsLastFail(failPlayer, failDetail);
				if (!isRetryOfSameFail) {
					puzzlesFailed++;
				}
				resolvePuzzleOutcome(PuzzleOutcome.FAILED, failPlayer, failDetail);
				if (isRetryOfSameFail) {
					SkyMellooConfig retryConfig = SkyMellooConfig.HANDLER.instance();
					if (retryConfig.dungeonPuzzleRetryFailEnabled) {
						Minecraft retryClient = Minecraft.getInstance();
						if (retryClient.player != null) {
							String text = retryConfig.dungeonPuzzleRetryFailTemplate.replace("{player}", failPlayer).replace("{detail}", failDetail);
							sendDungeonMessage(retryClient, text, retryConfig.dungeonPuzzleRetryFailDelivery);
						}
					}
				} else {
					maybeAnnounceSPlusImpossible(Component.translatable("skymelloo.chat.dungeon_report.reason_puzzle_failed").getString());
				}
				return;
			}

			Matcher puzzleMatcher = PUZZLE_SOLVED_PATTERN.matcher(colorless);
			if (puzzleMatcher.find()) {
				puzzlesSolved++;
				String solvedPlayer = puzzleMatcher.group(1);
				resolvePuzzleOutcome(PuzzleOutcome.SOLVED, solvedPlayer, extractPuzzleReason(colorless, solvedPlayer));
				return;
			}

			if (CRYPT_PATTERN.matcher(colorless).find()) {
				cryptsFound++;
				return;
			}

			// Requires bloodRoomCompleted - other bosses can also send early taunt lines mid-fight.
			if (!bossRoomEntered && bloodRoomCompleted) {
				Matcher bossMatcher = BOSS_CHAT_PATTERN.matcher(colorless);
				if (bossMatcher.find()) {
					bossRoomEntered = true;
					bossRoomEnteredMillis = System.currentTimeMillis();
					Minecraft client = Minecraft.getInstance();
					if (config.dungeonBossRoomAnnounceEnabled && client.player != null) {
						sendDungeonMessage(client, bossRoomMessageFor(config, Component.translatable("skymelloo.chat.dungeon_report.the_party").getString(), true), config.dungeonBossRoomMessageDelivery);
					}
				}
			}

			// Waits for the re-queue line (~2s later) instead of printing over Hypixel's own summary.
			if (DUNGEON_COMPLETE_PATTERN.matcher(colorless).matches()) {
				dungeonCompleteDetected = true;
				if (!bossRoomCleared) {
					bossRoomClearedMillis = System.currentTimeMillis();
				}
				bossRoomCleared = true;
			}
			if (dungeonCompleteDetected && REQUEUE_PATTERN.matcher(colorless).find()) {
				dungeonCompleteDetected = false;
				// finishRun() doesn't hide the HUD - that waits until the player actually leaves.
				TickDelay.schedule(40, () -> {
					finishRun(config);
					runReportSentAwaitingLeave = true;
				});
			}
		});
	}

	/** Reads the sidebar every tick; also detects run-start for solo runs, which skip the chat countdown. */
	public static void tick(Minecraft client) {
		if (client.level == null) {
			return;
		}
		boolean wasActive = runActive;
		readScoreboard(client);
		if (floor == null) {
			floorNullTicks++;
			// A single-tick sidebar glitch shouldn't count as a real hub return.
			if (floorNullTicks >= FLOOR_NULL_SETTLE_TICKS) {
				if (!sawNoDungeonSinceLastRun) {
					readyPlayers.clear();
					selfReadyReminderSent = false;
				}
				sawNoDungeonSinceLastRun = true;
			}
			// Skipped during the boss fight, where floor==null is normal, not a real exit.
			if (runActive && !bossRoomEntered && floorNullTicks >= FLOOR_NULL_END_RUN_TICKS) {
				DebugLog.log(DebugLog.Category.DUNGEON, "Left the dungeon while still marked active (no floor for " + floorNullTicks + " ticks) - ending run tracking silently, no report.");
				lastRunEndReason = isEntirePartyDead() ? "wiped" : "left";
				runActive = false;
				resetBossRoomDisplayFlags();
			}
		} else {
			floorNullTicks = 0;
			if (!wasActive && sawNoDungeonSinceLastRun) {
				startRun();
			}
		}
		if (!runActive) {
			return;
		}
		trackBossRoomEntry(client);
		logScoreInputsIfChanged();
		checkTimeLimitWarnings(client);
		maybeAnnounceRoomsDiscovered(client);
		maybeSendSecretsPaceWarning(client);
		maybeAnnounceSPlusBackInReach();
		updateScorePace();
		maybeAnnounceGradeMilestone(client);
		trackAfkMembers(client);
		updateVisibleTeammates(client);
	}

	// Real connection-tab-list roster members, used for the backend's mutual party-attestation check.
	private static Set<UUID> visibleTeammates = new LinkedHashSet<>();

	private static void updateVisibleTeammates(Minecraft client) {
		if (client.player == null || client.getConnection() == null) {
			visibleTeammates = new LinkedHashSet<>();
			return;
		}
		UUID self = client.player.getUUID();
		Set<UUID> visible = new LinkedHashSet<>();
		for (var info : client.getConnection().getOnlinePlayers()) {
			UUID id = info.getProfile().id();
			if (!id.equals(self) && runRoster.contains(id)) {
				visible.add(id);
			}
		}
		visibleTeammates = visible;
	}

	/** Other roster members currently confirmed as real connected players - see {@link #updateVisibleTeammates}. Copied out so callers can't mutate the live set. */
	public static Set<UUID> getVisibleTeammates() {
		return new LinkedHashSet<>(visibleTeammates);
	}

	private static final Map<String, Vec3> lastPositionByPlayer = new LinkedHashMap<>();
	private static final Map<String, Integer> afkTicksByPlayer = new LinkedHashMap<>();
	private static final Set<String> afkKicked = new HashSet<>();
	// Half a block or less counts as "hasn't moved" - small enough that combat strafing/knockback
	// never reads as AFK.
	private static final double AFK_STILL_THRESHOLD_SQ = 0.5 * 0.5;
	// Fixed threshold for the visual "seems AFK" flag - independent of the configurable Auto-Kick
	// threshold below, which is a bigger action and wants its own delay.
	private static final int AFK_FLAG_TICKS = 30 * 20;

	/** Tracks how long each roster member has gone without moving - drives both {@link #isAfk(String)} (a fixed 30s flag for the Party HUD) and the separately-configurable AFK Auto-Kick. */
	private static void trackAfkMembers(Minecraft client) {
		if (client.player == null || client.level == null) {
			return;
		}
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		int afkKickThresholdTicks = afkKickThresholdSeconds(config) * 20;
		boolean isLeader = PartyTracker.isLocalPlayerLeader();
		String selfName = client.player.getGameProfile().name();
		for (AbstractClientPlayer player : client.level.players()) {
			if (!runRoster.contains(player.getUUID())) {
				continue;
			}
			String name = player.getGameProfile().name();
			Vec3 pos = player.position();
			Vec3 last = lastPositionByPlayer.put(name, pos);
			int ticks;
			if (last != null && last.distanceToSqr(pos) < AFK_STILL_THRESHOLD_SQ) {
				ticks = afkTicksByPlayer.merge(name, 1, Integer::sum);
			} else {
				afkTicksByPlayer.put(name, 0);
				ticks = 0;
			}
			if (config.dungeonAfkKickEnabled && ticks >= afkKickThresholdTicks && !afkKicked.contains(name)
					&& !name.equalsIgnoreCase(selfName) && isLeader) {
				afkKicked.add(name);
				client.player.connection.sendCommand("party kick " + name);
				client.player.sendSystemMessage(ChatUtil.prefixed(
						Component.translatable("skymelloo.chat.dungeon_report.afk_auto_kick", name, afkKickThresholdSeconds(config))
				));
			}
		}
	}

	private static int afkKickThresholdSeconds(SkyMellooConfig config) {
		return switch (config.dungeonAfkKickThreshold) {
			case "30" -> 30;
			case "120" -> 120;
			default -> 60;
		};
	}

	/** Whether {@code username} appears to be AFK right now - fixed 30s-no-movement flag, for the Party HUD. Independent of the (separately configurable) Auto-Kick threshold. */
	public static boolean isAfk(String username) {
		return afkTicksByPlayer.getOrDefault(username, 0) >= AFK_FLAG_TICKS;
	}

	private static int paceHistoryScore = -1;
	private static int paceTickCounter = 0;
	private static final int PACE_SNAPSHOT_TICKS = 200; // 10s
	// Current score minus the score from ~10s ago - the Score HUD shows this as an up/down arrow.
	private static int scoreTrendDelta = 0;

	/** Snapshots the current score every ~10 real seconds and diffs against the previous snapshot - see {@link #scoreTrendDelta}/{@link #getScoreTrendDelta()}. */
	private static void updateScorePace() {
		paceTickCounter++;
		int current = currentDisplayedScore();
		if (paceHistoryScore < 0) {
			paceHistoryScore = current;
			return;
		}
		if (paceTickCounter >= PACE_SNAPSHOT_TICKS) {
			paceTickCounter = 0;
			scoreTrendDelta = current - paceHistoryScore;
			paceHistoryScore = current;
		}
	}

	/** Score change over the last ~10 seconds - positive means climbing, negative means falling (e.g. Speed decaying past the time limit), 0 means flat. See {@link DungeonScoreHud}. */
	public static int getScoreTrendDelta() {
		return scoreTrendDelta;
	}

	/** Seconds remaining before the current floor's time limit (Speed starts decaying past this) - negative once past it. Meaningless if {@link #hasTimeLimit()} is false. */
	public static int getTimeRemainingSeconds() {
		return currentFloorRequirement().timeLimit - elapsedSeconds;
	}

	/** Whether the current floor has a real time limit at all - false only if the floor failed to resolve (shouldn't normally happen mid-run). */
	public static boolean hasTimeLimit() {
		return currentFloorRequirement().timeLimit > 0;
	}

	// 1 = "C" already counted as the baseline, not a milestone - every run starts at 120
	// (Skill 20 + Speed 100 + Explore/Bonus 0), which is already "C" before a single room clears.
	private static int highestAnnouncedGradeRank = 1;

	private static int gradeRank(String grade) {
		return switch (grade) {
			case "S+" -> 5;
			case "S" -> 4;
			case "A" -> 3;
			case "B" -> 2;
			case "C" -> 1;
			default -> 0;
		};
	}

	/** Fires at most once per grade tier per run, tracking the highest rank ever announced. */
	private static void maybeAnnounceGradeMilestone(Minecraft client) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.dungeonGradeMilestoneEnabled) {
			return;
		}
		String currentGrade = gradeForTotal(currentDisplayedScore());
		int rank = gradeRank(currentGrade);
		if (rank <= highestAnnouncedGradeRank) {
			return;
		}
		highestAnnouncedGradeRank = rank;
		String text = config.dungeonGradeMilestoneTemplate.replace("{grade}", currentGrade);
		sendDungeonMessage(client, text, config.dungeonGradeMilestoneDelivery);
	}

	// Fraction of the floor's time limit that must elapse before the pace check is trusted.
	private static final double MIN_ELAPSED_RATIO_FOR_PACE_CHECK = 0.35;

	/** Fires once per run when the secrets-finding rate falls behind what's needed by the time limit. */
	private static void maybeSendSecretsPaceWarning(Minecraft client) {
		if (secretsPaceWarningAnnounced || client.player == null) {
			return;
		}
		if (bossRoomEntered) {
			return;
		}
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.dungeonSecretsPaceWarningEnabled) {
			return;
		}
		FloorRequirement requirement = currentFloorRequirement();
		if (requirement.timeLimit <= 0 || requirement.percentage <= 0) {
			return;
		}
		if (elapsedSeconds < requirement.timeLimit * MIN_ELAPSED_RATIO_FOR_PACE_CHECK) {
			return;
		}
		int remaining = requirement.timeLimit - elapsedSeconds;
		if (remaining <= 0) {
			return;
		}
		double secretsPercent = getSecretsPercentage();
		double stillNeeded = requirement.percentage - secretsPercent;
		if (stillNeeded <= 0) {
			return;
		}
		double currentRate = secretsPercent / elapsedSeconds;
		double requiredRate = stillNeeded / remaining;
		if (currentRate >= requiredRate) {
			return;
		}
		secretsPaceWarningAnnounced = true;
		String text = config.dungeonSecretsPaceWarningTemplate
				.replace("{secrets}", String.valueOf((int) secretsPercent))
				.replace("{required}", String.valueOf(requirement.percentage))
				.replace("{timeleft}", formatElapsed(remaining));
		sendDungeonMessage(client, text, config.dungeonSecretsPaceWarningDelivery);
	}

	/** Announces the final "possible" score once cleared% effectively hits 100. */
	private static void maybeAnnounceRoomsDiscovered(Minecraft client) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		// >= 99.5 - Hypixel's sidebar rarely shows a clean 100 even when fully explored.
		if (!config.dungeonRoomsDiscoveredAnnounceEnabled || roomsDiscoveredAnnounced || clearedPercent < 99.5) {
			return;
		}
		roomsDiscoveredAnnounced = true;
		int possible = getBestPossibleScore();
		String message = config.dungeonRoomsDiscoveredTemplate
				.replace("{possible}", String.valueOf(possible))
				.replace("{grade}", gradeForTotal(possible));
		sendDungeonMessage(client, message, config.dungeonRoomsDiscoveredDelivery);
	}

	/** Logs the raw score inputs behind {@link #calculateScore()} whenever any of them change - lets
	 * a wrong/stuck score be diagnosed directly from the debug log. */
	private static void logScoreInputsIfChanged() {
		int completedRooms = getCompletedRooms();
		double secretsPercentage = getSecretsPercentage();
		int crypts = getCrypts();
		int puzzleCount = getPuzzleCount();
		int total = calculateScore().total();
		if (completedRooms == loggedCompletedRooms && secretsPercentage == loggedSecretsPercentage
				&& crypts == loggedCrypts && puzzleCount == loggedPuzzleCount && total == loggedScoreTotal) {
			return;
		}
		loggedCompletedRooms = completedRooms;
		loggedSecretsPercentage = secretsPercentage;
		loggedCrypts = crypts;
		loggedPuzzleCount = puzzleCount;
		loggedScoreTotal = total;
		DebugLog.log(DebugLog.Category.DUNGEON, "Score inputs: completedRooms=" + completedRooms
				+ " (tab line 43=\"" + DungeonTabList.at(43) + "\"), secrets%=" + secretsPercentage
				+ " (line 44=\"" + DungeonTabList.at(44) + "\"), crypts=" + crypts
				+ " (line 33=\"" + DungeonTabList.at(33) + "\"), puzzleCount=" + puzzleCount
				+ " (line 47=\"" + DungeonTabList.at(47) + "\") -> score=" + total);
	}

	private static void readScoreboard(Minecraft client) {
		String previousFloor = floor;
		Scoreboard scoreboard = client.level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		floor = null; // recomputed below every call
		if (objective == null) {
			// Throttled rather than change-gated, so a permanently broken read still gets logged.
			if (runActive) {
				logNoObjectiveIfDue();
			}
			return;
		}
		// Sidebar rows are fake entries; real text lives in the scoreboard team's prefix+suffix.
		List<String> lines = scoreboard.listPlayerScores(objective).stream()
				.sorted(Comparator.comparingInt(PlayerScoreEntry::value).reversed())
				.map(entry -> {
					PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
					String raw = team != null
							? team.getPlayerPrefix().getString() + team.getPlayerSuffix().getString()
							: (entry.display() != null ? entry.display().getString() : entry.ownerName().getString());
					return FORMAT_CODE.matcher(raw).replaceAll("").trim();
				})
				.toList();

		String title = FORMAT_CODE.matcher(objective.getDisplayName().getString()).replaceAll("");
		for (String line : joinTitleAndLines(title, lines)) {
			Matcher floorMatcher = FLOOR_PATTERN.matcher(line);
			if (floorMatcher.find()) {
				floor = floorMatcher.group(1);
			}
			Matcher clearedMatcher = CLEARED_PATTERN.matcher(line);
			if (clearedMatcher.find()) {
				try {
					clearedPercent = Double.parseDouble(clearedMatcher.group(1));
				} catch (NumberFormatException ignored) {
				}
			}
			Matcher timeMatcher = TIME_ELAPSED_PATTERN.matcher(line);
			if (timeMatcher.find()) {
				elapsedSeconds = parseElapsed(timeMatcher.group(1));
			}
		}
		logFloorChangeIfAny(previousFloor, floor, "cleared=" + clearedPercent + "%, elapsed=" + elapsedSeconds + "s");
		if (runActive && (floor == null || clearedPercent == 0)) {
			logRawSidebarIfDue(title, lines);
		}
	}

	private static long lastNoObjectiveLogMillis = 0;
	private static long lastRawSidebarLogMillis = 0;

	private static void logNoObjectiveIfDue() {
		long now = System.currentTimeMillis();
		if (now - lastNoObjectiveLogMillis < 10_000) {
			return;
		}
		lastNoObjectiveLogMillis = now;
		DebugLog.log(DebugLog.Category.DUNGEON, "Sidebar: no SIDEBAR display objective found at all (still checking every 10s).");
	}

	/** Raw sidebar title + lines, for fixing a broken FLOOR_PATTERN/CLEARED_PATTERN. */
	private static void logRawSidebarIfDue(String title, List<String> lines) {
		long now = System.currentTimeMillis();
		if (now - lastRawSidebarLogMillis < 10_000) {
			return;
		}
		lastRawSidebarLogMillis = now;
		DebugLog.log(DebugLog.Category.DUNGEON, "Sidebar (floor/cleared% not matching - title=\"" + title + "\"): " + lines);
	}

	private static void logFloorChangeIfAny(String previousFloor, String newFloor, String extra) {
		if (!java.util.Objects.equals(previousFloor, newFloor)) {
			DebugLog.log(DebugLog.Category.DUNGEON, "Floor: " + previousFloor + " -> " + newFloor + " (" + extra + ")");
		}
	}

	/** Boss room entry via position (real Nether Portal hitbox overlap), not chat. */
	private static void trackBossRoomEntry(Minecraft client) {
		if (client.player == null) {
			return;
		}
		if ("E".equalsIgnoreCase(floor)) {
			return;
		}
		if (portalBlocks.isEmpty()) {
			if (!watcherEncountered || portalScanCooldown-- > 0) {
				return;
			}
			portalScanCooldown = PORTAL_SCAN_INTERVAL_TICKS;

			int[] bloodRoomPos = DungeonRoomTracker.getBloodRoomPhysicalPos();
			BlockPos center = bloodRoomPos != null
					? new BlockPos(bloodRoomPos[0] + 16, client.player.blockPosition().getY(), bloodRoomPos[1] + 16)
					: client.player.blockPosition();
			BlockPos min = center.offset(-PORTAL_SCAN_RADIUS_XZ, -PORTAL_SCAN_RADIUS_Y, -PORTAL_SCAN_RADIUS_XZ);
			BlockPos max = center.offset(PORTAL_SCAN_RADIUS_XZ, PORTAL_SCAN_RADIUS_Y, PORTAL_SCAN_RADIUS_XZ);
			for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
				if (client.level.getBlockState(pos).is(Blocks.NETHER_PORTAL)) {
					portalBlocks.add(pos.immutable());
				}
			}
			if (portalBlocks.isEmpty()) {
				return;
			}
			DebugLog.log(DebugLog.Category.DUNGEON, "Boss room portal located (" + portalBlocks.size() + " blocks, centered " + center + ")");
		}

		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		logPortalDistanceIfDue(client);
		for (AbstractClientPlayer player : client.level.players()) {
			if (!runRoster.contains(player.getUUID())) {
				continue;
			}
			String name = player.getGameProfile().name();
			if (playersEnteredBossRoom.contains(name)) {
				continue;
			}
			if (isReallyInPortal(player)) {
				DebugLog.log(DebugLog.Category.DUNGEON, "Boss room entry detected for " + name + " at " + player.blockPosition()
						+ " (portal blocks=" + portalBlocks + ")");
				playersEnteredBossRoom.add(name);
				boolean isFirstEntrant = playersEnteredBossRoom.size() == 1;
				if (!bossRoomEntered) {
					bossRoomEnteredMillis = System.currentTimeMillis();
				}
				bossRoomEntered = true;
				if (earlyBossRoomEntryScore == null) {
					int scoreAtEntry = currentDisplayedScore();
					if (scoreAtEntry < 300) {
						earlyBossRoomEntryScore = scoreAtEntry;
						earlyBossRoomEntryPlayer = name;
					}
				}
				if (config.dungeonBossRoomAnnounceEnabled) {
					sendDungeonMessage(client, bossRoomMessageFor(config, name, isFirstEntrant), config.dungeonBossRoomMessageDelivery);
				}
			}
		}
	}

	/** Warns before the boss portal spawns, since Hypixel teleports on contact with no delay. */
	private static void maybeSendPreBossScoreWarning(Minecraft client) {
		if (client.player == null) {
			return;
		}
		if ("E".equalsIgnoreCase(floor)) {
			return;
		}
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.dungeonPreBossScoreWarningEnabled) {
			return;
		}
		int score = currentDisplayedScore();
		if (score >= 300) {
			return;
		}
		if (splusImpossibleAnnounced) {
			String alreadyGoneText = config.dungeonPreBossScoreWarningAlreadyImpossibleTemplate.replace("{score}", String.valueOf(score));
			sendDungeonMessage(client, alreadyGoneText, config.dungeonPreBossScoreWarningDelivery);
			return;
		}
		String text = config.dungeonPreBossScoreWarningTemplate.replace("{score}", String.valueOf(score));
		sendDungeonMessage(client, text, config.dungeonPreBossScoreWarningDelivery);
	}

	/** Sends via {@code /pc} per the message's delivery setting; falls back to LOCAL outside a party. */
	// Chunked below Minecraft's 256-char command cap, staggered to avoid Hypixel's chat rate limit.
	private static final int PARTY_CHAT_SAFE_CHUNK_LENGTH = 220;
	private static final int PARTY_CHAT_CHUNK_STAGGER_TICKS = 20;

	public static void sendDungeonMessage(Minecraft client, String text, String delivery) {
		sendDungeonMessage(client, text, delivery, true);
	}

	/** @param leaderOnlyForRelay restrict "PARTY SM" relay to the leader's client (avoids duplicates). */
	public static void sendDungeonMessage(Minecraft client, String text, String delivery, boolean leaderOnlyForRelay) {
		if (client.player == null) {
			return;
		}
		if ("PARTY SM".equalsIgnoreCase(delivery)) {
			if (!leaderOnlyForRelay || com.melloo.skymelloo.client.party.PartyTracker.isLocalPlayerLeader()) {
				client.player.sendSystemMessage(ChatUtil.prefixed(text));
				com.melloo.mellooessentials.client.social.RelayChatManager.sendPartyAnnouncement(client, text);
			}
			return;
		}
		if ("PARTY".equalsIgnoreCase(delivery) && com.melloo.skymelloo.client.party.PartyTracker.isInParty()) {
			// Hypixel strips § from /pc but leaves the format letter as literal text; use a plain prefix.
			List<String> chunks = splitForPartyChat(text);
			for (int i = 0; i < chunks.size(); i++) {
				String chunk = chunks.get(i);
				TickDelay.schedule(i * PARTY_CHAT_CHUNK_STAGGER_TICKS, () -> {
					Minecraft mc = Minecraft.getInstance();
					if (mc.player != null) {
						com.melloo.skymelloo.client.util.PartyChatSender.send(mc, ChatUtil.partyPrefixed(chunk));
					}
				});
			}
		} else {
			client.player.sendSystemMessage(ChatUtil.prefixed(text));
		}
	}

	/** Splits {@code text} into /pc-safe chunks on word boundaries. */
	private static List<String> splitForPartyChat(String text) {
		if (text.length() <= PARTY_CHAT_SAFE_CHUNK_LENGTH) {
			return List.of(text);
		}
		List<String> chunks = new java.util.ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String word : text.split(" ")) {
			while (word.length() > PARTY_CHAT_SAFE_CHUNK_LENGTH) {
				chunks.add(word.substring(0, PARTY_CHAT_SAFE_CHUNK_LENGTH));
				word = word.substring(PARTY_CHAT_SAFE_CHUNK_LENGTH);
			}
			if (!current.isEmpty() && current.length() + 1 + word.length() > PARTY_CHAT_SAFE_CHUNK_LENGTH) {
				chunks.add(current.toString());
				current.setLength(0);
			}
			if (!current.isEmpty()) {
				current.append(' ');
			}
			current.append(word);
		}
		if (!current.isEmpty()) {
			chunks.add(current.toString());
		}
		return chunks;
	}

	private static long lastPortalDistanceLogMillis = 0;

	/** Throttled to once every ~5s; logs each roster player's distance to their own nearest portal block. */
	private static void logPortalDistanceIfDue(Minecraft client) {
		if (portalBlocks.isEmpty() || client.player == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastPortalDistanceLogMillis < 5000) {
			return;
		}
		lastPortalDistanceLogMillis = now;
		StringBuilder sb = new StringBuilder("Portal distance check (" + portalBlocks.size() + " portal blocks tracked):");
		for (AbstractClientPlayer player : client.level.players()) {
			if (!runRoster.contains(player.getUUID())) {
				continue;
			}
			double nearestDistSq = Double.MAX_VALUE;
			for (BlockPos portalPos : portalBlocks) {
				double distSq = portalPos.distToCenterSqr(player.position());
				if (distSq < nearestDistSq) {
					nearestDistSq = distSq;
				}
			}
			sb.append(" ").append(player.getGameProfile().name()).append("=").append(String.format("%.1f", Math.sqrt(nearestDistSq)));
		}
		DebugLog.log(DebugLog.Category.DUNGEON, sb.toString());
	}

	private static boolean isReallyInPortal(AbstractClientPlayer player) {
		for (BlockPos portalPos : portalBlocks) {
			if (player.getBoundingBox().intersects(portalPos)) {
				return true;
			}
		}
		// Teammate positions only update from server packets, so add a tight distance fallback.
		for (BlockPos portalPos : portalBlocks) {
			if (portalPos.distToCenterSqr(player.position()) <= PORTAL_ENTER_DISTANCE_SQ) {
				return true;
			}
		}
		return false;
	}

	public static boolean isNearBossPortal(Vec3 pos) {
		for (BlockPos portalPos : portalBlocks) {
			if (portalPos.distToCenterSqr(pos) <= NEAR_PORTAL_DISTANCE_SQ) {
				return true;
			}
		}
		return false;
	}

	private static List<String> joinTitleAndLines(String title, List<String> lines) {
		java.util.ArrayList<String> all = new java.util.ArrayList<>(lines.size() + 1);
		all.add(title);
		all.addAll(lines);
		return all;
	}

	private static int parseElapsed(String text) {
		int seconds = 0;
		Matcher token = TIME_TOKEN.matcher(text);
		while (token.find()) {
			int value = Integer.parseInt(token.group(1));
			seconds += switch (token.group(2)) {
				case "d" -> value * 86400;
				case "h" -> value * 3600;
				case "m" -> value * 60;
				default -> value;
			};
		}
		return seconds;
	}

	private static String formatElapsed(int seconds) {
		return (seconds / 60) + "m " + (seconds % 60) + "s";
	}

	/** Called after {@link #finishRun} returns, not from inside it - finishRun still reads these fields. */
	private static void resetBossRoomDisplayFlags() {
		bossRoomEntered = false;
		bossRoomCleared = false;
		bloodRoomCompleted = false;
		localPlayerDied = false;
		watcherEncountered = false;
		witherDoorOpened = false;
		bloodDoorOpened = false;
		witherDoorKeys.clear();
		witherDoorOpenedMillis.clear();
		bloodDoorOpenedMillis = 0;
		watcherEncounteredMillis = 0;
		bloodRoomCompletedMillis = 0;
	}

	private static void startRun() {
		lastRunStartMillis = System.currentTimeMillis();
		Minecraft client = Minecraft.getInstance();
		PartyTracker.requestRefreshNow();
		runRoster = new LinkedHashSet<>(PartyTracker.getMembers());
		if (client.player != null) {
			runRoster.add(client.player.getUUID());
		}
		DebugLog.log(DebugLog.Category.DUNGEON, "Run started (floor=" + floor + ", roster=" + runRoster.size() + ")");
		com.melloo.skymelloo.client.party.PartyHudManager.forceRefreshAll();
		floorNullTicks = 0;
		runActive = true;
		lastRunEndReason = null;
		sawNoDungeonSinceLastRun = false;
		bossRoomEntered = false;
		localPlayerDied = false;
		watcherEncountered = false;
		bloodRoomCompleted = false;
		witherDoorOpened = false;
		bloodDoorOpened = false;
		bloodKeyPlayer = null;
		bossRoomEnteredMillis = 0;
		bossRoomClearedMillis = 0;
		witherDoorKeys.clear();
		witherDoorOpenedMillis.clear();
		bloodDoorOpenedMillis = 0;
		watcherEncounteredMillis = 0;
		bloodRoomCompletedMillis = 0;
		mimicKilled = false;
		princeKilled = false;
		earlyBossRoomEntryScore = null;
		earlyBossRoomEntryPlayer = null;
		puzzlesSolved = 0;
		puzzlesFailed = 0;
		cryptsFound = 0;
		deaths.clear();
		deathMarkers.clear();
		doorsOpened.clear();
		puzzleOutcomes.clear();
		portalBlocks.clear();
		playersEnteredBossRoom.clear();
		portalScanCooldown = 0;
		loggedCompletedRooms = -1;
		loggedSecretsPercentage = -1;
		loggedCrypts = -1;
		loggedPuzzleCount = -1;
		loggedScoreTotal = Integer.MIN_VALUE;
		splusImpossibleAnnounced = false;
		splusBackAnnounced = false;
		secretsPaceWarningAnnounced = false;
		paceHistoryScore = -1;
		paceTickCounter = 0;
		scoreTrendDelta = 0;
		highestAnnouncedGradeRank = 1; // see the field's own comment - every run starts at "C"
		lastPositionByPlayer.clear();
		afkTicksByPlayer.clear();
		afkKicked.clear();
		firedTimeLimitCheckpoints.clear();
		timeLimitFinalCheckDone = false;
		lastCountdownSecondShown = -1;
		timeLimitReachedHandled = false;
		dungeonCompleteDetected = false;
		bossRoomCleared = false;
		roomsDiscoveredAnnounced = false;
		runReportSentAwaitingLeave = false;
		leaveEndScheduled = false;
		clearedPercent = 0;
		elapsedSeconds = 0;
	}

	private static void handleDeath(String rawName) {
		Minecraft client = Minecraft.getInstance();
		String name = rawName;
		if ("You".equals(name) && client.player != null) {
			name = client.player.getGameProfile().name();
			localPlayerDied = true;
		}
		int count = deaths.merge(name, 1, Integer::sum);
		recordDeathMarker(client, name, count);

		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (config.dungeonDeathMessageEnabled) {
			String text = config.dungeonDeathMessageTemplate.replace("{player}", name).replace("{count}", String.valueOf(count));
			sendDungeonMessage(client, text, config.dungeonDeathMessageDelivery);
		}
		maybeAnnounceSPlusImpossible(Component.translatable("skymelloo.chat.dungeon_report.reason_death").getString());

		if (client.player == null || client.player.getGameProfile().name().equalsIgnoreCase(name)) {
			return;
		}
		if (!config.dungeonDeathKickEnabled || count <= config.dungeonDeathKickThreshold) {
			return;
		}
		// Only the party leader can /party kick on Hypixel.
		if (!com.melloo.skymelloo.client.party.PartyTracker.isLocalPlayerLeader()) {
			return;
		}
		client.player.connection.sendCommand("party kick " + name);
		client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.chat.dungeon_report.death_auto_kick", name, count, config.dungeonDeathKickThreshold)));
	}

	/** Position snapshot at death for the website map marker; tolerant of the entity not resolving. */
	private static void recordDeathMarker(Minecraft client, String name, int deathNumber) {
		Double mapX = null;
		Double mapY = null;
		if (client.level != null) {
			for (net.minecraft.client.player.AbstractClientPlayer entity : client.level.players()) {
				if (entity.getGameProfile().name().equalsIgnoreCase(name)) {
					DungeonRoomTracker.ExactPosition pos = DungeonRoomTracker.getExactMapPositionFor(client, entity.getX(), entity.getZ(), entity.getYRot());
					if (pos != null) {
						mapX = pos.mapX();
						mapY = pos.mapY();
					}
					break;
				}
			}
		}
		deathMarkers.add(new DeathMarker(name, mapX, mapY, deathNumber, System.currentTimeMillis()));
	}

	public static List<DeathMarker> getDeathMarkers() {
		return deathMarkers;
	}

	public static int getDeaths(String username) {
		return deaths.getOrDefault(username, 0);
	}

	public static boolean isReady(String username) {
		return readyPlayers.contains(username);
	}

	private static void maybeSendSelfReadyReminder() {
		if (selfReadyReminderSent) {
			return;
		}
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.dungeonSelfReadyReminderEnabled) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		String selfName = client.player.getGameProfile().name();
		if (readyPlayers.contains(selfName)) {
			return;
		}
		Map<UUID, com.melloo.skymelloo.client.party.PartyHudManager.MemberInfo> members = com.melloo.skymelloo.client.party.PartyHudManager.getMembers();
		if (members.size() <= 1) {
			return;
		}
		for (com.melloo.skymelloo.client.party.PartyHudManager.MemberInfo member : members.values()) {
			if (!member.username().equalsIgnoreCase(selfName) && !readyPlayers.contains(member.username())) {
				return;
			}
		}
		selfReadyReminderSent = true;
		client.gui.setOverlayMessage(Component.literal(config.dungeonSelfReadyReminderText), false);
	}

	public static Set<UUID> getEffectiveRoster() {
		return runActive ? runRoster : PartyTracker.getMembers();
	}

	public static boolean isRunActive() {
		return runActive;
	}

	public static String getFloor() {
		return floor;
	}

	public static long getRunStartedAtMillis() {
		return lastRunStartMillis;
	}

	public static boolean isWitherDoorOpened() {
		return witherDoorOpened;
	}

	/** Copied out so the debug HUD can't mutate the live list. */
	public static List<Boolean> getWitherDoors() {
		return new java.util.ArrayList<>(witherDoorKeys);
	}

	public static List<Long> getWitherDoorOpenedMillis() {
		return new java.util.ArrayList<>(witherDoorOpenedMillis);
	}

	public static long getBloodDoorOpenedMillis() {
		return bloodDoorOpenedMillis;
	}

	public static long getBloodRoomClearedMillis() {
		return bloodRoomCompletedMillis;
	}

	public static long getBossRoomEnteredMillis() {
		return bossRoomEnteredMillis;
	}

	public static long getBossRoomClearedMillis() {
		return bossRoomClearedMillis;
	}

	public static boolean isBloodRoomEntered() {
		return watcherEncountered;
	}

	public static boolean isBloodDoorOpened() {
		return bloodDoorOpened;
	}

	public static boolean isBloodKeyObtained() {
		return bloodKeyPlayer != null;
	}

	public static boolean isBloodRoomCleared() {
		return bloodRoomCompleted;
	}

	public static boolean isBossRoomEntered() {
		return bossRoomEntered;
	}

	public static boolean isBossRoomCleared() {
		return bossRoomCleared;
	}

	public static boolean hasLocalPlayerDied() {
		return localPlayerDied;
	}

	/** True only once every roster member has a confirmed death entry (no mid-run respawn in Catacombs). */
	public static boolean isEntirePartyDead() {
		if (runRoster.isEmpty()) {
			return false;
		}
		Map<UUID, com.melloo.skymelloo.client.party.PartyHudManager.MemberInfo> members = com.melloo.skymelloo.client.party.PartyHudManager.getMembers();
		for (UUID uuid : runRoster) {
			com.melloo.skymelloo.client.party.PartyHudManager.MemberInfo info = members.get(uuid);
			if (info == null) {
				return false;
			}
			String username = info.username();
			boolean confirmedDead = username != null && deaths.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(username));
			if (!confirmedDead) {
				return false;
			}
		}
		return true;
	}

	/** "completed" | "wiped" | "left", or null if the run hasn't ended. */
	public static String getRunEndReason() {
		return lastRunEndReason;
	}

	public static boolean isShowingFinalResult() {
		if (lastFinalResult == null) {
			return false;
		}
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.dungeonScoreFinalResultEnabled) {
			return false;
		}
		return System.currentTimeMillis() - finalResultShownAtMillis < config.dungeonScoreFinalResultDurationSeconds * 1000L;
	}

	public static FinalResult getLastFinalResult() {
		return lastFinalResult;
	}

	public static double getClearedPercent() {
		return clearedPercent;
	}

	public static int getTotalDeaths() {
		return deaths.values().stream().mapToInt(Integer::intValue).sum();
	}

	public static List<PuzzleResult> getPuzzleOutcomes() {
		return puzzleOutcomes;
	}

	public static void markPuzzleRoomFound() {
		if (!runActive) {
			return;
		}
		puzzleOutcomes.add(new PuzzleResult(PuzzleOutcome.PENDING, "", ""));
		SkyblockerBridge.RoomSecrets roomSecrets = SkyblockerBridge.getCurrentRoomSecrets();
		String nameSuffix = roomSecrets != null && roomSecrets.roomName() != null ? " (" + roomSecrets.roomName() + ")" : "";
		DebugLog.log(DebugLog.Category.DUNGEON, "Puzzle room detected via map color - pending outcome" + nameSuffix);
	}

	private static void resolvePuzzleOutcome(PuzzleOutcome outcome, String player, String detail) {
		PuzzleResult result = new PuzzleResult(outcome, player, detail);
		int lastIndex = puzzleOutcomes.size() - 1;
		if (lastIndex >= 0 && (puzzleOutcomes.get(lastIndex).outcome() == PuzzleOutcome.PENDING || isSameAsLastFail(player, detail))) {
			puzzleOutcomes.set(lastIndex, result);
		} else {
			puzzleOutcomes.add(result);
		}
		DebugLog.log(DebugLog.Category.DUNGEON, "Puzzle " + outcome + " (" + player + "): " + detail);
	}

	private static boolean isSameAsLastFail(String player, String detail) {
		int lastIndex = puzzleOutcomes.size() - 1;
		if (lastIndex < 0) {
			return false;
		}
		PuzzleResult last = puzzleOutcomes.get(lastIndex);
		return last.outcome() == PuzzleOutcome.FAILED && last.player().equalsIgnoreCase(player) && last.detail().equals(detail);
	}

	/** Catches puzzles solved on retry with no chat line, via Skyblocker's room state; display-only, score penalty stays permanent. */
	static void checkPuzzleClearedViaSkyblocker() {
		if (!runActive) {
			return;
		}
		int lastIndex = puzzleOutcomes.size() - 1;
		if (lastIndex < 0) {
			return;
		}
		PuzzleResult last = puzzleOutcomes.get(lastIndex);
		if (last.outcome() == PuzzleOutcome.SOLVED) {
			return;
		}
		String clearState = SkyblockerBridge.getCurrentRoomClearState();
		if (!"GREEN_CHECKED".equals(clearState) && !"WHITE_CHECKED".equals(clearState)) {
			return;
		}
		String detail = last.outcome() == PuzzleOutcome.FAILED
				? last.detail() + " (solved on retry - no chat line, read from Skyblocker's room state)"
				: "solved on retry (no chat line, read from Skyblocker's room state)";
		puzzleOutcomes.set(lastIndex, new PuzzleResult(PuzzleOutcome.SOLVED, last.player(), detail));
		DebugLog.log(DebugLog.Category.DUNGEON, "Puzzle outcome corrected to SOLVED via Skyblocker room clearState (" + clearState + ")");
	}

	/** Uses the LAST occurrence of {@code name} - Lunar Client's chat_heads mod can prepend a duplicate name marker. */
	private static String extractPuzzleReason(String colorless, String name) {
		int idx = colorless.lastIndexOf(name);
		if (idx < 0) {
			return "";
		}
		return colorless.substring(idx + name.length()).trim();
	}

	public record ScoreEstimate(int skill, int explore, int speed, int bonus, int total, String grade) {
	}

	/**
	 * Live score estimate, ported from Skyblocker's DungeonScore.java (LGPL-3.0). Skill/Explore/Speed/Bonus
	 * per Hypixel's real formula; Entrance floor scores at 70%. Not ported: EZPZ perk, Spirit Pet death exemption.
	 */
	public static ScoreEstimate calculateScore() {
		boolean isEntrance = "E".equalsIgnoreCase(floor);
		int skillRaw = calculateSkillScore();
		int exploreRaw = calculateExploreScore();
		int speedRaw = calculateTimeScore();
		int bonusRaw = calculateBonusScore();
		int skill = isEntrance ? Math.round(skillRaw * 0.7f) : skillRaw;
		int explore = isEntrance ? Math.round(exploreRaw * 0.7f) : exploreRaw;
		int speed = isEntrance ? Math.round(speedRaw * 0.7f) : speedRaw;
		int bonus = isEntrance ? Math.round(bonusRaw * 0.7f) : bonusRaw;
		int total = skill + explore + speed + bonus;
		return new ScoreEstimate(skill, explore, speed, bonus, total, gradeForTotal(total));
	}

	/** Skyblocker's live score when available, otherwise our own estimate. */
	static int currentDisplayedScore() {
		Integer skyblockerScore = SkyblockerBridge.getScore();
		if (skyblockerScore != null) {
			return skyblockerScore;
		}
		return calculateScore().total();
	}

	/** Low-score variant only for the first entrant, and only if S+ wasn't already lost for a different reason. */
	private static String bossRoomMessageFor(SkyMellooConfig config, String player, boolean isFirstEntrant) {
		int score = currentDisplayedScore();
		if (score < 300 && isFirstEntrant && !splusImpossibleAnnounced) {
			return config.dungeonBossRoomLowScoreMessageTemplate.replace("{player}", player).replace("{score}", String.valueOf(score));
		}
		return config.dungeonBossRoomMessageTemplate.replace("{player}", player);
	}

	public static String gradeForTotal(double total) {
		if (total >= 300) {
			return "S+";
		} else if (total >= 269.5) {
			return "S";
		} else if (total >= 230) {
			return "A";
		} else if (total >= 160) {
			return "B";
		} else if (total >= 100) {
			return "C";
		} else {
			return "D";
		}
	}

	private static final double[] GRADE_THRESHOLDS = {100, 160, 230, 269.5, 300};
	private static final String[] GRADE_LABELS = {"C", "B", "A", "S", "S+"};

	/** Null if already at S+. */
	public record NextGrade(String label, int pointsNeeded) {
	}

	public static NextGrade nextGrade(double total) {
		for (int i = 0; i < GRADE_THRESHOLDS.length; i++) {
			if (total < GRADE_THRESHOLDS[i]) {
				return new NextGrade(GRADE_LABELS[i], (int) Math.ceil(GRADE_THRESHOLDS[i] - total));
			}
		}
		return null;
	}

	/** Best-case ceiling if everything still open goes perfectly; used only to answer "is S+ still reachable". */
	private static int bestPossibleTotal() {
		boolean isEntrance = "E".equalsIgnoreCase(floor);
		int bestSkillRaw = 20 + Math.clamp(80 - puzzlesFailed * 10 - getDeathScorePenalty(), 0, 80);
		int bestExploreRaw = 100;
		int bestSpeedRaw = calculateTimeScore();
		int bestBonusRaw = 8;
		int bestSkill = isEntrance ? Math.round(bestSkillRaw * 0.7f) : bestSkillRaw;
		int bestExplore = isEntrance ? Math.round(bestExploreRaw * 0.7f) : bestExploreRaw;
		int bestSpeed = isEntrance ? Math.round(bestSpeedRaw * 0.7f) : bestSpeedRaw;
		int bestBonus = isEntrance ? Math.round(bestBonusRaw * 0.7f) : bestBonusRaw;
		return bestSkill + bestExplore + bestSpeed + bestBonus;
	}

	/** Floored at the currently displayed score - our own estimate can read below Skyblocker's live score. */
	private static int effectiveBestPossible() {
		return Math.max(bestPossibleTotal(), currentDisplayedScore());
	}

	public static int getBestPossibleScore() {
		return effectiveBestPossible();
	}

	public static boolean isSPlusStillPossible() {
		return effectiveBestPossible() >= 300;
	}

	/** Null if no time limit, or if S+ is already impossible even at a perfect Speed score. */
	public static Integer getExtraSecondsForSPlus() {
		int timeLimit = currentFloorRequirement().timeLimit;
		if (timeLimit <= 0) {
			return null;
		}
		boolean isEntrance = "E".equalsIgnoreCase(floor);
		float scale = isEntrance ? 0.7f : 1f;
		int bestSkillRaw = 20 + Math.clamp(80 - puzzlesFailed * 10 - getDeathScorePenalty(), 0, 80);
		int fixedCeiling = Math.round(bestSkillRaw * scale) + Math.round(100 * scale) + Math.round(8 * scale);
		int requiredSpeedScaled = 300 - fixedCeiling;
		if (requiredSpeedScaled <= 0) {
			return Integer.MAX_VALUE;
		}
		int requiredSpeedRaw = (int) Math.ceil(requiredSpeedScaled / scale);
		if (requiredSpeedRaw > 100) {
			return null;
		}
		// calculateTimeScore() only decreases past the limit; scan forward instead of inverting it.
		int candidate = Math.max(elapsedSeconds, timeLimit);
		int hardCap = candidate + 20_000;
		while (candidate < hardCap && calculateTimeScore(candidate) >= requiredSpeedRaw) {
			candidate++;
		}
		return Math.max(0, candidate - 1 - elapsedSeconds);
	}

	public record ScorePenalties(int puzzleFailPenalty, int deathPenalty) {
	}

	public static ScorePenalties currentPenalties() {
		return new ScorePenalties(puzzlesFailed * 10, getDeathScorePenalty());
	}

	/** Fires once per run, the moment S+ first becomes unreachable. */
	private static void maybeAnnounceSPlusImpossible(String reason) {
		if (splusImpossibleAnnounced || isSPlusStillPossible()) {
			return;
		}
		splusImpossibleAnnounced = true;
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.dungeonSPlusImpossibleEnabled) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		String text = config.dungeonSPlusImpossibleTemplate.replace("{reason}", reason);
		sendDungeonMessage(client, text, config.dungeonSPlusImpossibleDelivery);
	}

	/** Counterpart to {@link #maybeAnnounceSPlusImpossible} - the ceiling can climb back over 300 later. */
	private static void maybeAnnounceSPlusBackInReach() {
		if (!splusImpossibleAnnounced || splusBackAnnounced || !isSPlusStillPossible()) {
			return;
		}
		splusBackAnnounced = true;
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.dungeonSPlusBackEnabled) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		sendDungeonMessage(client, config.dungeonSPlusBackTemplate, config.dungeonSPlusBackDelivery);
	}

	private static final int[] TIME_LIMIT_CHECKPOINTS = {60, 30, 15, 10};

	/** Time-limit chat checkpoints; stops firing once the boss room is entered (elapsed time gets unreliable mid-fight). */
	private static boolean timeLimitFinalCheckDone = false;

	private static void checkTimeLimitWarnings(Minecraft client) {
		if (bossRoomEntered) {
			if (timeLimitFinalCheckDone) {
				return;
			}
			timeLimitFinalCheckDone = true;
		}
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		int timeLimit = currentFloorRequirement().timeLimit;
		if (timeLimit <= 0) {
			return;
		}
		int remaining = timeLimit - elapsedSeconds;
		if (remaining <= 0) {
			if (!timeLimitReachedHandled) {
				timeLimitReachedHandled = true;
				if (config.dungeonTimeLimitExceededEnabled) {
					String text = config.dungeonTimeLimitExceededTemplate.replace("{floor}", floor != null ? floor : "?");
					sendDungeonMessage(client, text, config.dungeonTimeLimitExceededDelivery);
				}
			}
			return;
		}
		if (!config.dungeonTimeLimitWarningEnabled) {
			return;
		}
		// Based on how much longer S+ can actually stay reachable, not the floor's raw time limit.
		Integer extraSeconds = getExtraSecondsForSPlus();
		if (extraSeconds != null && extraSeconds != Integer.MAX_VALUE) {
			int startCheckpoint = switch (config.dungeonTimeLimitWarningStart) {
				case "60" -> 60;
				case "15" -> 15;
				case "10" -> 10;
				default -> 30;
			};
			// extraSeconds can drop abruptly, qualifying several checkpoints in one call - announce only the most urgent.
			Integer toAnnounce = null;
			for (int checkpoint : TIME_LIMIT_CHECKPOINTS) {
				if (checkpoint > startCheckpoint || firedTimeLimitCheckpoints.contains(checkpoint) || extraSeconds > checkpoint) {
					continue;
				}
				firedTimeLimitCheckpoints.add(checkpoint);
				toAnnounce = checkpoint;
			}
			if (toAnnounce != null) {
				String timeText = toAnnounce >= 60 ? (toAnnounce / 60) + " minute" + (toAnnounce >= 120 ? "s" : "") : toAnnounce + " seconds";
				String text = config.dungeonTimeLimitWarningTemplate.replace("{time}", timeText);
				sendDungeonMessage(client, text, config.dungeonTimeLimitWarningDelivery);
			}
		}
		// Personal on-screen countdown for the final 10 seconds - always local, not a chat message.
		if (remaining <= 10 && remaining != lastCountdownSecondShown) {
			lastCountdownSecondShown = remaining;
			client.gui.setOverlayMessage(Component.translatable("skymelloo.chat.dungeon_report.time_limit_countdown", remaining), false);
		}
	}

	/** Every input {@link #calculateScore()} used, for {@code /sm debug score}. */
	public record ScoreDebugInfo(boolean skyblockerAvailable, Integer skyblockerScore,
								  int totalRooms, int completedRooms, int extraCompletedRooms, double clearedPercentUsed,
								  int skill, int explore, int speed, int bonus, int total) {
	}

	public static ScoreDebugInfo debugScoreInfo() {
		ScoreEstimate est = calculateScore();
		return new ScoreDebugInfo(
				SkyblockerBridge.isAvailable(), SkyblockerBridge.getScore(),
				getTotalRooms(), getCompletedRooms(), getExtraCompletedRooms(), clearedPercent,
				est.skill(), est.explore(), est.speed(), est.bonus(), est.total());
	}

	private static int calculateSkillScore() {
		int totalRooms = getTotalRooms();
		int completedRoomScore = Math.clamp(totalRooms != 0 ? (int) (80.0 * (getCompletedRooms() + getExtraCompletedRooms()) / totalRooms) : 0, 0, 80);
		return 20 + Math.clamp(completedRoomScore - getPuzzlePenalty() - getDeathScorePenalty(), 0, 80);
	}

	private static int calculateExploreScore() {
		int totalRooms = getTotalRooms();
		int completedRoomScore = Math.clamp(totalRooms != 0 ? (int) (60.0 * (getCompletedRooms() + getExtraCompletedRooms()) / totalRooms) : 0, 0, 60);
		int requiredPercentage = currentFloorRequirement().percentage;
		int secretsScore = requiredPercentage != 0 ? Math.clamp((int) (40 * Math.min(requiredPercentage, getSecretsPercentage()) / requiredPercentage), 0, 40) : 0;
		return completedRoomScore + secretsScore;
	}

	private static int calculateTimeScore() {
		return calculateTimeScore(elapsedSeconds);
	}

	/** Same formula as {@link #calculateTimeScore()}, but for a hypothetical elapsed time rather than the live one - see {@link #getExtraSecondsForSPlus()}, which needs to evaluate this at times other than right now. */
	private static int calculateTimeScore(int hypotheticalElapsedSeconds) {
		int score = 100;
		int timeLimit = currentFloorRequirement().timeLimit;
		if (timeLimit <= 0 || hypotheticalElapsedSeconds < timeLimit) {
			return score;
		}
		double timePastRequirement = ((double) (hypotheticalElapsedSeconds - timeLimit) / timeLimit) * 100;
		if (timePastRequirement < 20) {
			return score - (int) timePastRequirement / 2;
		}
		if (timePastRequirement < 40) {
			return score - (int) (10 + (timePastRequirement - 20) / 4);
		}
		if (timePastRequirement < 50) {
			return score - (int) (15 + (timePastRequirement - 40) / 5);
		}
		if (timePastRequirement < 60) {
			return score - (int) (17 + (timePastRequirement - 50) / 6);
		}
		return Math.clamp((int) (score - (18 + (2.0 / 3.0) + (timePastRequirement - 60) / 7)), 0, 100);
	}

	private static int calculateBonusScore() {
		int cryptsScore = Math.clamp(getCrypts(), 0, 5);
		boolean floorHasMimics = floor != null && MIMIC_FLOORS_PATTERN.matcher(floor.toUpperCase()).matches();
		int mimicScore = (mimicKilled || (floorHasMimics && getSecretsPercentage() >= 100)) ? 2 : 0;
		int princeScore = princeKilled ? 1 : 0;
		return cryptsScore + mimicScore + princeScore;
	}

	// Inaccurate at low clear% since it's rounded to the nearest integer (ported from DungeonScore.java).
	private static int getTotalRooms() {
		double clearPercentage = clearedPercent / 100.0;
		return (int) Math.round(getCompletedRooms() / clearPercentage);
	}

	private static int getCompletedRooms() {
		Matcher matcher = DungeonTabList.matchAt(43, COMPLETED_ROOMS_PATTERN);
		return matcher != null ? Integer.parseInt(matcher.group("rooms")) : 0;
	}

	private static int getExtraCompletedRooms() {
		if (!bloodRoomCompleted) {
			return "E".equalsIgnoreCase(floor) ? 1 : 2;
		}
		if (!bossRoomEntered && !"E".equalsIgnoreCase(floor)) {
			return 1;
		}
		return 0;
	}

	private static int getDeathScorePenalty() {
		return deaths.values().stream().mapToInt(Integer::intValue).sum() * 2;
	}

	private static int getPuzzleCount() {
		Matcher matcher = DungeonTabList.matchAt(47, PUZZLE_COUNT_PATTERN);
		return matcher != null ? Integer.parseInt(matcher.group("count")) : 0;
	}

	private static int getPuzzlePenalty() {
		int incompletePuzzles = 0;
		int puzzleCount = getPuzzleCount();
		for (int i = 0; i < puzzleCount; i++) {
			Matcher matcher = DungeonTabList.matchAt(48 + i, PUZZLE_STATE_PATTERN);
			if (matcher == null) {
				break;
			}
			if (matcher.group("state").matches("[✖✦]")) {
				incompletePuzzles++;
			}
		}
		return incompletePuzzles * 10;
	}

	static double getSecretsPercentage() {
		Matcher matcher = DungeonTabList.matchAt(44, SECRETS_PATTERN);
		return matcher != null ? Double.parseDouble(matcher.group("secper")) : 0;
	}

	private static int getCrypts() {
		Matcher matcher = DungeonTabList.matchAt(33, CRYPTS_PATTERN);
		if (matcher == null) {
			matcher = DungeonTabList.matchAt(32, CRYPTS_PATTERN); // class milestone 9 shifts this up a line
		}
		return matcher != null ? Integer.parseInt(matcher.group("crypts")) : 0;
	}

	private static void finishRun(SkyMellooConfig config) {
		DebugLog.log(DebugLog.Category.DUNGEON, "Run finished (deaths=" + deaths + ", puzzlesSolved=" + puzzlesSolved + ", puzzlesFailed=" + puzzlesFailed + ", doorsOpened=" + doorsOpened + ")");
		// runActive stays true until the player leaves the dungeon; snapshot the score before it can reset.
		int finalScore = currentDisplayedScore();
		int finalDeaths = deaths.values().stream().mapToInt(Integer::intValue).sum();
		lastFinalResult = new FinalResult(floor, finalScore, gradeForTotal(finalScore), calculateScore(),
				clearedPercent, getSecretsPercentage(), cryptsFound, puzzlesSolved, puzzlesFailed,
				finalDeaths, new java.util.ArrayList<>(puzzleOutcomes));
		finalResultShownAtMillis = System.currentTimeMillis();
		sessionRunsCompleted++;
		sessionTotalScore += finalScore;
		sessionTotalDeaths += finalDeaths;
		sessionTotalSeconds += elapsedSeconds;
		if (finalScore >= 300) {
			sessionSPlusRuns++;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		// Full report is local-only - a long combined /pc message could crash a chat-rendering mod client-side.
		maybeSendPartyRunSummary(client, config);

		if (!config.dungeonRunReportEnabled) {
			return;
		}
		boolean canShowKickButtons = com.melloo.skymelloo.client.party.PartyTracker.isLocalPlayerLeader();
		java.util.function.Consumer<String> emit = line -> client.player.sendSystemMessage(ChatUtil.prefixed(line));

		emit.accept(Component.translatable("skymelloo.chat.dungeon_report.header").getString());

		int displayedScore = currentDisplayedScore();
		String scoreLine = Component.translatable("skymelloo.chat.dungeon_report.score_line",
				floor != null ? floor : "?", formatElapsed(elapsedSeconds), displayedScore, gradeForTotal(displayedScore)).getString();
		emit.accept(scoreLine);
		emit.accept(Component.translatable("skymelloo.chat.dungeon_report.rooms_line",
				(int) clearedPercent, getSecretsPercentage(), cryptsFound).getString());
		emit.accept(Component.translatable("skymelloo.chat.dungeon_report.puzzles_line", puzzlesSolved, puzzlesFailed).getString()
				+ (bossRoomEntered ? Component.translatable("skymelloo.chat.dungeon_report.boss_room_entered_suffix").getString() : "")
				+ (mimicKilled ? Component.translatable("skymelloo.chat.dungeon_report.mimic_killed_suffix").getString() : "")
				+ (princeKilled ? Component.translatable("skymelloo.chat.dungeon_report.prince_killed_suffix").getString() : ""));
		for (PuzzleResult puzzle : puzzleOutcomes) {
			if (puzzle.outcome() == PuzzleOutcome.PENDING) {
				continue;
			}
			String tag = puzzle.outcome() == PuzzleOutcome.SOLVED ? "§a✓" : "§c✖";
			emit.accept("  " + tag + " §f" + puzzle.player() + " §7" + puzzle.detail());
		}
		if (watcherEncountered) {
			String bloodDoorKeyText = bloodKeyPlayer != null ? bloodKeyPlayer : Component.translatable("skymelloo.chat.dungeon_report.blood_key_not_obtained").getString();
			String bloodDoorStateText = bloodDoorOpened ? Component.translatable("skymelloo.chat.dungeon_report.blood_door_opened").getString() : Component.translatable("skymelloo.chat.dungeon_report.blood_door_not_opened").getString();
			String bloodRoomStateText = bloodRoomCompleted ? Component.translatable("skymelloo.chat.dungeon_report.blood_room_cleared").getString() : Component.translatable("skymelloo.chat.dungeon_report.blood_room_entered").getString();
			emit.accept(Component.translatable("skymelloo.chat.dungeon_report.blood_room_line", bloodRoomStateText, bloodDoorKeyText, bloodDoorStateText).getString());
		}
		if (earlyBossRoomEntryScore != null) {
			emit.accept(Component.translatable("skymelloo.chat.dungeon_report.early_boss_room_entry", earlyBossRoomEntryPlayer, earlyBossRoomEntryScore).getString());
		}
		if (!playersEnteredBossRoom.isEmpty()) {
			emit.accept(Component.translatable("skymelloo.chat.dungeon_report.entered_boss_room", String.join("§7, §f", playersEnteredBossRoom)).getString());
		}
		// elapsedSeconds is frozen during the boss fight, so use wall-clock time instead.
		if (bossRoomEnteredMillis > 0 && bossRoomClearedMillis > 0) {
			int bossSeconds = (int) Math.max(0, (bossRoomClearedMillis - bossRoomEnteredMillis) / 1000);
			emit.accept(Component.translatable("skymelloo.chat.dungeon_report.time_in_boss_room", formatElapsed(bossSeconds)).getString());
		}
		if (!doorsOpened.isEmpty()) {
			// Only ever populated by Wither Door opens; the Blood Door's chat message is a nameless broadcast.
			StringBuilder doorsLine = new StringBuilder(Component.translatable("skymelloo.chat.dungeon_report.wither_doors_opened").getString());
			boolean first = true;
			for (Map.Entry<String, Integer> entry : doorsOpened.entrySet()) {
				if (!first) {
					doorsLine.append("§7, ");
				}
				first = false;
				doorsLine.append("§f").append(entry.getKey()).append(" §7x").append(entry.getValue());
			}
			emit.accept(doorsLine.toString());
		}
		if (deaths.isEmpty()) {
			emit.accept(Component.translatable("skymelloo.chat.dungeon_report.no_deaths").getString());
		} else {
			emit.accept(Component.translatable("skymelloo.chat.dungeon_report.deaths_header").getString());
			for (Map.Entry<String, Integer> entry : deaths.entrySet()) {
				String name = entry.getKey();
				int count = entry.getValue();
				boolean isSelf = client.player.getGameProfile().name().equalsIgnoreCase(name);
				if (!canShowKickButtons || isSelf) {
					emit.accept(Component.translatable("skymelloo.chat.dungeon_report.death_count_line", count, name).getString());
					continue;
				}
				MutableComponent line = Component.translatable("skymelloo.chat.dungeon_report.death_count_line", count, name);
				MutableComponent kickButton = Component.translatable("skymelloo.chat.dungeon_report.kick_button").withStyle(style -> style
						.withColor(ChatFormatting.RED)
						.withBold(true)
						.withClickEvent(new ClickEvent.RunCommand("/party kick " + name))
						.withHoverEvent(new HoverEvent.ShowText(Component.translatable("skymelloo.chat.dungeon_report.kick_button_hover", name))));
				client.player.sendSystemMessage(ChatUtil.prefixed(line.append(kickButton)));
			}
		}
	}

	/** Short opt-in party summary, independent of the local detailed report. */
	private static void maybeSendPartyRunSummary(Minecraft client, SkyMellooConfig config) {
		if (!config.dungeonRunPartySummaryEnabled) {
			return;
		}
		int displayedScore = currentDisplayedScore();
		String message = config.dungeonRunPartySummaryTemplate
				.replace("{floor}", floor != null ? floor : "?")
				.replace("{score}", String.valueOf(displayedScore))
				.replace("{grade}", gradeForTotal(displayedScore))
				.replace("{time}", formatElapsed(elapsedSeconds));
		sendDungeonMessage(client, message, config.dungeonRunPartySummaryDelivery);
	}
}
