package com.melloo.skymelloo.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * SkyMelloo's own outlined/glow button style - a translucent fill + outline in a given accent
 * color, brightening on hover, instead of vanilla's grey 9-slice {@link net.minecraft.client.gui.components.Button}.
 * Extracted from SocialMenuScreen (2026-07-29, "report a bug bitte überall custom button statt den
 * standart" - the Settings screen's Report a Bug button had been added using a plain vanilla
 * Button, the only place in the mod's UI that didn't match this look) so every screen can share the
 * exact same widget instead of each screen keeping its own private copy.
 */
public final class SkyMellooButtonWidget extends AbstractWidget {
	public static final int PINK = 0xFFFF6EC7;
	public static final int RED = 0xFFFF5555;

	private final int accent;
	private final Runnable onClick;

	public SkyMellooButtonWidget(int x, int y, int width, int height, String label, int accent, Runnable onClick) {
		super(x, y, width, height, Component.literal(label));
		this.accent = accent;
		this.onClick = onClick;
	}

	private static int withAlpha(int argb, int alpha) {
		return (alpha << 24) | (argb & 0xFFFFFF);
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
		int x1 = getX();
		int y1 = getY();
		int x2 = getX() + getWidth();
		int y2 = getY() + getHeight();
		boolean hovered = this.isHovered();
		gg.fill(x1, y1, x2, y2, withAlpha(accent, hovered ? 0x55 : 0x28));
		gg.outline(x1, y1, getWidth(), getHeight(), withAlpha(accent, hovered ? 0xFF : 0x90));
		gg.centeredText(Minecraft.getInstance().font, getMessage().getString(), (x1 + x2) / 2, y1 + (getHeight() - 8) / 2, 0xFFFFFFFF);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		this.defaultButtonNarrationText(output);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		onClick.run();
	}
}
