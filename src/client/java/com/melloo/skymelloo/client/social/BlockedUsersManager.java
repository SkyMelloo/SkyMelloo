package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.util.ChatUtil;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.isxander.yacl3.platform.YACLPlatform;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A personal, client-side-only block list for Hypixel party members - separate from
 * {@link FriendsManager} and never synced to sky.melloo.me, since blocking someone is purely a
 * local preference about who's allowed in YOUR party, not something anyone else needs to see.
 * Blocking someone auto-kicks them from any party you lead the moment they join (see
 * {@link PartyJoinWatcher#handlePartyMemberJoined}), and immediately if they're already in your
 * current party at the moment you block them.
 */
public final class BlockedUsersManager {
	private static final Path FILE = YACLPlatform.getConfigDir().resolve("skymelloo-blocked.txt");
	private static final Set<String> blocked = new LinkedHashSet<>();
	private static boolean loaded = false;

	private BlockedUsersManager() {
	}

	private static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		if (!Files.exists(FILE)) {
			return;
		}
		try {
			for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty()) {
					blocked.add(trimmed);
				}
			}
		} catch (IOException e) {
			// Non-fatal - worst case, the block list starts empty this session.
		}
	}

	private static synchronized void save() {
		try {
			Files.write(FILE, blocked, StandardCharsets.UTF_8);
		} catch (IOException e) {
			// Non-fatal - the in-memory list still works for the rest of this session.
		}
	}

	public static synchronized boolean isBlocked(String username) {
		ensureLoaded();
		return blocked.stream().anyMatch(b -> b.equalsIgnoreCase(username));
	}

	/** @return false if they were already blocked. */
	public static synchronized boolean block(String username) {
		ensureLoaded();
		if (isBlocked(username)) {
			return false;
		}
		blocked.add(username);
		save();
		return true;
	}

	/** @return false if they weren't blocked in the first place. */
	public static synchronized boolean unblock(String username) {
		ensureLoaded();
		boolean removed = blocked.removeIf(b -> b.equalsIgnoreCase(username));
		if (removed) {
			save();
		}
		return removed;
	}

	public static synchronized List<String> getBlocked() {
		ensureLoaded();
		return new ArrayList<>(blocked);
	}

	/** Blocks {@code username}, and if they're currently in the local player's own party and the local player is the leader, kicks them right away instead of waiting for their next join. */
	public static void blockAndKickIfPresent(Minecraft client, String username) {
		boolean wasNewlyBlocked = block(username);
		client.player.sendSystemMessage(ChatUtil.prefixed(wasNewlyBlocked
				? "§cBlocked §f" + username + "§c - they'll be auto-kicked from any party you lead."
				: "§7" + username + " §7was already blocked."));
		boolean currentlyInParty = com.melloo.skymelloo.client.party.PartyHudManager.getMembers().values().stream()
				.anyMatch(m -> m.username().equalsIgnoreCase(username));
		if (currentlyInParty && com.melloo.skymelloo.client.party.PartyTracker.isLocalPlayerLeader()) {
			PartyJoinWatcher.queueKick(username);
		}
	}

	// ---- command tree ----

	public static LiteralArgumentBuilder<FabricClientCommandSource> buildBlockCommand() {
		return ClientCommands.literal("block")
				.executes(ctx -> {
					sendBlockedList(ctx.getSource());
					return 1;
				})
				.then(ClientCommands.argument("name", StringArgumentType.word())
						.suggests(BlockedUsersManager::suggestPartyAndPlayers)
						.executes(ctx -> {
							String username = StringArgumentType.getString(ctx, "name");
							blockAndKickIfPresent(Minecraft.getInstance(), username);
							return 1;
						}));
	}

	public static LiteralArgumentBuilder<FabricClientCommandSource> buildUnblockCommand() {
		return ClientCommands.literal("unblock")
				.executes(ctx -> {
					ctx.getSource().sendFeedback(ChatUtil.prefixed("§cBenutzung: §f/sm unblock <name>"));
					return 1;
				})
				.then(ClientCommands.argument("name", StringArgumentType.word())
						.suggests(BlockedUsersManager::suggestBlocked)
						.executes(ctx -> {
							String username = StringArgumentType.getString(ctx, "name");
							boolean removed = unblock(username);
							ctx.getSource().sendFeedback(ChatUtil.prefixed(removed
									? "§7Unblocked §f" + username + "§7."
									: "§7" + username + " §7wasn't blocked."));
							return 1;
						}));
	}

	private static void sendBlockedList(FabricClientCommandSource source) {
		List<String> list = getBlocked();
		if (list.isEmpty()) {
			source.sendFeedback(ChatUtil.prefixed("§7No blocked users - §f/sm block <name>§7 to block someone from your parties."));
			return;
		}
		source.sendFeedback(ChatUtil.prefixed("§6=== Blocked users (" + list.size() + ") ==="));
		for (String username : list) {
			source.sendFeedback(ChatUtil.prefixed("§d" + username + " §7- §f/sm unblock " + username));
		}
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestBlocked(
			com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx,
			com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(getBlocked(), builder);
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPartyAndPlayers(
			com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx,
			com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		List<String> names = new ArrayList<>();
		for (com.melloo.skymelloo.client.party.PartyHudManager.MemberInfo member : com.melloo.skymelloo.client.party.PartyHudManager.getMembers().values()) {
			names.add(member.username());
		}
		return SharedSuggestionProvider.suggest(names, builder);
	}
}
