package com.melloo.skymelloo.client.config;

import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.autogen.AutoGen;
import dev.isxander.yacl3.config.v2.api.autogen.ColorField;
import dev.isxander.yacl3.config.v2.api.autogen.IntSlider;
import dev.isxander.yacl3.config.v2.api.autogen.StringField;
import dev.isxander.yacl3.config.v2.api.autogen.TickBox;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;
import dev.isxander.yacl3.platform.YACLPlatform;
import net.minecraft.resources.Identifier;

import java.awt.Color;
import java.util.LinkedHashSet;
import java.util.Set;

public class SkyMellooConfig {
	public static final ConfigClassHandler<SkyMellooConfig> HANDLER = ConfigClassHandler.createBuilder(SkyMellooConfig.class)
			.id(Identifier.fromNamespaceAndPath("skymelloo", "config"))
			.serializer(config -> GsonConfigSerializerBuilder.create(config)
					.setPath(YACLPlatform.getConfigDir().resolve("skymelloo.json5"))
					.setJson5(true)
					.build())
			.build();

	/**
	 * True if this device has never saved its own local config file - used by
	 * {@link com.melloo.skymelloo.client.social.CloudSyncManager} to decide whether pulling cloud
	 * settings is safe (a fresh device/reinstall) or would silently clobber settings the user
	 * already configured here (an existing install that just hasn't joined a server yet this run).
	 */
	public static boolean hasNoLocalConfigFile() {
		return !java.nio.file.Files.exists(YACLPlatform.getConfigDir().resolve("skymelloo.json5"));
	}

	// Everything below was drastically simplified (2026-07-27) from a fully customizable Highlighting system (name
	// filters, friendly-mob toggle, self/friend/other/NPC player colors, a separate general "Mob
	// Highlighting" for ALL hostile mobs everywhere) down to exactly three fixed, semantic
	// highlights: party members (green), SkyMelloo staff (pink, everywhere - not dungeon-specific),
	// and hostile mobs in your CURRENT dungeon room only (red, dungeon-only). Regular other players and NPCs get no highlight at all anymore.
	// Removed entirely, not just defaulted off: highlightEnabled/highlightNameFilters/defaultHighlightColor/
	// namedHighlightColor/friendlyMobsHighlightEnabled/friendlyHighlightColor (general mob Highlighting), selfHighlightColor,
	// friendHighlightNames/friendHighlightColor (see FriendListSync.java's deletion), otherPlayerHighlightColor,
	// modUserHighlightColor (regular non-staff SkyMelloo users no longer get a color at all),
	// showNpcHighlights/npcHighlightColor, autoSyncFriendsAndParty (only ever served the now-removed friend
	// highlighting - party detection itself already comes from HypixelModAPI, see PartyTracker).

	@AutoGen(category = "highlight", group = "highlight")
	@TickBox
	@SerialEntry(comment = "Highlight hostile mobs inside your CURRENT dungeon room in red, so the ones you still need to clear stand out from mobs elsewhere on the floor. Only active during an active dungeon run.")
	public boolean dungeonRoomMobHighlightEnabled = false;

	@AutoGen(category = "highlight", group = "highlight")
	@ColorField
	@SerialEntry(comment = "Glow color for hostile mobs inside your current dungeon room (see above).")
	public Color dungeonRoomMobHighlightColor = new Color(0xFFFF0000, true);

	@AutoGen(category = "highlight", group = "players")
	@TickBox
	@SerialEntry(comment = "Highlight your party members and SkyMelloo staff (owner/admin/developer) - its own independent switch from the dungeon mob highlighting above.")
	public boolean playerHighlightEnabled = false;

	@AutoGen(category = "highlight", group = "players")
	@ColorField
	@SerialEntry(comment = "Glow color for your current Hypixel party members.")
	public Color partyHighlightColor = new Color(0xFF55FF55, true);

	@AutoGen(category = "highlight", group = "players")
	@TickBox
	@SerialEntry(comment = "A party member's Highlighting (both the glow outline and the nametag marker) blinks bright red once their HP drops under 25% - an urgent \"someone needs help\" signal readable at a glance during a fight.")
	public boolean lowHpBlinkEnabled = true;

	@AutoGen(category = "highlight", group = "players")
	@ColorField
	// Default changed gold -> pink (2026-07-27, "developer admin und owner haben eine rosa aura
	// also genau überall") - resolved server-side from the player's real linked account/team-role
	// (see server.js's roleForUuid), never something another client could self-report to fake.
	@SerialEntry(comment = "Glow color for players whose linked sky.melloo.me account is the owner, an admin, or a developer - works everywhere, not just in dungeons.")
	public Color adminHighlightColor = new Color(0xFFFF66CC, true);

	@AutoGen(category = "highlight", group = "players")
	@ColorField
	// Added back (2026-07-27) with its own aqua/light-blue color, after the general Friend
	// highlighting removal earlier the same day - this is specifically a confirmed SkyMelloo Friend
	// (the mod's own friends system, see FriendsManager/social menu), NOT the old Hypixel-/friend-
	// list-based version, which stays gone.
	@SerialEntry(comment = "Glow color for confirmed SkyMelloo Friends (see the Social menu, key G) - not Hypixel's own /friend list.")
	public Color friendHighlightColor = new Color(0xFF55FFFF, true);

	@AutoGen(category = "highlight", group = "players")
	@TickBox
	@SerialEntry(comment = "Also force the glow outline (visible through walls) on players, not just colored names. Off by default: forcing glow on every player can hide cosmetic layers from mods like Lunar Client (capes/wings) for some players.")
	public boolean playerGlowOutline = false;

	@AutoGen(category = "highlight", group = "players")
	@TickBox
	// New (2026-07-27, "player die unsichtbar sind werden sichtbar und man kann deren nametag sehen
	// das standardmäßig aus") - off by default, since it defeats another player's REAL invisibility
	// (e.g. Invisibility Potion), a much bigger deal than the other cosmetic player-color options
	// above. Deliberately NOT a glow-outline/through-walls effect ("nicht glow outline einfach nur
	// sichtbar machen") - see MissileHitInvisibilityMixin's isInvisible() override, which makes them
	// render as a completely normal, visible player instead, still blocked by walls/line-of-sight.
	@SerialEntry(comment = "Makes other invisible players (e.g. from an Invisibility Potion) render normally instead of staying hidden. Not an Highlighting effect - they're still blocked by walls/line-of-sight like anyone else, just no longer artificially hidden.")
	public boolean showInvisiblePlayers = false;

	@AutoGen(category = "dungeons", group = "info")
	@TickBox
	@SerialEntry(comment = "\"Dungeon Info\": when someone joins your party (e.g. via dungeon party finder), look up their SkyBlock stats (sky.melloo.me/api) and post a summary in chat.")
	public boolean partyJoinStatsEnabled = false;

	@AutoGen(category = "dungeons", group = "info")
	@StringField
	@SerialEntry(comment = "Where the Dungeon Info join announcement goes - \"LOCAL\" (only you see it) or \"PARTY\" (sent for real via /pc, everyone sees it). Falls back to LOCAL automatically if you're not actually in a party.")
	public String dungeonInfoMessageDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "info")
	@TickBox
	@SerialEntry(comment = "Include Magical Power in the Dungeon Info join announcement.")
	public boolean dungeonInfoShowMp = true;

	@AutoGen(category = "dungeons", group = "info")
	@StringField
	@SerialEntry(comment = "Message template for the Dungeon Info join announcement. Placeholders: {username} {mp} {level} {cata} {class} {skillavg} {networth} {rank} {guild} {maxfloor} {qualfloor} {statscore} {expscore} {readiness} {skillsscore} {mpscore} {farmingpoints} {miningpoints} {combatpoints} {foragingpoints} {fishingpoints} {enchantingpoints} {alchemypoints} {tamingpoints} {classpoints} {sblevelpoints} {floorpoints} {completionspoints} {mptier} {catatier} {time} {date}. {maxfloor} = highest floor ever completed; {qualfloor} = highest floor they're currently level-eligible for. {statscore}/{expscore}/{readiness} are 0-1000 rough estimates (see DungeonReadiness) - NOT an authoritative score, just gear/level/experience proxies since Hypixel exposes no per-run damage/performance data at all. {statscore} itself is built from {skillsscore} (all 8 skills + class level + SB level) and {mpscore}, each 0-1000. {farmingpoints} etc. break {skillsscore} down into exactly how many points each skill/class-level/SB-level earned; {floorpoints}/{completionspoints} do the same for {expscore}. {mptier}/{catatier} are text labels (Very Low/Low/Medium/Good/Excellent/Overpowered) for MP and Catacombs level relative to dungeonTargetFloor's benchmark/requirement.")
	public String dungeonInfoMessageTemplate = "§a{username}§r: Cata §b{cata}§r, SB-Level §b{level}§r, Class §e{class}§r, MP §d{mp}§r ({mptier}), Max Floor §b{maxfloor}§r, Qualifies F§b{qualfloor}§r, Readiness §a{readiness}§r/1000 (Skills {skillsscore} · MP {mpscore})";

	@AutoGen(category = "dungeons", group = "info")
	@StringField
	@SerialEntry(comment = "Timezone used for the {time}/{date} placeholders above (IANA zone ID, e.g. Europe/Berlin, America/New_York).")
	public String dungeonInfoTimezone = java.time.ZoneId.systemDefault().getId();

	@AutoGen(category = "dungeons", group = "autoKick")
	@TickBox
	@SerialEntry(comment = "Automatically /party kick a joining member if their chosen stat (below) is under the threshold.")
	public boolean dungeonAutoKickEnabled = false;

	@AutoGen(category = "dungeons", group = "autoKick")
	@StringField
	@SerialEntry(comment = "Which stat Auto-Kick checks - \"MP\" (Magical Power) or \"LEVEL\" (SkyBlock level).")
	public String dungeonAutoKickStat = "MP";

	@AutoGen(category = "dungeons", group = "autoKick")
	@IntSlider(min = 0, max = 500, step = 10)
	@SerialEntry(comment = "Auto-Kick threshold - a joining player whose stat is below this gets kicked from the party.")
	public int dungeonAutoKickThreshold = 100;

	@AutoGen(category = "dungeons", group = "autoKick")
	@StringField
	@SerialEntry(comment = "Message template for the Auto-Kick announcement above. Placeholders: {player} {stat} {value} {threshold}.")
	public String dungeonAutoKickMessageTemplate = "{player} kicked - {stat} too low ({value} < {threshold})";

	@AutoGen(category = "dungeons", group = "autoKick")
	@StringField
	@SerialEntry(comment = "Where the Auto-Kick announcement above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party). Independent of every other kick message's delivery below.")
	public String dungeonAutoKickDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "autoKick")
	@TickBox
	@SerialEntry(comment = "The opposite of Auto-Kick above: automatically /party kick a joining member if their chosen stat (below) is OVER the threshold - for carry parties, where an overqualified joiner defeats the point (they don't need carrying, and may be taking a slot from someone who does).")
	public boolean dungeonAutoKickMaxEnabled = false;

	@AutoGen(category = "dungeons", group = "autoKick")
	@StringField
	@SerialEntry(comment = "Which stat the Max Auto-Kick above checks - \"MP\" (Magical Power) or \"LEVEL\" (SkyBlock level). Independent of the MIN stat above - a party can reasonably check both at once (e.g. MP for the min, Level for the max) or just one.")
	public String dungeonAutoKickMaxStat = "MP";

	@AutoGen(category = "dungeons", group = "autoKick")
	@IntSlider(min = 0, max = 500, step = 10)
	@SerialEntry(comment = "Max Auto-Kick threshold - a joining player whose stat is OVER this gets kicked from the party.")
	public int dungeonAutoKickMaxThreshold = 300;

	@AutoGen(category = "dungeons", group = "autoKick")
	@StringField
	@SerialEntry(comment = "Message template for the Max Auto-Kick announcement above. Placeholders: {player} {stat} {value} {threshold}.")
	public String dungeonAutoKickMaxMessageTemplate = "{player} kicked - {stat} too high for a carry ({value} > {threshold})";

	@AutoGen(category = "dungeons", group = "autoKick")
	@StringField
	@SerialEntry(comment = "Where the Max Auto-Kick announcement above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party). Independent of every other kick message's delivery.")
	public String dungeonAutoKickMaxDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "floorRequirement")
	@IntSlider(min = 0, max = 7, step = 1)
	@SerialEntry(comment = "Which floor to benchmark the readiness/stats score against (0 = Entrance, 7 = Floor VII) - see DungeonReadiness. Magical Power expectations are very different for Entrance vs. Floor VII, so this has to be set manually since there's no way to auto-detect which floor a party is actually planning to run before it starts.")
	public int dungeonTargetFloor = 7;

	@AutoGen(category = "dungeons", group = "floorRequirement")
	@TickBox
	@SerialEntry(comment = "Automatically /party kick (or warn with a manual [Kick] button, same behavior as the MP/Level Auto-Kick above) a party member who doesn't currently meet the REQUIREMENTS (Catacombs/Combat Skill, verified in-game) for the floor threshold below - not whether they've ever actually completed it before. Uses the same sky.melloo.me stats lookup as the join announcement, no extra API calls.")
	public boolean dungeonFloorKickEnabled = false;

	@AutoGen(category = "dungeons", group = "floorRequirement")
	@IntSlider(min = 0, max = 7, step = 1)
	@SerialEntry(comment = "Minimum floor a member needs to be level-ELIGIBLE for right now (0 = just needs Combat Skill 15 for Entrance, 7 = needs Catacombs Skill 24 for Floor VII). Based on the real Hypixel requirements (Combat 15 for Entrance, then Catacombs 1/3/5/9/14/19/24 for Floors I-VII) - Master Mode requirements aren't included, only Normal Mode.")
	public int dungeonFloorKickThreshold = 0;

	@AutoGen(category = "dungeons", group = "floorRequirement")
	@StringField
	@SerialEntry(comment = "Message template for the Floor Auto-Kick announcement above. Placeholders: {player} {value} {threshold}.")
	public String dungeonFloorKickMessageTemplate = "{player} kicked - only qualifies up to Floor {value} (need {threshold})";

	@AutoGen(category = "dungeons", group = "floorRequirement")
	@StringField
	@SerialEntry(comment = "Where the Floor Auto-Kick announcement above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party). Independent of every other kick message's delivery.")
	public String dungeonFloorKickDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "floorRequirement")
	@TickBox
	@SerialEntry(comment = "The opposite of Floor Auto-Kick above: automatically /party kick (or warn with a manual [Kick] button) a member who's ALREADY level-eligible for a floor higher than the threshold below - for carry parties, where someone who already qualifies for that floor doesn't need to be carried through it.")
	public boolean dungeonFloorKickMaxEnabled = false;

	@AutoGen(category = "dungeons", group = "floorRequirement")
	@IntSlider(min = 0, max = 7, step = 1)
	@SerialEntry(comment = "Maximum floor a member is allowed to already be level-eligible for - anyone eligible for something higher than this gets kicked by Max Floor Auto-Kick above.")
	public int dungeonFloorKickMaxThreshold = 7;

	@AutoGen(category = "dungeons", group = "floorRequirement")
	@StringField
	@SerialEntry(comment = "Message template for the Max Floor Auto-Kick announcement above. Placeholders: {player} {value} {threshold}.")
	public String dungeonFloorKickMaxMessageTemplate = "{player} kicked - already qualifies up to Floor {value} (allowed {threshold})";

	@AutoGen(category = "dungeons", group = "floorRequirement")
	@StringField
	@SerialEntry(comment = "Where the Max Floor Auto-Kick announcement above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party). Independent of every other kick message's delivery.")
	public String dungeonFloorKickMaxDelivery = "LOCAL";

	// "nicht nur max und min catacomb level aber auch so completion welchen höchsten floor die schon
	// haben also completed und auch freigeschaltet" (2026-07-27) - the floorRequirement group above
	// only ever checked whether a member currently MEETS THE REQUIREMENTS for a floor (Catacombs/Combat
	// Skill), never whether they've actually COMPLETED it before. Real completion (Hypixel's own
	// highest_tier_completed) is a genuinely different, independent signal - a party can reasonably
	// want either, both, or neither, same as every other kick check here.
	@AutoGen(category = "dungeons", group = "floorCompletion")
	@TickBox
	@SerialEntry(comment = "Automatically /party kick (or warn with a manual [Kick] button) a member who hasn't ACTUALLY COMPLETED the floor threshold below yet - unlike Floor Auto-Kick above, which only checks whether they currently meet the level requirements, not real experience with that floor.")
	public boolean dungeonFloorCompletionKickEnabled = false;

	@AutoGen(category = "dungeons", group = "floorCompletion")
	@IntSlider(min = 0, max = 7, step = 1)
	@SerialEntry(comment = "Minimum floor a member needs to have ACTUALLY COMPLETED before (0 = Entrance, 7 = Floor VII), per Hypixel's own completion record.")
	public int dungeonFloorCompletionKickThreshold = 0;

	@AutoGen(category = "dungeons", group = "floorCompletion")
	@StringField
	@SerialEntry(comment = "Message template for the Floor Completion Auto-Kick announcement above. Placeholders: {player} {value} {threshold}.")
	public String dungeonFloorCompletionKickMessageTemplate = "{player} kicked - has only completed up to Floor {value} (need {threshold})";

	@AutoGen(category = "dungeons", group = "floorCompletion")
	@StringField
	@SerialEntry(comment = "Where the Floor Completion Auto-Kick announcement above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party). Independent of every other kick message's delivery.")
	public String dungeonFloorCompletionKickDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "floorCompletion")
	@TickBox
	@SerialEntry(comment = "The opposite of Floor Completion Auto-Kick above: automatically /party kick (or warn with a manual [Kick] button) a member who's ALREADY completed a floor higher than the threshold below - for carry parties, where someone with real experience past that floor doesn't need to take a carry slot.")
	public boolean dungeonFloorCompletionKickMaxEnabled = false;

	@AutoGen(category = "dungeons", group = "floorCompletion")
	@IntSlider(min = 0, max = 7, step = 1)
	@SerialEntry(comment = "Maximum floor a member is allowed to have already completed - anyone who's completed something higher than this gets kicked by Max Floor Completion Auto-Kick above.")
	public int dungeonFloorCompletionKickMaxThreshold = 7;

	@AutoGen(category = "dungeons", group = "floorCompletion")
	@StringField
	@SerialEntry(comment = "Message template for the Max Floor Completion Auto-Kick announcement above. Placeholders: {player} {value} {threshold}.")
	public String dungeonFloorCompletionKickMaxMessageTemplate = "{player} kicked - already completed up to Floor {value} (allowed {threshold})";

	@AutoGen(category = "dungeons", group = "floorCompletion")
	@StringField
	@SerialEntry(comment = "Where the Max Floor Completion Auto-Kick announcement above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party). Independent of every other kick message's delivery.")
	public String dungeonFloorCompletionKickMaxDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Track deaths and puzzles solved during the run and post a detailed summary in chat (with clickable [Kick] buttons, if you're the party leader) when the dungeon completes. LOCAL only, always - see Party Run Summary below for a party-safe alternative.")
	public boolean dungeonRunReportEnabled = false;

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "A short, separate one-line result sent to PARTY chat when a run ends (score/grade/floor/time only, no per-death/per-puzzle detail) - independent of the detailed Run Report above, which used to be sendable to party too but a long combined line with many player names in it could crash the game client-side (a chat-rendering mod choking on it). This one is small and safe by design.")
	public boolean dungeonRunPartySummaryEnabled = false;

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Message template for the party run summary above. Placeholders: {floor} {score} {grade} {time}.")
	public String dungeonRunPartySummaryTemplate = "{floor} finished - Score: {score} ({grade}) in {time}";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Where the party run summary above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party). Defaults to PARTY since that was this message's only behavior before this setting existed.")
	public String dungeonRunPartySummaryDelivery = "PARTY";

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Announce when the boss room is entered.")
	public boolean dungeonBossRoomAnnounceEnabled = false;

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Message template for the boss-room announcement above, used when the run's score is still 300+ at the moment of entry. Placeholder: {player} (\"The party\" for the party-wide chat-detected trigger, or the specific name for the position-detected per-player trigger).")
	public String dungeonBossRoomMessageTemplate = "{player} entered the boss room!";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Message template for the SAME boss-room announcement above, used INSTEAD of the one above when the run's score is under 300 at the moment of entry - no more points can be earned past this point. Placeholders: {player} {score}.")
	public String dungeonBossRoomLowScoreMessageTemplate = "{player} entered the boss room at only {score} score - no more points can be earned from here, S+ (300) is out of reach!";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Where the boss-room announcement above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party).")
	public String dungeonBossRoomMessageDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Automatically /party kick a member once their death count for the current run exceeds the threshold below.")
	public boolean dungeonDeathKickEnabled = false;

	@AutoGen(category = "dungeons", group = "runTracker")
	@IntSlider(min = 1, max = 10, step = 1)
	@SerialEntry(comment = "Death count a party member has to exceed (during one run) to trigger Death Auto-Kick above.")
	public int dungeonDeathKickThreshold = 3;

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Automatically /party kick a member who hasn't moved at all for the threshold below during a run - only the party leader can actually kick, so this is silently a no-op otherwise.")
	public boolean dungeonAfkKickEnabled = false;

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "How long a member has to stand completely still before AFK Auto-Kick above fires - \"30\", \"60\", or \"120\" seconds. Independent of the Party HUD's own fixed 30s \"seems AFK\" flag, which always uses 30s regardless of this.")
	public String dungeonAfkKickThreshold = "60";

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Show a live dungeon score HUD during the run (Skill/Explore/Speed/Bonus), calculated the same way SkyblockerMod/Skyblocker does - real completed-rooms/secrets%/puzzle-state/crypts read from the dungeon tab list, not an optimistic ceiling. Position set via the HUD layout editor (default J).")
	public boolean dungeonScoreHudEnabled = false;

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Show a debug HUD with the run tracker's own internal state flags (run active, wither door opened, blood room entered/cleared, boss room entered) - lets you see at a glance whether detection is actually keeping up with what's happening, instead of only finding out something misfired after the fact. Position set via the HUD layout editor (default J).")
	public boolean dungeonDebugHudEnabled = false;

	// Removed the "Prefer Skyblocker Score" toggle (2026-07-27, "IMMER skyblocker score nutzen nicht
	// den eigenen") - Skyblocker's live score (same sidebar/tab-list data, battle-tested far longer) is
	// now ALWAYS used when Skyblocker is installed, unconditionally, not an opt-in. Our own
	// calculateScore() estimate only remains as (a) the fallback when Skyblocker genuinely isn't
	// installed, and (b) the source of the Skill/Explore/Speed/Bonus breakdown, which Skyblocker
	// doesn't expose individually - see DungeonRunTracker#currentDisplayedScore.

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Show the Skill/Explore/Speed/Bonus breakdown line on the Score HUD.")
	public boolean dungeonScoreShowBreakdown = true;

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Show the current room's secrets found/total (and per-secret found/missing dots) on the Score HUD - only ever populated if Skyblocker is also installed.")
	public boolean dungeonScoreShowRoomSecrets = true;

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Share your current room/floor/secrets/score with party members also running SkyMelloo (and the sky.melloo.me Dungeon lookup page), and show what they share back - relayed over sky.melloo.me (the same rendezvous already used for mod-user detection), invisible to anyone not running the mod too. Room/secrets detail only works for you and them if Skyblocker is ALSO installed (see Show Room Secrets above).")
	public boolean dungeonSyncEnabled = true;

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Show the puzzle outcome blocks and solved/failed detail lines on the Score HUD.")
	public boolean dungeonScoreShowPuzzles = true;

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Show the best-case ceiling score (\"Possible: X\") next to the current total, plus a breakdown of permanent penalties (puzzle fails, deaths) applied so far, on the Score HUD.")
	public boolean dungeonScoreShowPossible = true;

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Show how many more points are needed to reach the next grade above your current one (e.g. \"Next grade (A): +14\") on the Score HUD (2026-07-27).")
	public boolean dungeonScoreShowNextGrade = true;

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Show an up/down arrow next to the score total showing whether it climbed or fell over the last ~10 seconds, and a persistent \"Time left\" countdown line against the floor's time limit (turns red once past it) - both live on the Score HUD itself, not sent to chat.")
	public boolean dungeonScoreShowPaceAndCountdown = true;

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Announce in chat when a party member dies during the run.")
	public boolean dungeonDeathMessageEnabled = false;

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Message template for the death announcement above. Placeholders: {player} {count}.")
	public String dungeonDeathMessageTemplate = "{player} failed ({count} death(s) this run)";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Where the death announcement above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party).")
	public String dungeonDeathMessageDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Warn in chat the moment the Blood Room fight ends if the run's score is still under 300 (S+) - fires as soon as it's known the boss portal is about to exist, while the party can still decide not to go in yet.")
	public boolean dungeonPreBossScoreWarningEnabled = false;

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Message template for the pre-boss-room score warning above. Placeholder: {score}.")
	public String dungeonPreBossScoreWarningTemplate = "Don't go in yet - we only have {score} score!";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Message template used INSTEAD of the one above when S+ was already confirmed impossible earlier this run for a different reason (a puzzle fail, a death, or the time limit) - avoids implying that waiting or entering now is what costs the points. Placeholder: {score}.")
	public String dungeonPreBossScoreWarningAlreadyImpossibleTemplate = "S+ was already out of reach before this ({score} score) - go ahead and enter.";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Where the pre-boss-room score warning above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party).")
	public String dungeonPreBossScoreWarningDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Warn once per run (right after a puzzle fail, a death, or the floor's time limit passing) if S+ (300 score) has become mathematically impossible from here, even in the best case for everything still open. Fires at most once - doesn't repeat every subsequent death/fail.")
	public boolean dungeonSPlusImpossibleEnabled = false;

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Message template for the S+-impossible warning above. Placeholder: {reason} (\"a puzzle failed\", \"a death occurred\", or \"the time limit passed\").")
	public String dungeonSPlusImpossibleTemplate = "S+ is no longer possible this run - {reason}.";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Where the S+-impossible warning above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party).")
	public String dungeonSPlusImpossibleDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "The counterpart to the S+ Impossible warning above: if the score ceiling recovers back over 300 afterward (more crypts/secrets/rooms found), say so once. Requires the impossible warning to have fired first - fires at most once per run either way, so this can never come twice even if the estimate flip-flops near 300 afterward.")
	public boolean dungeonSPlusBackEnabled = false;

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Message template for the S+-back-in-reach announcement above.")
	public String dungeonSPlusBackTemplate = "S+ is back in reach - the ceiling recovered above 300!";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Where the S+-back-in-reach announcement above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party).")
	public String dungeonSPlusBackDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Announce live, the moment the run's grade first reaches a new tier (C/B/A/S/S+) - tracks the highest tier ever reached this run, so a tier already hit once never gets re-announced even if the grade fluctuates back down and up again (e.g. Speed decaying past the time limit).")
	public boolean dungeonGradeMilestoneEnabled = false;

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Message template for the live grade-milestone announcement above. Placeholder: {grade}.")
	public String dungeonGradeMilestoneTemplate = "Reached {grade} grade!";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Where the grade-milestone announcement above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party).")
	public String dungeonGradeMilestoneDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Nudge yourself via the action bar (personal, on-screen only) the moment you're the only one left who hasn't readied up for a dungeon yet - instead of relying on teammates spamming chat at you. Fires at most once per ready-check.")
	public boolean dungeonSelfReadyReminderEnabled = true;

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Action bar text for the self-ready reminder above.")
	public String dungeonSelfReadyReminderText = "Everyone else is ready - your turn!";

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Warn in chat as S+ is genuinely about to become mathematically unreachable from running out of time (checkpoints below - based on your REAL remaining S+ margin, not just the floor's raw par time, so this stays silent if your Skill/Explore/Bonus ceiling already secures S+ with time to spare), plus show a personal on-screen countdown for the last 10 seconds of the floor's own time limit. Silent if the time limit has no meaning for the current floor (e.g. not in a dungeon).")
	public boolean dungeonTimeLimitWarningEnabled = false;

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Which checkpoint to START warning at - \"60\", \"30\", \"15\", or \"10\" (seconds of real S+ margin left, see above). Every checkpoint from your chosen one DOWN to 10s fires - e.g. \"30\" fires at 30s and 10s remaining but not 60s; \"15\" only fires at 15s and 10s.")
	public String dungeonTimeLimitWarningStart = "30";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Message template for the time-limit checkpoint warnings above. Placeholder: {time} (e.g. \"30 seconds\").")
	public String dungeonTimeLimitWarningTemplate = "Only {time} left before S+ becomes unreachable - hurry!";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Where the time-limit checkpoint warnings above go - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party). The last-10-seconds countdown is always personal/on-screen only regardless of this setting.")
	public String dungeonTimeLimitWarningDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Announce once the floor's time limit is actually exceeded (0s remaining) - separate from the S+-impossible warning (which only fires once per run for whichever reason hits first, so the time limit passing wouldn't get its own message if a puzzle fail or death already triggered it earlier).")
	public boolean dungeonTimeLimitExceededEnabled = true;

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Message template for the time-limit-exceeded announcement above. Placeholder: {floor}.")
	public String dungeonTimeLimitExceededTemplate = "Time limit exceeded on {floor}!";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Where the time-limit-exceeded announcement above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party).")
	public String dungeonTimeLimitExceededDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Announce once per run, the moment all rooms are discovered (cleared% hits 100), exactly what score is still possible from here - only once every room/secret is known does the best-case ceiling stop being a guess.")
	public boolean dungeonRoomsDiscoveredAnnounceEnabled = false;

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Warn once per run, the moment your current secret-finding RATE first falls behind what's needed to hit the floor's required secrets% before its time limit - independent of the Time Limit Warning checkpoints above (which count down from the end and don't know anything about secrets specifically), so the two don't overlap or repeat the same information.")
	public boolean dungeonSecretsPaceWarningEnabled = false;

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Message template for the secrets pace warning above. Placeholders: {secrets} {required} {timeleft}.")
	public String dungeonSecretsPaceWarningTemplate = "Falling behind on secrets pace - {secrets}%/{required}% needed, {timeleft} left!";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Where the secrets pace warning above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party).")
	public String dungeonSecretsPaceWarningDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "Puzzle rooms can be reset and retried - announce it in chat when the SAME puzzle fails again after already having failed once (confirmed real: Hypixel sends a fresh \"PUZZLE FAIL!\" line for the retry too). Doesn't double-penalize Skill score or add a duplicate line to the Score HUD either way - this is purely an optional heads-up.")
	public boolean dungeonPuzzleRetryFailEnabled = false;

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Message template for the retry-fail announcement above. Placeholders: {player} {detail}.")
	public String dungeonPuzzleRetryFailTemplate = "{player} failed again after resetting the room - {detail}";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Where the retry-fail announcement above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party).")
	public String dungeonPuzzleRetryFailDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Message template for the all-rooms-discovered announcement above. Placeholders: {possible}, {grade}.")
	public String dungeonRoomsDiscoveredTemplate = "All rooms discovered - best possible score from here: {possible} ({grade}).";

	@AutoGen(category = "dungeons", group = "runTracker")
	@StringField
	@SerialEntry(comment = "Where the all-rooms-discovered announcement above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party).")
	public String dungeonRoomsDiscoveredDelivery = "LOCAL";

	@AutoGen(category = "dungeons", group = "runTracker")
	@TickBox
	@SerialEntry(comment = "When a run ends, keep the Score HUD up for a while showing a distinct \"Final Result\" panel (frozen final score/breakdown/puzzles) instead of just disappearing the instant the run ends.")
	public boolean dungeonScoreFinalResultEnabled = true;

	@AutoGen(category = "dungeons", group = "runTracker")
	@IntSlider(min = 5, max = 60, step = 5)
	@SerialEntry(comment = "How many seconds the Final Result panel above stays up before the Score HUD disappears.")
	public int dungeonScoreFinalResultDurationSeconds = 20;

	@SerialEntry(comment = "Position of the dungeon score HUD, set via the HUD layout editor (default J).")
	public int hudScoreX = 6;
	@SerialEntry(comment = "See hudScoreX.")
	public int hudScoreY = 90;

	@SerialEntry(comment = "Position of the dungeon debug HUD, set via the HUD layout editor (default J).")
	public int hudDebugX = 6;
	@SerialEntry(comment = "See hudDebugX.")
	public int hudDebugY = 260;

	@AutoGen(category = "general", group = "hud")
	@TickBox
	@SerialEntry(comment = "Master switch for the health/mana bars below (see Health Bar / Mana Bar to toggle each independently). A sleek custom health (green, gold where absorption extends past normal max) and mana (light blue, %) bar pair, replacing the vanilla hearts/XP-bar look. Flashes white briefly where health was just lost. Position set via the HUD layout editor (default J).")
	public boolean healthManaBarsEnabled = false;

	@AutoGen(category = "general", group = "hud")
	@TickBox
	@SerialEntry(comment = "Show the health bar (part of Health/Mana Bars above).")
	public boolean healthBarEnabled = false;

	@AutoGen(category = "general", group = "hud")
	@TickBox
	@SerialEntry(comment = "Show the mana bar (part of Health/Mana Bars above).")
	public boolean manaBarEnabled = false;

	@AutoGen(category = "general", group = "hud")
	@TickBox
	@SerialEntry(comment = "Mana bar next to the health bar instead of stacked below it.")
	public boolean healthManaBarsSideBySide = false;

	@AutoGen(category = "general", group = "hud")
	@TickBox
	@SerialEntry(comment = "Hides Hypixel's own plain Health/Defense/Mana actionbar text above the hotbar, since the custom bars above already show the same info (2026-07-26). Only suppresses it while Health/Mana Bars itself is on, in SkyBlock - never touches the actionbar anywhere else.")
	public boolean hideNativeStatusActionBarEnabled = true;

	@SerialEntry(comment = "Position of the health/mana bars, set via the HUD layout editor (default J).")
	public int hudHealthManaX = 6;
	@SerialEntry(comment = "See hudHealthManaX.")
	public int hudHealthManaY = 300;

	@AutoGen(category = "general", group = "hud")
	@TickBox
	@SerialEntry(comment = "FPS, ping, server address, area (real Hypixel Mod API data), coordinates, and facing direction. No TPS line - Hypixel doesn't expose real server tick rate to the client. Position set via the HUD layout editor (default J).")
	public boolean playerInfoHudEnabled = true;

	@SerialEntry(comment = "Position of the player info HUD, set via the HUD layout editor (default J).")
	public int hudPlayerInfoX = 6;
	@SerialEntry(comment = "See hudPlayerInfoX.")
	public int hudPlayerInfoY = 340;

	@AutoGen(category = "item", group = "item")
	@TickBox
	@SerialEntry(comment = "Highlight dropped items with a glowing outline and show their name, visible through walls.")
	public boolean itemHighlightEnabled = false;

	@AutoGen(category = "item", group = "item")
	@StringField
	@SerialEntry(comment = "Comma-separated, case-insensitive item name filters (e.g. \"Enchanted, Diamond\"). Leave empty to highlight all dropped items.")
	public String itemHighlightNameFilters = "";

	@AutoGen(category = "item", group = "item")
	@ColorField
	@SerialEntry(comment = "Glow color for dropped items.")
	public Color itemHighlightColor = new Color(0xFF55FF55, true);

	@AutoGen(category = "hp", group = "hp")
	@TickBox
	@SerialEntry(comment = "Show distance in blocks next to Highlighting'd mobs/players/items.")
	public boolean highlightShowDistance = true;

	@AutoGen(category = "fishing", group = "fishing")
	@TickBox
	@SerialEntry(comment = "Alert when your fishing bobber gets a bite.")
	public boolean fishingHelperEnabled = false;

	@AutoGen(category = "fishing", group = "fishing")
	@TickBox
	@SerialEntry(comment = "Play a sound in addition to the actionbar alert.")
	public boolean fishingHelperSound = true;

	@AutoGen(category = "fishing", group = "fishing")
	@ColorField
	@SerialEntry(comment = "Glow color of your bobber while waiting for a bite.")
	public Color fishingWaitingColor = new Color(0xFF5599FF, true);

	@AutoGen(category = "fishing", group = "fishing")
	@ColorField
	@SerialEntry(comment = "Glow color of your bobber the moment it's biting.")
	public Color fishingBitingColor = new Color(0xFFFFAA00, true);

	@AutoGen(category = "fishing", group = "minigame")
	@TickBox
	@SerialEntry(comment = "A little shooting-gallery minigame while your rod is cast out: pufferfish targets pop up 5-10 blocks in front of you and slowly inflate. Click them before they fully puff up for points (fewer the fatter they are) - chain hits within 2s for a combo. Purely cosmetic.")
	public boolean fishingMinigameEnabled = false;

	@AutoGen(category = "fishing", group = "minigame")
	@ColorField
	@SerialEntry(comment = "Extra glow tint on the fishing minigame targets (on top of their normal pufferfish look).")
	public Color fishingMinigameColor = new Color(0xFFFF6600, true);

	@SerialEntry(comment = "Best single-chain point total ever reached in the fishing minigame. Internal counter, not user-editable.")
	public int fishingMinigameHighscore = 0;

	@AutoGen(category = "block", group = "chest")
	@TickBox
	@SerialEntry(comment = "Highlight chests with a glowing box outline, visible through walls. Experimental: custom render pipeline.")
	public boolean chestHighlightEnabled = false;

	@AutoGen(category = "block", group = "chest")
	@ColorField
	@SerialEntry(comment = "Box color for chests.")
	public Color chestHighlightColor = new Color(0xFFFFD700, true);

	@AutoGen(category = "block", group = "block")
	@IntSlider(min = 8, max = 48, step = 8)
	@SerialEntry(comment = "Scan radius (in blocks) around you for Chest Highlight.")
	public int blockHighlightRange = 24;

	// Cosmetics are intentionally NOT exposed via @AutoGen/YACL - they live exclusively in the
	// Cosmetics tab of SkyMellooSettingsScreen (opened with J/H or /skymelloo cosmetics/config),
	// not the YACL menu. These fields still persist normally via @SerialEntry.
	@SerialEntry(comment = "Rotating ring of particles above your head.")
	public boolean haloEnabled = false;

	@SerialEntry(comment = "Halo particle color.")
	public Color haloColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "Layer a larger, brighter particle behind Halo's normal one for a stronger glow look.")
	public boolean haloGlow = false;

	@SerialEntry(comment = "\"DUST\" uses the normal colored particle above; any other ParticleKind name uses that fixed particle instead (ignoring the color).")
	public String haloParticleKind = "DUST";

	@SerialEntry(comment = "Cherry blossom petals gently falling around you.")
	public boolean cherryBlossomEnabled = false;

	@SerialEntry(comment = "Which particle Cherry Blossom spawns (see ParticleKind).")
	public String cherryBlossomParticle = "CHERRY_BLOSSOM";

	@SerialEntry(comment = "Double-helix spiral of particles around you.")
	public boolean rainbowHelixEnabled = false;

	@SerialEntry(comment = "Rainbow helix particle color.")
	public Color rainbowHelixColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "Slow, wide double ring of particles orbiting around your body.")
	public boolean auraEnabled = false;

	@SerialEntry(comment = "Aura particle color.")
	public Color auraColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "Layer a larger, brighter particle behind Aura's normal one for a stronger glow look.")
	public boolean auraGlow = false;

	@SerialEntry(comment = "\"DUST\" uses the normal colored particle above; any other ParticleKind name uses that fixed particle instead (ignoring the color).")
	public String auraParticleKind = "DUST";

	@SerialEntry(comment = "Punching empty air with an empty hand shoots a small particle projectile that bursts on impact. Purely cosmetic.")
	public boolean magicMissileEnabled = false;

	@SerialEntry(comment = "Spell particle color.")
	public Color magicMissileColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "Which spell type punching empty air casts - \"MISSILE\" (the default travelling particle projectile) or \"LIGHTNING\" (instant, only fires if a player is directly under your crosshair, strikes them with a real but visual-only lightning bolt). Switched via the SkyMelloo Menu item's Spells page.")
	public String magicMissileSpellType = "MISSILE";

	@SerialEntry(comment = "A ring of particles that pulses/expands outward from your feet on repeat.")
	public boolean waveEnabled = false;

	@SerialEntry(comment = "Wave particle color.")
	public Color waveColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "A small cloud above your head that continuously rains on you.")
	public boolean rainCloudEnabled = false;

	@SerialEntry(comment = "A rotating ring of small flames at your feet.")
	public boolean fireRingEnabled = false;

	@SerialEntry(comment = "Which particle Fire Ring spawns (see ParticleKind).")
	public String fireRingParticle = "FLAME";

	@SerialEntry(comment = "Sparkling particles drifting slowly down from above your head.")
	public boolean starRainEnabled = false;

	@SerialEntry(comment = "Which particle Star Rain spawns (see ParticleKind).")
	public String starRainParticle = "SPARKLE";

	@SerialEntry(comment = "Occasional electric sparks crackling around your body.")
	public boolean sparkAuraEnabled = false;

	@SerialEntry(comment = "Which particle Spark Aura spawns (see ParticleKind).")
	public String sparkAuraParticle = "SPARK";

	@SerialEntry(comment = "A 3D Lissajous curve (math-generated knot pattern) weaving around your body.")
	public boolean lissajousEnabled = false;

	@SerialEntry(comment = "Lissajous curve particle color.")
	public Color lissajousColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "A dense, constantly-morphing rose/rhodonea curve (r = cos(k*theta)) traced with many simultaneous points - much busier/more intense than the Lissajous curve.")
	public boolean roseCurveEnabled = false;

	@SerialEntry(comment = "Rose curve particle color.")
	public Color roseCurveColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "A one-shot expanding shockwave ring every time you land on the ground from a jump/fall.")
	public boolean landingShockwaveEnabled = false;

	@SerialEntry(comment = "Landing shockwave color.")
	public Color landingShockwaveColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "Occasional sparkle bursts above your head, like distant fireworks.")
	public boolean fireworkBurstEnabled = false;

	@SerialEntry(comment = "Which particle Firework Burst spawns (see ParticleKind).")
	public String fireworkBurstParticle = "FIREWORK";

	@SerialEntry(comment = "Snowflakes swirling tight and close around your body.")
	public boolean frostAuraEnabled = false;

	@SerialEntry(comment = "Musical notes floating up above your head occasionally.")
	public boolean noteMelodyEnabled = false;

	@SerialEntry(comment = "Which particle Note Melody spawns (see ParticleKind).")
	public String noteMelodyParticle = "NOTE";

	@SerialEntry(comment = "A fast-spinning double vortex of colored particles around your whole body.")
	public boolean portalVortexEnabled = false;

	@SerialEntry(comment = "Portal vortex particle color.")
	public Color portalVortexColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "Floating hearts drifting up above your head occasionally.")
	public boolean heartTrailEnabled = false;

	@SerialEntry(comment = "Which particle Heart Trail spawns (see ParticleKind).")
	public String heartTrailParticle = "HEART";

	@SerialEntry(comment = "A multi-arm spiral (a different curve family than Rose/Lissajous) fanning outward from your feet to above your head.")
	public boolean spiralGalaxyEnabled = false;

	@SerialEntry(comment = "Spiral galaxy particle color.")
	public Color spiralGalaxyColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "Leaves a particle trail behind you while airborne (jumping/falling), tracing your actual arc.")
	public boolean jumpTrailEnabled = false;

	@SerialEntry(comment = "Jump trail particle color.")
	public Color jumpTrailColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "Occasional Totem-of-Undying-style flash burst above your head.")
	public boolean totemFlashEnabled = false;

	@SerialEntry(comment = "Which particle Totem Flash spawns (see ParticleKind).")
	public String totemFlashParticle = "TOTEM";

	@SerialEntry(comment = "A dark, spooky pulsing ring of sculk particles at your feet.")
	public boolean sculkPulseEnabled = false;

	@SerialEntry(comment = "Which particle Sculk Pulse spawns (see ParticleKind).")
	public String sculkPulseParticle = "SCULK";

	@SerialEntry(comment = "An ominous swirling aura, like the Raid/Trial Omen status effects.")
	public boolean omenAuraEnabled = false;

	@SerialEntry(comment = "Which particle Omen Aura spawns (see ParticleKind).")
	public String omenAuraParticle = "OMEN";

	@SerialEntry(comment = "Colored wind gusts swirling around your body, like your own personal breeze.")
	public boolean gustAuraEnabled = false;

	@SerialEntry(comment = "Gust aura particle color.")
	public Color gustAuraColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "Colored fine particles gently falling around you, like ash near a volcano.")
	public boolean ashFallEnabled = false;

	@SerialEntry(comment = "Ash fall particle color.")
	public Color ashFallColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "Frost aura particle color.")
	public Color frostAuraColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "Cozy smoke wisps trailing gently from your feet.")
	public boolean campfireSmokeEnabled = false;

	@SerialEntry(comment = "A widening funnel of particles from your feet up to above your head, like a personal tornado.")
	public boolean tornadoEnabled = false;

	@SerialEntry(comment = "Tornado particle color.")
	public Color tornadoColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "Particles spiraling inward and vanishing into your body, like a personal black hole.")
	public boolean blackHoleEnabled = false;

	@SerialEntry(comment = "Black hole particle color.")
	public Color blackHoleColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "Two tight, contra-rotating spirals weaving around your whole body.")
	public boolean twinVortexEnabled = false;

	@SerialEntry(comment = "Twin vortex particle color.")
	public Color twinVortexColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "Enchanted-hit sparkles bursting around you occasionally, like a magic critical hit.")
	public boolean enchantedCritSparkleEnabled = false;

	@SerialEntry(comment = "Which particle Enchanted Crit Sparkle spawns (see ParticleKind).")
	public String enchantedCritSparkleParticle = "ENCHANTED_CRIT";

	@SerialEntry(comment = "A trailing plume of dust drifting up behind your feet, like sneaking through smoke.")
	public boolean dustPlumeTrailEnabled = false;

	@SerialEntry(comment = "Which particle Dust Plume Trail spawns (see ParticleKind).")
	public String dustPlumeTrailParticle = "DUST_PLUME";

	@SerialEntry(comment = "A ring of particles around your feet continuously drifts inward and up into your chest, like charging up energy.")
	public boolean chargeUpEnabled = false;

	@SerialEntry(comment = "Charge up particle color.")
	public Color chargeUpColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "A single wide, flat ring of particles orbiting around your waist, like Saturn's rings.")
	public boolean orbitRingsEnabled = false;

	@SerialEntry(comment = "Orbit rings particle color.")
	public Color orbitRingsColor = new Color(0xFFAA33FF, true);

	@SerialEntry(comment = "Occasional jagged lightning bolts arcing from above down to you.")
	public boolean lightningAuraEnabled = false;

	@SerialEntry(comment = "Occasional multi-colored confetti burst above your head.")
	public boolean confettiBurstEnabled = false;

	@SerialEntry(comment = "A pair of particle wings trailing from your back, gently flapping.")
	public boolean phoenixWingsEnabled = false;

	@SerialEntry(comment = "Phoenix wings particle color.")
	public Color phoenixWingsColor = new Color(0xFFFF8800, true);

	@SerialEntry(comment = "Occasional dark glitchy particles flickering around you, like a tear in reality.")
	public boolean voidRiftEnabled = false;

	@SerialEntry(comment = "Which particle Void Rift spawns (see ParticleKind).")
	public String voidRiftParticle = "VOID_RIFT";

	@TickBox
	@SerialEntry(comment = "Two counter-rotating rings of end-rod sparkles weaving through each other around you.")
	public boolean starWeaveEnabled = false;

	@TickBox
	@SerialEntry(comment = "A steady stream of end-rod sparkles rising from your feet past your head, like a personal ascension.")
	public boolean ascendingSparklesEnabled = false;

	@TickBox
	@SerialEntry(comment = "A stretched-out comet tail of end-rod sparkles streaming behind you while you move.")
	public boolean cometTrailEnabled = false;

	@TickBox
	@SerialEntry(comment = "A soft, slow-drifting cloud of end-rod sparkles orbiting loosely around you at varying heights.")
	public boolean starVeilEnabled = false;

	@TickBox
	@SerialEntry(comment = "A ring of end-rod sparkles that periodically pulses outward from your chest.")
	public boolean radiantPulseEnabled = false;

	@AutoGen(category = "general", group = "general")
	@TickBox
	@SerialEntry(comment = "Master switch for all debug messages below - each category can still be turned off individually once this is on. Command replies and safety warnings always show regardless.")
	public boolean debugMessagesEnabled = false;

	@SerialEntry(comment = "Debug messages for friend list / party syncing (/friend list, /party list results).")
	public boolean debugSync = false;
	@SerialEntry(comment = "Where Sync debug messages above go - \"LOCAL\" or \"PARTY\". Falls back to LOCAL automatically if you're not actually in a party.")
	public String debugSyncDelivery = "LOCAL";

	@SerialEntry(comment = "Debug messages for whitelist checks and per-feature permission fetches. Only ever shown to admin-linked accounts, regardless of this setting.")
	public boolean debugPermissions = false;
	@SerialEntry(comment = "Where Permissions debug messages above go - \"LOCAL\" or \"PARTY\". Falls back to LOCAL automatically if you're not actually in a party.")
	public String debugPermissionsDelivery = "LOCAL";

	@SerialEntry(comment = "Debug messages for cloud settings sync (push/pull to sky.melloo.me).")
	public boolean debugCloudSync = false;
	@SerialEntry(comment = "Where Cloud Sync debug messages above go - \"LOCAL\" or \"PARTY\". Falls back to LOCAL automatically if you're not actually in a party.")
	public String debugCloudSyncDelivery = "LOCAL";

	@SerialEntry(comment = "Debug messages for mod-presence reporting (detecting other SkyMelloo users nearby).")
	public boolean debugPresence = false;
	@SerialEntry(comment = "Where Presence debug messages above go - \"LOCAL\" or \"PARTY\". Falls back to LOCAL automatically if you're not actually in a party.")
	public String debugPresenceDelivery = "LOCAL";

	@SerialEntry(comment = "Debug messages for the party HUD's username/Magical Power resolution.")
	public boolean debugParty = false;
	@SerialEntry(comment = "Where Party debug messages above go - \"LOCAL\" or \"PARTY\". Falls back to LOCAL automatically if you're not actually in a party.")
	public String debugPartyDelivery = "LOCAL";

	@SerialEntry(comment = "Debug messages for the dungeon run tracker (floor/cleared%/time detection, run start/end).")
	public boolean debugDungeon = false;
	@SerialEntry(comment = "Where Dungeon debug messages above go - \"LOCAL\" or \"PARTY\". Falls back to LOCAL automatically if you're not actually in a party.")
	public String debugDungeonDelivery = "LOCAL";

	@TickBox
	@SerialEntry(comment = "Shows every (color, text) run actually seen in Hypixel's actionbar below the mana bar - for tracking down why mana detection isn't matching.")
	public boolean manaDebugEnabled = false;

	@AutoGen(category = "general", group = "general")
	@TickBox
	@SerialEntry(comment = "On joining a server, show a \"Connecting...\" title and check connection quality (ping stability, packet rate) for the first 5 seconds.")
	public boolean connectionQualityCheckEnabled = false;

	@AutoGen(category = "general", group = "general")
	@TickBox
	@SerialEntry(comment = "Automatically decline optional server resource packs on Hypixel instead of showing the prompt - avoids the pack download/load step that sometimes crashes or freezes the game and forces a restart. Packs the server marks as required (ones that could get you kicked for declining) are never touched.")
	public boolean autoDeclineHypixelResourcePacks = true;

	@SerialEntry(comment = "Sync your SkyMelloo settings to sky.melloo.me, so a new device/reinstall with the same account can start from your existing settings instead of all defaults.")
	public boolean cloudSyncEnabled = false;

	@SerialEntry(comment = "Master switch for showing up as \"online\" to anyone else - other SkyMelloo users' mod-user Highlighting detection, the in-game Credits menu's online dot, and the website's public online-user count all depend on this. Off means you still report NOTHING about yourself, but you can still see/detect others who have it on. Doesn't affect Cloud Sync (that's private, never shown to anyone else).")
	public boolean presenceSharingEnabled = false;

	@SerialEntry(comment = "Custom status text shown next to your name to other SkyMelloo users nearby (via sky.melloo.me presence). Leave empty to show nothing. Requires presenceSharingEnabled above.")
	public String customStatusText = "";

	// Made user-adjustable for a while (2026-07-27) then reverted back to a fixed 1s
	// (ModPresenceManager.REPORT_INTERVAL_TICKS) the same day - "fest machen sync interval 1 sekunde
	// nicht einstellbar nur ausschaltbar": a per-player-configurable interval meant the website's
	// render delay had to adapt per player too, and a mismatched interval made the live map (updates
	// immediately) and the player marker (rendered with a delay buffer) visibly drift out of sync
	// with each other. A single fixed interval for everyone is simpler and keeps both in lockstep -
	// presenceSharingEnabled/dungeonSyncEnabled above are still the real on/off switches.

	@AutoGen(category = "general", group = "menu")
	@TickBox
	@SerialEntry(comment = "A fake \"SkyMelloo Menu\" item in hotbar slot 8 (not 9 - that's Hypixel's own real SkyBlock Menu slot) whenever that slot is empty - right-click it to open a chest-style menu for SkyMelloo's own settings, the same way Hypixel's own menu item works. Client-side only, never overwrites a real item actually in that slot.")
	public boolean skyMellooMenuItemEnabled = true;

	@SerialEntry(comment = "Position of the sky.melloo.me status HUD, set via the HUD layout editor (default J).")
	public int hudStatusX = 6;
	@SerialEntry(comment = "See hudStatusX.")
	public int hudStatusY = 6;

	@SerialEntry(comment = "Position of the fishing minigame score HUD - -1 means \"not set yet\", uses the default centered-above-hotbar position.")
	public int hudFishingScoreX = -1;
	@SerialEntry(comment = "See hudFishingScoreX.")
	public int hudFishingScoreY = -1;

	@AutoGen(category = "dungeons", group = "info")
	@StringField
	@SerialEntry(comment = "Party HUD display mode - \"OFF\", \"COMPACT\" (name + MP), or \"FULL\" (also shows each member's death count for the current dungeon run). Position set via the HUD layout editor (default J).")
	public String partyHudMode = "COMPACT";

	@AutoGen(category = "dungeons", group = "info")
	@TickBox
	@SerialEntry(comment = "In FULL mode during a run, show each puzzle that member specifically solved/failed as extra lines under their row (only appears for members actually involved in a puzzle - everyone else's row stays as-is).")
	public boolean partyHudShowPuzzleHistory = true;

	@AutoGen(category = "dungeons", group = "info")
	@TickBox
	@SerialEntry(comment = "A horizontal Magical Power \"spread\" bar for the party - each member's face is placed along it from lowest to highest MP, with the range labeled above. Shows the SHAPE of the party's gear gap at a glance rather than exact numbers per member. Needs at least 2 members with known MP.")
	public boolean partyMpBarEnabled = false;

	@SerialEntry(comment = "Position of the party HUD (shows your party's members + Magical Power), set via the HUD layout editor (default J).")
	public int hudPartyX = 6;
	@SerialEntry(comment = "See hudPartyX.")
	public int hudPartyY = 50;

	@SerialEntry(comment = "Position of the party MP bar above, set via the HUD layout editor (default J).")
	public int hudPartyMpBarX = 6;
	@SerialEntry(comment = "See hudPartyMpBarX.")
	public int hudPartyMpBarY = 220;

	// playerKillTrackerEnabled/missileKillMessageDelivery removed as user-facing settings (2026-07-27) -
	// always on and always LOCAL now, hardcoded at the two call
	// sites (PlayerKillTracker#onPlayerDied, MagicMissileManager#announceMissileKill) rather than
	// read from config, still gated by the "killTracker" permission either way. Death Double and its
	// "on Spell Kill" variant were removed entirely the same day, not just defaulted off - see
	// combat/DeathDoubleManager.java's deletion.

	@SerialEntry(comment = "Running total of players killed (each counted once per lobby/world). Internal counter, not user-editable.")
	public int totalPlayersKilled = 0;

	// magicMissileEssenceEnabled removed as a user-facing toggle (2026-07-27, "spell essence immer
	// an") - always on now, whenever cosmetics permission allows it, see
	// MagicMissileManager#spawnCollectibleEssence.

	@SerialEntry(comment = "Running total of Spell Essence collected. Internal counter, not user-editable.")
	public int totalSpellEssenceCollected = 0;

	@SerialEntry(comment = "Running total of missiles cast (punching empty air with an empty hand). Internal counter, not user-editable.")
	public int totalSpellsCast = 0;

	@SerialEntry(comment = "Running total of confirmed own-kills across every victim, lifetime - the number shown on each \"Last Kills\" entry (\"that was your Nth kill\"). Internal counter, not user-editable.")
	public int totalMagicMissileKills = 0;

	@AutoGen(category = "general", group = "kills")
	@TickBox
	@SerialEntry(comment = "When you die, post a short \"Death Recap\" in chat - the last few hits you took and what/who dealt them (real damage-source data, not a proximity guess), so you can actually tell what killed you.")
	public boolean deathRecapEnabled = true;

	@AutoGen(category = "general", group = "kills")
	@TickBox
	@SerialEntry(comment = "Also share a short one-line version of the Death Recap above with the party (e.g. \"{player} died - killed by Bonzo (18.2), lava (4.0)\") - so teammates can see WHY you died, not just that you did. Independent of the local Death Recap above, which stays LOCAL-only regardless of this.")
	public boolean deathRecapPartyAnnounceEnabled = false;

	@AutoGen(category = "general", group = "kills")
	@StringField
	@SerialEntry(comment = "Message template for the party death-cause announcement above. Placeholders: {player} {cause}.")
	public String deathRecapPartyAnnounceTemplate = "{player} died - killed by {cause}";

	@AutoGen(category = "general", group = "kills")
	@StringField
	@SerialEntry(comment = "Where the party death-cause announcement above goes - \"LOCAL\" or \"PARTY\" (falls back to LOCAL automatically if you're not actually in a party). Defaults to PARTY since the whole point is telling teammates.")
	public String deathRecapPartyAnnounceDelivery = "PARTY";

	@AutoGen(category = "general", group = "connection")
	@TickBox
	@SerialEntry(comment = "Automatically rejoin Hypixel a few seconds after an unexpected disconnect - Hypixel only, never any other server. Capped at 3 attempts in a row (the streak resets once a connection actually holds for 30+ seconds) so a real kick/ban doesn't turn into hammering reconnect attempts forever.")
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
