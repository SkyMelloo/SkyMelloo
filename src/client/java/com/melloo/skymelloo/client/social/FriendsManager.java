package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.SkyMellooClient;
import com.melloo.skymelloo.client.api.ModAuthManager;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.skymelloo.client.util.DebugLog;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.List;
import java.util.Locale;

/**
 * A SkyMelloo-only friends list, entirely separate from Hypixel's real friend list - lets a player
 * keep a private list of other SkyMelloo users to DM (see {@link RelayChatManager}) without it
 * needing to be a real Hypixel friendship, or being visible to anyone not running the mod too.
 * Requests need mutual accept: sending one to someone who already sent you one completes it
 * immediately instead of leaving two one-way requests sitting around (see the server's own
 * lib/friends.js doc comment).
 * <p>
 * State (the friend list + incoming requests) is refreshed periodically and after every action -
 * there's no push notification for "someone added you", so a new incoming request only shows up
 * the next time this polls (see {@link #REFRESH_INTERVAL_TICKS}).
 */
public final class FriendsManager {
	private static final int REFRESH_INTERVAL_TICKS = 600; // 30s - a friend request isn't urgent enough to poll any faster than this on its own (RelayChatManager's inbox poll is the fast path for actual messages)

	private static volatile List<SkyMellooApiClient.FriendEntry> friends = List.of();
	private static volatile List<SkyMellooApiClient.FriendRequestEntry> incomingRequests = List.of();
	private static boolean refreshInFlight = false;
	private static int tickCounter = 0;

	private FriendsManager() {
	}

	public static void tick(Minecraft client) {
		if (client.player == null) {
			return;
		}
		tickCounter++;
		if (tickCounter == 40 || tickCounter % REFRESH_INTERVAL_TICKS == 0) {
			refresh(client);
		}
	}

	public static void refresh(Minecraft client) {
		if (refreshInFlight) {
			return;
		}
		refreshInFlight = true;
		ModAuthManager.getIdentity(client)
				.thenCompose(SkyMellooApiClient::fetchFriends)
				.whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
					refreshInFlight = false;
					if (error != null) {
						DebugLog.log(DebugLog.Category.PARTY, "Friends: refresh failed (" + error.getMessage() + ").");
						return;
					}
					List<SkyMellooApiClient.FriendRequestEntry> previousRequests = incomingRequests;
					friends = result.friends();
					incomingRequests = result.requests();
					for (SkyMellooApiClient.FriendRequestEntry request : incomingRequests) {
						boolean alreadyKnown = previousRequests.stream().anyMatch(r -> r.uuid().equals(request.uuid()));
						if (!alreadyKnown && Minecraft.getInstance().player != null) {
							Minecraft.getInstance().player.sendSystemMessage(ChatUtil.prefixed(
									"§d" + request.username() + " §7sent you a SkyMelloo friend request - §f/sm friend accept " + request.username()));
						}
					}
				}));
	}

	public static List<SkyMellooApiClient.FriendEntry> getFriends() {
		return friends;
	}

	public static List<SkyMellooApiClient.FriendRequestEntry> getIncomingRequests() {
		return incomingRequests;
	}

	public static boolean isFriend(String username) {
		return friends.stream().anyMatch(f -> f.username().equalsIgnoreCase(username));
	}

	public static java.util.Optional<SkyMellooApiClient.FriendEntry> findFriend(String username) {
		return friends.stream().filter(f -> f.username().equalsIgnoreCase(username)).findFirst();
	}

	public static void sendRequest(Minecraft client, String username) {
		ModAuthManager.getIdentity(client)
				.thenCompose(identity -> SkyMellooApiClient.sendFriendRequest(username, identity))
				.whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
					if (client.player == null) {
						return;
					}
					if (error != null) {
						client.player.sendSystemMessage(ChatUtil.prefixed("§cCouldn't send friend request: " + ChatUtil.friendlyError(error)));
						return;
					}
					client.player.sendSystemMessage(ChatUtil.prefixed(describeRequestResult(result)));
					refresh(client);
				}));
	}

	private static String describeRequestResult(SkyMellooApiClient.FriendRequestResult result) {
		String status = result.status() == null ? "" : result.status().toLowerCase(Locale.ROOT);
		return switch (status) {
			case "self" -> "§cYou can't friend yourself.";
			case "already_friends" -> "§7You're already SkyMelloo friends with §d" + result.username() + "§7.";
			case "accepted" -> "§d" + result.username() + " §7had already sent you a request - you're friends now!";
			case "pending" -> "§7Friend request sent to §d" + result.username() + "§7.";
			case "limit" -> "§c" + result.username() + " has too many pending friend requests right now.";
			default -> "§7Friend request sent to §d" + result.username() + "§7.";
		};
	}

	public static void accept(Minecraft client, String username) {
		ModAuthManager.getIdentity(client)
				.thenCompose(identity -> SkyMellooApiClient.acceptFriendRequest(username, identity))
				.whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
					if (client.player == null) {
						return;
					}
					client.player.sendSystemMessage(ChatUtil.prefixed(error == null
							? "§aYou're now SkyMelloo friends with §d" + result.username() + "§a."
							: "§cCouldn't accept: " + ChatUtil.friendlyError(error)));
					refresh(client);
				}));
	}

	public static void decline(Minecraft client, String username) {
		ModAuthManager.getIdentity(client)
				.thenCompose(identity -> SkyMellooApiClient.declineFriendRequest(username, identity))
				.whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
					if (client.player == null) {
						return;
					}
					client.player.sendSystemMessage(ChatUtil.prefixed(error == null
							? "§7Declined the friend request from §d" + (result != null ? result.username() : username) + "§7."
							: "§cCouldn't decline: " + ChatUtil.friendlyError(error)));
					refresh(client);
				}));
	}

	public static void remove(Minecraft client, String username) {
		ModAuthManager.getIdentity(client)
				.thenCompose(identity -> SkyMellooApiClient.removeFriend(username, identity))
				.whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
					if (client.player == null) {
						return;
					}
					client.player.sendSystemMessage(ChatUtil.prefixed(error == null
							? "§7Removed §d" + (result != null ? result.username() : username) + " §7from your SkyMelloo friends."
							: "§cCouldn't remove: " + ChatUtil.friendlyError(error)));
					refresh(client);
				}));
	}

	private static void sendFriendsList(FabricClientCommandSource source) {
		if (friends.isEmpty()) {
			source.sendFeedback(ChatUtil.prefixed("§7No SkyMelloo friends yet - §f/sm friend <name>§7 to send a request."));
		} else {
			source.sendFeedback(ChatUtil.prefixed("§6=== SkyMelloo Friends (" + friends.size() + ") ==="));
			for (SkyMellooApiClient.FriendEntry friend : friends) {
				source.sendFeedback(ChatUtil.prefixed("§d" + friend.username()));
			}
		}
		if (!incomingRequests.isEmpty()) {
			source.sendFeedback(ChatUtil.prefixed("§6=== Pending requests (" + incomingRequests.size() + ") ==="));
			for (SkyMellooApiClient.FriendRequestEntry request : incomingRequests) {
				source.sendFeedback(ChatUtil.prefixed("§d" + request.username() + " §7- §f/sm friend accept " + request.username()));
			}
		}
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestFriends(
			com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx,
			com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(friends.stream().map(SkyMellooApiClient.FriendEntry::username), builder);
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestIncomingRequests(
			com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx,
			com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(incomingRequests.stream().map(SkyMellooApiClient.FriendRequestEntry::username), builder);
	}

	// ---- command tree ----

	public static LiteralArgumentBuilder<FabricClientCommandSource> buildFriendCommand() {
		return ClientCommands.literal("friend")
				.executes(ctx -> {
					sendFriendsList(ctx.getSource());
					return 1;
				})
				.then(ClientCommands.literal("list").executes(ctx -> {
					sendFriendsList(ctx.getSource());
					return 1;
				}))
				.then(ClientCommands.literal("accept")
						.then(ClientCommands.argument("name", StringArgumentType.word())
								.suggests(FriendsManager::suggestIncomingRequests)
								.executes(ctx -> {
									accept(Minecraft.getInstance(), StringArgumentType.getString(ctx, "name"));
									return 1;
								})))
				.then(ClientCommands.literal("decline")
						.then(ClientCommands.argument("name", StringArgumentType.word())
								.suggests(FriendsManager::suggestIncomingRequests)
								.executes(ctx -> {
									decline(Minecraft.getInstance(), StringArgumentType.getString(ctx, "name"));
									return 1;
								})))
				.then(ClientCommands.literal("remove")
						.then(ClientCommands.argument("name", StringArgumentType.word())
								.suggests(FriendsManager::suggestFriends)
								.executes(ctx -> {
									remove(Minecraft.getInstance(), StringArgumentType.getString(ctx, "name"));
									return 1;
								})))
				.then(ClientCommands.argument("name", StringArgumentType.word())
						.suggests(SkyMellooClient::suggestOnlinePlayers)
						.executes(ctx -> {
							sendRequest(Minecraft.getInstance(), StringArgumentType.getString(ctx, "name"));
							return 1;
						}));
	}
}
