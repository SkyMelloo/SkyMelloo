package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;

import java.awt.Color;
import java.util.regex.Pattern;

/**
 * Highlights an incoming chat message when it actually mentions the local player's own username -
 * a whole-word, case-insensitive match against the message's plain flattened text, so e.g. "Steve"
 * doesn't also match "Steven". Bolds the whole message and prepends a colored marker rather than
 * recoloring the line outright - Hypixel bakes rank color into chat the same way it does nametags
 * (see HighlightManager#colorizeName's own doc comment on why that's preserved rather than flattened),
 * and bold is an additive style property that doesn't erase existing color. Also plays a short
 * sound, since a chat highlight alone is easy to miss if chat isn't actually being watched at the
 * moment it comes in.
 */
public final class ChatMentionHighlighter {
	private static boolean initialized = false;
	private static volatile Pattern mentionPattern = null;
	private static volatile String patternForName = null;

	private ChatMentionHighlighter() {
	}

	public static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
			if (overlay) {
				return message;
			}
			SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
			if (!config.chatMentionHighlightEnabled) {
				return message;
			}
			Minecraft client = Minecraft.getInstance();
			if (client.player == null) {
				return message;
			}
			String ownName = client.player.getGameProfile().name();
			if (ownName == null || ownName.isEmpty() || !mentions(message.getString(), ownName)) {
				return message;
			}
			client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.2F));
			TextColor color = TextColor.fromRgb(toRgb(config.chatMentionHighlightColor));
			MutableComponent highlighted = Component.literal("▶ ").withStyle(Style.EMPTY.withColor(color).withBold(true));
			highlighted.append(message.copy().withStyle(style -> style.withBold(true)));
			return highlighted;
		});
	}

	/** Rebuilds the compiled pattern only when the local username actually changes (practically never mid-session) rather than recompiling one on every single incoming chat line. */
	private static boolean mentions(String text, String name) {
		if (!name.equals(patternForName)) {
			mentionPattern = Pattern.compile("(?i)\\b" + Pattern.quote(name) + "\\b");
			patternForName = name;
		}
		return mentionPattern.matcher(text).find();
	}

	private static int toRgb(Color color) {
		return color.getRGB() | 0xFF000000;
	}
}
