package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.party.PartyTracker;
import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.skymelloo.client.util.TickDelay;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /sm roll <amount>} - a dice roll; {@code /sm roll party} - picks a random party member
 * (including yourself); {@code /sm roll <word> <seconds>} - a timed raffle, whoever types
 * {@code <word>} in party chat within the time limit gets entered, one winner picked at the end.
 */
public final class PartyGamesManager {
	// "Party > [MVP++] Name: message" or "Party > Name: message" - Hypixel never shows more than one
	// bracketed rank tag in practice.
	private static final Pattern PARTY_CHAT_LINE = Pattern.compile("^Party > (?:\\[[^]]+] )?([A-Za-z0-9_]{1,16}): (.*)$");

	private static boolean initialized = false;

	// Word-raffle state - null target means no raffle currently running.
	private static String wordRollTarget = null;
	private static final List<String> wordRollEntrants = new ArrayList<>();

	private PartyGamesManager() {
	}

	public static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (wordRollTarget == null) {
				return;
			}
			Matcher matcher = PARTY_CHAT_LINE.matcher(message.getString());
			if (matcher.find()) {
				onPartyChatLine(matcher.group(1), matcher.group(2).trim());
			}
		});
	}

	private static void onPartyChatLine(String sender, String body) {
		if (body.equalsIgnoreCase(wordRollTarget) && !containsIgnoreCase(wordRollEntrants, sender)) {
			wordRollEntrants.add(sender);
		}
	}

	private static boolean containsIgnoreCase(List<String> list, String value) {
		for (String entry : list) {
			if (entry.equalsIgnoreCase(value)) {
				return true;
			}
		}
		return false;
	}

	// ---- /sm roll ----

	public static void rollNumber(Minecraft client, int max) {
		int result = ThreadLocalRandom.current().nextInt(max) + 1;
		send(client, Component.translatable("skymelloo.chat.party_games.roll_result", result, max).getString());
	}

	public static void rollPartyMember(Minecraft client) {
		if (client.player == null || client.level == null) {
			return;
		}
		List<String> candidates = new ArrayList<>();
		candidates.add(client.player.getGameProfile().name());
		for (AbstractClientPlayer player : client.level.players()) {
			String name = player.getGameProfile().name();
			if (PartyTracker.isMember(player.getUUID()) && !containsIgnoreCase(candidates, name)) {
				candidates.add(name);
			}
		}
		String chosen = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
		send(client, Component.translatable("skymelloo.chat.party_games.roll_member_result", chosen).getString());
	}

	/** Starts a timed "say {word} in party chat" raffle - entries collected by the listener registered in {@link #init}. */
	public static void startWordRoll(Minecraft client, String word, int seconds) {
		wordRollTarget = word;
		wordRollEntrants.clear();
		send(client, Component.translatable("skymelloo.chat.party_games.word_roll_start", word, seconds).getString());
		String targetAtStart = word;
		TickDelay.schedule(seconds * 20, () -> {
			// A newer roll could have overwritten/cleared this one before the timer ran out - only
			// resolve if this callback still belongs to the CURRENT raffle, not a stale leftover one.
			if (!targetAtStart.equals(wordRollTarget)) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			if (wordRollEntrants.isEmpty()) {
				send(mc, Component.translatable("skymelloo.chat.party_games.word_roll_no_entrants", targetAtStart).getString());
			} else {
				String winner = wordRollEntrants.get(ThreadLocalRandom.current().nextInt(wordRollEntrants.size()));
				send(mc, Component.translatable("skymelloo.chat.party_games.word_roll_winner", targetAtStart, wordRollEntrants.size(), winner).getString());
			}
			wordRollTarget = null;
			wordRollEntrants.clear();
		});
	}

	private static void send(Minecraft client, String text) {
		if (client.player == null) {
			return;
		}
		if (PartyTracker.isInParty()) {
			// § codes don't survive /pc as a command string - see ChatUtil.partyPrefixed.
			com.melloo.skymelloo.client.util.PartyChatSender.send(client, ChatUtil.partyPrefixed(text));
		} else {
			client.player.sendSystemMessage(ChatUtil.prefixed(text));
		}
	}

	// ---- command tree ----

	public static LiteralArgumentBuilder<FabricClientCommandSource> buildRollCommand() {
		return ClientCommands.literal("roll")
				.executes(ctx -> {
					ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.chat.party_games.roll_root_usage")));
					return 1;
				})
				.then(ClientCommands.literal("party").executes(ctx -> {
					rollPartyMember(Minecraft.getInstance());
					return 1;
				}))
				.then(ClientCommands.argument("amount", IntegerArgumentType.integer(1, 1_000_000)).executes(ctx -> {
					rollNumber(Minecraft.getInstance(), IntegerArgumentType.getInteger(ctx, "amount"));
					return 1;
				}))
				.then(ClientCommands.argument("word", StringArgumentType.word())
						.then(ClientCommands.argument("seconds", IntegerArgumentType.integer(1, 300)).executes(ctx -> {
							startWordRoll(Minecraft.getInstance(), StringArgumentType.getString(ctx, "word"), IntegerArgumentType.getInteger(ctx, "seconds"));
							return 1;
						})));
	}

}
