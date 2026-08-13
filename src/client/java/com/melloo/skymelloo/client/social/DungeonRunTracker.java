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

/**
 * Tracks a dungeon run's key events from chat/scoreboard - deaths, puzzles solved/failed, crypts,
 * and boss room entry - to post an end-of-run chat summary (with kick buttons) and a live "best
 * possible score" estimate for {@link DungeonScoreHud}.
 * <p>
 * Door-opening WAS wrongly assumed unattributable in an earlier version of this file (based on
 * SkyHanni's dungeon chat-message catalog not listing one) - confirmed otherwise directly from a
 * real game log: Hypixel sends "&lt;name&gt; opened a WITHER door!" as an ordinary chat line, same
 * as the puzzle-fail correction below. Lesson: SkyHanni's catalog is what THEY chose to catalog for
 * message-hiding, not proof a message doesn't exist. The Blood Door's own message was WRONGLY
 * guessed to follow the same "&lt;name&gt; opened a ..." shape (marked "presumably" in an earlier
 * version, never actually verified) - confirmed otherwise directly from a real report/log: it's a
 * nameless broadcast, "The BLOOD DOOR has been opened!", not attributed to whoever opened it at
 * all. See BLOOD_DOOR_OPENED_PATTERN.
 * Boss room entry is tracked differently, at the position level rather than from chat: the entrance
 * is a real Nether Portal block, and the client already knows every visible player's exact position
 * (that's how their entity renders at all), so watching who walks into those block positions needs
 * no server cooperation - see {@link #trackBossRoomEntry}. A generic "[BOSS] &lt;name&gt;: ..." chat-line
 * fallback still sets the party-level flag used in the end-of-run report, in case the local player
 * wasn't close enough to have the portal loaded when someone else walked through.
 * <p>
 * "Puzzle failed" WAS wrongly assumed unavailable in an earlier version of this file - confirmed
 * otherwise by an actual in-game screenshot: "PUZZLE FAIL! Koala_Safety killed a Blaze in the
 * wrong order! Yikes!". The reason-text differs per puzzle type, so only the "PUZZLE FAIL! &lt;name&gt;"
 * prefix is matched generically rather than every puzzle's exact wording.
 * <p>
 * The live score estimate is now a faithful port of SkyblockerMod/Skyblocker's own live dungeon
 * score calculation (GitHub, LGPL-3.0, {@code skyblock/dungeon/DungeonScore.java}) - a REAL current
 * estimate using actual completed-rooms/secrets%/puzzle-state/crypts data (read from Hypixel's
 * dungeon TAB list via {@link DungeonTabList}, a different source than the sidebar Scoreboard used
 * for floor/cleared%/time), not the flat-ceiling approximation an earlier version of this file used
 * (which assumed 100% secrets/rooms since it had no way to read the real numbers at the time). See
 * {@link #calculateScore()} for the exact formula.
 * <p>
 * Delivery of each chat announcement below is configurable independently per message type (e.g.
 * {@link SkyMellooConfig#dungeonDeathMessageDelivery}, {@code dungeonBossRoomMessageDelivery},
 * {@code dungeonPreBossScoreWarningDelivery}) - each is either "LOCAL" (client-side only, via
 * {@link ChatUtil}) or "PARTY" (sent for real via {@code /pc}, so the whole party sees it) - see
 * {@link #sendDungeonMessage}. The detailed end-of-run report always stays local since its kick
 * buttons are a client-side-only {@link ClickEvent}, meaningless if actually transmitted through
 * party chat - the separate, short Party Run Summary is the party-safe alternative to it.
 */
public final class DungeonRunTracker {
	private static final Pattern FORMAT_CODE = Pattern.compile("§[0-9A-FK-ORa-fk-or]");
	private static final Pattern DEATH_PATTERN = Pattern.compile("^\\s*☠\\s+(\\S+)\\s+.+became a ghost\\.$");
	// Generic prefix-only match (name followed by a space, no hardcoded continuation phrase) - an
	// earlier version only recognized 2 literal continuations ("wasn't fooled by"/"tied Tic Tac
	// Toe"), which meant every OTHER puzzle type's solve message was silently never detected at all.
	// Same shape as PUZZLE_FAIL_PATTERN below, verified against a real "PUZZLE FAIL!" line. No
	// leading ^ - same chat-icon-prefix bug as DOOR_OPENED_PATTERN, confirmed on a real "PUZZLE
	// SOLVED!" line too.
	private static final Pattern PUZZLE_SOLVED_PATTERN = Pattern.compile("PUZZLE SOLVED! .*?([A-Za-z0-9_]{2,16}) ");
	private static final Pattern PUZZLE_FAIL_PATTERN = Pattern.compile("PUZZLE FAIL! .*?([A-Za-z0-9_]{2,16}) ");
	private static final Pattern CRYPT_PATTERN = Pattern.compile("found a Wither Essence! Everyone gains an extra essence!");
	// No leading ^ - confirmed real bug: a chat-icon mod (e.g. chat_heads) can prepend "[Name head]"
	// before the real name, which an anchored start would never match even though the door genuinely
	// opened. find() naturally lands on whichever name directly precedes the fixed "opened a ... door!" ending.
	private static final Pattern DOOR_OPENED_PATTERN = Pattern.compile("([A-Za-z0-9_]{1,16}) opened a (WITHER|BLOOD) door!$");
	// The Blood Door's REAL message - confirmed directly from a real log/screenshot that
	// DOOR_OPENED_PATTERN's guessed "BLOOD" branch above never actually fires: unlike the Wither Door
	// (attributed to whoever opened it), Hypixel sends this as a plain, nameless broadcast. Root cause
	// of a real reported bug: the debug HUD kept showing "Blood door not opened yet" even well after
	// chat clearly showed "The BLOOD DOOR has been opened!", because bloodDoorOpened was only ever set
	// from the never-matching DOOR_OPENED_PATTERN branch.
	// No leading ^ - same chat-icon-prefix risk as the patterns above.
	private static final Pattern BLOOD_DOOR_OPENED_PATTERN = Pattern.compile("The BLOOD DOOR has been opened!$");
	// Confirmed directly from a real log - two forms depending on who picked it up: "[rank] [head]Name
	// has obtained Wither Key!" for yourself, "A Wither Key was picked up!" (no name at all) seen for
	// a teammate. Either way, a floor can have several Wither Doors, each its own key-then-open cycle -
	// see witherDoorKeys.
	private static final Pattern WITHER_KEY_OBTAINED_PATTERN = Pattern.compile("has obtained Wither Key!$");
	// No leading ^ - same chat-icon-prefix risk as the patterns above.
	private static final Pattern WITHER_KEY_PICKED_GENERIC_PATTERN = Pattern.compile("A Wither Key was picked up!$");
	// Confirmed directly from a real log: "[rank]Name has obtained Blood Key!" - unlike
	// the Wither Key pattern above, this captures the player's name (".*?" skips over the rank tag),
	// for the end-of-run report's "who opened the Blood Room" line.
	private static final Pattern BLOOD_KEY_OBTAINED_PATTERN = Pattern.compile("^.*?([A-Za-z0-9_]{2,16}) has obtained Blood Key!$");
	// No leading ^ - same chat-icon-prefix risk as the patterns above.
	private static final Pattern READY_PATTERN = Pattern.compile("([A-Za-z0-9_]{1,16}) is now ready!$");
	// Excludes "The Watcher" specifically - confirmed directly from a real report/log: The Watcher
	// (the Blood Room's miniboss guardian, never the actual floor boss) sends MULTIPLE "[BOSS] The
	// Watcher: ..." lines throughout the Blood Room fight, not just its one opening line. Only that
	// opening line is consumed by the specific WATCHER_PATTERN check below (gated on
	// !watcherEncountered, which only guards the FIRST one) - every later Watcher line during the
	// same ongoing fight used to fall through all the way to this generic fallback and incorrectly
	// fire "entered boss room" while still fighting in the Blood Room.
	// No leading ^ - same chat-icon-prefix risk as the patterns above.
	private static final Pattern BOSS_CHAT_PATTERN = Pattern.compile("\\[BOSS] (?!The Watcher:)([^:]+):");
	// The Watcher (the miniboss guarding the Blood Room) always speaks with this exact prefix -
	// confirmed directly from SkyHanni's own chat-pattern catalog (BloodTimer.kt, 7 cataloged opening
	// lines all sharing this prefix), not guessed. Its FIRST line is the earliest reliable signal that
	// the Blood Room fight has started - the boss-room portal itself only spawns once that fight ends
	// (all Blood Room mobs defeated), so this just arms the portal scan below rather than claiming the
	// portal exists yet; the existing periodic retry naturally picks it up once it actually does.
	// No leading ^ - same chat-icon-prefix risk as the patterns above.
	private static final Pattern WATCHER_PATTERN = Pattern.compile("\\[BOSS] The Watcher: ");
	// The Watcher's exact farewell line once the Blood Room fight is actually won - confirmed
	// directly from SkyblockerMod/Skyblocker's DungeonScore.java (checkMessageForWatcher), not
	// guessed. Marks rooms/secrets as no longer improvable for the score formula below.
	// No leading ^ - same chat-icon-prefix risk as the patterns above.
	private static final Pattern WATCHER_CLEARED_PATTERN = Pattern.compile("\\[BOSS] The Watcher: You have proven yourself\\. You may pass\\.$");
	// Mimic/Prince kill announcements - also straight from DungeonScore.java. Various community mods
	// (including Skytils) broadcast one of these in party chat when either is killed; Hypixel itself
	// doesn't announce it, hence checking several known phrasings.
	private static final Pattern MIMIC_PATTERN = Pattern.compile(".*?(?:Mimic dead!?|Mimic Killed!|\\$SKYTILS-DUNGEON-SCORE-MIMIC\\$)$");
	private static final Pattern PRINCE_PATTERN = Pattern.compile(".*?(?:Prince dead!?|Prince Killed!)$");
	private static final String PRINCE_KILL_MESSAGE = "A Prince falls. +1 Bonus Score";
	// Which floors can even spawn a Mimic chest, per DungeonScore.java's MIMIC_FLOORS_PATTERN.
	private static final Pattern MIMIC_FLOORS_PATTERN = Pattern.compile("[FM][67]");
	private static final Pattern DUNGEON_COMPLETE_PATTERN = Pattern.compile("\\s*(?:Master Mode )?The Catacombs - (?:Floor [IVX]{1,4}|Entrance)\\s*");
	// Confirmed directly from a real log - the true tail end of Hypixel's own multi-stage completion
	// sequence, ~2 real seconds after the "The Catacombs - Floor X" header line above.
	private static final Pattern REQUEUE_PATTERN = Pattern.compile("Click HERE to re-queue into .+!");
	// Confirmed directly from a real game log - "Dungeon starts in 1 second." (SkyHanni's own
	// pattern, and what an earlier version of this file used) never actually appears; the real
	// message has no "Dungeon" prefix at all.
	private static final String DUNGEON_START_MESSAGE = "Starting in 1 second.";
	// Verified directly from a real log: "Sending to server mini17BS..." - Hypixel's own network-level
	// message whenever the client is transferred to a different server instance (island, hub, dungeon,
	// ...), for ANY reason (a normal warp, an early manual leave, etc.) - not dungeon-specific wording,
	// deliberately broad rather than trying to match every possible way of leaving a dungeon.
	private static final Pattern SENDING_TO_SERVER_PATTERN = Pattern.compile("^Sending to server ");

	private static final Pattern FLOOR_PATTERN = Pattern.compile("The Catacombs \\(([A-Za-z0-9]+)\\)");
	private static final Pattern CLEARED_PATTERN = Pattern.compile("Cleared: ([\\d.]+)%");
	private static final Pattern TIME_ELAPSED_PATTERN = Pattern.compile("Time Elapsed: ((?:\\d+[dhms] ?)+)");
	private static final Pattern TIME_TOKEN = Pattern.compile("(\\d+)([dhms])");

	// Tab-list patterns/indices for dungeon stats - ported verbatim from DungeonScore.java. The tab
	// list is sorted the same way vanilla's own tab overlay sorts it (see DungeonTabList/
	// PlayerTabOverlayAccessor), which is what makes these fixed indices reliable at all.
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

	// Radius (in blocks) periodically scanned around the local player for the boss room's Nether
	// Portal blocks, until found once - Catacombs rooms are well within this, and the room grid
	// means the portal is never far from wherever the party actually is by the time it matters.
	private static final int PORTAL_SCAN_RADIUS_XZ = 20;
	private static final int PORTAL_SCAN_RADIUS_Y = 10;
	private static final int PORTAL_SCAN_INTERVAL_TICKS = 20;
	// "Near the boss portal" threshold for the Party HUD indicator - generous on purpose, this is
	// informational flavor, not a precision requirement.
	private static final double NEAR_PORTAL_DISTANCE_SQ = 12.0 * 12.0;
	// "Actually at the portal" for entry detection - much tighter than the HUD's own NEAR_PORTAL_DISTANCE_SQ
	// above (that one's deliberately generous informational flavor), see isReallyInPortal's distance
	// fallback. Originally 2.0 blocks, but confirmed directly from a real report that was too generous -
	// it fired for someone standing on a nearby platform/ledge next to the portal, not actually in it.
	// 1.0 is still enough slack to catch a teammate's position sample landing just outside the exact
	// block (the reason this fallback exists at all - see isReallyInPortal), without reaching a
	// separate platform a couple of blocks away.
	private static final double PORTAL_ENTER_DISTANCE_SQ = 1.0 * 1.0;

	private static boolean runActive = false;
	// "completed" | "wiped" | "left" | null (unknown/not yet ended) - set right before each
	// runActive=false, for the website's run-history end-reason display. See DungeonSyncManager.
	private static String lastRunEndReason = null;
	private static boolean bossRoomEntered = false;
	private static int puzzlesSolved = 0;
	private static int puzzlesFailed = 0;
	private static int cryptsFound = 0;
	private static final Map<String, Integer> deaths = new LinkedHashMap<>();
	/** Where (and which numbered death for that player) each death this run happened, shown as an "X" marker with name and death number on the live/replay map. Position is a best-effort snapshot of the dying player's entity at the moment their death message is seen - null if they're not currently visible (e.g. already de-rendered) rather than skipping the marker's name/number entirely. */
	public record DeathMarker(String username, Double mapX, Double mapY, int deathNumber, long atMillis) {
	}
	private static final List<DeathMarker> deathMarkers = new java.util.ArrayList<>();
	private static final Map<String, Integer> doorsOpened = new LinkedHashMap<>();
	private static final Set<String> readyPlayers = new LinkedHashSet<>();
	// Reset alongside readyPlayers.clear() (same lifecycle - a fresh ready-check cycle) - see
	// maybeSendSelfReadyReminder().
	private static boolean selfReadyReminderSent = false;
	// Chronological outcomes as puzzles are found/resolved during the run. A PENDING entry is
	// appended by markPuzzleRoomFound() (see DungeonRoomTracker, which reads the dungeon map's
	// pixel colors to detect a Puzzle room before its outcome is known), then flipped to
	// SOLVED/FAILED in place once the already-verified PUZZLE SOLVED/FAIL chat lines land - see
	// resolvePuzzleOutcome().
	private static final List<PuzzleResult> puzzleOutcomes = new java.util.ArrayList<>();
	private static final Set<BlockPos> portalBlocks = new HashSet<>();
	private static final Set<String> playersEnteredBossRoom = new LinkedHashSet<>();
	private static int portalScanCooldown = 0;
	// Set the moment The Watcher's first chat line is seen - see WATCHER_PATTERN. Arms the portal
	// scan in trackBossRoomEntry() so it doesn't waste time scanning before the portal can possibly
	// exist yet.
	private static boolean watcherEncountered = false;
	// Set once the Watcher's farewell line (WATCHER_CLEARED_PATTERN) is seen - the score formula
	// treats rooms/secrets as no longer improvable from that point on, matching real Hypixel scoring
	// (see getExtraCompletedRooms() in calculateScore()'s section).
	private static boolean bloodRoomCompleted = false;
	// True once Hypixel's own completion chat sequence (header + re-queue line) has finished and
	// finishRun() has already captured the final snapshot/sent the report - but runActive/the HUD
	// deliberately stay untouched until the player actually leaves (see SENDING_TO_SERVER_PATTERN's
	// handler). Confirmed as a real bug from a live report: the run/HUD used to
	// disappear a few seconds after the boss died, well before anyone had actually left the
	// dungeon - that only happens now once the player leaves, plus another 20s on top.
	private static boolean runReportSentAwaitingLeave = false;
	// Guards the SENDING_TO_SERVER_PATTERN handler against scheduling its 20s end-of-run delay more
	// than once for the same leave (that chat line could in principle repeat).
	private static boolean leaveEndScheduled = false;
	// Set from DOOR_OPENED_PATTERN's own captured door type (WITHER/BLOOD) - the debug HUD's own
	// flag, distinct from the per-player doorsOpened counter above which doesn't track door type.
	private static boolean witherDoorOpened = false;
	// Mirrors witherDoorOpened above but for the Blood Room's own door - "blood door wie wither door
	// anzeigen also selbe text etc": the debug HUD shows this the same way it shows Wither Doors,
	// using this real DOOR_OPENED_PATTERN("BLOOD") event rather than only the room-entered/cleared
	// flags it used before.
	private static boolean bloodDoorOpened = false;
	// Who obtained the Blood Key this run, from BLOOD_KEY_OBTAINED_PATTERN - null if nobody has (yet),
	// or if the run never reached the Blood Room. For the end-of-run report's Blood Room line.
	private static String bloodKeyPlayer = null;
	// Real wall-clock timestamps (not elapsedSeconds, which the sidebar stops updating for the whole
	// boss fight - see the FLOOR_NULL_END_RUN_TICKS comment on tick()) bracketing the boss fight, for
	// the report's "time in boss room" line. 0 =
	// not set yet. Set the moment bossRoomEntered/bossRoomCleared each FIRST flip true, from whichever
	// of the two independent detection paths (chat fallback or the authoritative position-based one)
	// gets there first.
	private static long bossRoomEnteredMillis = 0;
	private static long bossRoomClearedMillis = 0;
	// One entry per Wither Key obtained this run (a floor can have several Wither Doors), in pickup
	// order - true once THAT door's been opened. A door-opened event marks the oldest still-false
	// entry, since keys are used in roughly the order they're picked up in practice; see
	// getWitherDoors().
	private static final List<Boolean> witherDoorKeys = new java.util.ArrayList<>();
	// Wall-clock timestamp each witherDoorKeys entry was actually OPENED (0 = not opened yet), same
	// index/order as witherDoorKeys - for the debug HUD's per-event elapsed-time display, always
	// shown as minutes/seconds/milliseconds. Real wall-clock
	// millis rather than the scoreboard's own elapsedSeconds, which freezes for the whole boss fight
	// (see FLOOR_NULL_END_RUN_TICKS's own comment) - not useful for anything happening during it.
	private static final List<Long> witherDoorOpenedMillis = new java.util.ArrayList<>();
	private static long bloodDoorOpenedMillis = 0;
	private static long watcherEncounteredMillis = 0;
	private static long bloodRoomCompletedMillis = 0;
	private static boolean mimicKilled = false;
	private static boolean princeKilled = false;
	// The score (and who) at the moment someone FIRST entered the boss room while still under 300 -
	// null if nobody ever entered early (or score was already >= 300 whenever anyone did). Purely for
	// the end-of-run report.
	private static Integer earlyBossRoomEntryScore = null;
	private static String earlyBossRoomEntryPlayer = null;
	// Snapshotted from PartyTracker once at run start and NOT kept in sync with live party
	// membership afterward - someone leaving the party mid-run (intentionally or via a disconnect/
	// rejoin) should still get tracked (deaths, boss-room entry, HP/ready in the Party HUD) for the
	// rest of THIS run, since they're still physically in the dungeon with you.
	private static Set<UUID> runRoster = new LinkedHashSet<>();

	private static String floor = null;
	private static double clearedPercent = 0;
	private static int elapsedSeconds = 0;
	// Guards the scoreboard-based auto-start below: without this, the tick right after finishRun()
	// would immediately see runActive=false + floor still non-null (the scoreboard doesn't clear
	// the instant the completion banner shows) and restart tracking for a run that just ended.
	// Only re-arms once the floor line has genuinely disappeared (e.g. back at the Dungeon Hub).
	private static boolean sawNoDungeonSinceLastRun = true;
	private static long lastGateLogMillis = 0;
	// Set at the top of every startRun() call - see the DUNGEON_START_MESSAGE chat handler above.
	private static long lastRunStartMillis = 0;
	// Consecutive ticks the sidebar has read "no floor" - a single-tick blip (a genuine scoreboard
	// read glitch mid-run, see logRawSidebarIfDue) isn't unusual, so ending the run over it would lose
	// tracking (deaths, puzzles, ...) for a run that's still actually going - confirmed directly from a
	// real log that even 60 ticks (3s) was too eager (Hypixel's sidebar doesn't show the normal
	// Floor/cleared% lines during the boss fight itself, wrongly firing mid-fight). Bumped 3s -> 10s
	// -> 20s for further margin - the tick() gate additionally never fires this at all once
	// the boss room's been entered (see FLOOR_NULL_END_RUN_TICKS's use in tick()), which is the
	// actual fix for the boss-fight false positive; this timeout is purely the fallback for
	// genuinely leaving without a cleaner signal (e.g. a hard disconnect).
	private static int floorNullTicks = 0;
	private static final int FLOOR_NULL_END_RUN_TICKS = 400; // 20s
	// A single-tick sidebar glitch (confirmed directly from a real log: right as a run completes, the
	// victory/extra-stats screen briefly doesn't match the normal Floor/cleared% lines) shouldn't count
	// as "genuinely left the dungeon" - this waits a handful of consecutive null ticks first.
	private static final int FLOOR_NULL_SETTLE_TICKS = 5;

	// Last-logged raw tab-list score inputs - only re-logged when one actually changes, so the score
	// calculation is debuggable directly from chat (which line/index produced which number) without
	// spamming a line every tick. See logScoreInputsIfChanged().
	private static int loggedCompletedRooms = -1;
	private static double loggedSecretsPercentage = -1;
	private static int loggedCrypts = -1;
	private static int loggedPuzzleCount = -1;
	private static int loggedScoreTotal = Integer.MIN_VALUE;

	// S+-impossible warning: fires at most once per run (see maybeAnnounceSPlusImpossible), and the
	// time-limit checkpoint warnings/countdown - both reset at run start.
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
	// Set the moment the LOCAL player's own death is seen (see handleDeath) - deliberately separate
	// from bossRoomCleared/bloodRoomCompleted/etc: those reflect real, independent chat events and can
	// legitimately still end up true even after a personal death (the PARTY can finish a run around a
	// dead/ghosted player) - confirmed as a real, confusing case from a live report: the
	// debug HUD showed "Boss room cleared ✔" with a real timestamp while "Boss room entered ✖" was
	// still red, because the party genuinely did finish the floor while this player never personally
	// got to witness entering/clearing the boss room. Rather than hacking one of those existing flags
	// to lie about what actually happened, this is its own flag - see DungeonDebugHud's "Run failed" line.
	private static boolean localPlayerDied = false;

	/** A puzzle room's lifecycle as tracked here: found (via map color, see {@link DungeonRoomTracker}) but not yet resolved, or resolved one way or the other via chat. */
	public enum PuzzleOutcome {
		PENDING, SOLVED, FAILED
	}

	/** A puzzle's current outcome plus, once resolved, who it was and the puzzle-specific text straight from the SOLVED/FAIL chat line (e.g. "lost Tic Tac Toe! Yikes!") - both empty while PENDING. */
	public record PuzzleResult(PuzzleOutcome outcome, String player, String detail) {
	}

	/**
	 * A frozen snapshot of everything the Score HUD needs to show a "Final Result" panel, taken the
	 * moment {@link #finishRun(SkyMellooConfig)} runs - by then the dungeon tab list/scoreboard can
	 * already be gone or resetting (you're usually already walking back towards the hub), so the panel
	 * reads from this snapshot instead of re-querying live tab-list data that may no longer reflect the
	 * run that just ended.
	 */
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

			// Definitive "you're leaving this Hypixel server instance right now" signal - confirmed
			// directly from a real log ("Sending to server mini17BS..."), verbatim, not guessed. Far
			// more reliable than waiting out the floor-null timeout in tick() (FLOOR_NULL_END_RUN_TICKS,
			// which stays as a fallback for whatever this doesn't catch, e.g. a hard disconnect) -
			// checked ahead of the permission/toggle gate below since ending a stuck run shouldn't
			// depend on which announcements happen to be turned on.
			if (runActive && !leaveEndScheduled && SENDING_TO_SERVER_PATTERN.matcher(colorless).find()) {
				leaveEndScheduled = true;
				// Fallback: Hypixel doesn't always send the re-queue line (see REQUEUE_PATTERN below), so
				// if we're leaving with the floor already confirmed complete but no report sent yet, send
				// it here instead - the floor header alone is good enough confirmation.
				if (dungeonCompleteDetected && !runReportSentAwaitingLeave) {
					dungeonCompleteDetected = false;
					finishRun(SkyMellooConfig.HANDLER.instance());
					runReportSentAwaitingLeave = true;
				}
				boolean reportAlreadySent = runReportSentAwaitingLeave;
				DebugLog.log(DebugLog.Category.DUNGEON, "Leaving this server instance (\"" + colorless + "\") while still marked active - keeping run/HUD visible for 20 more seconds before actually ending tracking"
						+ (reportAlreadySent ? " (report already sent)." : " (no report, run wasn't fully completed)."));
				// reportAlreadySent means finishRun() genuinely ran (either just above, or earlier via
				// the normal completion chat sequence) - a real "completed" signal, not a guess.
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
				// Throttled to once per 30s (not per-message) - this gate is evaluated on every single
				// chat line, and most of Hypixel's own dungeon spam would otherwise flood the debug log.
				long now = System.currentTimeMillis();
				if (now - lastGateLogMillis > 30_000) {
					lastGateLogMillis = now;
					DebugLog.log(DebugLog.Category.DUNGEON, "Chat listener gated off (no relevant toggle on)");
				}
				return;
			}

			if (colorless.equals(DUNGEON_START_MESSAGE)) {
				// Usually redundant with the scoreboard-based auto-start in tick() (which normally
				// fires first) - the plain "!runActive" guard alone used to mean that if runActive was
				// ever left stuck true from a PREVIOUS run failing to end cleanly (confirmed as a real,
				// repeatedly-hit bug this session - the boss-fight false-positive, the missing
				// disband-wording match, etc.), this genuinely NEW dungeon's "Starting in 1 second."
				// would be silently ignored too, carrying over deaths/puzzles/everything from the run
				// that never properly ended. Still guarded against double-firing for the SAME run (this
				// line arriving a tick or two after the scoreboard-based auto-start already handled it)
				// by requiring a few real seconds to have passed since the last actual start.
				if (!runActive || System.currentTimeMillis() - lastRunStartMillis > 3000) {
					startRun();
				}
				return;
			}

			// Ready-up ("X is now ready!") happens BEFORE the run officially starts (before the
			// countdown above), so this has to be checked ahead of the runActive gate below or it'd
			// never be seen at all.
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
				// Puzzle rooms can be reset and retried - Hypixel sends another "PUZZLE FAIL!" line for
				// the retry, treated as the same failure repeating (not double-penalized) when it
				// matches the most recently resolved entry.
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

			// Requires bloodRoomCompleted (set only by the Watcher's VERIFIED farewell line, "You have
			// proven yourself. You may pass.") before trusting this at all - confirmed directly from a
			// real log that excluding "The Watcher" by name specifically wasn't enough: Hypixel has the
			// REAL destination boss (in that case "Scarf") send its own taunt/flavor line DURING the
			// still-ongoing Blood Room fight, which matched this same pattern and fired "entered the
			// boss room" while still visibly in the Blood Room. Excluding bosses by name one at a time
			// as each one gets caught doing this isn't sustainable - requiring the fight to have
			// actually ended first rules out the entire category regardless of which boss talks early.
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

			// Confirmed directly from a real log: the "The Catacombs - Floor X" completion header and
			// the actual end-of-run report both landed at the SAME timestamp, but Hypixel's own
			// "Click HERE to re-queue" line only showed up 2 real seconds later - meaning the header
			// alone is only the START of a multi-stage completion sequence, not its end. Firing our
			// own report on the header made it print interleaved with (or before) Hypixel's own
			// summary. Now waits for the re-queue line specifically before actually sending the report.
			if (DUNGEON_COMPLETE_PATTERN.matcher(colorless).matches()) {
				dungeonCompleteDetected = true;
				// Unlike dungeonCompleteDetected above (deliberately transient - cleared again a couple
				// seconds later once the re-queue line lands), this stays true for the rest of the run -
				// a persistent "boss room cleared" flag for the debug HUD, same lifecycle as
				// bossRoomEntered/bloodRoomCompleted.
				if (!bossRoomCleared) {
					bossRoomClearedMillis = System.currentTimeMillis();
				}
				bossRoomCleared = true;
			}
			if (dungeonCompleteDetected && REQUEUE_PATTERN.matcher(colorless).find()) {
				dungeonCompleteDetected = false;
				// Still 2 more real seconds of Hypixel's own output after this line specifically -
				// confirmed directly from a real report. Delayed rather than sent immediately so our
				// report reliably lands after ALL of Hypixel's own summary, not just most of it.
				// finishRun() captures the final score/report right here (while the data's still live) but
				// deliberately no longer flips runActive/hides the HUD itself anymore - confirmed as a real
				// bug from a live report: the run/HUD used to disappear a few seconds after
				// the boss died, well before anyone had actually left the dungeon. That now only happens
				// once the player actually leaves (see SENDING_TO_SERVER_PATTERN's handler above), plus
				// another 20s on top of that.
				TickDelay.schedule(40, () -> {
					finishRun(config);
					runReportSentAwaitingLeave = true;
				});
			}
		});
	}

	/**
	 * Reads Hypixel's own sidebar scoreboard for floor/cleared%/time every tick (regardless of
	 * whether a run is already known to be active), and watches for party members walking into the
	 * boss room's Nether Portal once one is. The scoreboard read doubles as run-start detection: a
	 * solo dungeon run never shows the party-oriented "Dungeon starts in 1 second." chat countdown
	 * the chat listener above relies on, so without this the score HUD/run tracker just never
	 * turned on for solo runs at all. Detecting the floor line appearing at all, party or not, is
	 * both more robust and no longer chat-wording-dependent.
	 */
	public static void tick(Minecraft client) {
		if (client.level == null) {
			return;
		}
		boolean wasActive = runActive;
		readScoreboard(client);
		if (floor == null) {
			floorNullTicks++;
			// Confirmed directly from a real log: a SINGLE-tick sidebar glitch right as a run actually
			// completes (the "victory"/extra-stats screen briefly not matching the normal Floor/
			// cleared% lines) used to instantly arm sawNoDungeonSinceLastRun, which then let the very
			// next tick's scoreboard-based auto-start fire startRun() again a moment later (floor was
			// back to showing the same completed dungeon, since the party hadn't actually left yet) -
			// silently resetting bossRoomEntered/splusImpossibleAnnounced etc. and letting Necron's own
			// end-of-fight flavor dialogue re-trigger a duplicate "entered the boss room" announcement.
			// Requiring a few consecutive null ticks first filters that one-tick blip out while still
			// reacting fast to a genuine hub return.
			if (floorNullTicks >= FLOOR_NULL_SETTLE_TICKS) {
				if (!sawNoDungeonSinceLastRun) {
					// Just left the dungeon area entirely (not just between runs) - only NOW is it safe
					// to drop ready-up tracking, since that happens before startRun() and would otherwise
					// get wiped by it.
					readyPlayers.clear();
					selfReadyReminderSent = false;
				}
				sawNoDungeonSinceLastRun = true;
			}
			// Never once the boss room's been entered - confirmed directly from a real log this
			// wrongly fired 3 seconds into an active boss fight (Hypixel's sidebar apparently doesn't
			// show the normal Floor/cleared% lines during the fight itself), silently wiping the whole
			// run's state - including bossRoomEntered - mid-fight, which then let the SAME boss's
			// repeated dialogue line re-trigger a duplicate "entered the boss room!" once startRun()
			// re-armed a few seconds later. The boss fight is exactly the one place floor==null for a
			// while is normal and NOT a real dungeon exit.
			if (runActive && !bossRoomEntered && floorNullTicks >= FLOOR_NULL_END_RUN_TICKS) {
				// Left the dungeon without the normal chat-based completion sequence ever firing (an
				// early /leave, disconnect+rejoin, manual hub-return item, etc.) - runActive would
				// otherwise stay stuck true forever, since it's ONLY ever cleared by finishRun(). That
				// froze getEffectiveRoster() (and therefore the Party HUD) on the run's old roster even
				// back in the hub, long after actually leaving both the dungeon and the party. No chat
				// report here - that's only for real completions, see finishRun() - this just silently
				// releases the run-lock so live party membership takes back over immediately.
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

	// Which OTHER roster members are currently visible as REAL, server-confirmed connected players in
	// this Minecraft session's own tab list (Minecraft.getConnection().getOnlinePlayers(), the actual
	// join/leave-packet-driven player list - not Hypixel's fake dungeon-stat tab entries, which never
	// match a real runRoster UUID anyway) - the basis for the backend's mutual party-attestation
	// check (the server cross-checks that both sides of a claimed party relationship actually
	// report seeing each other). See getVisibleTeammates()/DungeonSyncManager.
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
	// Half a block or less counts as "hasn't moved" - small enough that normal combat
	// strafing/knockback never reads as AFK, large enough that idle camera-jitter (there isn't any for
	// a real player position, but floating-point noise near a boundary could exist) doesn't matter.
	private static final double AFK_STILL_THRESHOLD_SQ = 0.5 * 0.5;
	// Fixed threshold for the VISUAL "seems AFK" flag (see isAfk()) - independent of the
	// separately-configurable Auto-Kick threshold below, which is a bigger action and reasonably
	// wants its own (potentially longer) delay.
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
	// Current score minus the score from ~10s ago - the Score HUD shows this as an up/down arrow next
	// to the total, not the raw number. Positive/negative/zero, not just a boolean, so a big recent
	// gain and a barely-there one aren't visually identical.
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

	// 1 = "C" already counted as the baseline, not a milestone - confirmed directly from a real report
	// ("C reached!" fired the instant a run started, which makes no sense since every run starts at C):
	// Skill starts at 20 (0 rooms cleared) and Speed starts at 100 (calculateTimeScore() returns 100
	// unconditionally until the actual time limit is exceeded) with Explore/Bonus both still 0, so
	// EVERY run's very first score is always 20+0+100+0=120, which is already "C" (100-159) before a
	// single room is cleared - that's the true starting point, not "D".
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

	/**
	 * Fires at most once per grade tier per run (tracks the HIGHEST rank ever announced, not just the
	 * last one) - the run's live grade can fluctuate (Speed decaying past the time limit can drop it
	 * back down a tier), and re-announcing "reached B!" every time it climbs back to a tier it already
	 * hit once would be spammy, not a real milestone.
	 */
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

	// Fraction of the floor's OWN time limit that must have elapsed before the pace check is trusted at
	// all - confirmed directly from a real log that a flat 60-second gate was nowhere near enough: 0%
	// secrets found 60 seconds in is completely normal (finding the very FIRST secret at all reliably
	// takes longer than that on most floors, regardless of actual pace), so the check fired almost
	// immediately on nearly every run. Scaling the grace period to the floor's own length instead (35%
	// of a 600s floor is 210s, not 60s) gives a genuinely representative sample before judging pace.
	private static final double MIN_ELAPSED_RATIO_FOR_PACE_CHECK = 0.35;

	/**
	 * Fires once per run, whenever the current secrets-finding RATE (secrets% found per second so far)
	 * first falls behind the rate that would still be needed to hit the floor's required secrets% by
	 * its time limit. Deliberately independent of {@link #checkTimeLimitWarnings} (fixed 60/30/15/10s-
	 * remaining checkpoints, counting down from the END) - this instead triggers off elapsed-time-based
	 * pace math and can fire at any point in the run, so the two systems don't overlap or repeat the
	 * same information.
	 */
	private static void maybeSendSecretsPaceWarning(Minecraft client) {
		if (secretsPaceWarningAnnounced || client.player == null) {
			return;
		}
		// Boss fights can run long, and runActive stays true the whole time (only actually leaving
		// the dungeon ends it) - a borderline pace earlier in the floor could otherwise still fire
		// this MINUTES into the boss fight, well after secrets/the floor time limit stopped meaning
		// anything. Confirmed as a real bug from a live report: "Falling behind on secrets pace"
		// firing while already deep in the boss room, long after the floor itself was done.
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
			// Already past the time limit - checkTimeLimitWarnings()/the S+-impossible announcement
			// already cover that state, nothing extra to say here.
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

	/**
	 * Once cleared% first hits 100 (every room in the run discovered), the best-case ceiling in
	 * {@link #bestPossibleTotal()} stops being a guess - every room/secret that could still swing it
	 * either way is now known. Announces that final "possible" score once, in chat, rather than only
	 * ever showing it silently on the Score HUD.
	 */
	private static void maybeAnnounceRoomsDiscovered(Minecraft client) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		// >= 99.5 (rounds to 100) rather than requiring the literal text to hit exactly "100" - reported
		// directly as never firing across several full-clear runs despite genuinely exploring
		// everything. Hypixel's sidebar "Cleared: X%" is well known to often round down and/or never
		// quite display a clean 100 even on a fully-explored floor (the room-count denominator can
		// include rooms that aren't actually reachable in that generation), so waiting for an exact
		// match could realistically never fire at all on some layouts.
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

	/**
	 * Logs the raw tab-list numbers behind {@link #calculateScore()} (completed rooms, secrets%,
	 * crypts, puzzle count) plus the resulting total, whenever any of them actually changes - lets a
	 * wrong/stuck score be diagnosed directly from chat (which raw input is 0/stale) instead of
	 * guessed at. Change-gated rather than logged every tick so it stays readable during a run.
	 */
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
		floor = null; // recomputed below every call - stale otherwise once we ever leave a dungeon
		if (objective == null) {
			// Was previously only logged on CHANGE from the last state - but "no objective" -> "no
			// objective" forever (this being permanently broken, e.g. Hypixel's sidebar isn't in the
			// SIDEBAR slot at all here) never counts as a change, so it could never actually surface
			// that this is stuck rather than working. Throttled instead, so a genuinely broken read is
			// now visible instead of silently invisible. Only while a run is actually active - this read
			// runs EVERY tick regardless (it's also what detects a run starting), so logging it outside
			// a run would just spam constantly for anyone standing anywhere else in Skyblock at all.
			if (runActive) {
				logNoObjectiveIfDue();
			}
			return;
		}
		// The REAL root cause of "floor/cleared% never match": Hypixel's sidebar rows are fake score
		// entries whose visible text comes from their SCOREBOARD TEAM's prefix+suffix (the classic
		// vanilla trick to get around a score entry's own name-length limits) - entry.display() is
		// null for them and entry.ownerName() is just an internal placeholder (literally single dots
		// in one real capture), never the actual line text. Confirmed directly against Skyblocker's own
		// Utils.java (GitHub, LGPL-3.0, updateScoreboard()) - it reads team prefix+suffix for exactly
		// this reason, not the score entry's own display/owner. Falls back to the old approach only if
		// an entry genuinely has no team, so a differently-built sidebar isn't made WORSE by this.
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
		// Same problem as the "no objective" case above: if the objective IS found but floor/cleared%
		// just never actually match any line (a wrong/stale regex), floor stays null -> null forever
		// and this "cleared% never actually updates" state would otherwise never get logged either.
		// Only while a run is active - plenty of OTHER Skyblock sidebars (hub, other islands) also
		// won't match these patterns, and that's completely expected, not a bug worth logging.
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

	/** Raw, unparsed sidebar title + lines - lets a broken FLOOR_PATTERN/CLEARED_PATTERN be fixed from real current text instead of guessed. */
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

	/**
	 * Boss room entry is tracked at the position level rather than from chat: the entrance is a real Nether
	 * Portal block, and the client already knows every visible player's exact position (that's how
	 * their entity renders at all) - no server cooperation needed.
	 * <p>
	 * The portal doesn't exist until The Watcher's Blood Room fight is over, and it can appear while
	 * the local player is elsewhere on the floor - scanning around the LOCAL player (an earlier
	 * version of this) would miss it entirely in that case. Instead this scans around the Blood
	 * Room's own location, found independently off the dungeon map by {@link DungeonRoomTracker}
	 * (falling back to the local player's position only if that hasn't resolved yet), and only starts
	 * trying once {@link #watcherEncountered} confirms the fight has actually begun.
	 * <p>
	 * "Entered" is real hitbox overlap ({@link AABB#intersects(BlockPos)}) against a confirmed portal
	 * block, not just a fixed feet/waist/head blockPosition guess - someone merely brushing past the
	 * doorway shouldn't count. Only works for members within tracking range when they walk through -
	 * same limitation as the face icons/HP lookups elsewhere.
	 */
	private static void trackBossRoomEntry(Minecraft client) {
		if (client.player == null) {
			return;
		}
		// Entrance's Watcher fight (confirmed real, see maybeSendPreBossScoreWarning) never spawns an
		// actual boss-room portal afterward - Entrance just ends there. Scanning for one would only
		// ever find nothing, so skip it outright rather than waste a scan every 20 ticks for the rest
		// of the run.
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
		// Diagnostic only - confirmed directly from a real report that neither the local player nor a
		// teammate ever triggered a "Boss room entry detected"/"Ignored..." line despite the boss fight
		// clearly happening (dialogue, defeat, score all landed normally), meaning isReallyInPortal()
		// never matched at all for anyone - either the scanned portalBlocks locked onto the WRONG real
		// portal (e.g. a leftover Blood Room entrance one within scan radius) or a genuine precision
		// miss. No way to tell which from a chat log alone with no coordinates - this logs the local
		// player's own live distance to whatever WAS found, periodically, so the next occurrence has
		// real numbers to diagnose from instead of another guess.
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
				// Used to also require getRoomTypeAt(...) != BLOOD here as corroboration, added
				// defensively against an earlier false-positive report that (per real log evidence at
				// the time) actually came from the CHAT-pattern path, not this one. Confirmed directly
				// from a real log this guard was itself now causing false NEGATIVES: a player's real,
				// hitbox-verified portal touch 28 blocks away from the Blood Room's own position still
				// read back as "type=BLOOD" and got thrown out. The boss room sits outside Hypixel's
				// normal 32-block room grid our map-color reader was built for, so asking it "what room
				// is this" for a boss-room position was never reliable to begin with - it can wrap back
				// onto the Blood Room's own map tile. A hitbox actually intersecting a real, scanned
				// Nether Portal block (see portalBlocks) is already solid physical proof on its own.
				DebugLog.log(DebugLog.Category.DUNGEON, "Boss room entry detected for " + name + " at " + player.blockPosition()
						+ " (portal blocks=" + portalBlocks + ")");
				playersEnteredBossRoom.add(name);
				boolean isFirstEntrant = playersEnteredBossRoom.size() == 1;
				if (!bossRoomEntered) {
					bossRoomEnteredMillis = System.currentTimeMillis();
				}
				bossRoomEntered = true;
				// Tracked purely for the end-of-run report - independent of any live chat warning.
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

	/**
	 * Fires once per run, the moment the Blood Room fight ends (The Watcher's farewell line) - as soon
	 * as it's known the boss portal is about to spawn, while the party can still decide to keep
	 * exploring instead of going in (rather than only once someone's hitbox is already touching the
	 * portal, too late to reconsider since Hypixel's portal teleports on contact with no delay).
	 */
	private static void maybeSendPreBossScoreWarning(Minecraft client) {
		if (client.player == null) {
			return;
		}
		// Entrance DOES have a real Watcher fight (confirmed directly from a real log - full dialogue,
		// "Defeated The Watcher in..."), but there's no actual boss room/portal afterward to warn
		// against entering - Entrance just ends there, no "go in or not" decision exists. "Don't go in
		// yet" is meaningless on a floor with nothing to go into.
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
			// S+ was already confirmed impossible earlier this run for a DIFFERENT reason (a puzzle
			// fail, a death, or the time limit) - confirmed directly from a real log: "Don't go in yet"
			// still fired here a full 2 minutes after that announcement, which actively misleads (it
			// implies waiting could still help, and that entering now is what costs the points, when
			// the real cause happened well before the boss room even existed). Say so plainly instead.
			String alreadyGoneText = config.dungeonPreBossScoreWarningAlreadyImpossibleTemplate.replace("{score}", String.valueOf(score));
			sendDungeonMessage(client, alreadyGoneText, config.dungeonPreBossScoreWarningDelivery);
			return;
		}
		String text = config.dungeonPreBossScoreWarningTemplate.replace("{score}", String.valueOf(score));
		sendDungeonMessage(client, text, config.dungeonPreBossScoreWarningDelivery);
	}

	/**
	 * Sends a dungeon-tracker chat message either locally (default) or for real via {@code /pc}, per
	 * that message's OWN delivery setting - death/boss-room/portal-warning/join-announcement each have
	 * their own independent LOCAL-or-PARTY choice rather than one shared toggle for all of them, so
	 * e.g. death spam can stay local while a boss-room announcement goes to the whole party. Public so
	 * {@link PartyJoinWatcher}'s join announcement can reuse the exact same logic instead of
	 * duplicating it.
	 * <p>
	 * Falls back to LOCAL regardless of {@code delivery} if {@link PartyTracker#isInParty()} says
	 * there's no party right now - {@code /pc} with no party just fails server-side (Hypixel replies
	 * with its own error), silently swallowing the message instead of showing it anywhere. Confirmed
	 * directly from testing solo: PARTY selected still only showed the message locally, because the
	 * /pc attempt itself never actually delivered - this makes that the deliberate, documented
	 * behavior instead of a silent failure that looks like a bug.
	 */
	// Minecraft caps a typed/sent command at 256 characters total - "pc " (3) + the "[SkyMelloo] "
	// prefix (12) leaves headroom short of that, so long content (e.g. the "all" stat's full summary)
	// silently got truncated or rejected outright by the server rather than actually reaching the
	// party. Chunked at a safe margin below the real limit, one /pc per chunk, staggered the same way
	// a party-wide sequential announce already is elsewhere (see ANNOUNCE_STAGGER_TICKS in
	// SkyMellooClient) so consecutive chunks don't trip Hypixel's own rate limit either.
	private static final int PARTY_CHAT_SAFE_CHUNK_LENGTH = 220;
	private static final int PARTY_CHAT_CHUNK_STAGGER_TICKS = 20; // 1s

	public static void sendDungeonMessage(Minecraft client, String text, String delivery) {
		sendDungeonMessage(client, text, delivery, true);
	}

	/**
	 * @param leaderOnlyForRelay Only matters for the "PARTY SM" delivery option. True (the default via
	 * the 3-arg overload above) for the vast majority of callers here: a shared fact about the run/party
	 * that EVERY SM client in the party independently detects and would otherwise each relay their own
	 * duplicate copy of (room discovered, boss room entered, grade milestone, a death - deaths are
	 * server-broadcast to everyone, not just the player who died, run summary, etc.) - restricting the
	 * actual relay send to just the party leader means the party sees exactly one copy. False for the
	 * one deliberate exception, {@link com.melloo.skymelloo.client.combat.DeathRecapManager}'s party
	 * announce: a death recap is inherently personal (only the player who died would ever generate
	 * their OWN recap text), never duplicated, and leader-gating it would wrongly suppress it entirely
	 * whenever the person who died isn't the party leader.
	 */
	public static void sendDungeonMessage(Minecraft client, String text, String delivery, boolean leaderOnlyForRelay) {
		if (client.player == null) {
			return;
		}
		if ("PARTY SM".equalsIgnoreCase(delivery)) {
			if (!leaderOnlyForRelay || com.melloo.skymelloo.client.party.PartyTracker.isLocalPlayerLeader()) {
				client.player.sendSystemMessage(ChatUtil.prefixed(text));
				com.melloo.mellooessentials.client.social.RelayChatManager.sendPartyAnnouncement(client, text);
			}
			// Non-leader clients (when leaderOnlyForRelay) stay silent - only the leader's client
			// actually sends, so the party sees one copy instead of one per SM user present.
			return;
		}
		if ("PARTY".equalsIgnoreCase(delivery) && com.melloo.skymelloo.client.party.PartyTracker.isInParty()) {
			// § codes don't survive /pc as a command string - confirmed directly from a real
			// screenshot: Hypixel strips the § itself but leaves its format-code letter behind as
			// literal text (e.g. "§b[SkyMelloo]§r" arrived as literal "b[SkyMelloo]r"), which reads as
			// garbled nonsense. ChatUtil.partyPrefixed strips every §-code and uses a plain prefix instead.
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

	/** Word-wraps {@code text} into chunks that fit safely within a single /pc command - splits on spaces so a word is never cut mid-way, one exception being a single word longer than the whole limit (falls back to a hard cut for just that word, better than an infinite loop). */
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

	/**
	 * See the diagnostic comment at the call site in {@link #trackBossRoomEntry} - throttled to once
	 * every ~5s so it's usable without flooding the log. Every tracked roster player's distance to
	 * their OWN nearest portal block (not one shared fixed point - the boss portal is a multi-block
	 * structure, see portalBlocks), not just the local player, so a teammate-only entry that never
	 * trips isReallyInPortal() still leaves real numbers behind to diagnose from.
	 */
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
		// Exact hitbox intersection reliably catches our OWN player (locally predicted every client
		// tick, so the walk-through is smooth), but a teammate's position only ever updates from
		// server packets - confirmed directly from a real log where a teammate's boss dialogue fired
		// (proving they'd gone through) while isReallyInPortal() never once matched for anyone that
		// run. A tight distance fallback catches "clearly standing at the portal" even on a tick
		// where no received position sample happened to overlap the 1-block hitbox exactly.
		for (BlockPos portalPos : portalBlocks) {
			if (portalPos.distToCenterSqr(player.position()) <= PORTAL_ENTER_DISTANCE_SQ) {
				return true;
			}
		}
		return false;
	}

	/** Whether any known boss-room-portal block is within {@link #NEAR_PORTAL_DISTANCE_SQ} of the given position - used by the Party HUD's "near portal" indicator. */
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

	/** Inverse of {@link #parseElapsed} - whole seconds as "Xm Ys", for the end-of-run report. */
	private static String formatElapsed(int seconds) {
		return (seconds / 60) + "m " + (seconds % 60) + "s";
	}

	/**
	 * Clears the debug-HUD-relevant "what happened this run" flags as soon as the run actually ENDS
	 * (any way - completed, or left/disconnected early) - these used to only ever get cleared by
	 * {@link #startRun()} at the start of the NEXT run, so a failed run's stale
	 * bossRoomEntered/bossRoomCleared/etc. state kept showing on the debug HUD the whole time back in
	 * the hub, looking like it was still (incorrectly) tracking the old run. Deliberately NOT called
	 * from inside {@link #finishRun} itself - that method reads several of these fields (bossRoomEntered
	 * etc.) while building the end-of-run report, so it's called right after finishRun() returns at its
	 * one call site instead, once nothing further needs the old values.
	 */
	private static void resetBossRoomDisplayFlags() {
		bossRoomEntered = false;
		bossRoomCleared = false;
		bloodRoomCompleted = false;
		localPlayerDied = false;
		// Missing here (only ever reset in startRun(), i.e. at the START of the NEXT run) was the
		// actual bug - Blood Room "entered"/"key obtained" reuses this same flag (see
		// isBloodRoomEntered's own doc comment), so it stayed
		// true the whole gap between one run ending and the next one starting.
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
		// PartyTracker's cached membership can be a few seconds stale right as a run begins (e.g. just
		// switched parties shortly before queueing) - request a fresh packet right now so it catches up
		// as soon as possible, same idea as forceRefreshAll() below for AP/gear data.
		PartyTracker.requestRefreshNow();
		runRoster = new LinkedHashSet<>(PartyTracker.getMembers());
		if (client.player != null) {
			runRoster.add(client.player.getUUID());
		}
		DebugLog.log(DebugLog.Category.DUNGEON, "Run started (floor=" + floor + ", roster=" + runRoster.size() + ")");
		// Readiness (see DungeonReadiness) needs CURRENT gear/AP, not a stale cached reading from
		// before someone swapped armor - force a fresh fetch for everyone right as the run begins.
		com.melloo.skymelloo.client.party.PartyHudManager.forceRefreshAll();
		// Reset here, not just implicitly whenever tick() next sees a non-null floor - startRun() can
		// fire straight from the "Dungeon starts in 1 second." chat line, a moment BEFORE the
		// scoreboard's own floor line actually populates. Without this, floorNullTicks could already be
		// sitting way past FLOOR_NULL_END_RUN_TICKS from time spent standing in the hub between runs,
		// and the very next tick would immediately (and wrongly) end this run again before it even
		// really started - confirmed directly from a real report: the first run of a session worked,
		// every run after it silently failed to track at all until the client was restarted (the only
		// thing that reset this counter back to 0).
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
		// clearedPercent/elapsedSeconds ARE reset here now, unlike an earlier version of this method -
		// confirmed directly from a real log that leaving them stale across a run boundary let a
		// freshly-reset one-shot flag (e.g. secretsPaceWarningAnnounced) immediately re-fire using the
		// PREVIOUS run's leftover numbers, before readScoreboard() had a chance to populate real ones
		// for the new run. A one-tick flicker back to 0 here is harmless (nothing meaningful renders
		// that fast); silently reusing a stale number from a run that already ended is not.
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
			return; // never auto/prompt-kick yourself
		}
		if (!config.dungeonDeathKickEnabled || count <= config.dungeonDeathKickThreshold) {
			return;
		}
		// Only the actual party LEADER can /party kick at all on Hypixel - skip silently rather than
		// send a command that would just fail server-side for anyone else.
		if (!com.melloo.skymelloo.client.party.PartyTracker.isLocalPlayerLeader()) {
			return;
		}
		client.player.connection.sendCommand("party kick " + name);
		client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.chat.dungeon_report.death_auto_kick", name, count, config.dungeonDeathKickThreshold)));
	}

	/**
	 * Best-effort position snapshot at the moment of death, for the "X" death marker on the website's
	 * map (both live and replay), labeled with the player's name and which death number this is for
	 * them. The dying player's entity typically
	 * still resolves right after their death message (they become a ghost, not despawn), but this is
	 * deliberately tolerant of it not being found - a death with no position still records the
	 * name/death-number, just with a null spot, rather than dropping the marker entirely.
	 */
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

	/** Every death this run so far, with position/death-number - see DeathMarker's own doc comment. */
	public static List<DeathMarker> getDeathMarkers() {
		return deathMarkers;
	}

	/** Current per-player death count for this run - used by {@link com.melloo.skymelloo.client.party.PartyHud}'s "full" display mode. */
	public static int getDeaths(String username) {
		return deaths.getOrDefault(username, 0);
	}

	/** Whether this player has sent "ready up" for the current dungeon (persists through the whole run, not just the ready-check screen). */
	public static boolean isReady(String username) {
		return readyPlayers.contains(username);
	}

	/**
	 * Fires at most once per ready-check cycle (reset alongside {@link #readyPlayers} - see the clear()
	 * call in {@link #tick}) - the moment the LOCAL player is the only one left who hasn't readied up
	 * yet, nudges them via the action bar instead of relying on teammates spamming chat. Needs live
	 * party membership resolved via {@link com.melloo.skymelloo.client.party.PartyHudManager} - this
	 * happens before a run starts, so {@link #runRoster} doesn't exist yet.
	 */
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
				// Someone else still isn't ready either - not specifically on the local player yet.
				return;
			}
		}
		selfReadyReminderSent = true;
		client.gui.setOverlayMessage(Component.literal(config.dungeonSelfReadyReminderText), false);
	}

	/**
	 * Who to track for HUD/boss-room purposes right now - the roster locked in at run start while a
	 * run is active (so someone leaving the party mid-run doesn't drop out of tracking), otherwise
	 * live party membership. Used by {@link com.melloo.skymelloo.client.party.PartyHudManager}.
	 */
	public static Set<UUID> getEffectiveRoster() {
		return runActive ? runRoster : PartyTracker.getMembers();
	}

	public static boolean isRunActive() {
		return runActive;
	}

	public static String getFloor() {
		return floor;
	}

	/** Real wall-clock start time of the CURRENT run (set fresh at the top of every {@link #startRun()}) - see the payload field's own comment on why this exists. */
	public static long getRunStartedAtMillis() {
		return lastRunStartMillis;
	}

	/** Debug HUD flags - see DungeonDebugHud. Blood Room "entered" reuses watcherEncountered, since the Watcher only ever speaks once you're physically inside it. */
	public static boolean isWitherDoorOpened() {
		return witherDoorOpened;
	}

	/** One entry per Wither Key obtained this run, in pickup order - true once that door's been opened. Copied out so the debug HUD can't mutate the live list. */
	public static List<Boolean> getWitherDoors() {
		return new java.util.ArrayList<>(witherDoorKeys);
	}

	/** Wall-clock timestamp each {@link #getWitherDoors()} entry was opened (0 = not opened yet), same index/order - for the debug HUD's per-event elapsed time. */
	public static List<Long> getWitherDoorOpenedMillis() {
		return new java.util.ArrayList<>(witherDoorOpenedMillis);
	}

	/** 0 if not opened yet this run. */
	public static long getBloodDoorOpenedMillis() {
		return bloodDoorOpenedMillis;
	}

	/** 0 if the Blood Room hasn't been cleared yet this run. */
	public static long getBloodRoomClearedMillis() {
		return bloodRoomCompletedMillis;
	}

	/** 0 if the boss room hasn't been entered yet this run. */
	public static long getBossRoomEnteredMillis() {
		return bossRoomEnteredMillis;
	}

	/** 0 if the boss room hasn't been cleared yet this run. */
	public static long getBossRoomClearedMillis() {
		return bossRoomClearedMillis;
	}

	public static boolean isBloodRoomEntered() {
		return watcherEncountered;
	}

	/** Mirrors {@link #isWitherDoorOpened()} but for the Blood Room's own door - see DOOR_OPENED_PATTERN("BLOOD"). */
	public static boolean isBloodDoorOpened() {
		return bloodDoorOpened;
	}

	/** Whether the real "X has obtained Blood Key!" chat line has fired this run - see BLOOD_KEY_OBTAINED_PATTERN. */
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

	/** Whether the LOCAL player personally died this run - see the field's own doc comment on why this is tracked separately from bossRoomCleared/etc. */
	public static boolean hasLocalPlayerDied() {
		return localPlayerDied;
	}

	/**
	 * Whether EVERY current roster member has been confirmed dead this run - covers the case
	 * {@link #localPlayerDied} alone can't (the local player personally survives, but the rest of the
	 * party wiped) - a full party wipe. Catacombs
	 * has no mid-run respawn, so one death entry per name reliably means "still a ghost", not
	 * "was dead, then came back". Deliberately conservative: a roster member whose real username
	 * hasn't resolved yet (still showing PartyHudManager's placeholder) can never match a death
	 * entry, so this only ever reports true once every member is genuinely confirmed, never guesses.
	 */
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

	/** "completed" | "wiped" | "left", or {@code null} if the run hasn't ended yet (or hasn't ended since the last one started) - see {@link #lastRunEndReason}. */
	public static String getRunEndReason() {
		return lastRunEndReason;
	}

	/** Whether the Score HUD's post-run "Final Result" panel should currently be shown - see {@link #lastFinalResult}/{@link SkyMellooConfig#dungeonScoreFinalResultDurationSeconds}. False once a new run starts (isRunActive() takes priority over this in the HUD regardless). */
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

	/** The frozen snapshot backing {@link #isShowingFinalResult()} - null until a run has ever finished. */
	public static FinalResult getLastFinalResult() {
		return lastFinalResult;
	}

	/** Live cleared-rooms percentage from Hypixel's own scoreboard - the Explore score component IS room-based, see {@link #calculateScore()}. */
	public static double getClearedPercent() {
		return clearedPercent;
	}

	/** Total deaths across the whole run so far, all players combined. */
	public static int getTotalDeaths() {
		return deaths.values().stream().mapToInt(Integer::intValue).sum();
	}

	/** Chronological puzzle outcomes this run - see {@link #puzzleOutcomes}. */
	public static List<PuzzleResult> getPuzzleOutcomes() {
		return puzzleOutcomes;
	}

	/**
	 * Called by {@link DungeonRoomTracker} when the player's current room is newly detected as a
	 * Puzzle room (via dungeon-map pixel color), before its outcome is known. Shown as a "pending"
	 * block in {@link DungeonScoreHud} until {@link #resolvePuzzleOutcome} flips it once a PUZZLE
	 * SOLVED/FAIL chat line lands.
	 */
	public static void markPuzzleRoomFound() {
		if (!runActive) {
			return;
		}
		puzzleOutcomes.add(new PuzzleResult(PuzzleOutcome.PENDING, "", ""));
		// The specific puzzle type (e.g. "Water Board") if Skyblocker's own room database is also
		// installed and has already matched this room - our own map-color reading only knows it's SOME
		// puzzle, never which one, until it resolves via chat.
		SkyblockerBridge.RoomSecrets roomSecrets = SkyblockerBridge.getCurrentRoomSecrets();
		String nameSuffix = roomSecrets != null && roomSecrets.roomName() != null ? " (" + roomSecrets.roomName() + ")" : "";
		DebugLog.log(DebugLog.Category.DUNGEON, "Puzzle room detected via map color - pending outcome" + nameSuffix);
	}

	/**
	 * Flips the most recent PENDING entry to its real outcome, or appends the outcome directly if
	 * there's no pending entry to resolve (e.g. the player wasn't holding/near the dungeon map when
	 * the room was entered, so {@link DungeonRoomTracker} never saw it) - keeps the run report/score
	 * HUD accurate either way, just without a pending phase having been shown first in that case.
	 */
	private static void resolvePuzzleOutcome(PuzzleOutcome outcome, String player, String detail) {
		PuzzleResult result = new PuzzleResult(outcome, player, detail);
		int lastIndex = puzzleOutcomes.size() - 1;
		// Same (player, detail) as the entry already sitting there - a reset-and-retried puzzle
		// resolving again (see isSameAsLastFail) - updates in place instead of appending a second,
		// visually duplicate line for what's really the same puzzle attempt.
		if (lastIndex >= 0 && (puzzleOutcomes.get(lastIndex).outcome() == PuzzleOutcome.PENDING || isSameAsLastFail(player, detail))) {
			puzzleOutcomes.set(lastIndex, result);
		} else {
			puzzleOutcomes.add(result);
		}
		DebugLog.log(DebugLog.Category.DUNGEON, "Puzzle " + outcome + " (" + player + "): " + detail);
	}

	/** Whether the most recently resolved puzzle entry was already a FAILED result for this same (player, detail) - see the PUZZLE_FAIL_PATTERN handler above. */
	private static boolean isSameAsLastFail(String player, String detail) {
		int lastIndex = puzzleOutcomes.size() - 1;
		if (lastIndex < 0) {
			return false;
		}
		PuzzleResult last = puzzleOutcomes.get(lastIndex);
		return last.outcome() == PuzzleOutcome.FAILED && last.player().equalsIgnoreCase(player) && last.detail().equals(detail);
	}

	/**
	 * Called every tick (from {@link DungeonRoomTracker#tick}) while a run is active - catches puzzle
	 * rooms solved on a retry without Hypixel sending a fresh "PUZZLE SOLVED!" chat line, via
	 * Skyblocker's {@link SkyblockerBridge#getCurrentRoomClearState()} (mirrors the real dungeon-map
	 * checkmark). Only corrects the DISPLAYED outcome - deliberately does NOT touch
	 * {@link #puzzlesFailed}/{@link #puzzlesSolved}, since the Skill-score penalty for the original
	 * fail is permanent on Hypixel even after a successful retry.
	 */
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

	/**
	 * The puzzle-specific text after a player's name in a PUZZLE SOLVED/FAIL line (e.g. "lost Tic Tac
	 * Toe! Yikes!"), used instead of hardcoding every puzzle's wording. Looks up the LAST occurrence
	 * of {@code name} in the line rather than the first, since Lunar Client's "chat_heads" mod
	 * prepends a "[Name head]" marker directly in front of real usernames in chat text - the first
	 * occurrence of the name can be inside that marker, with the real name (and the actual reason
	 * text after it) only starting at the second occurrence.
	 */
	private static String extractPuzzleReason(String colorless, String name) {
		int idx = colorless.lastIndexOf(name);
		if (idx < 0) {
			return "";
		}
		return colorless.substring(idx + name.length()).trim();
	}

	/** Result of {@link #calculateScore()} - the REAL current estimate at this instant, not a ceiling (see {@link #calculateScore()}). */
	public record ScoreEstimate(int skill, int explore, int speed, int bonus, int total, String grade) {
	}

	/**
	 * Live dungeon score, ported verbatim from SkyblockerMod/Skyblocker's DungeonScore.java
	 * (GitHub, LGPL-3.0) - a REAL current estimate using actual completed-rooms/secrets%/puzzle-
	 * state/crypts (from the dungeon tab list, see {@link DungeonTabList}), not the flat-ceiling
	 * approximation an earlier version of this file used.
	 * <p>
	 * Skill = 20 + clamp(completedRoomScore(0-80) - puzzlePenalty(10/incomplete) - deathPenalty(2/death), 0, 80).
	 * Explore = completedRoomScore(0-60) + secretsScore(0-40, scaled against the floor's required %).
	 * Speed = 100, decaying in tiers once elapsed time exceeds the floor's time limit.
	 * Bonus = crypts(0-5) + mimicScore(0/2) + princeScore(0/1). Entrance floor scores at 70% of the
	 * summed total, exactly like every other floor requirement, per Hypixel's own formula.
	 * <p>
	 * Not ported: Mayor Paul's EZPZ +10 Bonus perk (would need a SkyBlock mayor-API lookup this mod
	 * has no other use for) and the "first death had a Spirit Pet" -1 Skill exemption (would need a
	 * profile API lookup per death) - both omitted rather than guessed, so Skill/Bonus here are, at
	 * worst, very slightly pessimistic (never overestimate).
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

	/**
	 * The score value actually shown/announced right now - Skyblocker's live score when available,
	 * ALWAYS (no opt-out toggle),
	 * otherwise our own estimate (also still the only source for the Skill/Explore/Speed/Bonus
	 * breakdown, which Skyblocker doesn't expose individually). Used by every chat announcement that
	 * includes a {score} placeholder, so the number a player sees in chat always matches what the
	 * Score HUD is showing, rather than the two silently disagreeing.
	 */
	static int currentDisplayedScore() {
		Integer skyblockerScore = SkyblockerBridge.getScore();
		if (skyblockerScore != null) {
			return skyblockerScore;
		}
		return calculateScore().total();
	}

	/**
	 * Picks the boss-room-entered wording based on the score AT THIS MOMENT - the low-score variant
	 * only when THIS entry is actually the reason S+ is at risk: {@code isFirstEntrant} (someone else
	 * walking through an already-open portal isn't new information, don't repeat the same "score too
	 * low" blame for every single person) and {@code !splusImpossibleAnnounced} (confirmed directly
	 * from a real log: the low-score variant still fired a full 2 minutes after "S+ is no longer
	 * possible" had already been announced for a completely different reason - a puzzle fail, not the
	 * boss timing - which misattributes the actual cause and reads as nonsensical once you already
	 * knew S+ was gone).
	 */
	private static String bossRoomMessageFor(SkyMellooConfig config, String player, boolean isFirstEntrant) {
		int score = currentDisplayedScore();
		if (score < 300 && isFirstEntrant && !splusImpossibleAnnounced) {
			return config.dungeonBossRoomLowScoreMessageTemplate.replace("{player}", player).replace("{score}", String.valueOf(score));
		}
		return config.dungeonBossRoomMessageTemplate.replace("{player}", player);
	}

	/** Same S+/S/A/B/C/D thresholds {@link #calculateScore()} uses, exposed standalone so a score from another source (e.g. {@link SkyblockerBridge#getScore()}) can be graded the same way. */
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

	// Ascending, paired with the grade each one unlocks - same thresholds as gradeForTotal above,
	// just walkable in order for nextGrade() below instead of checked top-down.
	private static final double[] GRADE_THRESHOLDS = {100, 160, 230, 269.5, 300};
	private static final String[] GRADE_LABELS = {"C", "B", "A", "S", "S+"};

	/** How many more total-score points until the next grade up, and what it's called. {@code null} if already at the top grade (S+) - there's no "next" one. */
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

	/**
	 * Best-case total assuming everything still open goes perfectly from here: full rooms/secrets
	 * (Explore maxed), max crypts/mimic/prince (Bonus maxed), no more puzzle fails or deaths beyond
	 * what's ALREADY happened (Skill uses the real {@link #puzzlesFailed} count, not the tab list's
	 * "incomplete" symbol which pessimistically also counts still-open puzzles as failed - those are
	 * assumed solvable here). Speed uses today's actual value since it can only decay further as time
	 * passes, never improve - this run's current Speed score IS its own best-case ceiling. Used only to
	 * answer "is S+ still reachable at all", not shown as a real score anywhere.
	 */
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

	/**
	 * {@link #bestPossibleTotal()} is entirely our OWN independent component estimate (skill/explore/
	 * speed/bonus) - it can end up reading slightly BELOW the actual current total when
	 * {@link #currentDisplayedScore()} is instead showing Skyblocker's own, separately-calculated live
	 * score (confirmed directly from a real screenshot: "Current: 310" next to "Possible: 308", an
	 * impossible result on its face). The ceiling can never truly be lower than where you already
	 * are, so this floors it at whatever's actually currently displayed rather than trusting our own
	 * formula blindly over reality.
	 */
	private static int effectiveBestPossible() {
		return Math.max(bestPossibleTotal(), currentDisplayedScore());
	}

	/** Public accessor - the Score HUD shows this alongside the current total so the gap between "where you are" and "the absolute ceiling from here" is visible at a glance. */
	public static int getBestPossibleScore() {
		return effectiveBestPossible();
	}

	/** Whether S+ (300 score) is still mathematically reachable, even in the best case for everything not yet locked in - see {@link #effectiveBestPossible()}. */
	public static boolean isSPlusStillPossible() {
		return effectiveBestPossible() >= 300;
	}

	/**
	 * How many MORE seconds (from right now) can still pass while S+ stays mathematically reachable,
	 * same best-case assumption as {@link #bestPossibleTotal()} for everything except Speed (Skill/
	 * Explore/Bonus all maxed out, permanent penalties aside) - {@code null} if the current floor has
	 * no time limit at all, or if S+ is already impossible for a reason more time can't fix (not
	 * enough ceiling even at a perfect Speed score of 100).
	 */
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
			// Not even a full Speed=100 is needed - time genuinely isn't the constraint right now.
			return Integer.MAX_VALUE;
		}
		int requiredSpeedRaw = (int) Math.ceil(requiredSpeedScaled / scale);
		if (requiredSpeedRaw > 100) {
			// Impossible even with a perfect Speed score - not something more time can fix.
			return null;
		}
		// calculateTimeScore() is monotonically non-increasing once past the time limit - walking
		// forward from now until it drops below what's needed is simpler and less error-prone than
		// inverting the piecewise formula by hand, and this is only ever called on demand (a command/
		// occasional HUD refresh), never per-tick, so the cost of a scan is a non-issue. Capped well
		// beyond any realistic dungeon length as a hard safety bound, not an expected case.
		int candidate = Math.max(elapsedSeconds, timeLimit);
		int hardCap = candidate + 20_000;
		while (candidate < hardCap && calculateTimeScore(candidate) >= requiredSpeedRaw) {
			candidate++;
		}
		return Math.max(0, candidate - 1 - elapsedSeconds);
	}

	/** Permanent Skill-score penalties applied so far this run - puzzle fails and deaths, both irreversible (unlike still-open rooms/secrets/crypts, which just count toward the ceiling instead). */
	public record ScorePenalties(int puzzleFailPenalty, int deathPenalty) {
	}

	public static ScorePenalties currentPenalties() {
		return new ScorePenalties(puzzlesFailed * 10, getDeathScorePenalty());
	}

	/**
	 * Fires at most ONCE per run, the moment S+ first becomes unreachable (see
	 * {@link #isSPlusStillPossible()}) - called after a puzzle fail or a death, both real permanent
	 * score penalties. Deliberately NOT tied to the floor's time limit simply passing (see
	 * {@link #checkTimeLimitWarnings}) - S+ is still genuinely reachable after that checkpoint, so
	 * treating it as an impossible-trigger read as a false alarm. Doesn't repeat on every subsequent
	 * fail/death once already announced.
	 */
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

	/**
	 * The counterpart to {@link #maybeAnnounceSPlusImpossible} - once S+ was confirmed impossible,
	 * more crypts/secrets/rooms found afterward can push {@link #effectiveBestPossible()} back over
	 * 300 (it's a live ceiling, not a permanent verdict). Fires at most ONCE per run, same as the
	 * impossible warning, and specifically requires the impossible warning to have already fired first
	 * - there's nothing to "come back" from otherwise. Both flags are one-shot and never reset except
	 * at {@link #startRun()}, so the estimate flip-flopping near the 300 boundary afterward can send at
	 * most 2 messages total this run ("impossible" once, then "back" once) and never a third -
	 * deliberately built this way so it can never come twice, per explicit instruction.
	 */
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

	/**
	 * Chat checkpoints as the floor's time limit approaches, plus a personal on-screen countdown for
	 * the last 10 seconds. Which checkpoints are active is a single cascading choice
	 * ({@link SkyMellooConfig#dungeonTimeLimitWarningStart}): picking "30" arms 30s and 10s but not
	 * 60s; picking "60" arms all four. 10s is always the lowest tier and always included once enabled,
	 * matching how the setting was asked for. Also detects the limit actually passing (0 remaining) to
	 * feed {@link #maybeAnnounceSPlusImpossible}. Never fires once the boss room's been entered -
	 * confirmed directly from a real log/screenshot: "hurry or S+ may no longer be possible" fired
	 * twice, 3 real seconds apart (60s and 30s remaining), while already mid-boss-fight. Two things
	 * are wrong with that once you're in there: hurrying can't clear more rooms anymore, so the
	 * warning is pointless, AND Hypixel's sidebar stops reliably showing "Time Elapsed" during the
	 * fight (confirmed elsewhere this session), so the elapsed time this reads updates sparsely and
	 * unevenly - which is exactly why two checkpoints landed almost back-to-back instead of ~30s
	 * apart. Whether S+ was still reachable was already decided (and warned about, if not) the moment
	 * the boss room was entered - see maybeSendPreBossScoreWarning/earlyBossRoomEntryScore.
	 */
	// Set the first time checkTimeLimitWarnings runs after bossRoomEntered flips true - lets that ONE
	// call still fire any checkpoint that was already genuinely due (using elapsedSeconds as of right
	// now, before the sidebar stops reliably updating it), rather than a blanket "never again" cutting
	// off a checkpoint that simply hadn't had a chance to fire yet on a fast run that reached the boss
	// room with real time still on the clock. Never runs a second time after that, which is what
	// actually matters for the original bug (stale/jumpy elapsedSeconds mid-fight firing checkpoints
	// out of order).
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
				// Deliberately NOT calling maybeAnnounceSPlusImpossible here - the per-floor time limit is
				// just a Speed checkpoint, not a hard cutoff (S+ stays reachable after it passes, Speed
				// just decays gradually), so tying the impossible-warning to this event would be a false alarm.
			}
			return;
		}
		if (!config.dungeonTimeLimitWarningEnabled) {
			return;
		}
		// Checkpoints fire based on how much longer S+ can ACTUALLY stay reachable
		// ({@link #getExtraSecondsForSPlus}), not the floor's raw nominal time limit - otherwise the
		// warning can fire even when hurrying no longer matters for the score.
		Integer extraSeconds = getExtraSecondsForSPlus();
		// null: S+ already impossible for a non-time reason (maybeAnnounceSPlusImpossible already
		// covers that separately - a "hurry" message here would be actively wrong once it's already
		// gone). MAX_VALUE: Speed isn't even the constraint right now - S+ stays safe no matter how
		// much more time passes, so there is nothing to warn about.
		if (extraSeconds != null && extraSeconds != Integer.MAX_VALUE) {
			int startCheckpoint = switch (config.dungeonTimeLimitWarningStart) {
				case "60" -> 60;
				case "15" -> 15;
				case "10" -> 10;
				default -> 30;
			};
			// Unlike the old floor-par-time-based `remaining`, extraSeconds can drop abruptly (a puzzle
			// fail/death can shrink the margin from e.g. 200s to single digits in one tick) instead of
			// counting down smoothly one second at a time - so several checkpoints can newly qualify in
			// the SAME call. Still consume (mark fired) every one that's now moot, but only actually
			// send a message for the most urgent (smallest) one crossed this call, not one per
			// checkpoint - TIME_LIMIT_CHECKPOINTS is in descending order, so the last match wins.
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
		// Personal countdown for the final 10 seconds - always local (an on-screen action bar cue,
		// not a chat message), regardless of dungeonTimeLimitWarningDelivery.
		if (remaining <= 10 && remaining != lastCountdownSecondShown) {
			lastCountdownSecondShown = remaining;
			client.gui.setOverlayMessage(Component.translatable("skymelloo.chat.dungeon_report.time_limit_countdown", remaining), false);
		}
	}

	/**
	 * Every input {@link #calculateScore()} actually used, for {@code /sm debug score} - a real bugfix
	 * traced to the live score staying frozen at exactly 20+0+
	 * 100+0=120 for an ENTIRE run regardless of real progress, across many different runs/floors. That
	 * total matches calculateSkillScore()/calculateExploreScore() both reading ZERO completed rooms the
	 * whole time - meaning either {@link #getTotalRooms()} divided by a real clearedPercent still came
	 * out 0 (only possible if {@link #getCompletedRooms()} itself was 0), or Skyblocker's own score
	 * (now ALWAYS used when available, no more opt-out toggle) was never actually being used. This
	 * exposes every number in that chain so a live report can show exactly which one is wrong instead
	 * of guessing blind.
	 */
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

	// This is not very accurate at the beginning of a floor since clear% is rounded to the nearest
	// integer, so at low percentages its effect on the result is disproportionately large - ported
	// comment/behavior from DungeonScore.java verbatim, same caveat applies here.
	private static int getTotalRooms() {
		double clearPercentage = clearedPercent / 100.0;
		return (int) Math.round(getCompletedRooms() / clearPercentage);
	}

	private static int getCompletedRooms() {
		Matcher matcher = DungeonTabList.matchAt(43, COMPLETED_ROOMS_PATTERN);
		return matcher != null ? Integer.parseInt(matcher.group("rooms")) : 0;
	}

	/** Needed for calculating score before the Blood Room/boss room, since the tab list's own "Completed Rooms" lags a little behind - ported from DungeonScore.java's getExtraCompletedRooms(). */
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
			matcher = DungeonTabList.matchAt(32, CRYPTS_PATTERN); // shifts up one line at class milestone 9
		}
		return matcher != null ? Integer.parseInt(matcher.group("crypts")) : 0;
	}

	private static void finishRun(SkyMellooConfig config) {
		DebugLog.log(DebugLog.Category.DUNGEON, "Run finished (deaths=" + deaths + ", puzzlesSolved=" + puzzlesSolved + ", puzzlesFailed=" + puzzlesFailed + ", doorsOpened=" + doorsOpened + ")");
		// runActive is deliberately NOT flipped false here anymore - the caller (the REQUEUE_PATTERN
		// handler) keeps the run/HUD fully live until the player actually leaves the dungeon, see
		// runReportSentAwaitingLeave's own comment.
		// Snapshotted BEFORE anything below could return early (dungeonRunReportEnabled only gates the
		// chat report, not the Score HUD's own "Final Result" panel) and before the dungeon tab
		// list/scoreboard has a chance to reset from walking back towards the hub.
		int finalScore = currentDisplayedScore();
		int finalDeaths = deaths.values().stream().mapToInt(Integer::intValue).sum();
		lastFinalResult = new FinalResult(floor, finalScore, gradeForTotal(finalScore), calculateScore(),
				clearedPercent, getSecretsPercentage(), cryptsFound, puzzlesSolved, puzzlesFailed,
				finalDeaths, new java.util.ArrayList<>(puzzleOutcomes));
		finalResultShownAtMillis = System.currentTimeMillis();
		// Session stats accumulate unconditionally, same as the Final Result snapshot above - /sm
		// session should always have accurate numbers regardless of which report/HUD toggles are on.
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
		// Sending the full itemized report to party chat used to combine every line into one giant
		// /pc message to dodge Hypixel's own command cooldown - but a single very long chat line with
		// many player names in it (deaths, per-puzzle results, boss-room entries) turned out to still
		// be able to crash the game client-side (a chat-rendering mod choking on it, not our own code).
		// The full report is LOCAL-only now, always - no delivery choice for it anymore. Anyone who
		// wants the party to see a result gets the separate, short, opt-in summary below instead.
		maybeSendPartyRunSummary(client, config);

		if (!config.dungeonRunReportEnabled) {
			return;
		}
		// Kick buttons are a client-side-only ClickEvent - always safe to show now that this report
		// never leaves the local client as a plain-text party command.
		boolean canShowKickButtons = com.melloo.skymelloo.client.party.PartyTracker.isLocalPlayerLeader();
		java.util.function.Consumer<String> emit = line -> client.player.sendSystemMessage(ChatUtil.prefixed(line));

		emit.accept(Component.translatable("skymelloo.chat.dungeon_report.header").getString());

		// Skyblocker's live score is silently used as the ONE number shown whenever it's available and
		// preferred (see currentDisplayedScore()) - no separate "[Skyblocker: X]" annotation cluttering
		// the line, since that just invited confusion about which number to trust.
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
		// Named per-puzzle results (who, and why) instead of just a count - the reason text is
		// Hypixel's own SOLVED/FAIL wording, see extractPuzzleReason().
		for (PuzzleResult puzzle : puzzleOutcomes) {
			if (puzzle.outcome() == PuzzleOutcome.PENDING) {
				continue;
			}
			String tag = puzzle.outcome() == PuzzleOutcome.SOLVED ? "§a✓" : "§c✖";
			emit.accept("  " + tag + " §f" + puzzle.player() + " §7" + puzzle.detail());
		}
		// Blood Room summary line - only shown if the
		// Blood Room was actually reached this run, same as the mimic/prince lines above.
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
		// Real wall-clock duration spent in the boss room until the boss died (elapsedSeconds
		// itself is frozen for the whole boss fight, see FLOOR_NULL_END_RUN_TICKS's own comment on why).
		if (bossRoomEnteredMillis > 0 && bossRoomClearedMillis > 0) {
			int bossSeconds = (int) Math.max(0, (bossRoomClearedMillis - bossRoomEnteredMillis) / 1000);
			emit.accept(Component.translatable("skymelloo.chat.dungeon_report.time_in_boss_room", formatElapsed(bossSeconds)).getString());
		}
		if (!doorsOpened.isEmpty()) {
			// Renamed from the old generic "Doors opened" to specifically "Wither Doors opened":
			// this map is only ever actually populated from Wither Door opens (the Blood
			// Door's real chat message is a nameless broadcast, see BLOOD_DOOR_OPENED_PATTERN, so it can
			// never attribute a player here), so the old generic label was misleading.
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
				// Rich kick-button line - LOCAL-only (see canShowKickButtons), sent directly rather
				// than batched, since a ClickEvent can't survive being flattened to plain text anyway.
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

	/**
	 * The short, separately-toggleable party message - one line, no per-puzzle/per-death detail, no
	 * player names beyond what fits in the template placeholders. Independent of
	 * {@link SkyMellooConfig#dungeonRunReportEnabled} (that one only controls the LOCAL detailed
	 * report) and off by default, since it's a deliberate opt-in trade: less detail in exchange for
	 * actually being safe to send to party chat.
	 */
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
