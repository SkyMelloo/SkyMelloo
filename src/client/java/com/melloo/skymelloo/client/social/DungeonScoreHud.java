package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

// Live dungeon score estimate for the current run - a real current estimate, not a best-case ceiling.
// Every extra line is plain text so the box only ever grows downward, keeping the HUD Layout Editor's preview accurate.
public final class DungeonScoreHud implements HudElement {
	public static final DungeonScoreHud INSTANCE = new DungeonScoreHud();

	private DungeonScoreHud() {
	}

	// Whole seconds as "Xm Ys", for the countdown line.
	private static String formatMinSec(int seconds) {
		return (seconds / 60) + "m " + (seconds % 60) + "s";
	}

	private static int gradeColor(String grade) {
		return switch (grade) {
			case "S+" -> 0xFFFFD700;
			case "S" -> 0xFFFF55FF;
			case "A" -> 0xFF55FF55;
			case "B" -> 0xFF55FFFF;
			case "C" -> 0xFFFFAA00;
			default -> 0xFFFF5555;
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, DeltaTracker deltaTracker) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		boolean runActive = DungeonRunTracker.isRunActive();
		boolean showFinalResult = !runActive && DungeonRunTracker.isShowingFinalResult();
		if (!config.dungeonScoreHudEnabled || (!runActive && !showFinalResult)) {
			return;
		}
		Minecraft client = Minecraft.getInstance();

		if (showFinalResult) {
			renderFinalResult(gg, client, config);
			return;
		}

		DungeonRunTracker.ScoreEstimate score = DungeonRunTracker.calculateScore();
		// Skyblocker's live score is always used for the headline when available - our own breakdown
		// (Skill/Explore/Speed/Bonus) still shows below, since Skyblocker only exposes the combined total.
		Integer skyblockerScore = SkyblockerBridge.getScore();
		int displayedTotal = skyblockerScore != null ? skyblockerScore : score.total();
		String displayedGrade = skyblockerScore != null ? DungeonRunTracker.gradeForTotal(skyblockerScore) : score.grade();

		int x = config.hudScoreX;
		int y = config.hudScoreY;

		List<int[]> colors = new ArrayList<>(); // parallel to lines, one 1-element int[] per line
		List<String> lines = new ArrayList<>();
		// Pace arrow embedded as a §-color code so it can differ from the score/grade text's own color.
		String paceArrow = "";
		if (config.dungeonScoreShowPaceAndCountdown) {
			int trend = DungeonRunTracker.getScoreTrendDelta();
			paceArrow = trend > 0 ? " §a▲" : trend < 0 ? " §c▼" : "";
		}
		lines.add(Component.translatable("skymelloo.chat.dungeon_score.header", displayedTotal, displayedGrade, paceArrow).getString());
		colors.add(new int[]{gradeColor(displayedGrade)});

		if (config.dungeonScoreShowNextGrade) {
			DungeonRunTracker.NextGrade next = DungeonRunTracker.nextGrade(displayedTotal);
			if (next != null) {
				lines.add(Component.translatable("skymelloo.chat.dungeon_score.next_grade", next.label(), next.pointsNeeded()).getString());
				colors.add(new int[]{gradeColor(next.label())});
			} else {
				lines.add(Component.translatable("skymelloo.chat.dungeon_score.max_grade_reached").getString());
				colors.add(new int[]{gradeColor("S+")});
			}
		}

		if (config.dungeonScoreShowPaceAndCountdown && DungeonRunTracker.hasTimeLimit()) {
			int remaining = DungeonRunTracker.getTimeRemainingSeconds();
			if (remaining >= 0) {
				lines.add(Component.translatable("skymelloo.chat.dungeon_score.time_left", formatMinSec(remaining)).getString());
				colors.add(new int[]{0xFFAAAAAA});
			} else {
				lines.add(Component.translatable("skymelloo.chat.dungeon_score.time_up", formatMinSec(-remaining)).getString());
				colors.add(new int[]{0xFFFF5555});
			}
			// null means S+ is already impossible for a non-time reason; MAX_VALUE means time isn't the binding constraint.
			Integer splusMargin = DungeonRunTracker.getExtraSecondsForSPlus();
			if (splusMargin != null && splusMargin.intValue() != Integer.MAX_VALUE) {
				lines.add(Component.translatable("skymelloo.chat.dungeon_score.splus_margin", formatMinSec(splusMargin)).getString());
				colors.add(new int[]{splusMargin <= 30 ? 0xFFFF5555 : 0xFFFFD700});
			}
		}

		if (config.dungeonScoreShowPossible) {
			// Best-case ceiling assuming everything still open goes perfectly from here.
			int possible = DungeonRunTracker.getBestPossibleScore();
			lines.add(Component.translatable("skymelloo.chat.dungeon_score.possible", possible, DungeonRunTracker.gradeForTotal(possible)).getString());
			colors.add(new int[]{gradeColor(DungeonRunTracker.gradeForTotal(possible))});

			DungeonRunTracker.ScorePenalties penalties = DungeonRunTracker.currentPenalties();
			if (penalties.puzzleFailPenalty() > 0) {
				lines.add(Component.translatable("skymelloo.chat.dungeon_score.puzzles_failed_penalty", penalties.puzzleFailPenalty()).getString());
				colors.add(new int[]{0xFFFF5555});
			}
			if (penalties.deathPenalty() > 0) {
				lines.add(Component.translatable("skymelloo.chat.dungeon_score.deaths_penalty", penalties.deathPenalty()).getString());
				colors.add(new int[]{0xFFFF5555});
			}
		}

		if (config.dungeonScoreShowBreakdown) {
			lines.add(Component.translatable("skymelloo.chat.dungeon_score.breakdown", score.skill(), score.explore(), score.speed(), score.bonus()).getString());
			colors.add(new int[]{0xFFAAAAAA});
		}

		// Shown separately since it's not obvious from the breakdown alone that clearing rooms is part of Explore.
		lines.add(Component.translatable("skymelloo.chat.dungeon_score.rooms_cleared", (int) DungeonRunTracker.getClearedPercent()).getString());
		colors.add(new int[]{0xFFAAAAAA});

		if (config.dungeonScoreShowRoomSecrets) {
			// Only populated if Skyblocker is also installed - its room database identifies the current room, ours doesn't.
			SkyblockerBridge.RoomSecrets roomSecrets = SkyblockerBridge.getCurrentRoomSecrets();
			// A negative max means "not applicable" for that room type (e.g. a Teleport Maze), not a real 0-secret room.
			if (roomSecrets != null && roomSecrets.max() >= 0) {
				lines.add(Component.translatable("skymelloo.chat.dungeon_score.room_secrets", roomSecrets.found(), roomSecrets.max()).getString()
						+ (roomSecrets.roomName() != null ? " (" + roomSecrets.roomName() + ")" : ""));
				colors.add(new int[]{0xFF66DDFF});
			}
			List<SkyblockerBridge.SecretRow> secretRows = SkyblockerBridge.getCurrentRoomSecretDetails();
			if (secretRows != null) {
				for (SkyblockerBridge.SecretRow secret : secretRows) {
					lines.add((secret.found() ? "✓ " : "✖ ") + Component.translatable("skymelloo.chat.dungeon_score.secret_row", secret.secretIndex() + 1).getString());
					colors.add(new int[]{secret.found() ? 0xFF55FF55 : 0xFFFF5555});
				}
			}
			// Only populated if Dungeon Sync is on for both sides; entries age out if a teammate stops updating.
			for (java.util.Map.Entry<String, DungeonSyncManager.TeammateProgressView> entry : DungeonSyncManager.getTeammateProgress().entrySet()) {
				DungeonSyncManager.TeammateProgressView progress = entry.getValue();
				lines.add(entry.getKey() + ": " + progress.found() + "/" + progress.max() + (progress.room() != null ? " (" + progress.room() + ")" : ""));
				colors.add(new int[]{0xFFAA66FF});
			}
		}

		if (config.dungeonScoreShowPuzzles) {
			for (DungeonRunTracker.PuzzleResult puzzle : DungeonRunTracker.getPuzzleOutcomes()) {
				switch (puzzle.outcome()) {
					case PENDING -> {
						lines.add("… " + Component.translatable("skymelloo.chat.dungeon_score.puzzle_pending").getString());
						colors.add(new int[]{0xFFFFAA00});
					}
					case SOLVED -> {
						lines.add("✓ " + puzzle.player() + " " + puzzle.detail());
						colors.add(new int[]{0xFF55FF55});
					}
					case FAILED -> {
						lines.add("✖ " + puzzle.player() + " " + puzzle.detail());
						colors.add(new int[]{0xFFFF5555});
					}
				}
			}
		}

		int width = 8;
		for (String line : lines) {
			width = Math.max(width, client.font.width(line) + 8);
		}
		int height = 4 + lines.size() * 10 + 2;

		gg.fill(x - 4, y - 3, x + width, y + height, 0x99101018);
		int lineY = y;
		for (int i = 0; i < lines.size(); i++) {
			gg.text(client.font, lines.get(i), x, lineY, colors.get(i)[0]);
			lineY += 10;
		}
	}

	// Reads a snapshot frozen the instant the run ended, not live tab-list/scoreboard data that may
	// already be gone by the time this renders.
	private void renderFinalResult(GuiGraphicsExtractor gg, Minecraft client, SkyMellooConfig config) {
		DungeonRunTracker.FinalResult result = DungeonRunTracker.getLastFinalResult();
		if (result == null) {
			return;
		}
		int x = config.hudScoreX;
		int y = config.hudScoreY;

		List<int[]> colors = new ArrayList<>();
		List<String> lines = new ArrayList<>();

		lines.add(Component.translatable("skymelloo.chat.dungeon_score.final_result_header").getString());
		colors.add(new int[]{0xFFFFD700});

		lines.add(Component.translatable("skymelloo.chat.dungeon_score.final_score", result.displayedScore(), result.displayedGrade()).getString());
		colors.add(new int[]{gradeColor(result.displayedGrade())});

		lines.add(Component.translatable("skymelloo.chat.dungeon_score.final_floor_summary",
				result.floor() != null ? result.floor() : "?", (int) result.clearedPercent(), (int) result.secretsPercentage()).getString());
		colors.add(new int[]{0xFFAAAAAA});

		if (config.dungeonScoreShowBreakdown) {
			DungeonRunTracker.ScoreEstimate estimate = result.estimate();
			lines.add(Component.translatable("skymelloo.chat.dungeon_score.breakdown", estimate.skill(), estimate.explore(), estimate.speed(), estimate.bonus()).getString());
			colors.add(new int[]{0xFFAAAAAA});
		}

		lines.add(Component.translatable("skymelloo.chat.dungeon_score.crypts_opened", result.crypts()).getString());
		colors.add(new int[]{0xFFAAAAAA});

		lines.add(Component.translatable("skymelloo.chat.dungeon_score.puzzles_summary", result.puzzlesSolved(), result.puzzlesFailed()).getString());
		colors.add(new int[]{result.puzzlesFailed() > 0 ? 0xFFFFAA00 : 0xFF55FF55});

		if (result.deathsTotal() == 0) {
			lines.add(Component.translatable("skymelloo.chat.dungeon_score.no_deaths").getString());
			colors.add(new int[]{0xFF55FF55});
		} else {
			lines.add(Component.translatable("skymelloo.chat.dungeon_score.deaths_total", result.deathsTotal()).getString());
			colors.add(new int[]{0xFFFF5555});
		}

		if (config.dungeonScoreShowPuzzles) {
			for (DungeonRunTracker.PuzzleResult puzzle : result.puzzleOutcomes()) {
				if (puzzle.outcome() == DungeonRunTracker.PuzzleOutcome.PENDING) {
					continue;
				}
				boolean solved = puzzle.outcome() == DungeonRunTracker.PuzzleOutcome.SOLVED;
				lines.add((solved ? "✓ " : "✖ ") + puzzle.player() + " " + puzzle.detail());
				colors.add(new int[]{solved ? 0xFF55FF55 : 0xFFFF5555});
			}
		}

		int width = 8;
		for (String line : lines) {
			width = Math.max(width, client.font.width(line) + 8);
		}
		int height = 4 + lines.size() * 10 + 2;

		gg.fill(x - 4, y - 3, x + width, y + height, 0x99101018);
		int lineY = y;
		for (int i = 0; i < lines.size(); i++) {
			gg.text(client.font, lines.get(i), x, lineY, colors.get(i)[0]);
			lineY += 10;
		}
	}
}
