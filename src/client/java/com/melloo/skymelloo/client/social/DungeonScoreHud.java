package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Live dungeon score estimate for the current run - a real current estimate (completed rooms,
 * secrets%, puzzle states, crypts all read live from the dungeon tab list), not a best-case ceiling,
 * see {@link DungeonRunTracker#calculateScore()} - plus one line per puzzle/secret as they resolve
 * (see {@link DungeonRunTracker#getPuzzleOutcomes()}). Only shows while a run is active. Position set
 * via the HUD layout editor (default J).
 * <p>
 * Every extra line below the core 3 (title/breakdown/rooms) is a plain text line, not a row of
 * pixel-drawn blocks - a row's width used to grow with however many puzzles/secrets existed that
 * run, which made the HUD Layout Editor's static preview size permanently guess-work. Text lines only
 * ever grow the box DOWNWARD (one more line = +10px height), never sideways, so the editor's preview
 * width can actually match the real thing.
 */
public final class DungeonScoreHud implements HudElement {
	public static final DungeonScoreHud INSTANCE = new DungeonScoreHud();

	private DungeonScoreHud() {
	}

	/** Whole seconds as "Xm Ys", for the countdown line. */
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
		// Skyblocker's own live score (same sidebar/tab-list data, battle-tested far longer than our
		// own read) is ALWAYS used for the headline total/grade when available, unconditionally
		// (2026-07-27, no more opt-out toggle) -
		// our own breakdown (Skill/Explore/Speed/Bonus) still shows below regardless, since Skyblocker
		// doesn't expose those individually, only the combined total.
		Integer skyblockerScore = SkyblockerBridge.getScore();
		int displayedTotal = skyblockerScore != null ? skyblockerScore : score.total();
		String displayedGrade = skyblockerScore != null ? DungeonRunTracker.gradeForTotal(skyblockerScore) : score.grade();

		int x = config.hudScoreX;
		int y = config.hudScoreY;

		List<int[]> colors = new ArrayList<>(); // parallel to lines, one 1-element int[] per line
		List<String> lines = new ArrayList<>();
		// Pace arrow embedded as a §-color code within the line itself (Font rendering always honors
		// these regardless of the base color passed to gg.text below), so it can be a different color
		// than the score/grade text next to it without needing per-character color arrays.
		String paceArrow = "";
		if (config.dungeonScoreShowPaceAndCountdown) {
			int trend = DungeonRunTracker.getScoreTrendDelta();
			paceArrow = trend > 0 ? " §a▲" : trend < 0 ? " §c▼" : "";
		}
		// No "[Skyblocker]" tag anymore (2026-07-27) -
		// now that it's always the source when available (not a toggle), calling it out every time
		// was just clutter, not useful information.
		lines.add("Dungeon Score: " + displayedTotal + " (" + displayedGrade + ")" + paceArrow);
		colors.add(new int[]{gradeColor(displayedGrade)});

		if (config.dungeonScoreShowNextGrade) {
			DungeonRunTracker.NextGrade next = DungeonRunTracker.nextGrade(displayedTotal);
			if (next != null) {
				lines.add("Next grade (" + next.label() + "): +" + next.pointsNeeded());
				colors.add(new int[]{gradeColor(next.label())});
			} else {
				lines.add("Max grade reached!");
				colors.add(new int[]{gradeColor("S+")});
			}
		}

		if (config.dungeonScoreShowPaceAndCountdown && DungeonRunTracker.hasTimeLimit()) {
			int remaining = DungeonRunTracker.getTimeRemainingSeconds();
			if (remaining >= 0) {
				lines.add("Time left: " + formatMinSec(remaining));
				colors.add(new int[]{0xFFAAAAAA});
			} else {
				lines.add("Time's up! +" + formatMinSec(-remaining));
				colors.add(new int[]{0xFFFF5555});
			}
			// How much MORE time can still pass (from right now) while S+ stays mathematically
			// reachable - null means either no time limit at all (already excluded above) or S+ is
			// already impossible for a reason more time can't fix, MAX_VALUE means time isn't the
			// binding constraint right now (everything else alone already covers it).
			Integer splusMargin = DungeonRunTracker.getExtraSecondsForSPlus();
			if (splusMargin != null && splusMargin.intValue() != Integer.MAX_VALUE) {
				lines.add("S+ margin: +" + formatMinSec(splusMargin));
				colors.add(new int[]{splusMargin <= 30 ? 0xFFFF5555 : 0xFFFFD700});
			}
		}

		if (config.dungeonScoreShowPossible) {
			// Best-case ceiling assuming everything still open goes perfectly from here (see
			// DungeonRunTracker#bestPossibleTotal) - lets you see at a glance how much room is left
			// versus how much has already been permanently lost to fails/deaths.
			int possible = DungeonRunTracker.getBestPossibleScore();
			lines.add("Possible: " + possible + " (" + DungeonRunTracker.gradeForTotal(possible) + ")");
			colors.add(new int[]{gradeColor(DungeonRunTracker.gradeForTotal(possible))});

			// Itemized rather than combined onto one line - each deduction type gets its own line so
			// it reads as a real breakdown, not a crammed single summary.
			DungeonRunTracker.ScorePenalties penalties = DungeonRunTracker.currentPenalties();
			if (penalties.puzzleFailPenalty() > 0) {
				lines.add("-" + penalties.puzzleFailPenalty() + " Puzzles failed");
				colors.add(new int[]{0xFFFF5555});
			}
			if (penalties.deathPenalty() > 0) {
				lines.add("-" + penalties.deathPenalty() + " Deaths");
				colors.add(new int[]{0xFFFF5555});
			}
		}

		if (config.dungeonScoreShowBreakdown) {
			lines.add("Skill " + score.skill() + "  Explore " + score.explore() + "  Speed " + score.speed() + "  Bonus " + score.bonus());
			colors.add(new int[]{0xFFAAAAAA});
		}

		// Explore is 60%-rooms + 40%-secrets combined into one number - shown separately here since
		// it's not obvious from the breakdown alone that clearing rooms is part of it at all.
		lines.add("Rooms cleared: " + (int) DungeonRunTracker.getClearedPercent() + "%");
		colors.add(new int[]{0xFFAAAAAA});

		if (config.dungeonScoreShowRoomSecrets) {
			// Local-player-only, opportunistic - only ever populated if Skyblocker also happens to be
			// installed (its own room database identifies the current room, ours doesn't).
			SkyblockerBridge.RoomSecrets roomSecrets = SkyblockerBridge.getCurrentRoomSecrets();
			// Some room types (e.g. a Teleport Maze / "teleport-pad-room") report a negative max from
			// Skyblocker's own side - confirmed directly from a real screenshot showing "-1/-1" - meaning
			// "not applicable/not yet counted" for that room type, not a real 0-secret room. Showing the
			// raw negative numbers read as broken, so this just hides the line entirely in that case.
			if (roomSecrets != null && roomSecrets.max() >= 0) {
				lines.add("Room secrets: " + roomSecrets.found() + "/" + roomSecrets.max() + (roomSecrets.roomName() != null ? " (" + roomSecrets.roomName() + ")" : ""));
				colors.add(new int[]{0xFF66DDFF});
			}
			List<SkyblockerBridge.SecretRow> secretRows = SkyblockerBridge.getCurrentRoomSecretDetails();
			if (secretRows != null) {
				for (SkyblockerBridge.SecretRow secret : secretRows) {
					lines.add((secret.found() ? "✓ Secret " : "✖ Secret ") + (secret.secretIndex() + 1));
					colors.add(new int[]{secret.found() ? 0xFF55FF55 : 0xFFFF5555});
				}
			}
			// Whatever teammates last shared via presence sync (see DungeonSyncManager) - only ever
			// populated if Dungeon Sync is on for both sides, entries age out on their own if a teammate
			// stops updating (room changed, run ended, they left).
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
						lines.add("… Puzzle pending");
						colors.add(new int[]{0xFFFFAA00});
					}
					// Which puzzle solved/failed, who, and why - straight from Hypixel's own chat
					// text (see DungeonRunTracker#extractPuzzleReason), never a hardcoded puzzle name.
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

	/**
	 * The distinct post-run panel - reads entirely from {@link DungeonRunTracker#getLastFinalResult()},
	 * a snapshot frozen the instant the run ended, rather than re-querying live tab-list/scoreboard
	 * data that may already be gone or resetting by the time this renders (you're usually already
	 * walking back towards the hub). Same box-drawing approach as the live panel, just a different
	 * headline and no PENDING puzzle state (a finished run can't have one).
	 */
	private void renderFinalResult(GuiGraphicsExtractor gg, Minecraft client, SkyMellooConfig config) {
		DungeonRunTracker.FinalResult result = DungeonRunTracker.getLastFinalResult();
		if (result == null) {
			return;
		}
		int x = config.hudScoreX;
		int y = config.hudScoreY;

		List<int[]> colors = new ArrayList<>();
		List<String> lines = new ArrayList<>();

		lines.add("=== Final Result ===");
		colors.add(new int[]{0xFFFFD700});

		lines.add("Score: " + result.displayedScore() + " (" + result.displayedGrade() + ")");
		colors.add(new int[]{gradeColor(result.displayedGrade())});

		lines.add("Floor: " + (result.floor() != null ? result.floor() : "?") + "  Rooms: " + (int) result.clearedPercent() + "%  Secrets: " + (int) result.secretsPercentage() + "%");
		colors.add(new int[]{0xFFAAAAAA});

		if (config.dungeonScoreShowBreakdown) {
			DungeonRunTracker.ScoreEstimate estimate = result.estimate();
			lines.add("Skill " + estimate.skill() + "  Explore " + estimate.explore() + "  Speed " + estimate.speed() + "  Bonus " + estimate.bonus());
			colors.add(new int[]{0xFFAAAAAA});
		}

		lines.add("Crypts opened: " + result.crypts());
		colors.add(new int[]{0xFFAAAAAA});

		lines.add("Puzzles: " + result.puzzlesSolved() + " solved, " + result.puzzlesFailed() + " failed");
		colors.add(new int[]{result.puzzlesFailed() > 0 ? 0xFFFFAA00 : 0xFF55FF55});

		if (result.deathsTotal() == 0) {
			lines.add("No deaths this run.");
			colors.add(new int[]{0xFF55FF55});
		} else {
			lines.add("Deaths: " + result.deathsTotal());
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
