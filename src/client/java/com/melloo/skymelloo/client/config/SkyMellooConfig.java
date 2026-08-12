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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Plain Gson-persisted settings (no YACL) - saved to {@code config/skymelloo.json5}, same file path
 * as before so existing installs keep their settings. The in-game settings screen (key H via
 * MellooEssentials, "SkyMelloo Config" button) is fully custom and never used YACL's own generated
 * screen - YACL was only ever providing (de)serialization here plus a secondary Mod Menu entry (see
 * ModMenuIntegration, now pointed at the same custom screen instead of YACL's generated one).
 * {@link Color} is stored as a plain ARGB int via a custom adapter, matching MellooEssentials' own
 * EssentialsConfig.
 */
public final class SkyMellooConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("skymelloo.json5");
	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.registerTypeAdapter(Color.class, (JsonSerializer<Color>) (src, type, ctx) -> ctx.serialize(src.getRGB()))
			.registerTypeAdapter(Color.class, (JsonDeserializer<Color>) (json, type, ctx) -> new Color(json.getAsInt(), true))
			.create();

	public static final ConfigHandler HANDLER = new ConfigHandler();

	/** Mimics the tiny slice of YACL's own ConfigClassHandler API every existing call site already used (instance()/save()/load()) so none of the ~30 files calling SkyMellooConfig.HANDLER.* needed to change. */
	public static final class ConfigHandler {
		private SkyMellooConfig instance;

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
				} catch (IOException ignored) {
					// Falls through to a fresh default config below.
				}
			}
			instance = new SkyMellooConfig();
		}

		public void save() {
			if (instance == null) {
				return;
			}
			try {
				Files.createDirectories(FILE.getParent());
				try (Writer writer = Files.newBufferedWriter(FILE)) {
					GSON.toJson(instance, writer);
				}
			} catch (IOException e) {
				throw new RuntimeException("Could not save SkyMelloo config", e);
			}
		}
	}

	// Highlight hostile mobs inside your CURRENT dungeon room in red, so the ones you still need to clear stand out from mobs elsewhere on the floor. Only active during an active dungeon run.
	public boolean dungeonRoomMobHighlightEnabled = false;

	// Glow color for hostile mobs inside your current dungeon room (see above).
	public Color dungeonRoomMobHighlightColor = new Color(0xFFFF0000, true);

	// A party member's highlight (both the glow outline and the nametag marker) blinks bright red once their HP drops under 25% - an urgent "someone needs help" signal readable at a glance during a fight.
	public boolean lowHpBlinkEnabled = true;

	// Party/staff/friend highlighting (toggle, color, glow-outline) are all MellooEssentials' job -
	// see its EssentialsConfig/highlight.HighlightManager. This mod's own HighlightManager only
	// decides /sm search.

	// Glow color for the player targeted with /sm search - only works in a Hypixel lobby (not SkyBlock, which has its own party/staff/friend highlighting in MellooEssentials).
	public Color lobbySearchColor = new Color(0xFF55FF55, true);

	// Off by default, since it defeats another player's REAL invisibility (e.g. Invisibility
	// Potion), a much bigger deal than the other cosmetic player-color options above. Deliberately
	// not a glow-outline/through-walls effect - see MissileHitInvisibilityMixin's isInvisible()
	// override, which makes them render as a completely normal, visible player instead, still
	// blocked by walls/line-of-sight.
	// Makes other invisible players (e.g. from an Invisibility Potion) render normally instead of staying hidden. Not a highlighting effect - they're still blocked by walls/line-of-sight like anyone else, just no longer artificially hidden.
	public boolean showInvisiblePlayersEnabled = false;

	// Highlight (bold + colored marker) any chat message that mentions your own username, and play a short sound - so a mention doesn't slip by unnoticed.
	public boolean chatMentionHighlightEnabled = true;

	// Marker color for a chat mention of your own username (see above).
	public Color chatMentionHighlightColor = new Color(0xFFFFD700, true);

	// Flags trade-offer-shaped public chat as possible lowball spam - see AntiScamFilter.
	public boolean antiScamEnabled = true;

	// true = hide the flagged message entirely, false = leave it visible with a warning marker prepended.
	public boolean antiScamHideMessages = true;

	// "Dungeon Info": when someone joins your party (e.g. via dungeon party finder), look up their SkyBlock stats (sky.melloo.me/api) and post a summary in chat.
	public boolean partyJoinStatsEnabled = false;

	// Where the Dungeon Info join announcement goes - "LOCAL" (only you see it) or "PARTY" (sent for real via /pc, everyone sees it). Falls back to LOCAL automatically if you're not actually in a party.
	public String dungeonInfoMessageDelivery = "LOCAL";

	// Include Magical Power in the Dungeon Info join announcement.
	public boolean dungeonInfoShowMp = true;

	// Message template for the Dungeon Info join announcement. Placeholders: {username} {mp} {level} {cata} {class} {skillavg} {networth} {rank} {guild} {maxfloor} {qualfloor} {statscore} {expscore} {readiness} {skillsscore} {mpscore} {farmingpoints} {miningpoints} {combatpoints} {foragingpoints} {fishingpoints} {enchantingpoints} {alchemypoints} {tamingpoints} {classpoints} {sblevelpoints} {floorpoints} {completionspoints} {mptier} {catatier} {time} {date}. {maxfloor} = highest floor ever completed; {qualfloor} = highest floor they're currently level-eligible for. {statscore}/{expscore}/{readiness} are 0-1000 rough estimates (see DungeonReadiness) - NOT an authoritative score, just gear/level/experience proxies since Hypixel exposes no per-run damage/performance data at all. {statscore} itself is built from {skillsscore} (all 8 skills + class level + SB level) and {mpscore}, each 0-1000. {farmingpoints} etc. break {skillsscore} down into exactly how many points each skill/class-level/SB-level earned; {floorpoints}/{completionspoints} do the same for {expscore}. {mptier}/{catatier} are text labels (Very Low/Low/Medium/Good/Excellent/Overpowered) for MP and Catacombs level relative to dungeonTargetFloor's benchmark/requirement.
	public String dungeonInfoMessageTemplate = "§a{username}§r: Cata §b{cata}§r, SB-Level §b{level}§r, Class §e{class}§r, MP §d{mp}§r ({mptier}), Max Floor §b{maxfloor}§r, Qualifies F§b{qualfloor}§r, Readiness §a{readiness}§r/1000 (Skills {skillsscore} · MP {mpscore})";

	// Timezone used for the {time}/{date} placeholders above (IANA zone ID, e.g. Europe/Berlin, America/New_York).
	public String dungeonInfoTimezone = java.time.ZoneId.systemDefault().getId();

	// Automatically /party kick a joining member if their chosen stat (below) is under the threshold.
	public boolean dungeonAutoKickEnabled = false;

	// Which stat Auto-Kick checks - "MP" (Magical Power) or "LEVEL" (SkyBlock level).
	public String dungeonAutoKickStat = "MP";

	// Auto-Kick threshold - a joining player whose stat is below this gets kicked from the party.
	public int dungeonAutoKickThreshold = 100;

	// Message template for the Auto-Kick announcement above. Placeholders: {player} {stat} {value} {threshold}.
	public String dungeonAutoKickMessageTemplate = "{player} kicked - {stat} too low ({value} < {threshold})";

	// Where the Auto-Kick announcement above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party). Independent of every other kick message's delivery below.
	public String dungeonAutoKickDelivery = "LOCAL";

	// The opposite of Auto-Kick above: automatically /party kick a joining member if their chosen stat (below) is OVER the threshold - for carry parties, where an overqualified joiner defeats the point (they don't need carrying, and may be taking a slot from someone who does).
	public boolean dungeonAutoKickMaxEnabled = false;

	// Which stat the Max Auto-Kick above checks - "MP" (Magical Power) or "LEVEL" (SkyBlock level). Independent of the MIN stat above - a party can reasonably check both at once (e.g. MP for the min, Level for the max) or just one.
	public String dungeonAutoKickMaxStat = "MP";

	// Max Auto-Kick threshold - a joining player whose stat is OVER this gets kicked from the party.
	public int dungeonAutoKickMaxThreshold = 300;

	// Message template for the Max Auto-Kick announcement above. Placeholders: {player} {stat} {value} {threshold}.
	public String dungeonAutoKickMaxMessageTemplate = "{player} kicked - {stat} too high for a carry ({value} > {threshold})";

	// Where the Max Auto-Kick announcement above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party). Independent of every other kick message's delivery.
	public String dungeonAutoKickMaxDelivery = "LOCAL";

	// Which floor to benchmark the readiness/stats score against (0 = Entrance, 7 = Floor VII) - see DungeonReadiness. Magical Power expectations are very different for Entrance vs. Floor VII, so this has to be set manually since there's no way to auto-detect which floor a party is actually planning to run before it starts.
	public int dungeonTargetFloor = 7;

	// Automatically /party kick (or warn with a manual [Kick] button, same behavior as the MP/Level Auto-Kick above) a party member who doesn't currently meet the REQUIREMENTS (Catacombs/Combat Skill, verified in-game) for the floor threshold below - not whether they've ever actually completed it before. Uses the same sky.melloo.me stats lookup as the join announcement, no extra API calls.
	public boolean dungeonFloorKickEnabled = false;

	// Minimum floor a member needs to be level-ELIGIBLE for right now (0 = just needs Combat Skill 15 for Entrance, 7 = needs Catacombs Skill 24 for Floor VII). Based on the real Hypixel requirements (Combat 15 for Entrance, then Catacombs 1/3/5/9/14/19/24 for Floors I-VII) - Master Mode requirements aren't included, only Normal Mode.
	public int dungeonFloorKickThreshold = 0;

	// Message template for the Floor Auto-Kick announcement above. Placeholders: {player} {value} {threshold}.
	public String dungeonFloorKickMessageTemplate = "{player} kicked - only qualifies up to Floor {value} (need {threshold})";

	// Where the Floor Auto-Kick announcement above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party). Independent of every other kick message's delivery.
	public String dungeonFloorKickDelivery = "LOCAL";

	// The opposite of Floor Auto-Kick above: automatically /party kick (or warn with a manual [Kick] button) a member who's ALREADY level-eligible for a floor higher than the threshold below - for carry parties, where someone who already qualifies for that floor doesn't need to be carried through it.
	public boolean dungeonFloorKickMaxEnabled = false;

	// Maximum floor a member is allowed to already be level-eligible for - anyone eligible for something higher than this gets kicked by Max Floor Auto-Kick above.
	public int dungeonFloorKickMaxThreshold = 7;

	// Message template for the Max Floor Auto-Kick announcement above. Placeholders: {player} {value} {threshold}.
	public String dungeonFloorKickMaxMessageTemplate = "{player} kicked - already qualifies up to Floor {value} (allowed {threshold})";

	// Where the Max Floor Auto-Kick announcement above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party). Independent of every other kick message's delivery.
	public String dungeonFloorKickMaxDelivery = "LOCAL";

	// The floorRequirement group above
	// only ever checked whether a member currently MEETS THE REQUIREMENTS for a floor (Catacombs/Combat
	// Skill), never whether they've actually COMPLETED it before. Real completion (Hypixel's own
	// highest_tier_completed) is a genuinely different, independent signal - a party can reasonably
	// want either, both, or neither, same as every other kick check here.
	// Automatically /party kick (or warn with a manual [Kick] button) a member who hasn't ACTUALLY COMPLETED the floor threshold below yet - unlike Floor Auto-Kick above, which only checks whether they currently meet the level requirements, not real experience with that floor.
	public boolean dungeonFloorCompletionKickEnabled = false;

	// Minimum floor a member needs to have ACTUALLY COMPLETED before (0 = Entrance, 7 = Floor VII), per Hypixel's own completion record.
	public int dungeonFloorCompletionKickThreshold = 0;

	// Message template for the Floor Completion Auto-Kick announcement above. Placeholders: {player} {value} {threshold}.
	public String dungeonFloorCompletionKickMessageTemplate = "{player} kicked - has only completed up to Floor {value} (need {threshold})";

	// Where the Floor Completion Auto-Kick announcement above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party). Independent of every other kick message's delivery.
	public String dungeonFloorCompletionKickDelivery = "LOCAL";

	// The opposite of Floor Completion Auto-Kick above: automatically /party kick (or warn with a manual [Kick] button) a member who's ALREADY completed a floor higher than the threshold below - for carry parties, where someone with real experience past that floor doesn't need to take a carry slot.
	public boolean dungeonFloorCompletionKickMaxEnabled = false;

	// Maximum floor a member is allowed to have already completed - anyone who's completed something higher than this gets kicked by Max Floor Completion Auto-Kick above.
	public int dungeonFloorCompletionKickMaxThreshold = 7;

	// Message template for the Max Floor Completion Auto-Kick announcement above. Placeholders: {player} {value} {threshold}.
	public String dungeonFloorCompletionKickMaxMessageTemplate = "{player} kicked - already completed up to Floor {value} (allowed {threshold})";

	// Where the Max Floor Completion Auto-Kick announcement above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party). Independent of every other kick message's delivery.
	public String dungeonFloorCompletionKickMaxDelivery = "LOCAL";

	// Track deaths and puzzles solved during the run and post a detailed summary in chat (with clickable [Kick] buttons, if you're the party leader) when the dungeon completes. LOCAL only, always - see Party Run Summary below for a party-safe alternative.
	public boolean dungeonRunReportEnabled = false;

	// A short, separate one-line result sent to PARTY chat when a run ends (score/grade/floor/time only) - the detailed Run Report above stays LOCAL-only, since a long combined line with many player names can crash some chat-rendering mods.
	public boolean dungeonRunPartySummaryEnabled = false;

	// Message template for the party run summary above. Placeholders: {floor} {score} {grade} {time}.
	public String dungeonRunPartySummaryTemplate = "{floor} finished - Score: {score} ({grade}) in {time}";

	// Where the party run summary above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party). Defaults to PARTY since that was this message's only behavior before this setting existed.
	public String dungeonRunPartySummaryDelivery = "PARTY";

	// Announce when the boss room is entered.
	public boolean dungeonBossRoomAnnounceEnabled = false;

	// Message template for the boss-room announcement above, used when the run's score is still 300+ at the moment of entry. Placeholder: {player} ("The party" for the party-wide chat-detected trigger, or the specific name for the position-detected per-player trigger).
	public String dungeonBossRoomMessageTemplate = "{player} entered the boss room!";

	// Message template for the SAME boss-room announcement above, used INSTEAD of the one above when the run's score is under 300 at the moment of entry - no more points can be earned past this point. Placeholders: {player} {score}.
	public String dungeonBossRoomLowScoreMessageTemplate = "{player} entered the boss room at only {score} score - no more points can be earned from here, S+ (300) is out of reach!";

	// Where the boss-room announcement above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party).
	public String dungeonBossRoomMessageDelivery = "LOCAL";

	// Automatically /party kick a member once their death count for the current run exceeds the threshold below.
	public boolean dungeonDeathKickEnabled = false;

	// Death count a party member has to exceed (during one run) to trigger Death Auto-Kick above.
	public int dungeonDeathKickThreshold = 3;

	// Automatically /party kick a member who hasn't moved at all for the threshold below during a run - only the party leader can actually kick, so this is silently a no-op otherwise.
	public boolean dungeonAfkKickEnabled = false;

	// How long a member has to stand completely still before AFK Auto-Kick above fires - "30", "60", or "120" seconds. Independent of the Party HUD's own fixed 30s "seems AFK" flag, which always uses 30s regardless of this.
	public String dungeonAfkKickThreshold = "60";

	// Show a live dungeon score HUD during the run (Skill/Explore/Speed/Bonus), calculated the same way SkyblockerMod/Skyblocker does - real completed-rooms/secrets%/puzzle-state/crypts read from the dungeon tab list, not an optimistic ceiling. Position set via the HUD layout editor (default J).
	public boolean dungeonScoreHudEnabled = false;

	// Show a debug HUD with the run tracker's own internal state flags (run active, wither door opened, blood room entered/cleared, boss room entered) - lets you see at a glance whether detection is actually keeping up with what's happening, instead of only finding out something misfired after the fact. Position set via the HUD layout editor (default J).
	public boolean dungeonDebugHudEnabled = false;

	// Removed the "Prefer Skyblocker Score" toggle - Skyblocker's live score (same sidebar/tab-list data, battle-tested far longer) is
	// now ALWAYS used when Skyblocker is installed, unconditionally, not an opt-in. Our own
	// calculateScore() estimate only remains as (a) the fallback when Skyblocker genuinely isn't
	// installed, and (b) the source of the Skill/Explore/Speed/Bonus breakdown, which Skyblocker
	// doesn't expose individually - see DungeonRunTracker#currentDisplayedScore.

	// Show the Skill/Explore/Speed/Bonus breakdown line on the Score HUD.
	public boolean dungeonScoreShowBreakdown = true;

	// Show the current room's secrets found/total (and per-secret found/missing dots) on the Score HUD - only ever populated if Skyblocker is also installed.
	public boolean dungeonScoreShowRoomSecrets = true;

	// Share your current room/floor/secrets/score with party members also running SkyMelloo (and the sky.melloo.me Dungeon lookup page), and show what they share back - relayed over sky.melloo.me (the same rendezvous already used for mod-user detection), invisible to anyone not running the mod too. Room/secrets detail only works for you and them if Skyblocker is ALSO installed (see Show Room Secrets above).
	public boolean dungeonSyncEnabled = true;

	// Show the puzzle outcome blocks and solved/failed detail lines on the Score HUD.
	public boolean dungeonScoreShowPuzzles = true;

	// Show the best-case ceiling score ("Possible: X") next to the current total, plus a breakdown of permanent penalties (puzzle fails, deaths) applied so far, on the Score HUD.
	public boolean dungeonScoreShowPossible = true;

	// Show how many more points are needed to reach the next grade above your current one (e.g. "Next grade (A): +14") on the Score HUD.
	public boolean dungeonScoreShowNextGrade = true;

	// Show an up/down arrow next to the score total showing whether it climbed or fell over the last ~10 seconds, and a persistent "Time left" countdown line against the floor's time limit (turns red once past it) - both live on the Score HUD itself, not sent to chat.
	public boolean dungeonScoreShowPaceAndCountdown = true;

	// Announce in chat when a party member dies during the run.
	public boolean dungeonDeathMessageEnabled = false;

	// Message template for the death announcement above. Placeholders: {player} {count}.
	public String dungeonDeathMessageTemplate = "{player} failed ({count} death(s) this run)";

	// Where the death announcement above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party).
	public String dungeonDeathMessageDelivery = "LOCAL";

	// Warn in chat the moment the Blood Room fight ends if the run's score is still under 300 (S+) - fires as soon as it's known the boss portal is about to exist, while the party can still decide not to go in yet.
	public boolean dungeonPreBossScoreWarningEnabled = false;

	// Message template for the pre-boss-room score warning above. Placeholder: {score}.
	public String dungeonPreBossScoreWarningTemplate = "Don't go in yet - we only have {score} score!";

	// Message template used INSTEAD of the one above when S+ was already confirmed impossible earlier this run for a different reason (a puzzle fail, a death, or the time limit) - avoids implying that waiting or entering now is what costs the points. Placeholder: {score}.
	public String dungeonPreBossScoreWarningAlreadyImpossibleTemplate = "S+ was already out of reach before this ({score} score) - go ahead and enter.";

	// Where the pre-boss-room score warning above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party).
	public String dungeonPreBossScoreWarningDelivery = "LOCAL";

	// Warn once per run (right after a puzzle fail, a death, or the floor's time limit passing) if S+ (300 score) has become mathematically impossible from here, even in the best case for everything still open. Fires at most once - doesn't repeat every subsequent death/fail.
	public boolean dungeonSPlusImpossibleEnabled = false;

	// Message template for the S+-impossible warning above. Placeholder: {reason} ("a puzzle failed", "a death occurred", or "the time limit passed").
	public String dungeonSPlusImpossibleTemplate = "S+ is no longer possible this run - {reason}.";

	// Where the S+-impossible warning above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party).
	public String dungeonSPlusImpossibleDelivery = "LOCAL";

	// The counterpart to the S+ Impossible warning above: if the score ceiling recovers back over 300 afterward (more crypts/secrets/rooms found), say so once. Requires the impossible warning to have fired first - fires at most once per run either way, so this can never come twice even if the estimate flip-flops near 300 afterward.
	public boolean dungeonSPlusBackEnabled = false;

	// Message template for the S+-back-in-reach announcement above.
	public String dungeonSPlusBackTemplate = "S+ is back in reach - the ceiling recovered above 300!";

	// Where the S+-back-in-reach announcement above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party).
	public String dungeonSPlusBackDelivery = "LOCAL";

	// Announce live, the moment the run's grade first reaches a new tier (C/B/A/S/S+) - tracks the highest tier ever reached this run, so a tier already hit once never gets re-announced even if the grade fluctuates back down and up again (e.g. Speed decaying past the time limit).
	public boolean dungeonGradeMilestoneEnabled = false;

	// Message template for the live grade-milestone announcement above. Placeholder: {grade}.
	public String dungeonGradeMilestoneTemplate = "Reached {grade} grade!";

	// Where the grade-milestone announcement above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party).
	public String dungeonGradeMilestoneDelivery = "LOCAL";

	// Nudge yourself via the action bar (personal, on-screen only) the moment you're the only one left who hasn't readied up for a dungeon yet - instead of relying on teammates spamming chat at you. Fires at most once per ready-check.
	public boolean dungeonSelfReadyReminderEnabled = true;

	// Action bar text for the self-ready reminder above.
	public String dungeonSelfReadyReminderText = "Everyone else is ready - your turn!";

	// Warn in chat as S+ is genuinely about to become mathematically unreachable from running out of time (checkpoints below - based on your REAL remaining S+ margin, not just the floor's raw par time, so this stays silent if your Skill/Explore/Bonus ceiling already secures S+ with time to spare), plus show a personal on-screen countdown for the last 10 seconds of the floor's own time limit. Silent if the time limit has no meaning for the current floor (e.g. not in a dungeon).
	public boolean dungeonTimeLimitWarningEnabled = false;

	// Which checkpoint to START warning at - "60", "30", "15", or "10" (seconds of real S+ margin left, see above). Every checkpoint from your chosen one DOWN to 10s fires - e.g. "30" fires at 30s and 10s remaining but not 60s; "15" only fires at 15s and 10s.
	public String dungeonTimeLimitWarningStart = "30";

	// Message template for the time-limit checkpoint warnings above. Placeholder: {time} (e.g. "30 seconds").
	public String dungeonTimeLimitWarningTemplate = "Only {time} left before S+ becomes unreachable - hurry!";

	// Where the time-limit checkpoint warnings above go - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party). The last-10-seconds countdown is always personal/on-screen only regardless of this setting.
	public String dungeonTimeLimitWarningDelivery = "LOCAL";

	// Announce once the floor's time limit is actually exceeded (0s remaining) - separate from the S+-impossible warning (which only fires once per run for whichever reason hits first, so the time limit passing wouldn't get its own message if a puzzle fail or death already triggered it earlier).
	public boolean dungeonTimeLimitExceededEnabled = true;

	// Message template for the time-limit-exceeded announcement above. Placeholder: {floor}.
	public String dungeonTimeLimitExceededTemplate = "Time limit exceeded on {floor}!";

	// Where the time-limit-exceeded announcement above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party).
	public String dungeonTimeLimitExceededDelivery = "LOCAL";

	// Announce once per run, the moment all rooms are discovered (cleared% hits 100), exactly what score is still possible from here - only once every room/secret is known does the best-case ceiling stop being a guess.
	public boolean dungeonRoomsDiscoveredAnnounceEnabled = false;

	// Warn once per run, the moment your current secret-finding RATE first falls behind what's needed to hit the floor's required secrets% before its time limit - independent of the Time Limit Warning checkpoints above (which count down from the end and don't know anything about secrets specifically), so the two don't overlap or repeat the same information.
	public boolean dungeonSecretsPaceWarningEnabled = false;

	// Message template for the secrets pace warning above. Placeholders: {secrets} {required} {timeleft}.
	public String dungeonSecretsPaceWarningTemplate = "Falling behind on secrets pace - {secrets}%/{required}% needed, {timeleft} left!";

	// Where the secrets pace warning above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party).
	public String dungeonSecretsPaceWarningDelivery = "LOCAL";

	// Puzzle rooms can be reset and retried - announce it in chat when the SAME puzzle fails again after already having failed once (confirmed real: Hypixel sends a fresh "PUZZLE FAIL!" line for the retry too). Doesn't double-penalize Skill score or add a duplicate line to the Score HUD either way - this is purely an optional heads-up.
	public boolean dungeonPuzzleRetryFailEnabled = false;

	// Message template for the retry-fail announcement above. Placeholders: {player} {detail}.
	public String dungeonPuzzleRetryFailTemplate = "{player} failed again after resetting the room - {detail}";

	// Where the retry-fail announcement above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party).
	public String dungeonPuzzleRetryFailDelivery = "LOCAL";

	// Message template for the all-rooms-discovered announcement above. Placeholders: {possible}, {grade}.
	public String dungeonRoomsDiscoveredTemplate = "All rooms discovered - best possible score from here: {possible} ({grade}).";

	// Where the all-rooms-discovered announcement above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party).
	public String dungeonRoomsDiscoveredDelivery = "LOCAL";

	// When a run ends, keep the Score HUD up for a while showing a distinct "Final Result" panel (frozen final score/breakdown/puzzles) instead of just disappearing the instant the run ends.
	public boolean dungeonScoreFinalResultEnabled = true;

	// How many seconds the Final Result panel above stays up before the Score HUD disappears.
	public int dungeonScoreFinalResultDurationSeconds = 20;

	// Position of the dungeon score HUD, set via the HUD layout editor (default J).
	public int hudScoreX = 6;
	// See hudScoreX.
	public int hudScoreY = 90;

	// Position of the dungeon debug HUD, set via the HUD layout editor (default J).
	public int hudDebugX = 6;
	// See hudDebugX.
	public int hudDebugY = 260;

	// Master switch for the health/mana bars below (see Health Bar / Mana Bar to toggle each independently). A sleek custom health (green, gold where absorption extends past normal max) and mana (light blue, %) bar pair, replacing the vanilla hearts/XP-bar look. Flashes white briefly where health was just lost. Position set via the HUD layout editor (default J).
	public boolean healthManaBarsEnabled = false;

	// Show the health bar (part of Health/Mana Bars above).
	public boolean healthBarEnabled = false;

	// Show the mana bar (part of Health/Mana Bars above).
	public boolean manaBarEnabled = false;

	// Mana bar next to the health bar instead of stacked below it.
	public boolean healthManaBarsSideBySide = false;

	// Hides Hypixel's own plain Health/Defense/Mana actionbar text above the hotbar, since the custom bars above already show the same info. Only suppresses it while Health/Mana Bars itself is on, in SkyBlock - never touches the actionbar anywhere else.
	public boolean hideNativeStatusActionBarEnabled = true;

	// Position of the health/mana bars, set via the HUD layout editor (default J).
	public int hudHealthManaX = 6;
	// See hudHealthManaX.
	public int hudHealthManaY = 300;

	// Highlight dropped items with a glowing outline and show their name, visible through walls.
	public boolean itemHighlightEnabled = false;

	// Comma-separated, case-insensitive item name filters (e.g. "Enchanted, Diamond"). Leave empty to highlight all dropped items.
	public String itemHighlightNameFilters = "";

	// Glow color for dropped items.
	public Color itemHighlightColor = new Color(0xFF55FF55, true);

	// Show distance in blocks next to highlighted mobs/players/items.
	public boolean showDistanceEnabled = true;

	// Alert when your fishing bobber gets a bite.
	public boolean fishingHelperEnabled = false;

	// Play a sound in addition to the actionbar alert.
	public boolean fishingHelperSound = true;

	// Glow color of your bobber while waiting for a bite.
	public Color fishingWaitingColor = new Color(0xFF5599FF, true);

	// Glow color of your bobber the moment it's biting.
	public Color fishingBitingColor = new Color(0xFFFFAA00, true);

	// A little shooting-gallery minigame while your rod is cast out: pufferfish targets pop up 5-10 blocks in front of you and slowly inflate. Click them before they fully puff up for points (fewer the fatter they are) - chain hits within 2s for a combo. Purely cosmetic.
	public boolean fishingMinigameEnabled = false;

	// Extra glow tint on the fishing minigame targets (on top of their normal pufferfish look).
	public Color fishingMinigameColor = new Color(0xFFFF6600, true);

	// Best single-chain point total ever reached in the fishing minigame. Internal counter, not user-editable.
	public int fishingMinigameHighscore = 0;

	// Highlight chests with a glowing box outline, visible through walls. Experimental: custom render pipeline.
	public boolean chestHighlightEnabled = false;

	// Box color for chests.
	public Color chestHighlightColor = new Color(0xFFFFD700, true);

	// Scan radius (in blocks) around you for Chest Highlight.
	public int blockHighlightRange = 24;

	// Cosmetics are intentionally not exposed here - they live exclusively in the
	// Particle cosmetics (the old Cosmetics tab) moved to MellooEssentials entirely (a hard
	// dependency now) - its own config, not duplicated here. Magic Missile stays - it's a SkyBlock
	// gameplay ability, not a generic cosmetic, and reuses the same "cosmetics" permission key.
	// Punching empty air with an empty hand shoots a small particle projectile that bursts on impact. Purely cosmetic.
	public boolean magicMissileEnabled = false;

	// Spell particle color.
	public Color magicMissileColor = new Color(0xFFAA33FF, true);

	// Which spell type punching empty air casts - "MISSILE" (the default travelling particle projectile) or "LIGHTNING" (instant, only fires if a player is directly under your crosshair, strikes them with a real but visual-only lightning bolt). Switched via the SkyMelloo Menu item's Spells page.
	public String magicMissileSpellType = "MISSILE";

	// Master switch for all debug messages below - each category can still be turned off individually once this is on. Command replies and safety warnings always show regardless.
	public boolean debugMessagesEnabled = false;

	// Debug messages for friend list / party syncing (/friend list, /party list results).
	public boolean debugSync = false;
	// Where Sync debug messages above go - "LOCAL" or "PARTY". Falls back to LOCAL automatically if you're not actually in a party.
	public String debugSyncDelivery = "LOCAL";

	// Debug messages for whitelist checks and per-feature permission fetches. Only ever shown to admin-linked accounts, regardless of this setting.
	public boolean debugPermissions = false;
	// Where Permissions debug messages above go - "LOCAL" or "PARTY". Falls back to LOCAL automatically if you're not actually in a party.
	public String debugPermissionsDelivery = "LOCAL";

	// Debug messages for cloud settings sync (push/pull to sky.melloo.me).
	public boolean debugCloudSync = false;
	// Where Cloud Sync debug messages above go - "LOCAL" or "PARTY". Falls back to LOCAL automatically if you're not actually in a party.
	public String debugCloudSyncDelivery = "LOCAL";

	// Debug messages for mod-presence reporting (detecting other SkyMelloo users nearby).
	public boolean debugPresence = false;
	// Where Presence debug messages above go - "LOCAL" or "PARTY". Falls back to LOCAL automatically if you're not actually in a party.
	public String debugPresenceDelivery = "LOCAL";

	// Debug messages for the party HUD's username/Magical Power resolution.
	public boolean debugParty = false;
	// Where Party debug messages above go - "LOCAL" or "PARTY". Falls back to LOCAL automatically if you're not actually in a party.
	public String debugPartyDelivery = "LOCAL";

	// Debug messages for the dungeon run tracker (floor/cleared%/time detection, run start/end).
	public boolean debugDungeon = false;
	// Where Dungeon debug messages above go - "LOCAL" or "PARTY". Falls back to LOCAL automatically if you're not actually in a party.
	public String debugDungeonDelivery = "LOCAL";

	// Debug messages for staff-encounter scanning (/sm hitstaff).
	public boolean debugStaff = false;
	// Where Staff debug messages above go - "LOCAL" or "PARTY". Falls back to LOCAL automatically if you're not actually in a party.
	public String debugStaffDelivery = "LOCAL";

	// Shows every (color, text) run actually seen in Hypixel's actionbar below the mana bar - for tracking down why mana detection isn't matching.
	public boolean manaDebugEnabled = false;

	// On joining a server, show a "Connecting..." title and check connection quality (ping stability, packet rate) for the first 5 seconds.
	public boolean connectionQualityCheckEnabled = false;

	// Sync your SkyMelloo settings to sky.melloo.me while your account is linked, so a new device/reinstall under the same account can start from your existing settings instead of all defaults, and multiple installs under the same account stay in sync with each other. On by default once linked - cloud is always authoritative on join, and local edits are pushed up the moment the settings screen closes.
	public boolean cloudSyncEnabled = true;

	// Master switch for showing up as "online" to anyone else - other SkyMelloo users' mod-user detection, the in-game Credits menu's online dot, and the website's public online-user count all depend on this. Off means you still report NOTHING about yourself, but you can still see/detect others who have it on. Doesn't affect Cloud Sync (that's private, never shown to anyone else).
	public boolean presenceSharingEnabled = false;

	// Custom status text shown next to your name to other SkyMelloo users nearby (via sky.melloo.me presence). Leave empty to show nothing. Requires presenceSharingEnabled above.
	public String customStatusText = "";

	// A fake "SkyMelloo Menu" item in hotbar slot 8 (not 9 - that's Hypixel's own real SkyBlock Menu slot) whenever that slot is empty - right-click it to open a chest-style menu for SkyMelloo's own settings, the same way Hypixel's own menu item works. Client-side only, never overwrites a real item actually in that slot.
	public boolean skyMellooMenuItemEnabled = true;

	// Position of the fishing minigame score HUD - -1 means "not set yet", uses the default centered-above-hotbar position.
	public int hudFishingScoreX = -1;
	// See hudFishingScoreX.
	public int hudFishingScoreY = -1;

	// Party HUD display mode - "OFF", "COMPACT" (name + MP), or "FULL" (also shows each member's death count for the current dungeon run). Position set via the HUD layout editor (default J).
	public String partyHudMode = "COMPACT";

	// In FULL mode during a run, show each puzzle that member specifically solved/failed as extra lines under their row (only appears for members actually involved in a puzzle - everyone else's row stays as-is).
	public boolean partyHudShowPuzzleHistory = true;

	// A horizontal Magical Power "spread" bar for the party - each member's face is placed along it from lowest to highest MP, with the range labeled above. Shows the SHAPE of the party's gear gap at a glance rather than exact numbers per member. Needs at least 2 members with known MP.
	public boolean partyMpBarEnabled = false;

	// Position of the party HUD (shows your party's members + Magical Power), set via the HUD layout editor (default J).
	public int hudPartyX = 6;
	// See hudPartyX.
	public int hudPartyY = 50;

	// Position of the party MP bar above, set via the HUD layout editor (default J).
	public int hudPartyMpBarX = 6;
	// See hudPartyMpBarX.
	public int hudPartyMpBarY = 220;

	// Running total of players killed (each counted once per lobby/world). Internal counter, not user-editable.
	public int totalPlayersKilled = 0;

	// Running total of Spell Essence collected. Internal counter, not user-editable.
	public int totalSpellEssenceCollected = 0;

	// Running total of missiles cast (punching empty air with an empty hand). Internal counter, not user-editable.
	public int totalSpellsCast = 0;

	// Running total of confirmed own-kills across every victim, lifetime - the number shown on each "Last Kills" entry ("that was your Nth kill"). Internal counter, not user-editable.
	public int totalMagicMissileKills = 0;

	// When you die, post a short "Death Recap" in chat - the last few hits you took and what/who dealt them (real damage-source data, not a proximity guess), so you can actually tell what killed you.
	public boolean deathRecapEnabled = true;

	// Also share a short one-line version of the Death Recap above with the party (e.g. "{player} died - killed by Bonzo (18.2), lava (4.0)") - so teammates can see WHY you died, not just that you did. Independent of the local Death Recap above, which stays LOCAL-only regardless of this.
	public boolean deathRecapPartyAnnounceEnabled = false;

	// Message template for the party death-cause announcement above. Placeholders: {player} {cause}.
	public String deathRecapPartyAnnounceTemplate = "{player} died - killed by {cause}";

	// Where the party death-cause announcement above goes - "LOCAL" or "PARTY" (falls back to LOCAL automatically if you're not actually in a party). Defaults to PARTY since the whole point is telling teammates.
	public String deathRecapPartyAnnounceDelivery = "PARTY";

	// Automatically rejoin Hypixel a few seconds after an unexpected disconnect - Hypixel only, never any other server. Capped at 3 attempts in a row (the streak resets once a connection actually holds for 30+ seconds) so a real kick/ban doesn't turn into hammering reconnect attempts forever.
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
