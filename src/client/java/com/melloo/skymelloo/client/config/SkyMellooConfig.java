package com.melloo.skymelloo.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import net.fabricmc.loader.api.FabricLoader;

import java.awt.Color;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// Plain Gson-persisted settings, saved to config/skymelloo.json5.
public final class SkyMellooConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("skymelloo.json5");
	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.registerTypeAdapter(Color.class, (JsonSerializer<Color>) (src, type, ctx) -> ctx.serialize(src.getRGB()))
			.registerTypeAdapter(Color.class, (JsonDeserializer<Color>) (json, type, ctx) -> new Color(json.getAsInt(), true))
			.create();

	public static final ConfigHandler HANDLER = new ConfigHandler();

	public static final class ConfigHandler {
		private static final int SAVE_DEBOUNCE_SECONDS = 5;

		private SkyMellooConfig instance;
		private final AtomicBoolean dirty = new AtomicBoolean(false);
		private ScheduledExecutorService debounceExecutor;

		private ConfigHandler() {
		}

		public SkyMellooConfig instance() {
			if (instance == null) {
				load();
			}
			return instance;
		}

		public void load() {
			if (Files.exists(FILE)) {
				try (Reader reader = Files.newBufferedReader(FILE)) {
					SkyMellooConfig loaded = GSON.fromJson(reader, SkyMellooConfig.class);
					if (loaded != null) {
						instance = loaded;
						return;
					}
					// Valid JSON but not a config object - falls through to fresh defaults below.
				} catch (IOException | com.google.gson.JsonParseException e) {
					backupBrokenConfig();
				}
			}
			instance = new SkyMellooConfig();
		}

		private void backupBrokenConfig() {
			try {
				Path backup = FILE.resolveSibling(FILE.getFileName() + ".broken-" + System.currentTimeMillis());
				Files.move(FILE, backup, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
			}
		}

		public void save() {
			if (instance == null) {
				return;
			}
			try {
				Files.createDirectories(FILE.getParent());
				// Write-temp + atomic move so a crash mid-write can't corrupt the real config file.
				Path tempFile = FILE.resolveSibling(FILE.getFileName() + ".tmp");
				try (Writer writer = Files.newBufferedWriter(tempFile)) {
					GSON.toJson(instance, writer);
				}
				try {
					Files.move(tempFile, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(tempFile, FILE, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException e) {
				throw new RuntimeException("Could not save SkyMelloo config", e);
			}
		}

		/** For hot-path callers; coalesces into one {@link #save()} per {@value #SAVE_DEBOUNCE_SECONDS}s, flushed on shutdown. */
		public void saveDebounced() {
			dirty.set(true);
			if (debounceExecutor == null) {
				startDebounceScheduler();
			}
		}

		private synchronized void startDebounceScheduler() {
			if (debounceExecutor != null) {
				return;
			}
			debounceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "skymelloo-config-save-debounce");
				thread.setDaemon(true);
				return thread;
			});
			debounceExecutor.scheduleWithFixedDelay(this::saveIfDirty, SAVE_DEBOUNCE_SECONDS, SAVE_DEBOUNCE_SECONDS, TimeUnit.SECONDS);
			Runtime.getRuntime().addShutdownHook(new Thread(this::saveIfDirty, "skymelloo-config-save-shutdown-flush"));
		}

		private void saveIfDirty() {
			if (dirty.compareAndSet(true, false)) {
				save();
			}
		}
	}

	// Delivery fields below accept "LOCAL" or "PARTY", falling back to LOCAL outside a party.

	// Highlight hostile mobs in your current dungeon room.
	public boolean dungeonRoomMobHighlightEnabled = false;

	public Color dungeonRoomMobHighlightColor = new Color(0xFFFFA500, true);

	// Blink a party member's highlight red once their HP drops under 25%.
	public boolean lowHpBlinkEnabled = true;

	// Glow color for the player targeted with /sm search (Hypixel lobby only).
	public Color lobbySearchColor = new Color(0xFF55FF55, true);

	// Render other invisible players normally instead of hidden; still blocked by walls/line-of-sight.
	public boolean showInvisiblePlayersEnabled = false;

	// Highlight chat messages mentioning your own username, with a sound.
	public boolean chatMentionHighlightEnabled = true;

	public Color chatMentionHighlightColor = new Color(0xFFFFD700, true);

	// Flags trade-offer-shaped public chat as possible lowball spam - see AntiScamFilter.
	public boolean antiScamEnabled = true;

	// true = hide the flagged message, false = leave it visible with a warning prepended.
	public boolean antiScamHideMessages = true;

	// Look up a joining party member's SkyBlock stats and post a summary in chat.
	public boolean partyJoinStatsEnabled = false;

	public String dungeonInfoMessageDelivery = "LOCAL";

	// Include Accessory Power in the join announcement.
	public boolean dungeonInfoShowMp = true;

	// Placeholders: {username} {ap} {level} {cata} {class} {skillavg} {networth} {rank} {guild} {maxfloor} {qualfloor} {statscore} {expscore} {readiness} {skillsscore} {apscore} {farmingpoints} {miningpoints} {combatpoints} {foragingpoints} {fishingpoints} {enchantingpoints} {alchemypoints} {tamingpoints} {classpoints} {sblevelpoints} {floorpoints} {completionspoints} {aptier} {catatier} {time} {date} ({mp}/{mptier}/{mpscore} still work as legacy aliases).
	public String dungeonInfoMessageTemplate = "§a{username}§r: Cata §b{cata}§r, SB-Level §b{level}§r, Class §e{class}§r, AP §d{ap}§r ({aptier}), Max Floor §b{maxfloor}§r, Qualifies F§b{qualfloor}§r, Readiness §a{readiness}§r/1000 (Skills {skillsscore} · AP {apscore})";

	// IANA zone ID for the {time}/{date} placeholders above.
	public String dungeonInfoTimezone = java.time.ZoneId.systemDefault().getId();

	// Kick a joining member if their chosen stat is under the threshold below.
	public boolean dungeonAutoKickEnabled = false;

	// "AP" or "LEVEL".
	public String dungeonAutoKickStat = "AP";

	public int dungeonAutoKickThreshold = 100;

	// Placeholders: {player} {stat} {value} {threshold}.
	public String dungeonAutoKickMessageTemplate = "{player} kicked - {stat} too low ({value} < {threshold})";

	public String dungeonAutoKickDelivery = "LOCAL";

	// Kick a joining member if their chosen stat is OVER the threshold below (carry parties).
	public boolean dungeonAutoKickMaxEnabled = false;

	// "AP" or "LEVEL".
	public String dungeonAutoKickMaxStat = "AP";

	public int dungeonAutoKickMaxThreshold = 300;

	public String dungeonAutoKickMaxMessageTemplate = "{player} kicked - {stat} too high for a carry ({value} > {threshold})";

	public String dungeonAutoKickMaxDelivery = "LOCAL";

	// Floor to benchmark the readiness/stats score against (0 = Entrance, 7 = Floor VII).
	public int dungeonTargetFloor = 7;

	// Kick a member who doesn't currently meet the level requirements for the floor threshold below.
	public boolean dungeonFloorKickEnabled = false;

	// Minimum floor a member must be level-eligible for right now (Normal Mode requirements).
	public int dungeonFloorKickThreshold = 0;

	public String dungeonFloorKickMessageTemplate = "{player} kicked - only qualifies up to Floor {value} (need {threshold})";

	public String dungeonFloorKickDelivery = "LOCAL";

	// Kick a member already eligible for a floor higher than the threshold below (carry parties).
	public boolean dungeonFloorKickMaxEnabled = false;

	public int dungeonFloorKickMaxThreshold = 7;

	public String dungeonFloorKickMaxMessageTemplate = "{player} kicked - already qualifies up to Floor {value} (allowed {threshold})";

	public String dungeonFloorKickMaxDelivery = "LOCAL";

	// Kick a member who hasn't actually completed the floor threshold below (per Hypixel's completion record, not just level requirements).
	public boolean dungeonFloorCompletionKickEnabled = false;

	public int dungeonFloorCompletionKickThreshold = 0;

	public String dungeonFloorCompletionKickMessageTemplate = "{player} kicked - has only completed up to Floor {value} (need {threshold})";

	public String dungeonFloorCompletionKickDelivery = "LOCAL";

	// Kick a member who's already completed a floor higher than the threshold below (carry parties).
	public boolean dungeonFloorCompletionKickMaxEnabled = false;

	public int dungeonFloorCompletionKickMaxThreshold = 7;

	public String dungeonFloorCompletionKickMaxMessageTemplate = "{player} kicked - already completed up to Floor {value} (allowed {threshold})";

	public String dungeonFloorCompletionKickMaxDelivery = "LOCAL";

	// Post a detailed run summary in chat when the dungeon completes, LOCAL only.
	public boolean dungeonRunReportEnabled = false;

	// Short one-line result sent to party chat when a run ends.
	public boolean dungeonRunPartySummaryEnabled = false;

	// Placeholders: {floor} {score} {grade} {time}.
	public String dungeonRunPartySummaryTemplate = "{floor} finished - Score: {score} ({grade}) in {time}";

	public String dungeonRunPartySummaryDelivery = "PARTY";

	// Announce when the boss room is entered.
	public boolean dungeonBossRoomAnnounceEnabled = false;

	// Used when score is still 300+ at entry. Placeholder: {player}.
	public String dungeonBossRoomMessageTemplate = "{player} entered the boss room!";

	// Used instead of the above when score is under 300 at entry. Placeholders: {player} {score}.
	public String dungeonBossRoomLowScoreMessageTemplate = "{player} entered the boss room at only {score} score - no more points can be earned from here, S+ (300) is out of reach!";

	public String dungeonBossRoomMessageDelivery = "LOCAL";

	// Kick a member once their run death count exceeds the threshold below.
	public boolean dungeonDeathKickEnabled = false;

	public int dungeonDeathKickThreshold = 3;

	// Kick a member who hasn't moved for the threshold below.
	public boolean dungeonAfkKickEnabled = false;

	// "30", "60", or "120" seconds.
	public String dungeonAfkKickThreshold = "60";

	// Live dungeon score HUD (Skill/Explore/Speed/Bonus), same formula as Skyblocker.
	public boolean dungeonScoreHudEnabled = false;

	// Debug HUD with the run tracker's internal state flags.
	public boolean dungeonDebugHudEnabled = false;

	// Show the Skill/Explore/Speed/Bonus breakdown line on the Score HUD.
	public boolean dungeonScoreShowBreakdown = true;

	// Show the current room's secrets found/total, if Skyblocker is installed.
	public boolean dungeonScoreShowRoomSecrets = true;

	// Share your current room/floor/secrets/score with SkyMelloo party members via sky.melloo.me.
	public boolean dungeonSyncEnabled = true;

	// Show puzzle outcome blocks and solved/failed detail lines on the Score HUD.
	public boolean dungeonScoreShowPuzzles = true;

	// Show the best-case ceiling score and permanent penalties applied so far.
	public boolean dungeonScoreShowPossible = true;

	// Show points needed to reach the next grade above the current one.
	public boolean dungeonScoreShowNextGrade = true;

	// Show a pace arrow and a time-limit countdown on the Score HUD.
	public boolean dungeonScoreShowPaceAndCountdown = true;

	// Announce in chat when a party member dies.
	public boolean dungeonDeathMessageEnabled = false;

	// Placeholders: {player} {count}.
	public String dungeonDeathMessageTemplate = "{player} failed ({count} death(s) this run)";

	public String dungeonDeathMessageDelivery = "LOCAL";

	// Warn once the Blood Room fight ends if score is still under 300.
	public boolean dungeonPreBossScoreWarningEnabled = false;

	// Placeholder: {score}.
	public String dungeonPreBossScoreWarningTemplate = "Don't go in yet - we only have {score} score!";

	// Used instead of the above if S+ was already confirmed impossible for a different reason. Placeholder: {score}.
	public String dungeonPreBossScoreWarningAlreadyImpossibleTemplate = "S+ was already out of reach before this ({score} score) - go ahead and enter.";

	public String dungeonPreBossScoreWarningDelivery = "LOCAL";

	// Warn once per run if S+ has become mathematically impossible.
	public boolean dungeonSPlusImpossibleEnabled = false;

	// Placeholder: {reason}.
	public String dungeonSPlusImpossibleTemplate = "S+ is no longer possible this run - {reason}.";

	public String dungeonSPlusImpossibleDelivery = "LOCAL";

	// Announce once if the score ceiling recovers back over 300 after the impossible warning fired.
	public boolean dungeonSPlusBackEnabled = false;

	public String dungeonSPlusBackTemplate = "S+ is back in reach - the ceiling recovered above 300!";

	public String dungeonSPlusBackDelivery = "LOCAL";

	// Announce the moment the run's grade first reaches a new tier.
	public boolean dungeonGradeMilestoneEnabled = false;

	// Placeholder: {grade}.
	public String dungeonGradeMilestoneTemplate = "Reached {grade} grade!";

	public String dungeonGradeMilestoneDelivery = "LOCAL";

	// Nudge yourself via the action bar once you're the last one not ready.
	public boolean dungeonSelfReadyReminderEnabled = true;

	public String dungeonSelfReadyReminderText = "Everyone else is ready - your turn!";

	// Warn as S+ becomes unreachable from running out of time, plus a personal 10s countdown.
	public boolean dungeonTimeLimitWarningEnabled = false;

	// Checkpoint to start warning at - "60", "30", "15", or "10" seconds of S+ margin left.
	public String dungeonTimeLimitWarningStart = "30";

	// Placeholder: {time}.
	public String dungeonTimeLimitWarningTemplate = "Only {time} left before S+ becomes unreachable - hurry!";

	// The last-10-seconds countdown is always personal/on-screen regardless of this delivery setting.
	public String dungeonTimeLimitWarningDelivery = "LOCAL";

	// Announce once the floor's time limit is exceeded.
	public boolean dungeonTimeLimitExceededEnabled = true;

	// Placeholder: {floor}.
	public String dungeonTimeLimitExceededTemplate = "Time limit exceeded on {floor}!";

	public String dungeonTimeLimitExceededDelivery = "LOCAL";

	// Announce the best possible score once all rooms are discovered.
	public boolean dungeonRoomsDiscoveredAnnounceEnabled = false;

	// Warn once your secret-finding rate falls behind what's needed for the floor's required secrets%.
	public boolean dungeonSecretsPaceWarningEnabled = false;

	// Placeholders: {secrets} {required} {timeleft}.
	public String dungeonSecretsPaceWarningTemplate = "Falling behind on secrets pace - {secrets}%/{required}% needed, {timeleft} left!";

	public String dungeonSecretsPaceWarningDelivery = "LOCAL";

	// Announce when the same puzzle fails again after already having failed once.
	public boolean dungeonPuzzleRetryFailEnabled = false;

	// Placeholders: {player} {detail}.
	public String dungeonPuzzleRetryFailTemplate = "{player} failed again after resetting the room - {detail}";

	public String dungeonPuzzleRetryFailDelivery = "LOCAL";

	// Placeholders: {possible} {grade}.
	public String dungeonRoomsDiscoveredTemplate = "All rooms discovered - best possible score from here: {possible} ({grade}).";

	public String dungeonRoomsDiscoveredDelivery = "LOCAL";

	// Keep the Score HUD up after a run ends, showing a frozen "Final Result" panel.
	public boolean dungeonScoreFinalResultEnabled = true;

	public int dungeonScoreFinalResultDurationSeconds = 20;

	// HUD position, set via the layout editor (default J).
	public int hudScoreX = 6;
	public int hudScoreY = 90;

	public int hudDebugX = 6;
	public int hudDebugY = 260;

	// Custom health/mana bar pair replacing the vanilla hearts/XP-bar look.
	public boolean healthManaBarsEnabled = false;

	public boolean healthBarEnabled = false;

	public boolean manaBarEnabled = false;

	// Mana bar next to the health bar instead of stacked below it.
	public boolean healthManaBarsSideBySide = false;

	// Hide Hypixel's own Health/Defense/Mana actionbar text while Health/Mana Bars is on.
	public boolean hideNativeStatusActionBarEnabled = true;

	public int hudHealthManaX = 6;
	public int hudHealthManaY = 300;

	// Highlight dropped items with a glowing outline, visible through walls.
	public boolean itemHighlightEnabled = false;

	// Comma-separated, case-insensitive item name filters. Empty = highlight everything.
	public String itemHighlightNameFilters = "";

	public Color itemHighlightColor = new Color(0xFF55FF55, true);

	// Show distance in blocks next to highlighted mobs/players/items.
	public boolean showDistanceEnabled = true;

	// Alert when your fishing bobber gets a bite.
	public boolean fishingHelperEnabled = false;

	// Play a sound in addition to the actionbar alert.
	public boolean fishingHelperSound = true;

	public Color fishingWaitingColor = new Color(0xFF5599FF, true);

	public Color fishingBitingColor = new Color(0xFFFFAA00, true);

	// Cosmetic pufferfish minigame while your rod is cast out.
	public boolean fishingMinigameEnabled = false;

	public Color fishingMinigameColor = new Color(0xFFFF6600, true);

	// Internal counter, not user-editable.
	public int fishingMinigameHighscore = 0;

	// Highlight chests with a glowing outline, visible through walls.
	public boolean chestHighlightEnabled = false;

	public Color chestHighlightColor = new Color(0xFFFFD700, true);

	// Scan radius in blocks for Chest Highlight.
	public int blockHighlightRange = 24;

	// Punching empty air with an empty hand shoots a small particle projectile. Purely cosmetic.
	public boolean magicMissileEnabled = false;

	public Color magicMissileColor = new Color(0xFFAA33FF, true);

	// "MISSILE" or "LIGHTNING". Switched via the SkyMelloo Menu item's Spells page.
	public String magicMissileSpellType = "MISSILE";

	// Master switch for the debug categories below.
	public boolean debugMessagesEnabled = false;

	public boolean debugSync = false;
	public String debugSyncDelivery = "LOCAL";

	// Only ever shown to admin-linked accounts, regardless of this setting.
	public boolean debugPermissions = false;
	public String debugPermissionsDelivery = "LOCAL";

	public boolean debugCloudSync = false;
	public String debugCloudSyncDelivery = "LOCAL";

	public boolean debugPresence = false;
	public String debugPresenceDelivery = "LOCAL";

	public boolean debugParty = false;
	public String debugPartyDelivery = "LOCAL";

	public boolean debugDungeon = false;
	public String debugDungeonDelivery = "LOCAL";

	public boolean debugStaff = false;
	public String debugStaffDelivery = "LOCAL";

	public boolean manaDebugEnabled = false;

	// Check connection quality (ping stability, packet rate) for 5s after joining.
	public boolean connectionQualityCheckEnabled = false;

	// Sync settings to sky.melloo.me; cloud is authoritative on join.
	public boolean cloudSyncEnabled = false;

	// Fake "SkyMelloo Menu" item in hotbar slot 8 when that slot is empty.
	public boolean skyMellooMenuItemEnabled = true;

	// -1 = not set yet, uses the default centered-above-hotbar position.
	public int hudFishingScoreX = -1;
	public int hudFishingScoreY = -1;

	// "OFF", "COMPACT", or "FULL".
	public String partyHudMode = "COMPACT";

	// In FULL mode, show each puzzle a member solved/failed as extra lines under their row.
	public boolean partyHudShowPuzzleHistory = true;

	// Horizontal AP "spread" bar across the party. Needs at least 2 members with known AP.
	public boolean partyMpBarEnabled = false;

	public int hudPartyX = 6;
	public int hudPartyY = 50;

	public int hudPartyMpBarX = 6;
	public int hudPartyMpBarY = 220;

	// Internal counters, not user-editable.
	public int totalPlayersKilled = 0;
	public int totalSpellEssenceCollected = 0;
	public int totalSpellsCast = 0;
	public int totalMagicMissileKills = 0;

	// Post a short "Death Recap" in chat with real damage-source data.
	public boolean deathRecapEnabled = true;

	// Share a short one-line version of the Death Recap with the party.
	public boolean deathRecapPartyAnnounceEnabled = false;

	// Placeholders: {player} {cause}.
	public String deathRecapPartyAnnounceTemplate = "{player} died - killed by {cause}";

	public String deathRecapPartyAnnounceDelivery = "PARTY";

	// Rejoin Hypixel a few seconds after an unexpected disconnect, capped at 3 attempts in a row.
	public boolean autoReconnectEnabled = false;

	private transient String cachedItemFilterSource = null;
	private transient Set<String> cachedItemFilters = Set.of();

	/** Lazily re-parsed only when {@link #itemHighlightNameFilters} actually changes. */
	public Set<String> parsedItemFilters() {
		if (!itemHighlightNameFilters.equals(cachedItemFilterSource)) {
			cachedItemFilterSource = itemHighlightNameFilters;
			cachedItemFilters = parseCommaList(itemHighlightNameFilters);
		}
		return cachedItemFilters;
	}

	private static Set<String> parseCommaList(String raw) {
		Set<String> parsed = new LinkedHashSet<>();
		for (String part : raw.split(",")) {
			String trimmed = part.trim().toLowerCase();
			if (!trimmed.isEmpty()) {
				parsed.add(trimmed);
			}
		}
		return parsed;
	}
}
