package com.melloo.skymelloo.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Shown only when there's a genuine choice to make: this device has its own local settings AND the
 * cloud has a genuinely different saved copy too (compared by actual content, not a timestamp), so
 * neither side can be silently preferred without possibly throwing away real work either way. Asked
 * about once per divergence - see CloudSyncManager#reconcile, which only ever opens this when both
 * sides genuinely exist and differ, and otherwise resolves silently on its own.
 */
public class CloudSyncChoiceScreen extends Screen {
	private static final int PANEL_WIDTH = 360;
	private static final int PANEL_HEIGHT = 140;
	private static final int BUTTON_WIDTH = 150;
	private static final int BUTTON_HEIGHT = 24;
	private static final int BORDER_COLOR = 0xFFFF6EC7;
	private static final int PANEL_COLOR = 0xF0101018;
	private static final int LOCAL_ACCENT = 0xFF5599FF;
	private static final int CLOUD_ACCENT = 0xFFAA33FF;

	private final Runnable onUseLocal;
	private final Runnable onUseCloud;

	public CloudSyncChoiceScreen(Runnable onUseLocal, Runnable onUseCloud) {
		super(Component.literal("Cloud Sync"));
		this.onUseLocal = onUseLocal;
		this.onUseCloud = onUseCloud;
	}

	@Override
	protected void init() {
		int panelX = (this.width - PANEL_WIDTH) / 2;
		int panelY = (this.height - PANEL_HEIGHT) / 2;
		int buttonY = panelY + PANEL_HEIGHT - BUTTON_HEIGHT - 16;
		int localX = panelX + 16;
		int cloudX = panelX + PANEL_WIDTH - 16 - BUTTON_WIDTH;

		addRenderableWidget(new ChoiceButton(localX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
				"Keep Local", LOCAL_ACCENT, () -> {
			onUseLocal.run();
			Minecraft.getInstance().setScreen(null);
		}));
		addRenderableWidget(new ChoiceButton(cloudX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
				"Load Cloud", CLOUD_ACCENT, () -> {
			onUseCloud.run();
			Minecraft.getInstance().setScreen(null);
		}));
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}

	@Override
	public void onClose() {
		// Deciding neither right now is fine - this device just keeps whatever it already had, and
		// gets asked again next launch (see CloudSyncManager's own once-per-launch reasoning).
		Minecraft.getInstance().setScreen(null);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
		gg.fill(0, 0, this.width, this.height, 0xC0000000);

		int panelX = (this.width - PANEL_WIDTH) / 2;
		int panelY = (this.height - PANEL_HEIGHT) / 2;

		gg.fill(panelX - 2, panelY - 2, panelX + PANEL_WIDTH + 2, panelY + PANEL_HEIGHT + 2, BORDER_COLOR);
		gg.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, PANEL_COLOR);

		gg.text(this.font, "§6Cloud Sync", panelX + 16, panelY + 14, 0xFFFFD700);
		gg.text(this.font, "§7Both this device and the cloud have saved", panelX + 16, panelY + 32, 0xFFAAAAAA);
		gg.text(this.font, "§7settings. Which one do you want to keep?", panelX + 16, panelY + 44, 0xFFAAAAAA);

		super.extractRenderState(gg, mouseX, mouseY, partialTick);
	}

	/** Same styled-button look as StringInputScreen's Save/Cancel, just with two neutral accent colors instead of green/red since neither choice here is "correct". */
	private static final class ChoiceButton extends AbstractWidget {
		private final int accentColor;
		private final Runnable onClick;

		ChoiceButton(int x, int y, int width, int height, String label, int accentColor, Runnable onClick) {
			super(x, y, width, height, Component.literal(label));
			this.accentColor = accentColor;
			this.onClick = onClick;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			int x1 = getX();
			int y1 = getY();
			int x2 = getX() + getWidth();
			int y2 = getY() + getHeight();
			int fill = (accentColor & 0x00FFFFFF) | (this.isHovered() ? 0x66000000 : 0x33000000);
			gg.fill(x1, y1, x2, y2, fill);
			gg.fill(x1, y1, x2, y1 + 1, accentColor);
			gg.fill(x1, y2 - 1, x2, y2, accentColor);
			gg.fill(x1, y1, x1 + 1, y2, accentColor);
			gg.fill(x2 - 1, y1, x2, y2, accentColor);

			var font = Minecraft.getInstance().font;
			String label = this.getMessage().getString();
			int textWidth = font.width(label);
			gg.text(font, label, x1 + (getWidth() - textWidth) / 2, y1 + (getHeight() - 8) / 2, 0xFFFFFFFF);
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
}
