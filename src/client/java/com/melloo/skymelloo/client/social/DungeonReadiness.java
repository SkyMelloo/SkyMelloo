package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Map;

/**
 * A rough "how ready is this player for serious (S+) dungeon runs" estimate, split into two
 * independent 0-1000 scores plus a weighted combination:
 * <ul>
 *     <li>{@link #statsBreakdown} - general SkyBlock investment, itself built from two 0-1000
 *     sub-scores: {@link #skillsBreakdown} (ALL 8 SkyBlock skills + SkyBlock level, weighted by
 *     dungeon relevance - Combat/dungeon-class-level highest, Mana-adjacent skills like Enchanting/
 *     Alchemy lowest) and Accessory Power (benchmarked against a target floor - see
 *     {@link #AP_BENCHMARK_PER_FLOOR}, since "good enough AP" for Entrance is very different from
 *     "good enough AP" for Floor VII). Every field fetched fresh each time (no client-side caching
 *     inside this class) since gear/AP can change right before a run - see the callers
 *     ({@link PartyHudManager}'s periodic re-fetch, {@link PartyJoinWatcher}'s on-join fetch) for
 *     where the actual network calls and their own cache windows live.</li>
 *     <li>{@link #experienceBreakdown} - actual dungeon track record: highest floor ever completed,
 *     and total dungeon completions as a volume/experience proxy. Also a per-component breakdown.</li>
 *     <li>{@link #combinedScore} - a weighted average of the two totals (stats weighted slightly
 *     higher, since gear/AP readiness is a better predictor of actually scoring well than raw
 *     completion count alone - a veteran with bad gear can still struggle).</li>
 * </ul>
 * <p>
 * Every stat component is scored on a 3-tier curve (low/mid/high thresholds, see {@link #tieredRatio})
 * instead of a flat linear ramp to a single cap: below "low" is a gentle 0-50% ramp (not ready yet,
 * but no single stat is the only thing that matters - other stats/gear can compensate somewhat, so
 * this isn't a hard cliff), low-to-mid is 50-75% ("acceptable"), mid-to-high is 75-100% ("good"), and
 * above "high" is capped at 100%. Each component's low/mid/high gap is sized individually to that
 * stat's own scale - not a uniform number across every stat.
 * <p>
 * NOT included, and NOT computable at all right now: actual current Defense/Health/Strength/Speed/
 * Intelligence (the real combat stat sheet). Those are derived from armor + accessories + reforges +
 * gemstones + stars + pets + active buffs, not exposed anywhere as a precomputed number by Hypixel's
 * API (only raw item NBT is) - correctly computing them would need a full SkyBlock stat-calculation
 * engine (what NEU/SkyblockAddons build entire separate libraries for), which is out of scope here.
 * Skill LEVELS are used instead as the closest buildable proxy, not a replacement for real stats.
 * <p>
 * Important honesty note: this is NOT the same kind of thing as {@link PartyJoinWatcher#qualifyingFloor}
 * (which is a hard, in-game-verified requirement). There is no single canonical "S+ readiness
 * formula" - Hypixel's API doesn't expose per-run damage, time, or teammate data at all (that
 * information simply isn't persisted anywhere retrievable, in-game or via API, once a run ends), so
 * "how much damage did they usually do relative to their team" is NOT computable no matter how this
 * is built. The weights/thresholds below are a reasonable, transparent approximation (loosely
 * informed by community discussion - e.g. Accessory Power >1000 commonly recommended for M4+ on any
 * class), not a verified fact - expect to tune them, and don't present the result to users as more
 * authoritative than it is.
 */
public final class DungeonReadiness {
	// Rough per-floor Accessory Power benchmark (the "mid" tier for AP below), index 0 = Entrance,
	// 1-7 = Floor I-VII. There is no single published per-floor AP table anywhere (checked multiple
	// wiki/forum sources) - this is OUR OWN interpolation between the only two concrete
	// community-cited anchor points found: ~300 AP "optimal" to comfortably start Catacombs
	// progression at all, and 600+ AP specifically cited for F7 Mage. Everything between is a smooth
	// guess, not a verified figure - tune freely.
	private static final int[] AP_BENCHMARK_PER_FLOOR = {50, 50, 100, 150, 250, 350, 450, 600};
	private static final int AP_TIER_GAP = 50;

	// Dungeon classes cap at level 50 - long-standing, well-established. The low/mid/high tiers are
	// NOT verified milestones, just a reasonable guess.
	private static final int CLASS_LEVEL_LOW = 30, CLASS_LEVEL_MID = 40, CLASS_LEVEL_HIGH = 50;

	// Typical skill level caps (without accounting for the handful of perks/events that extend some
	// of them further) - used only to scale each skill's own low/mid/high tier proportionally
	// (50%/70%/90% of cap), not claimed as exact per-skill maximums.
	private static final Map<String, Integer> SKILL_CAPS = Map.of(
			"farming", 60, "mining", 60, "combat", 60, "foraging", 50,
			"fishing", 50, "enchanting", 60, "alchemy", 50, "taming", 60
	);
	// Dungeon-relevance weight per skill (out of the 1000-point skills score, alongside class level
	// and SkyBlock level below) - Combat highest (direct dungeon combat), Mining/Foraging next
	// (Defense/Strength respectively), Mana-adjacent skills (Enchanting/Alchemy -> Intelligence) and
	// Fishing lowest, per the "Mana isn't as important" guidance this was built from. Entirely a
	// judgment call, not a verified formula.
	private static final Map<String, Integer> SKILL_WEIGHTS = Map.of(
			"combat", 200, "mining", 100, "foraging", 100, "farming", 60,
			"taming", 60, "enchanting", 60, "alchemy", 60, "fishing", 60
	);
	private static final int CLASS_LEVEL_WEIGHT = 200;
	// SkyBlock level correlates with base Health - a small weight, not a primary signal.
	private static final int SKYBLOCK_LEVEL_WEIGHT = 100;
	// A well-progressed veteran is roughly SkyBlock level 350+ (level itself is uncapped) - a rough
	// benchmark for "excellent" here, not a hard maximum.
	private static final int SKYBLOCK_LEVEL_LOW = 100, SKYBLOCK_LEVEL_MID = 200, SKYBLOCK_LEVEL_HIGH = 350;

	private static final int SKILLS_SCORE_MAX = 1000;
	private static final double AP_SHARE = 0.45;
	private static final double SKILLS_SHARE = 0.55;

	private static final int HIGHEST_FLOOR_WEIGHT = 600;
	private static final int COMPLETIONS_WEIGHT = 400;
	// Soft cap for "total completions" scoring - past this, more completions stop adding points.
	private static final int COMPLETIONS_BENCHMARK = 500;

	private static final double STATS_SHARE = 0.55;
	private static final double EXPERIENCE_SHARE = 0.45;

	/** Per-skill point breakdown (0-1000 total) - see {@link #SKILL_WEIGHTS} for each skill's own weight, {@link #CLASS_LEVEL_WEIGHT}/{@link #SKYBLOCK_LEVEL_WEIGHT} for the other two. */
	public record SkillsBreakdown(int farming, int mining, int combat, int foraging, int fishing,
	                               int enchanting, int alchemy, int taming, int classLevel, int skyblockLevel, int total) {
	}

	/** {@code skills}/{@code ap} are each their own 0-1000 score, {@code total} is their weighted combination (see {@link #AP_SHARE}/{@link #SKILLS_SHARE}). */
	public record StatsBreakdown(SkillsBreakdown skills, int ap, int total) {
	}

	/** Per-component point breakdown for {@link #experienceBreakdown} - see {@link StatsBreakdown}. */
	public record ExperienceBreakdown(int highestFloor, int completions, int total) {
	}

	private DungeonReadiness() {
	}

	/**
	 * Point breakdown (0-1000 total): all 8 SkyBlock skills (Farming/Mining/Combat/Foraging/Fishing/
	 * Enchanting/Alchemy/Taming - deliberately ALL of them, not just Combat/Foraging), the selected
	 * dungeon class's level, and SkyBlock level as a minor Health proxy. Each on its own 3-tier curve
	 * scaled to that skill's own cap (see {@link #SKILL_CAPS}).
	 */
	public static SkillsBreakdown skillsBreakdown(SkyMellooApiClient.SummaryResult summary) {
		Map<String, Integer> levels = summary.skillLevels();
		int farming = skillPoints(levels, "farming");
		int mining = skillPoints(levels, "mining");
		int combat = skillPoints(levels, "combat");
		int foraging = skillPoints(levels, "foraging");
		int fishing = skillPoints(levels, "fishing");
		int enchanting = skillPoints(levels, "enchanting");
		int alchemy = skillPoints(levels, "alchemy");
		int taming = skillPoints(levels, "taming");

		int classLevel = summary.selectedClass() != null
				? summary.classLevels().getOrDefault(summary.selectedClass().toLowerCase(Locale.ROOT), 0)
				: 0;
		int classPart = (int) Math.round(tieredRatio(classLevel, CLASS_LEVEL_LOW, CLASS_LEVEL_MID, CLASS_LEVEL_HIGH) * CLASS_LEVEL_WEIGHT);
		int skyblockPart = (int) Math.round(tieredRatio(summary.skyblockLevel(), SKYBLOCK_LEVEL_LOW, SKYBLOCK_LEVEL_MID, SKYBLOCK_LEVEL_HIGH) * SKYBLOCK_LEVEL_WEIGHT);

		int total = farming + mining + combat + foraging + fishing + enchanting + alchemy + taming + classPart + skyblockPart;
		return new SkillsBreakdown(farming, mining, combat, foraging, fishing, enchanting, alchemy, taming, classPart, skyblockPart, total);
	}

	private static int skillPoints(Map<String, Integer> levels, String skill) {
		int level = levels.getOrDefault(skill, 0);
		int cap = SKILL_CAPS.get(skill);
		int weight = SKILL_WEIGHTS.get(skill);
		return (int) Math.round(tieredRatio(level, cap * 0.5, cap * 0.7, cap * 0.9) * weight);
	}

	/**
	 * Point breakdown (0-1000 total): {@link #skillsBreakdown} and Accessory Power (benchmarked
	 * against {@code targetFloor}, see {@link #AP_BENCHMARK_PER_FLOOR}) as two independent 0-1000
	 * scores, combined {@link #SKILLS_SHARE}/{@link #AP_SHARE}.
	 *
	 * @param targetFloor which floor to benchmark AP against - 0 = Entrance, 1-7 = Floor I-VII, clamped to that range.
	 */
	public static StatsBreakdown statsBreakdown(SkyMellooApiClient.SummaryResult summary, int accessoryPower, int targetFloor) {
		int floorIndex = Math.max(0, Math.min(targetFloor, AP_BENCHMARK_PER_FLOOR.length - 1));
		int apMid = AP_BENCHMARK_PER_FLOOR[floorIndex];
		int apScore = (int) Math.round(tieredRatio(Math.max(accessoryPower, 0), apMid - AP_TIER_GAP, apMid, apMid + AP_TIER_GAP) * SKILLS_SCORE_MAX);

		SkillsBreakdown skills = skillsBreakdown(summary);
		int total = (int) Math.round(skills.total() * SKILLS_SHARE + apScore * AP_SHARE);
		return new StatsBreakdown(skills, apScore, total);
	}

	/** Point breakdown (0-1000 total): highest floor ever completed (up to {@value #HIGHEST_FLOOR_WEIGHT}), plus total dungeon completions as an experience proxy (up to {@value #COMPLETIONS_WEIGHT}) - no per-run data exists to do better than this. */
	public static ExperienceBreakdown experienceBreakdown(SkyMellooApiClient.SummaryResult summary) {
		int floorPart = (int) Math.round(capped((double) Math.max(summary.highestFloor(), 0) / 7) * HIGHEST_FLOOR_WEIGHT);
		int completionsPart = (int) Math.round(capped((double) Math.max(summary.dungeonCompletions(), 0) / COMPLETIONS_BENCHMARK) * COMPLETIONS_WEIGHT);
		return new ExperienceBreakdown(floorPart, completionsPart, floorPart + completionsPart);
	}

	/** Weighted average of {@link #statsBreakdown}'s/{@link #experienceBreakdown}'s totals - stats weighted slightly higher, see class doc. */
	public static int combinedScore(SkyMellooApiClient.SummaryResult summary, int accessoryPower, int targetFloor) {
		return (int) Math.round(statsBreakdown(summary, accessoryPower, targetFloor).total() * STATS_SHARE + experienceBreakdown(summary).total() * EXPERIENCE_SHARE);
	}

	/**
	 * Text label for Accessory Power relative to {@code targetFloor}'s benchmark ({@link #AP_BENCHMARK_PER_FLOOR}) -
	 * a "Very Low" AP for F7 can be a comically "Overpowered" AP for Entrance. Doesn't affect
	 * {@link #statsBreakdown} at all - purely an additional, easier-to-read label.
	 */
	public static String accessoryPowerTierLabel(int accessoryPower, int targetFloor) {
		int floorIndex = Math.max(0, Math.min(targetFloor, AP_BENCHMARK_PER_FLOOR.length - 1));
		int mid = AP_BENCHMARK_PER_FLOOR[floorIndex];
		return tierLabel(Math.max(accessoryPower, 0), Math.max(mid - 30, 0), mid, mid + 50, mid + 150, mid + 400);
	}

	/**
	 * Text label for Catacombs Skill level relative to {@code targetFloor}'s VERIFIED requirement
	 * ({@link PartyJoinWatcher#requiredCatacombsLevel}) - ratio-based rather than a flat gap, since
	 * requirements themselves span 1 (Floor I) to 24 (Floor VII), a huge range where a flat number
	 * wouldn't scale sensibly. E.g. Catacombs 26 while only queueing Floor I (needs Catacombs 1) is a
	 * ~26x overshoot - comically "Overpowered" - while the same Catacombs 26 on Floor VII (needs 24)
	 * is barely above the minimum, correctly "Low" instead.
	 */
	public static String catacombsLevelTierLabel(int catacombsLevel, int targetFloor) {
		int required = PartyJoinWatcher.requiredCatacombsLevel(Math.max(targetFloor, 1));
		if (catacombsLevel < required) {
			return Component.translatable("skymelloo.chat.dungeon_readiness.not_qualified").getString();
		}
		double ratio = (double) catacombsLevel / required;
		return tierLabel(ratio, 1.0, 1.5, 2.5, 4.0, 8.0);
	}

	/** 6-band text tier from 5 ascending thresholds: Very Low / Low / Medium / Good / Excellent / Overpowered. */
	private static String tierLabel(double value, double t1, double t2, double t3, double t4, double t5) {
		if (value >= t5) {
			return Component.translatable("skymelloo.chat.dungeon_readiness.tier_overpowered").getString();
		}
		if (value >= t4) {
			return Component.translatable("skymelloo.chat.dungeon_readiness.tier_excellent").getString();
		}
		if (value >= t3) {
			return Component.translatable("skymelloo.chat.dungeon_readiness.tier_good").getString();
		}
		if (value >= t2) {
			return Component.translatable("skymelloo.chat.dungeon_readiness.tier_medium").getString();
		}
		if (value >= t1) {
			return Component.translatable("skymelloo.chat.dungeon_readiness.tier_low").getString();
		}
		return Component.translatable("skymelloo.chat.dungeon_readiness.tier_very_low").getString();
	}

	/**
	 * 3-tier curve instead of one linear ramp: 0-50% below {@code low} (a gentle ramp - being under
	 * the "low" tier isn't a hard cliff, since other stats/gear can compensate somewhat), 50-75%
	 * between {@code low} and {@code mid} ("acceptable"), 75-100% between {@code mid} and
	 * {@code high} ("good"), capped at 100% above {@code high}.
	 */
	private static double tieredRatio(double value, double low, double mid, double high) {
		value = Math.max(0, value);
		low = Math.max(0, low);
		if (value >= high) {
			return 1.0;
		}
		if (value >= mid) {
			return 0.75 + 0.25 * (value - mid) / Math.max(high - mid, 1e-9);
		}
		if (value >= low) {
			return 0.5 + 0.25 * (value - low) / Math.max(mid - low, 1e-9);
		}
		return low <= 0 ? 0.5 : 0.5 * value / low;
	}

	private static double capped(double ratio) {
		return Math.max(0, Math.min(1, ratio));
	}
}
