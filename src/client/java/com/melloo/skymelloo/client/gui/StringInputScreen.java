package com.melloo.skymelloo.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Popup for editing one string config field (name filters, message templates, ...) - a bordered,
 * branded card with a title, optional description (e.g. which placeholders a template supports),
 * a multi-line text area (long templates/comma-separated lists need more than one visible line,
 * unlike the old single-line EditBox this replaced), and explicit Save/Cancel buttons.
 * <p>
 * Cancel (and Esc) now genuinely discards - the previous version always saved on close regardless
 * of how you left, which wasn't a real "cancel" option at all.
 */
public class StringInputScreen extends Screen {
	private static final int PANEL_MAX_WIDTH = 460;
	private static final int PANEL_MAX_HEIGHT = 260;
	private static final int BUTTON_WIDTH = 90;
	private static final int BUTTON_HEIGHT = 20;
	// Was 0xFF6EC7FF - a byte-order mistake that rendered as light blue instead of the intended pink
	// (ARGB needs 0xAARRGGBB, this had the R/G/B bytes shuffled).
	private static final int BORDER_COLOR = 0xFFFF6EC7;
	private static final int PANEL_COLOR = 0xF0101018;
	private static final int SAVE_ACCENT = 0xFF55FF55;
	private static final int CANCEL_ACCENT = 0xFFFF5555;

	private final Screen parent;
	private final String label;
	private final String description;
	private final String initialValue;
	private final Consumer<String> onSave;
	private MultiLineEditBox editBox;

	public StringInputScreen(Screen parent, String label, String initialValue, Consumer<String> onSave) {
		this(parent, label, null, initialValue, onSave);
	}

	public StringInputScreen(Screen parent, String label, String description, String initialValue, Consumer<String> onSave) {
		super(Component.literal(label));
		this.parent = parent;
		this.label = label;
		this.description = description;
		this.initialValue = initialValue;
		this.onSave = onSave;
	}

	private int panelWidth() {
		return Math.min(PANEL_MAX_WIDTH, this.width - 40);
	}

	private int panelHeight() {
		return Math.min(PANEL_MAX_HEIGHT, this.height - 60);
	}

	private int headerHeight() {
		return description != null ? 54 : 38;
	}

	@Override
	protected void init() {
		int panelWidth = panelWidth();
		int panelHeight = panelHeight();
		int panelX = (this.width - panelWidth) / 2;
		int panelY = (this.height - panelHeight) / 2;

		int boxX = panelX + 16;
		int boxY = panelY + headerHeight();
		int boxWidth = panelWidth - 32;
		int boxHeight = panelHeight - headerHeight() - (BUTTON_HEIGHT + 24);

		editBox = MultiLineEditBox.builder()
				.setX(boxX)
				.setY(boxY)
				.setShowBackground(true)
				.setShowDecorations(true)
				.build(this.font, boxWidth, boxHeight, Component.literal(label));
		editBox.setValue(initialValue);
		addRenderableWidget(editBox);
		setInitialFocus(editBox);

		int buttonY = panelY + panelHeight - BUTTON_HEIGHT - 12;
		int cancelX = panelX + panelWidth - 16 - BUTTON_WIDTH;
		int saveX = cancelX - 8 - BUTTON_WIDTH;

		addRenderableWidget(new StyledButton(saveX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT, "Speichern", SAVE_ACCENT, () -> {
			onSave.accept(editBox.getValue());
			Minecraft.getInstance().setScreen(parent);
		}));

		addRenderableWidget(new StyledButton(cancelX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT, "Abbrechen", CANCEL_ACCENT,
				() -> Minecraft.getInstance().setScreen(parent)));
	}

	/**
	 * Save/Cancel styled to match the rest of SkyMelloo's settings UI (flat accent-tinted fill,
	 * colored border, brighter on hover) instead of vanilla's plain gray {@code Button} widget, which
	 * looked out of place against the rest of this branded popup.
	 */
	private static final class StyledButton extends AbstractWidget {
		private final int accentColor;
		private final Runnable onClick;

		StyledButton(int x, int y, int width, int height, String label, int accentColor, Runnable onClick) {
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

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}

	@Override
	public void onClose() {
		// Esc (or clicking outside, if ever wired up that way) = cancel, discards changes.
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
		gg.fill(0, 0, this.width, this.height, 0xC0000000);

		int panelWidth = panelWidth();
		int panelHeight = panelHeight();
		int panelX = (this.width - panelWidth) / 2;
		int panelY = (this.height - panelHeight) / 2;

		gg.fill(panelX - 2, panelY - 2, panelX + panelWidth + 2, panelY + panelHeight + 2, BORDER_COLOR);
		gg.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_COLOR);

		gg.text(this.font, label, panelX + 16, panelY + 14, 0xFFFFD700);
		if (description != null) {
			gg.text(this.font, description, panelX + 16, panelY + 30, 0xFFAAAAAA);
		}

		super.extractRenderState(gg, mouseX, mouseY, partialTick);
	}
}
