package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flags lowball-spam public chat messages - any trade-offer-shaped line from a stranger. Never
 * applies to party/guild/whisper chat or messages from SkyMelloo Friends.
 *
 * <p>Deliberately does NOT try to judge whether a price is actually "low" - that needs live market
 * data this mod doesn't have and would be unreliable. Link/phishing filtering isn't needed here -
 * Hypixel's own chat filter already blocks links in public chat.
 */
public final class AntiScamFilter {
	private static boolean initialized = false;

	// Party/Guild/Co-op chat and whispers are never scanned - "public chat" only, per the feature's
	// own scope. A line matching this is skipped entirely regardless of content.
	private static final Pattern PRIVATE_CHANNEL_PREFIX = Pattern.compile("^(?:Guild|Party|Co-op)\\s*>|^From |^To ");
	// Hypixel prefixes public chat with zero or more [rank/tag] brackets, then "Username: message".
	private static final Pattern PUBLIC_CHAT_LINE = Pattern.compile("^(?:\\[[^\\]]+]\\s*)*([A-Za-z0-9_]{1,16}): (.+)$");

	// "offer/pay/give <number> for" - the structural shape of an unsolicited buy offer, regardless
	// of whether the price is actually low.
	private static final Pattern TRADE_OFFER_SHAPE = Pattern.compile("(?i)\\b(?:offer|pay|paying|give|giving|buying)\\b.{0,40}\\b\\d[\\d,.]*\\s*(?:k|m|mil|million)?\\b.{0,40}\\bfor\\b");

	private AntiScamFilter() {
	}

	public static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		// Hiding needs ALLOW_GAME (returning false suppresses the message entirely) - MODIFY_GAME
		// can't remove a line, only transform it, so the two config modes need separate handlers.
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (overlay) {
				return true;
			}
			SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
			if (!config.antiScamEnabled || !config.antiScamHideMessages) {
				return true;
			}
			return !isLowball(message.getString());
		});
		ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
			if (overlay) {
				return message;
			}
			SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
			if (!config.antiScamEnabled || config.antiScamHideMessages) {
				return message;
			}
			if (!isLowball(message.getString())) {
				return message;
			}
			MutableComponent warning = Component.literal("[Possible lowball] ")
					.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555)).withBold(true));
			return warning.append(message);
		});
	}

	private static boolean isLowball(String text) {
		if (PRIVATE_CHANNEL_PREFIX.matcher(text).find()) {
			return false;
		}
		Matcher lineMatch = PUBLIC_CHAT_LINE.matcher(text);
		if (!lineMatch.matches()) {
			return false;
		}
		String sender = lineMatch.group(1);
		String body = lineMatch.group(2);
		if (com.melloo.mellooessentials.client.social.FriendsManager.isFriend(sender)) {
			return false;
		}
		return TRADE_OFFER_SHAPE.matcher(body).find();
	}
}
