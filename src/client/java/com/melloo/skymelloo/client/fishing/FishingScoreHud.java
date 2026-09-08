package com.melloo.skymelloo.client.fishing;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// Persistent fishing-minigame combo/score display. Not the vanilla actionbar, since Hypixel's own
// health/mana/defense HUD overwrites that every tick. A -1 position sentinel means "use default".
public final class FishingScoreHud implements HudElement {
	private static final int DEFAULT_BOTTOM_MARGIN = 58;
	private static final long RECENT_HIT_WINDOW_MS = 1000;

	public static final FishingScoreHud INSTANCE = new FishingScoreHud();

	private FishingScoreHud() {
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, DeltaTracker deltaTracker) {
		if (!FishingMinigameManager.isDisplayActive()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();

		String mainText = "Fishing Combo x" + FishingMinigameManager.getComboCount() + "  " + FishingMinigameManager.getChainScore() + " pts";
		int mainWidth = client.font.width(mainText);

		boolean recentHit = System.currentTimeMillis() - FishingMinigameManager.getLastHitTimeMillis() < RECENT_HIT_WINDOW_MS;
		String bonusText = recentHit ? " +" + FishingMinigameManager.getLastPointsGained() : "";
		int bonusWidth = bonusText.isEmpty() ? 0 : client.font.width(bonusText);
		int totalWidth = mainWidth + bonusWidth;

		int x = config.hudFishingScoreX;
		int y = config.hudFishingScoreY;
		if (x < 0 || y < 0) {
			x = client.getWindow().getGuiScaledWidth() / 2 - totalWidth / 2;
			y = client.getWindow().getGuiScaledHeight() - DEFAULT_BOTTOM_MARGIN;
		}

		gg.fill(x - 6, y - 4, x + totalWidth + 6, y + 12, 0x99101018);
		gg.text(client.font, mainText, x, y, 0xFFFFFFAA);
		if (!bonusText.isEmpty()) {
			gg.text(client.font, bonusText, x + mainWidth, y, 0xFF55FF55);
		}
	}
}
