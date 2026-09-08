package com.melloo.skymelloo.client.gui;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.social.ActionBarTracker;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

// Replacement bar display for health (green, gold for absorption) and mana (light blue), stacked or
// side-by-side. Mana has no real vanilla stat - reads it from ActionBarTracker's actionbar text instead.
public final class HealthManaBarsHud implements HudElement {
	public static final HealthManaBarsHud INSTANCE = new HealthManaBarsHud();

	// Public - /sm debug hm-bar reports fill state against these same constants.
	public static final int BAR_WIDTH = 120;
	public static final int BAR_HEIGHT = 8;
	private static final int BAR_GAP = 4;
	// Fraction of the bar drained per tick for the "recently lost" trail (and the mirrored gain fill-in).
	private static final float DAMAGE_TRAIL_DECAY_PER_TICK = 0.03F;
	// A heal/mana-regen shows the full new amount instantly in this color, while the real fill animates up to meet it.
	private static final int GAIN_HIGHLIGHT_COLOR = 0xFFFF5555;

	private float displayedHealthFraction = 1F;
	// Mirrors displayedHealthFraction for gains instead of losses - lags behind, catching up from below.
	private float risingHealthFraction = 1F;
	private boolean initializedTrail = false;
	private float displayedManaFraction = 1F;
	private float risingManaFraction = 1F;
	private boolean initializedManaTrail = false;

	private HealthManaBarsHud() {
	}

	// Shared by renderHealthBar and /sm debug hm-bar so they can never drift. fromActionBar is false
	// only before the first actionbar packet arrives, when this falls back to the vanilla attribute.
	public record HealthBarState(float health, float maxHealth, float absorption, float healthFraction,
								  float absorptionFraction, int healthPx, int absorptionPx, boolean fromActionBar) {
	}

	public static HealthBarState computeHealthBarState(LocalPlayer player) {
		Integer abHealth = ActionBarTracker.getCurrentHealth();
		Integer abMaxHealth = ActionBarTracker.getMaxHealth();
		float maxHealth;
		float health;
		boolean fromActionBar;
		if (abHealth != null && abMaxHealth != null && abMaxHealth > 0) {
			maxHealth = abMaxHealth;
			health = abHealth;
			fromActionBar = true;
		} else {
			maxHealth = Math.max(1F, player.getMaxHealth());
			health = Math.max(0F, player.getHealth());
			fromActionBar = false;
		}
		float absorption = Math.max(0F, health - maxHealth);
		float baseHealth = Math.min(health, maxHealth);
		float total = maxHealth + absorption;
		float healthFraction = baseHealth / total;
		float absorptionFraction = absorption / total;
		int healthPx = Math.round(BAR_WIDTH * healthFraction);
		int absorptionPx = Math.round(BAR_WIDTH * absorptionFraction);
		return new HealthBarState(health, maxHealth, absorption, healthFraction, absorptionFraction, healthPx, absorptionPx, fromActionBar);
	}

	// fraction/manaPx are null/0 until the first actionbar mana readout has arrived this session.
	public record ManaBarState(Integer current, Integer max, Float fraction, int manaPx) {
	}

	public static ManaBarState computeManaBarState() {
		Float manaFraction = ActionBarTracker.getManaFraction();
		int manaPx = manaFraction != null ? Math.round(BAR_WIDTH * manaFraction) : 0;
		return new ManaBarState(ActionBarTracker.getCurrentMana(), ActionBarTracker.getMaxMana(), manaFraction, manaPx);
	}

	// Only meaningful once isHealthTrailInitialized() is true.
	public float getDisplayedHealthFraction() {
		return displayedHealthFraction;
	}

	public boolean isHealthTrailInitialized() {
		return initializedTrail;
	}

	public float getDisplayedManaFraction() {
		return displayedManaFraction;
	}

	public boolean isManaTrailInitialized() {
		return initializedManaTrail;
	}

	public float getRisingHealthFraction() {
		return risingHealthFraction;
	}

	public float getRisingManaFraction() {
		return risingManaFraction;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, DeltaTracker deltaTracker) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.healthManaBarsEnabled || (!config.healthBarEnabled && !config.manaBarEnabled)) {
			initializedTrail = false;
			initializedManaTrail = false;
			return;
		}
		if (!com.melloo.skymelloo.client.util.SkyblockDetector.isInSkyblock()) {
			initializedTrail = false;
			initializedManaTrail = false;
			return;
		}
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null) {
			initializedTrail = false;
			initializedManaTrail = false;
			return;
		}

		int x = config.hudHealthManaX;
		int y = config.hudHealthManaY;

		if (config.healthBarEnabled) {
			renderHealthBar(gg, client, player, x, y);
		}
		if (config.manaBarEnabled) {
			int manaX = config.healthBarEnabled && config.healthManaBarsSideBySide ? x + BAR_WIDTH + 24 : x;
			int manaY = config.healthBarEnabled && !config.healthManaBarsSideBySide ? y + BAR_HEIGHT + BAR_GAP : y;
			renderManaBar(gg, client, player, manaX, manaY);
		}
	}

	private void renderHealthBar(GuiGraphicsExtractor gg, Minecraft client, LocalPlayer player, int x, int y) {
		HealthBarState state = computeHealthBarState(player);
		float health = state.health();
		float maxHealth = state.maxHealth();
		float healthFraction = state.healthFraction();

		if (!initializedTrail) {
			displayedHealthFraction = healthFraction;
			risingHealthFraction = healthFraction;
			initializedTrail = true;
		} else {
			// Loss trail lags behind on the way down, fading out.
			displayedHealthFraction = displayedHealthFraction > healthFraction
					? Math.max(healthFraction, displayedHealthFraction - DAMAGE_TRAIL_DECAY_PER_TICK)
					: healthFraction;
			// Gain fill-in: the green fill catches up to the instantly-shown highlight block.
			risingHealthFraction = risingHealthFraction < healthFraction
					? Math.min(healthFraction, risingHealthFraction + DAMAGE_TRAIL_DECAY_PER_TICK)
					: healthFraction;
		}

		// healthPx is the full health/total fraction, not minus absorption - otherwise the green segment
		// would fall short of the gold absorption segment, leaving a gap instead of sitting flush.
		gg.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0x99101018);
		int trailPx = Math.round(BAR_WIDTH * displayedHealthFraction);
		if (trailPx > 0) {
			gg.fill(x, y, x + trailPx, y + BAR_HEIGHT, 0xFFFFFFFF);
		}
		int healthPx = state.healthPx();
		int absorptionPx = state.absorptionPx();
		if (healthPx > 0) {
			gg.fill(x, y, x + healthPx, y + BAR_HEIGHT, GAIN_HIGHLIGHT_COLOR);
		}
		int risingHealthPx = Math.round(BAR_WIDTH * risingHealthFraction);
		if (risingHealthPx > 0) {
			gg.fill(x, y, x + risingHealthPx, y + BAR_HEIGHT, 0xFF55DD55);
		}
		if (absorptionPx > 0) {
			gg.fill(x + healthPx, y, x + healthPx + absorptionPx, y + BAR_HEIGHT, 0xFFFFD700);
		}
		gg.outline(x, y, BAR_WIDTH, BAR_HEIGHT, 0xFF000000);
		// Doesn't count absorption - that's already visually distinct as the gold segment above.
		String healthText = Math.round(health) + "/" + Math.round(maxHealth);
		int healthTextX = x + BAR_WIDTH / 2 - client.font.width(healthText) / 2;
		gg.text(client.font, healthText, healthTextX, y, 0xFFFFFFFF);
	}

	private void renderManaBar(GuiGraphicsExtractor gg, Minecraft client, LocalPlayer player, int x, int y) {
		Float manaFraction = ActionBarTracker.getManaFraction();
		float safeManaFraction = manaFraction != null ? manaFraction : displayedManaFraction;
		if (!initializedManaTrail) {
			displayedManaFraction = safeManaFraction;
			risingManaFraction = safeManaFraction;
			initializedManaTrail = true;
		} else {
			displayedManaFraction = displayedManaFraction > safeManaFraction
					? Math.max(safeManaFraction, displayedManaFraction - DAMAGE_TRAIL_DECAY_PER_TICK)
					: safeManaFraction;
			risingManaFraction = risingManaFraction < safeManaFraction
					? Math.min(safeManaFraction, risingManaFraction + DAMAGE_TRAIL_DECAY_PER_TICK)
					: safeManaFraction;
		}

		gg.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0x99101018);
		int manaTrailPx = Math.round(BAR_WIDTH * displayedManaFraction);
		if (manaTrailPx > 0) {
			gg.fill(x, y, x + manaTrailPx, y + BAR_HEIGHT, 0xFFFFFFFF);
		}
		if (manaFraction != null) {
			int manaPx = Math.round(BAR_WIDTH * manaFraction);
			if (manaPx > 0) {
				gg.fill(x, y, x + manaPx, y + BAR_HEIGHT, GAIN_HIGHLIGHT_COLOR);
			}
			int risingManaPx = Math.round(BAR_WIDTH * risingManaFraction);
			if (risingManaPx > 0) {
				gg.fill(x, y, x + risingManaPx, y + BAR_HEIGHT, 0xFF55CCFF);
			}
		}
		gg.outline(x, y, BAR_WIDTH, BAR_HEIGHT, 0xFF000000);
		Integer current = ActionBarTracker.getCurrentMana();
		Integer max = ActionBarTracker.getMaxMana();
		if (current != null && max != null) {
			String text = current + "/" + max;
			int textX = x + BAR_WIDTH / 2 - client.font.width(text) / 2;
			gg.text(client.font, text, textX, y, 0xFFFFFFFF);
		}
		if (SkyMellooConfig.HANDLER.instance().manaDebugEnabled) {
			renderManaDebug(gg, client, x, y);
		}
	}

	// Toggled via "Mana Debug" in the settings screen.
	private void renderManaDebug(GuiGraphicsExtractor gg, Minecraft client, int x, int y) {
		int lineY = y + BAR_HEIGHT + 2;
		long sincePacket = System.currentTimeMillis() - ActionBarTracker.getLastPacketMillis();
		String header = ActionBarTracker.getLastPacketMillis() == 0
				? "no actionbar packet seen yet"
				: "last packet " + sincePacket + "ms ago, " + ActionBarTracker.getLastSegments().size() + " segment(s):";
		gg.text(client.font, header, x, lineY, 0xFFFFAA00);
		lineY += 10;
		for (ActionBarTracker.Segment seg : ActionBarTracker.getLastSegments()) {
			gg.text(client.font, "[" + seg.colorHex() + "] \"" + seg.text() + "\"", x, lineY, 0xFFCCCCCC);
			lineY += 10;
		}
	}
}
