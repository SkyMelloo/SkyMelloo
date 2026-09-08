package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Estimates dungeon readiness as a 0-1000 score, combining {@link #statsBreakdown}
 * (skills + Accessory Power) and {@link #experienceBreakdown} (floor/completions).
 * Not a verified formula - Hypixel's API exposes no per-run damage/team data.
 */
public final class DungeonReadiness {
	// Target Accessory Power per floor (index 0 = Entrance, 1-7 = Floor I-VII).
	private static final int[] AP_BENCHMARK_PER_FLOOR = {50, 50, 100, 150, 250, 350, 450, 600};
	private static final int AP_TIER_GAP = 50;

	private static final int CLASS_LEVEL_LOW = 30, CLASS_LEVEL_MID = 40, CLASS_LEVEL_HIGH = 50;

	// Per-skill level cap, used to scale each skill's low/mid/high tier to 50%/70%/90%.
	private static final Map<String, Integer> SKILL_CAPS = Map.of(
			"farming", 60, "mining", 60, "combat", 60, "foraging", 50,
			"fishing", 50, "enchanting", 60, "alchemy", 50, "taming", 60
	);
	// Per-skill weight within the 1000-point skills score.
	private static final Map<String, Integer> SKILL_WEIGHTS = Map.of(
			"combat", 200, "mining", 100, "foraging", 100, "farming", 60,
			"taming", 60, "enchanting", 60, "alchemy", 60, "fishing", 60
	);
	private static final int CLASS_LEVEL_WEIGHT = 200;
	private static final int SKYBLOCK_LEVEL_WEIGHT = 100;
	private static final int SKYBLOCK_LEVEL_LOW = 100, SKYBLOCK_LEVEL_MID = 200, SKYBLOCK_LEVEL_HIGH = 350;

	private static final int SKILLS_SCORE_MAX = 1000;
	private static final double AP_SHARE = 0.45;
	private static final double SKILLS_SHARE = 0.55;

	private static final int HIGHEST_FLOOR_WEIGHT = 600;
	private static final int COMPLETIONS_WEIGHT = 400;
	// Completion count past which additional runs stop adding points.
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

	/** Point breakdown (0-1000 total): all 8 SkyBlock skills, dungeon class level, and SkyBlock level. */
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

	/** Combines {@link #skillsBreakdown} with Accessory Power benchmarked against {@code targetFloor}. */
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

	/** Text label for Accessory Power relative to {@code targetFloor}'s benchmark - purely display, doesn't affect {@link #statsBreakdown}. */
	public static String accessoryPowerTierLabel(int accessoryPower, int targetFloor) {
		int floorIndex = Math.max(0, Math.min(targetFloor, AP_BENCHMARK_PER_FLOOR.length - 1));
		int mid = AP_BENCHMARK_PER_FLOOR[floorIndex];
		return tierLabel(Math.max(accessoryPower, 0), Math.max(mid - 30, 0), mid, mid + 50, mid + 150, mid + 400);
	}

	/** Text label for Catacombs level relative to {@code targetFloor}'s requirement, ratio-based since requirements span 1-24. */
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

	/** 3-tier curve: 0-50% below {@code low}, 50-75% to {@code mid}, 75-100% to {@code high}, capped above it. */
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
