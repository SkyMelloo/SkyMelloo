package com.melloo.skymelloo.client.cosmetics;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.highlight.HighlightManager;
import com.melloo.skymelloo.client.social.PermissionsManager;
import com.melloo.skymelloo.client.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Purely cosmetic "magic missile": cast by punching empty air with an empty hand (see
 * EmptyHandAirClickMixin - briefly moved to a "Cast Spell" menu button instead and reverted back,
 * since needing to open a whole menu just to cast felt worse than the incidental-punch trigger it
 * replaced), fires a slow particle projectile from the player's eyes that bursts into a small
 * particle explosion on impact with a solid block or another player. Client-side-only, no gameplay
 * effect (no real damage, no packets) beyond the deliberate arm swing {@link #trigger} sends so other
 * SkyMelloo users can still see it (see RemoteMissileTriggerMixin).
 */
public final class MagicMissileManager {
	private static final double SPEED = 0.9;
	private static final int MAX_AGE_TICKS = 90; // was 60 (ranges increased across all spells)
	private static final int BURST_PARTICLES = 64;
	private static final int HIT_INVISIBLE_TICKS = 100; // 5 seconds
	private static final int TRAIL_SPIRAL_RGB = 0xFFFFFF;
	private static final double TRAIL_SPIRAL_START_RADIUS = 0.12;
	private static final double TRAIL_SPIRAL_OUTWARD_SPEED = 0.1;

	private static final int REMOTE_RGB = 0xFF5555;

	private static final class Missile {
		Vec3 pos;
		Vec3 velocity; // not final - Homing Arrow steers this in flight, see tick()
		final int rgb;
		final boolean own;
		final AbstractClientPlayer shooter;
		// Tagged at spawn time rather than re-read from live config on hit, so switching spell type
		// mid-flight can't change what an already-fired missile does on impact.
		final String spellType;
		AbstractClientPlayer homingTarget; // null unless spellType is "ARROW" - not final, re-acquired mid-flight, see tick()
		int age;
		float spiralAngle;

		Missile(Vec3 pos, Vec3 velocity, int rgb, boolean own, AbstractClientPlayer shooter, String spellType, AbstractClientPlayer homingTarget) {
			this.pos = pos;
			this.velocity = velocity;
			this.rgb = rgb;
			this.own = own;
			this.shooter = shooter;
			this.spellType = spellType;
			this.homingTarget = homingTarget;
		}
	}

	private static final long KILL_ANNOUNCE_COOLDOWN_TICKS = 60L * 60 * 20; // 60 minutes at 20 ticks/s

	private static final int MESSAGE_HISTORY_SIZE = 5;

	private static final List<Missile> active = new ArrayList<>();
	private static final Map<Integer, Long> invisibleUntilTick = new HashMap<>();
	private static final Map<UUID, Long> killAnnounceCooldownUntil = new HashMap<>();
	private static final Deque<String> recentKillMessages = new ArrayDeque<>();

	// "Spell Essence" - a fake, client-only collectible dropped on a missile kill (own kills only).
	// Never actually vanilla-pickable (see the huge setPickUpDelay below - a real pickup attempt on an
	// entity the server doesn't know about would just look broken), collection is handled entirely
	// ourselves via simple distance checks each tick.
	private static final int ESSENCE_DESPAWN_TICKS = 400; // 20s - uncollected essence just vanishes
	// Was 1.3 - reported as basically never actually collectible. Most likely cause: it spawns right
	// where the target was standing, and the target (still solid even while rendered invisible, see
	// MissileHitInvisibilityMixin - only the RENDERING is faked, not real collision) physically blocks
	// walking close enough to reach the old, tighter radius. Widened well past "standing on top of it"
	// so it's reachable from beside the target instead of requiring the same tile.
	private static final double ESSENCE_COLLECT_DISTANCE_SQ = 2.5 * 2.5;
	private record PendingEssence(int entityId, long expiryTick) {
	}
	private static final List<PendingEssence> pendingEssence = new ArrayList<>();
	// Counting down from the top of the int range, kept in its own separate range from other fake
	// entity ids this mod creates elsewhere so they can never collide.
	// Shared by essence item drops AND fake lightning bolt entities - both just need a unique id, no
	// reason to keep two separate counters.
	private static int nextFakeEntityId = Integer.MAX_VALUE - 100_000;
	private static long currentTick = 0;

	private static final double LIGHTNING_MAX_RANGE = 60; // was 40 (ranges increased across all spells)
	// Your lifetime overall kill count (config.totalMagicMissileKills) at the moment of this kill -
	// not how many times this specific victim has been hit.
	public record RecentKill(GameProfile profile, long timestampMillis, int victimKillNumber) {
	}
	private static final int MAX_RECENT_KILLS = 20;
	private static final Deque<RecentKill> recentKills = new ArrayDeque<>();

	private MagicMissileManager() {
	}

	public static void trigger(Minecraft client) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.magicMissileEnabled || !PermissionsManager.has("cosmetics")) {
			return;
		}
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}
		// Casting rides along on a real vanilla attack (punching empty air, see
		// EmptyHandAirClickMixin) again, which already swings the arm and sends the network packet
		// other clients need to mirror it (see RemoteMissileTriggerMixin) for free - this explicit
		// swing is technically redundant with that now, but harmless (just re-plays the same
		// animation) and cheap insurance against ever needing a non-punch trigger path again.
		player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		config.totalSpellsCast++;
		SkyMellooConfig.HANDLER.save();
		String spellType = config.magicMissileSpellType;
		if ("LIGHTNING".equalsIgnoreCase(spellType)) {
			triggerLightning(client, player);
			return;
		}
		if ("LEVITATE".equalsIgnoreCase(spellType)) {
			triggerLevitate(client, player);
			return;
		}
		Vec3 origin = player.getEyePosition();
		Vec3 velocity = player.getLookAngle().scale(SPEED);
		int rgb = config.magicMissileColor.getRGB() & 0xFFFFFF;
		AbstractClientPlayer homingTarget = "ARROW".equalsIgnoreCase(spellType)
				? findHomingTarget(client, origin, player.getLookAngle(), player) : null;
		active.add(new Missile(origin, velocity, rgb, true, player, spellType, homingTarget));
		client.level.playLocalSound(origin.x, origin.y, origin.z, SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.0F, 1.6F, false);
	}

	private static final double HOMING_MAX_RANGE = 60; // was 40 (ranges increased across all spells)
	private static final int HOMING_REACQUIRE_INTERVAL_TICKS = 5;
	// Within this distance, ignore wall-proximity cost and beeline straight at the target instead of
	// letting the obstacle-steering swerve wide - see the close-range terminal guidance check in tick().
	private static final double HOMING_DIRECT_APPROACH_DISTANCE_SQ = 3.5 * 3.5;
	// cos(35 degrees) - how far off dead-center the candidate can be and still be picked. Used both
	// for the initial crosshair lock and every mid-flight re-scan (from the arrow's own position/
	// heading at that point, not the shooter's) - see HOMING_REACQUIRE_INTERVAL_TICKS in tick().
	private static final double HOMING_MIN_ALIGNMENT = Math.cos(Math.toRadians(35));
	// How many homing arrows are allowed to share one target
	// before it's considered "full" for target-selection purposes (still allowed as a last resort -
	// see findHomingTarget - just never preferred over an untargeted player).
	private static final int HOMING_MAX_PER_TARGET = 5;

	/** How many currently in-flight homing arrows already have {@code target} locked on. */
	private static int countHomingArrowsTargeting(AbstractClientPlayer target) {
		int count = 0;
		for (Missile m : active) {
			if ("ARROW".equalsIgnoreCase(m.spellType) && m.homingTarget == target) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Called right when {@code deadTarget} is confirmed hit/killed (by anything, not just an arrow) -
	 * any OTHER in-flight homing arrows still chasing that same now-invisible player immediately look
	 * for a new target instead of waiting for their own next scheduled re-scan tick (up to
	 * {@link #HOMING_REACQUIRE_INTERVAL_TICKS} ticks later).
	 */
	private static void reassignArrowsTargeting(AbstractClientPlayer deadTarget) {
		Minecraft client = Minecraft.getInstance();
		for (Missile m : active) {
			if ("ARROW".equalsIgnoreCase(m.spellType) && m.homingTarget == deadTarget) {
				m.homingTarget = findHomingTarget(client, m.pos, m.velocity.normalize(), m.shooter);
			}
		}
	}

	/**
	 * Best player within a generous cone of {@code look} from {@code origin} for the "Homing Arrow"
	 * spell type - closest to dead-center wins, not just closest in distance - AND only if there's
	 * an actual clear line of sight to them (see {@link #hasLineOfSight}), so it won't lock onto
	 * someone standing behind a wall just because they're within the cone. Null if nobody qualifies.
	 * Reused both for the initial launch (origin/look = shooter's eye/look) and for continuous
	 * re-acquisition while already in flight (origin/look = the arrow's own current position/heading),
	 * so a target is continuously re-validated rather than locked once at launch.
	 * <p>
	 * When multiple arrows are in flight, this spreads them across different targets rather than
	 * piling everything onto one - an untargeted player is always preferred over one that already has
	 * an arrow homing on them, up to {@link #HOMING_MAX_PER_TARGET} arrows per target; only once every
	 * candidate in range/sight is already at that cap does it fall back to just the best-aligned one
	 * regardless.
	 */
	private static AbstractClientPlayer findHomingTarget(Minecraft client, Vec3 origin, Vec3 look, AbstractClientPlayer shooter) {
		if (!(client.level instanceof ClientLevel level)) {
			return null;
		}
		// Three priority tiers, checked in order at the end: nobody homing on them yet, then anyone
		// still under the per-target cap, then finally anyone at all regardless of load - so the cap
		// only ever gets exceeded as a genuine last resort.
		AbstractClientPlayer bestUntargeted = null;
		double bestUntargetedAlignment = HOMING_MIN_ALIGNMENT;
		AbstractClientPlayer bestUnderCap = null;
		double bestUnderCapAlignment = HOMING_MIN_ALIGNMENT;
		AbstractClientPlayer bestOverall = null;
		double bestOverallAlignment = HOMING_MIN_ALIGNMENT;
		for (AbstractClientPlayer other : client.level.players()) {
			if (other == shooter || HighlightManager.isNpc(other) || isTemporarilyInvisible(other) || isInActiveSequence(other)) {
				continue;
			}
			Vec3 targetPos = other.position().add(0, other.getBbHeight() / 2, 0);
			Vec3 toOther = targetPos.subtract(origin);
			double distSq = toOther.lengthSqr();
			if (distSq < 0.01 || distSq > HOMING_MAX_RANGE * HOMING_MAX_RANGE) {
				continue;
			}
			double alignment = toOther.normalize().dot(look);
			if (alignment <= bestOverallAlignment && alignment <= bestUnderCapAlignment && alignment <= bestUntargetedAlignment) {
				continue; // can't improve any bucket - skip the line-of-sight raycast, it isn't free
			}
			if (!hasLineOfSight(level, origin, targetPos)) {
				continue;
			}
			if (alignment > bestOverallAlignment) {
				bestOverallAlignment = alignment;
				bestOverall = other;
			}
			int load = countHomingArrowsTargeting(other);
			if (load < HOMING_MAX_PER_TARGET && alignment > bestUnderCapAlignment) {
				bestUnderCapAlignment = alignment;
				bestUnderCap = other;
			}
			if (load == 0 && alignment > bestUntargetedAlignment) {
				bestUntargetedAlignment = alignment;
				bestUntargeted = other;
			}
		}
		if (bestUntargeted != null) {
			return bestUntargeted;
		}
		return bestUnderCap != null ? bestUnderCap : bestOverall;
	}

	/** Whether a straight line from {@code from} to {@code to} is unobstructed by any solid block. */
	private static boolean hasLineOfSight(ClientLevel level, Vec3 from, Vec3 to) {
		ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
		return level.clip(ctx).getType() == HitResult.Type.MISS;
	}

	// Homing Arrow's "leicht" (light) pathfinding - not a real grid search, just cost-scored steering
	// sampled around the direct line to the target every tick, cheap enough to run continuously. Air
	// is free, solid costs 3 (discouraged, not impassable - sometimes there's no way around), and cost
	// rises the closer a candidate cell sits to a solid block so the arrow keeps some clearance from
	// walls instead of grazing them.
	private static final double STEER_PROBE_DISTANCE = 1.6;
	private static final double STEER_ANGLE_WEIGHT = 4.0;
	// Small yaw/pitch-style offsets (in a side/up basis around the direct direction), radians-ish -
	// dead center plus a ring of 8 around it, enough to find a way around a thin wall/pillar without
	// being a real search.
	private static final double[][] STEER_OFFSETS = {
			{0, 0},
			{0.4, 0}, {-0.4, 0}, {0, 0.4}, {0, -0.4},
			{0.3, 0.3}, {-0.3, 0.3}, {0.3, -0.3}, {-0.3, -0.3},
	};
	// Axis-aligned neighbor offsets, checked at distance 1 and distance 2 - a cheap stand-in for a full
	// spherical "within N blocks" check that's more than good enough for steering feel.
	private static final int[][] AXIS_OFFSETS_1 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
	private static final int[][] AXIS_OFFSETS_2 = {{2, 0, 0}, {-2, 0, 0}, {0, 2, 0}, {0, -2, 0}, {0, 0, 2}, {0, 0, -2}};

	private static boolean isSolidBlock(ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
	}

	/** Air = 0, solid = 7, +4 if a solid block is directly adjacent, +2 if one's within 2 blocks otherwise - raised from 3/2/1 so it keeps noticeably more clearance from walls instead of grazing past them. */
	private static double blockTraversalCost(ClientLevel level, BlockPos pos) {
		if (isSolidBlock(level, pos)) {
			return 7.0;
		}
		for (int[] o : AXIS_OFFSETS_1) {
			if (isSolidBlock(level, pos.offset(o[0], o[1], o[2]))) {
				return 4.0;
			}
		}
		for (int[] o : AXIS_OFFSETS_2) {
			if (isSolidBlock(level, pos.offset(o[0], o[1], o[2]))) {
				return 2.0;
			}
		}
		return 0.0;
	}

	/** Picks the lowest-cost direction near {@code desiredDir}, blending obstacle cost with staying close to the direct line so it still generally beelines for the target. */
	private static Vec3 steerAroundObstacles(ClientLevel level, Vec3 origin, Vec3 desiredDir) {
		Vec3 reference = Math.abs(desiredDir.y) < 0.99 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		Vec3 side = desiredDir.cross(reference).normalize();
		Vec3 up = side.cross(desiredDir).normalize();

		Vec3 best = desiredDir;
		double bestScore = Double.MAX_VALUE;
		for (double[] offset : STEER_OFFSETS) {
			Vec3 candidate = desiredDir.add(side.scale(offset[0])).add(up.scale(offset[1]));
			double candidateLen = candidate.length();
			if (candidateLen < 0.001) {
				continue;
			}
			candidate = candidate.scale(1.0 / candidateLen);
			Vec3 probePos = origin.add(candidate.scale(STEER_PROBE_DISTANCE));
			double cost = blockTraversalCost(level, BlockPos.containing(probePos));
			double angleCost = (1.0 - candidate.dot(desiredDir)) * STEER_ANGLE_WEIGHT;
			double score = cost + angleCost;
			if (score < bestScore) {
				bestScore = score;
				best = candidate;
			}
		}
		return best;
	}

	/**
	 * The "Lightning" spell type - instant, not a travelling projectile like the default Missile: only
	 * fires if a player is directly under the crosshair right now (same segment/AABB hit-test as
	 * {@link #findHitPlayer}, just one long segment instead of one tick's worth of missile travel), and
	 * strikes them with a real (but {@link LightningBolt#setVisualOnly}) lightning bolt - no real
	 * damage, just the flash/thunder. Silent no-op if nothing's actually under the crosshair, rather
	 * than firing blind.
	 */
	private static void triggerLightning(Minecraft client, LocalPlayer player) {
		Vec3 origin = player.getEyePosition();
		Vec3 end = origin.add(player.getLookAngle().scale(LIGHTNING_MAX_RANGE));
		AbstractClientPlayer target = findHitPlayer(client, origin, end, player);
		if (target == null || !(client.level instanceof ClientLevel level)) {
			return;
		}
		LightningBolt bolt = new LightningBolt(EntityType.LIGHTNING_BOLT, level);
		bolt.setPos(target.getX(), target.getY(), target.getZ());
		bolt.setVisualOnly(true);
		bolt.setId(nextFakeEntityId--);
		level.addEntity(bolt);
		invisibleUntilTick.put(target.getId(), currentTick + HIT_INVISIBLE_TICKS);
		reassignArrowsTargeting(target);
		playHitConfirmSound(client);
		recordOwnKill(client, target, target.position());
	}

	/**
	 * The "Levitate" spell type - same instant crosshair hit-test as Lightning (no travelling
	 * projectile), but the kill isn't immediate: the target vanishes right away (same as every other
	 * spell's hit-invisibility) and a rising effigy of particles lifts off from where they stood,
	 * climbing for the first stretch of the sequence,
	 * then a {@link net.minecraft.core.particles.ParticleTypes#SONIC_BOOM} shockwave ring at the peak,
	 * then the usual own-kill burst/death-double at the very end - see {@link #tickLevitateSequences}.
	 */
	private static void triggerLevitate(Minecraft client, LocalPlayer player) {
		Vec3 origin = player.getEyePosition();
		Vec3 end = origin.add(player.getLookAngle().scale(LIGHTNING_MAX_RANGE));
		AbstractClientPlayer target = findHitPlayer(client, origin, end, player);
		if (target == null || !(client.level instanceof ClientLevel level)) {
			return;
		}
		Vec3 anchor = target.position();
		// Stays visible through the whole rise/shockwave buildup - only actually vanishes at the kill
		// moment itself (see tickLevitateSequences), not the instant they're hit. Going invisible
		// before anything has actually "killed" them yet didn't make sense.
		levitateSequences.add(new LevitateSequence(target, currentTick, anchor, SkyMellooConfig.HANDLER.instance().magicMissileColor.getRGB() & 0xFFFFFF));
		level.playLocalSound(anchor.x, anchor.y, anchor.z, SoundEvents.SHULKER_TELEPORT, SoundSource.PLAYERS, 1.2F, 0.7F, false);
	}

	/** Shared by both spell types for a confirmed own-kill hit - announce/essence-drop/recent-kills tracking, all in one place so Missile and Lightning can never drift out of sync with each other. */
	private static void recordOwnKill(Minecraft client, AbstractClientPlayer hitPlayer, Vec3 hitPos) {
		announceMissileKill(client, hitPlayer);
		spawnCollectibleEssence(client, hitPos);
		// Lifetime total across every victim (persisted in config, same as totalSpellsCast) - "war
		// mein 837 kill", not how many times THIS specific person has been hit, which is a much
		// smaller and less interesting number for the Last Kills list to show.
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		config.totalMagicMissileKills++;
		SkyMellooConfig.HANDLER.save();
		recentKills.addFirst(new RecentKill(hitPlayer.getGameProfile(), System.currentTimeMillis(), config.totalMagicMissileKills));
		while (recentKills.size() > MAX_RECENT_KILLS) {
			recentKills.removeLast();
		}
	}

	/** Most recent own-kills first, up to {@link #MAX_RECENT_KILLS} - see the menu's "Last Kills" page. */
	public static List<RecentKill> getRecentKills() {
		return new ArrayList<>(recentKills);
	}

	/**
	 * Mirrors another SkyMelloo user's magic missile locally, purely predicted from their swing +
	 * look direction at that moment - there's no real position-sync packet for this (nor should
	 * there be, for a purely cosmetic effect), so it's an approximation, not a synced projectile.
	 * Always rendered in a fixed red rather than that player's own configured color, so at a glance
	 * it's obvious which missiles are yours (always your own color) versus someone else's (always red).
	 */
	public static void spawnRemote(Minecraft client, AbstractClientPlayer other) {
		if (!SkyMellooConfig.HANDLER.instance().magicMissileEnabled || !PermissionsManager.has("cosmetics")) {
			return;
		}
		Vec3 origin = other.getEyePosition();
		Vec3 velocity = other.getLookAngle().scale(SPEED);
		active.add(new Missile(origin, velocity, REMOTE_RGB, false, other, "MISSILE", null));
	}

	/** Whether {@code entity} should currently render as invisible from a recent missile hit. */
	public static boolean isTemporarilyInvisible(Entity entity) {
		Long until = invisibleUntilTick.get(entity.getId());
		return until != null && until > currentTick;
	}

	/** Whether {@code entity} is currently the target of an in-progress Plasma or Levitate sequence - see {@link #findHitPlayer}'s doc comment for why this matters. */
	private static boolean isInActiveSequence(Entity entity) {
		int id = entity.getId();
		for (PlasmaSequence seq : plasmaSequences) {
			if (seq.target().getId() == id) {
				return true;
			}
		}
		for (LevitateSequence seq : levitateSequences) {
			if (seq.target().getId() == id) {
				return true;
			}
		}
		return false;
	}

	public static void tick(Minecraft client) {
		currentTick++;
		if (client.level == null) {
			return;
		}
		tickEssenceCollection(client);
		tickPlasmaSequences(client);
		tickLevitateSequences(client);
		if (active.isEmpty()) {
			return;
		}

		active.removeIf(missile -> {
			if ("ARROW".equalsIgnoreCase(missile.spellType) && client.level instanceof ClientLevel arrowLevel) {
				// Keeps looking for a valid target the whole flight, not just once at launch -
				// re-scanning every
				// tick would be wasteful for something this cheap-but-not-free (a per-candidate
				// raycast), so it's throttled instead, and only actually re-scans when the current
				// target isn't usable anymore (lost, hit-invisible, or no longer in clear sight).
				boolean targetUsable = missile.homingTarget != null && missile.homingTarget.isAlive()
						&& !isTemporarilyInvisible(missile.homingTarget)
						&& hasLineOfSight(arrowLevel, missile.pos, missile.homingTarget.position().add(0, missile.homingTarget.getBbHeight() / 2, 0));
				if (!targetUsable && missile.age % HOMING_REACQUIRE_INTERVAL_TICKS == 0) {
					missile.homingTarget = findHomingTarget(client, missile.pos, missile.velocity.normalize(), missile.shooter);
				}
			}
			if (missile.homingTarget != null && missile.homingTarget.isAlive() && !isTemporarilyInvisible(missile.homingTarget)
					&& client.level instanceof ClientLevel steerLevel) {
				Vec3 toTarget = missile.homingTarget.position().add(0, missile.homingTarget.getBbHeight() / 2, 0).subtract(missile.pos);
				if (toTarget.lengthSqr() > 0.01) {
					Vec3 targetDir = toTarget.normalize();
					Vec3 steeredDir;
					// Close-range terminal guidance: the wall-clearance cost used to be able to swerve
					// the arrow wide right at the last second and fly straight over/past a target
					// standing near a wall, missing entirely. Once genuinely close, ignore wall-proximity entirely and beeline straight at
					// them instead - UNLESS there's an actual solid block directly on that line (not
					// just nearby), in which case it still needs to steer around it as usual.
					if (toTarget.lengthSqr() < HOMING_DIRECT_APPROACH_DISTANCE_SQ
							&& hasLineOfSight(steerLevel, missile.pos, missile.homingTarget.position().add(0, missile.homingTarget.getBbHeight() / 2, 0))) {
						steeredDir = targetDir;
					} else {
						// Steers gradually toward the target rather than snapping straight at it - a real
						// "homing" curve, not a laser-straight correction every tick - and the direction
						// fed in isn't the raw straight line but one nudged around nearby obstacles
						// first, see steerAroundObstacles().
						steeredDir = steerAroundObstacles(steerLevel, missile.pos, targetDir);
					}
					Vec3 desired = steeredDir.scale(SPEED);
					// A bit more agile than before - reacts to a steering change
					// faster instead of easing into every turn quite so gradually.
					Vec3 blended = missile.velocity.scale(0.65).add(desired.scale(0.35));
					double len = blended.length();
					missile.velocity = len > 0.001 ? blended.scale(SPEED / len) : blended;
				}
			}
			Vec3 previousPos = missile.pos;
			missile.pos = missile.pos.add(missile.velocity);
			missile.age++;

			AbstractClientPlayer hitPlayer = findHitPlayer(client, previousPos, missile.pos, missile.shooter);
			if (hitPlayer != null) {
				if (missile.own && "PLASMA".equalsIgnoreCase(missile.spellType)) {
					startPlasmaSequence(client, hitPlayer, missile.rgb);
					return true;
				}
				invisibleUntilTick.put(hitPlayer.getId(), currentTick + HIT_INVISIBLE_TICKS);
				reassignArrowsTargeting(hitPlayer);
				burstOnPlayerHit(client, missile.pos, missile.rgb);
				if (missile.own) {
					playHitConfirmSound(client);
					recordOwnKill(client, hitPlayer, missile.pos);
				}
				return true;
			}

			BlockPos blockPos = BlockPos.containing(missile.pos);
			BlockState state = client.level.getBlockState(blockPos);
			boolean hitGround = !state.isAir() && !state.getCollisionShape(client.level, blockPos).isEmpty();

			if (hitGround) {
				burstOnGroundHit(client, missile.pos, missile.rgb);
				return true;
			}

			client.level.addParticle(new DustParticleOptions(missile.rgb, 1.2F), missile.pos.x, missile.pos.y, missile.pos.z, 0, 0, 0);
			// Icy keeps the original white spiral trail; the other spell types each get their own look
			// rather than all sharing it - a straight sharp streak for the Arrow, a denser crackling
			// trail for Plasma.
			if ("ARROW".equalsIgnoreCase(missile.spellType)) {
				spawnArrowTrail(client, missile);
			} else if ("PLASMA".equalsIgnoreCase(missile.spellType)) {
				spawnPlasmaTravelTrail(client, missile);
			} else {
				spawnSpiralTrail(client, missile);
			}
			// A homing arrow that's still actively chasing a real target doesn't time out mid-chase -
			// only the ordinary age limit applies once it has
			// nobody to home in on (or a hard safety cap far beyond that, so a target that somehow
			// never dies or goes out of range doesn't keep it alive forever).
			if ("ARROW".equalsIgnoreCase(missile.spellType) && missile.homingTarget != null && missile.homingTarget.isAlive()) {
				return missile.age >= MAX_AGE_TICKS * 4;
			}
			return missile.age >= MAX_AGE_TICKS;
		});
	}

	/**
	 * Homing Arrow's own look, completely redone with all-new particles - a tight double-helix of
	 * electric sparks winding right around the core (a much smaller radius than Icy's loose white
	 * spiral, reading as fast/precise instead of lazy), reusing {@code spiralAngle} the same way Icy
	 * does but with its own faster spin rate so the two never look alike.
	 */
	private static void spawnArrowTrail(Minecraft client, Missile missile) {
		missile.spiralAngle += 1.4F;
		if (missile.spiralAngle > (float) (Math.PI * 2)) {
			missile.spiralAngle -= (float) (Math.PI * 2);
		}
		Vec3 dir = missile.velocity.normalize();
		Vec3 back = dir.scale(-0.3);
		Vec3 reference = Math.abs(dir.y) < 0.99 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		Vec3 side = dir.cross(reference).normalize();
		Vec3 up = side.cross(dir).normalize();
		double radius = 0.09;
		for (int i = 0; i < 2; i++) {
			float angle = missile.spiralAngle + i * (float) Math.PI;
			double cos = Math.cos(angle);
			double sin = Math.sin(angle);
			double x = missile.pos.x + back.x + (side.x * cos + up.x * sin) * radius;
			double y = missile.pos.y + back.y + (side.y * cos + up.y * sin) * radius;
			double z = missile.pos.z + back.z + (side.z * cos + up.z * sin) * radius;
			client.level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, 0, 0, 0);
		}
	}

	/** Plasma's travel trail - denser, larger dust with the occasional spark, foreshadowing the crackling energy-ball it becomes on impact. */
	private static void spawnPlasmaTravelTrail(Minecraft client, Missile missile) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int i = 0; i < 3; i++) {
			double ox = (random.nextDouble() - 0.5) * 0.2;
			double oy = (random.nextDouble() - 0.5) * 0.2;
			double oz = (random.nextDouble() - 0.5) * 0.2;
			client.level.addParticle(new DustParticleOptions(missile.rgb, 1.6F), missile.pos.x + ox, missile.pos.y + oy, missile.pos.z + oz, 0, 0.01, 0);
		}
		if (random.nextFloat() < 0.3F) {
			client.level.addParticle(ParticleTypes.END_ROD, missile.pos.x, missile.pos.y, missile.pos.z, 0, 0, 0);
		}
	}

	/**
	 * A white double-helix of small particles winding around the missile's core dust particle as it
	 * flies - each one starts right at the core and drifts slowly outward (via its own particle
	 * velocity) instead of sitting at a fixed radius, so the spiral visibly expands as it trails
	 * behind the missile instead of just being a thin static ring.
	 */
	private static void spawnSpiralTrail(Minecraft client, Missile missile) {
		missile.spiralAngle += 0.9F;
		if (missile.spiralAngle > (float) (Math.PI * 2)) {
			missile.spiralAngle -= (float) (Math.PI * 2);
		}

		Vec3 direction = missile.velocity.normalize();
		Vec3 reference = Math.abs(direction.y) < 0.99 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		Vec3 side = direction.cross(reference).normalize();
		Vec3 up = side.cross(direction).normalize();

		for (int i = 0; i < 3; i++) {
			float angle = missile.spiralAngle + i * (float) (Math.PI * 2 / 3);
			double cos = Math.cos(angle);
			double sin = Math.sin(angle);
			double x = missile.pos.x + side.x * cos * TRAIL_SPIRAL_START_RADIUS + up.x * sin * TRAIL_SPIRAL_START_RADIUS;
			double y = missile.pos.y + side.y * cos * TRAIL_SPIRAL_START_RADIUS + up.y * sin * TRAIL_SPIRAL_START_RADIUS;
			double z = missile.pos.z + side.z * cos * TRAIL_SPIRAL_START_RADIUS + up.z * sin * TRAIL_SPIRAL_START_RADIUS;
			double vx = (side.x * cos + up.x * sin) * TRAIL_SPIRAL_OUTWARD_SPEED;
			double vy = (side.y * cos + up.y * sin) * TRAIL_SPIRAL_OUTWARD_SPEED;
			double vz = (side.z * cos + up.z * sin) * TRAIL_SPIRAL_OUTWARD_SPEED;
			client.level.addParticle(new DustParticleOptions(TRAIL_SPIRAL_RGB, 0.7F), x, y, z, vx, vy, vz);
		}
	}

	/**
	 * "Kill Tracker" is about Magic Missile hits, not real combat kills - the missile never deals
	 * real damage (no packets, purely client-side), so a hit here is the closest thing SkyMelloo has
	 * to a "kill" worth counting and announcing. The same target only counts once per 60 minutes -
	 * without this, repeatedly hitting the same nearby player would inflate the counter every tick
	 * they're in range, unlike real kills, which can only happen once per life anyway.
	 */
	private static void announceMissileKill(Minecraft client, AbstractClientPlayer hitPlayer) {
		if (client.player == null) {
			return;
		}
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		UUID uuid = hitPlayer.getUUID();
		Long cooldownUntil = killAnnounceCooldownUntil.get(uuid);
		if (cooldownUntil != null && cooldownUntil > currentTick) {
			return;
		}
		killAnnounceCooldownUntil.put(uuid, currentTick + KILL_ANNOUNCE_COOLDOWN_TICKS);
		config.totalPlayersKilled++;
		SkyMellooConfig.HANDLER.save();
		String template = pickKillMessageTemplate();
		String text = template
				.replace("{player}", hitPlayer.getName().getString())
				.replace("{count}", String.valueOf(config.totalPlayersKilled));
		// Always local - a kill message used to be optionally sent to the real party via /pc,
		// removed since it's always LOCAL-only now.
		client.player.sendSystemMessage(ChatUtil.prefixed(text));
	}

	/**
	 * Fixed pool, not user-editable - one picked at random per kill, never repeating one of the last
	 * {@link #MESSAGE_HISTORY_SIZE} used so it doesn't feel too samey back-to-back.
	 */
	private static final List<String> KILL_MESSAGES = List.of(
			"§c{player}§d got wiped out by your spell!",
			"§c{player}§d got hit by your spell!",
			"§c{player}§d never saw that spell coming!",
			"§dYou blasted §c{player}§d with pure spell power!",
			"§c{player}§d was struck down by your spell!",
			"§dYour spell erased §c{player}§d!",
			"§c{player}§d got obliterated by your spell!",
			"§dA well-aimed spell just took out §c{player}§d!",
			"§c{player}§d didn't dodge that one - spell hit confirmed!",
			"§dYour spell found its mark on §c{player}§d!",
			"§c{player}§d got zapped into oblivion!",
			"§dSpell connects - §c{player}§d is history!",
			"§c{player}§d got vaporized by your spell!",
			"§dYour spell just nailed §c{player}§d!",
			"§c{player}§d got smoked by your spell!",
			"§dSpell landed - §c{player}§d didn't stand a chance!",
			"§c{player}§d got cooked by your spell!",
			"§dDirect hit! §c{player}§d felt the full force of your spell!",
			"§c{player}§d just got spell-slapped!",
			"§dYour spell turned §c{player}§d into confetti!",
			"§c{player}§d got annihilated by pure spell power!",
			"§dThat spell hit §c{player}§d right where it hurts!",
			"§c{player}§d got demolished by your spell!",
			"§dSpell #{count}: §c{player}§d didn't survive it!",
			"§c{player}§d just got turned into a pretzel by your spell!",
			"§dYour spell absolutely yeeted §c{player}§d into next week!",
			"§c{player}§d got launched into orbit by your spell!",
			"§dNo mercy - §c{player}§d just got spell-bonked!",
			"§c{player}§d got sent to the shadow realm!",
			"§dYour spell said \"nope\" to §c{player}§d!",
			"§c{player}§d got clapped by your spell!",
			"§dSpell #{count}: §c{player}§d got sent packing!",
			"§c{player}§d just experienced true spell violence!",
			"§dYour spell gave §c{player}§d a one-way ticket to the void!"
	);

	private static String pickKillMessageTemplate() {
		List<String> candidates = KILL_MESSAGES.stream().filter(line -> !recentKillMessages.contains(line)).toList();
		List<String> effectivePool = candidates.isEmpty() ? KILL_MESSAGES : candidates;
		String chosen = effectivePool.get(ThreadLocalRandom.current().nextInt(effectivePool.size()));
		recentKillMessages.addLast(chosen);
		if (recentKillMessages.size() > MESSAGE_HISTORY_SIZE) {
			recentKillMessages.removeFirst();
		}
		return chosen;
	}

	/** 1-3 fake, collectible-by-walking-near "Spell Essence" items scattered at the kill spot - see {@link #tickEssenceCollection}. Always on - no toggle anymore. */
	private static void spawnCollectibleEssence(Minecraft client, Vec3 pos) {
		if (!PermissionsManager.has("cosmetics") || !(client.level instanceof ClientLevel level)) {
			return;
		}
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int count = 1 + random.nextInt(3); // 1-3
		for (int i = 0; i < count; i++) {
			ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
			stack.set(DataComponents.CUSTOM_NAME, Component.literal("§dSpell Essence"));
			double vx = (random.nextDouble() - 0.5) * 0.2;
			double vz = (random.nextDouble() - 0.5) * 0.2;
			ItemEntity essence = new ItemEntity(level, pos.x, pos.y, pos.z, stack, vx, 0.15 + random.nextDouble() * 0.1, vz);
			essence.setId(nextFakeEntityId--);
			essence.setPickUpDelay(32_000); // never a real vanilla pickup - see the class-level fake-item reasoning
			level.addEntity(essence);
			pendingEssence.add(new PendingEssence(essence.getId(), currentTick + ESSENCE_DESPAWN_TICKS));
		}
	}

	// "Plasma" spell type - impact doesn't kill immediately like Missile/Lightning: particles spawn
	// around the target and get pulled inward, bundling into a growing ball that shifts toward a bright
	// blue glow, then throws off sparks once "unstable", then detonates - THAT'S the actual kill moment
	// (see recordOwnKill at the end of tickPlasmaSequences), not the initial impact.
	private static final int PLASMA_DURATION_TICKS = 110; // ~5.5s - longer, more intense charge-up
	private static final int PLASMA_CHARGE_SOUND_INTERVAL_TICKS = 22; // spaced out for the longer anchor-charge sound
	private record PlasmaSequence(AbstractClientPlayer target, long startTick, int baseRgb) {
	}
	private static final List<PlasmaSequence> plasmaSequences = new ArrayList<>();

	// Warden's own sonic-charge buildup - a genuinely long (multi-second), rising, unmistakably
	// "charging up" sound in vanilla, not just a short blip.
	// Retriggered again partway through tickPlasmaSequences() so it spans the whole sequence.
	private static void startPlasmaSequence(Minecraft client, AbstractClientPlayer target, int rgb) {
		plasmaSequences.add(new PlasmaSequence(target, currentTick, rgb));
		Vec3 pos = target.position();
		client.level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 1.6F, 0.8F, false);
	}

	private static void tickPlasmaSequences(Minecraft client) {
		if (plasmaSequences.isEmpty() || !(client.level instanceof ClientLevel level)) {
			return;
		}
		ThreadLocalRandom random = ThreadLocalRandom.current();
		plasmaSequences.removeIf(seq -> {
			int elapsed = (int) (currentTick - seq.startTick());
			Vec3 center = seq.target().isAlive() ? seq.target().position().add(0, seq.target().getBbHeight() / 2, 0) : seq.target().position();
			if (elapsed >= PLASMA_DURATION_TICKS) {
				// A bright flash right at the pop, on top of the usual burst.
				level.addParticle(net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFF), center.x, center.y, center.z, 0, 0, 0);
				burstOnPlayerHit(client, center, 0x66CCFF);
				burstOnPlayerHit(client, center, 0xFFFFFF);
				burstOnPlayerHit(client, center, seq.baseRgb());
				// A real expanding shockwave ring (the same particle the Warden's sonic attack uses),
				// on top of the hand-rolled spark ring inside burstOnPlayerHit.
				level.addParticle(ParticleTypes.SONIC_BOOM, center.x, center.y, center.z, 0, 0, 0);
				level.playLocalSound(center.x, center.y, center.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 3.2F, 0.6F, false);
				level.playLocalSound(center.x, center.y, center.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 2.0F, 1.3F, false);
				level.playLocalSound(center.x, center.y, center.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 2.0F, 0.9F, false);
				invisibleUntilTick.put(seq.target().getId(), currentTick + HIT_INVISIBLE_TICKS);
				reassignArrowsTargeting(seq.target());
				playHitConfirmSound(client);
				recordOwnKill(client, seq.target(), center);
				return true;
			}
			double progress = elapsed / (double) PLASMA_DURATION_TICKS;
			int rgb = lerpColor(seq.baseRgb(), 0x2288FF, Math.min(1.0, progress * 1.3));
			// A rising-pitch generator-charge sound, periodically, so the buildup is heard as well as
			// seen, instead of the old note-block pling.
			if (elapsed % PLASMA_CHARGE_SOUND_INTERVAL_TICKS == 0) {
				float pitch = 0.6F + (float) progress * 1.0F;
				float volume = 1.0F + (float) progress * 1.0F;
				level.playLocalSound(center.x, center.y, center.z, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, volume, pitch, false);
			}
			// A second surge of the long warden charge sound partway through, so its rising "charging"
			// arc covers the full (now much longer) buildup instead of just the very start.
			if (elapsed == PLASMA_DURATION_TICKS / 2) {
				level.playLocalSound(center.x, center.y, center.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 1.8F, 1.1F, false);
			}
			// A white-blue helix "snake" threading straight through the ball's vertical axis, on top of
			// the inward-pulling particles below.
			float helixAngle = elapsed * 0.5F;
			double helixRadius = 0.5 + 0.3 * Math.sin(elapsed * 0.3);
			for (int i = 0; i < 2; i++) {
				float a = helixAngle + i * (float) Math.PI;
				double hx = center.x + Math.cos(a) * helixRadius;
				double hz = center.z + Math.sin(a) * helixRadius;
				double hy = center.y + Math.sin(elapsed * 0.4 + i * Math.PI) * 1.2;
				level.addParticle(new DustParticleOptions(0xDDEEFF, 1.0F), hx, hy, hz, 0, 0, 0);
			}
			// Particles are pulled in from progressively farther out as it builds, and more of them -
			// the pull itself
			// ramps up hard over the sequence AND the spawn ring itself
			// shrinks in toward the end so the bundle visibly compresses into a much
			// denser, tighter core instead of just orbiting at the same spread.
			int particleCount = 1 + (int) (progress * 10);
			double outerRadius = (0.6 + progress * 3.2) * (1.0 - progress * 0.55);
			// Pushed further still - particles now snap in
			// noticeably faster/harder, especially in the back half of the buildup.
			double pullStrength = 0.5 + progress * 2.0;
			for (int i = 0; i < particleCount; i++) {
				double angle = random.nextDouble() * Math.PI * 2;
				double heightOffset = (random.nextDouble() - 0.5) * 1.6 * (1.0 - progress * 0.4);
				double spawnRadius = outerRadius * (0.4 + random.nextDouble() * 0.6);
				double sx = center.x + Math.cos(angle) * spawnRadius;
				double sy = center.y + heightOffset;
				double sz = center.z + Math.sin(angle) * spawnRadius;
				double vx = (center.x - sx) * pullStrength;
				double vy = (center.y - sy) * pullStrength;
				double vz = (center.z - sz) * pullStrength;
				level.addParticle(new DustParticleOptions(rgb, 1.0F + (float) progress), sx, sy, sz, vx, vy, vz);
			}
			// "Unstable" phase - little sparks shoot outward far past the ball right before it pops.
			if (progress > 0.7) {
				for (int i = 0; i < 2; i++) {
					double angle = random.nextDouble() * Math.PI * 2;
					double dist = 2.0 + random.nextDouble() * 3.0;
					double vx = Math.cos(angle) * 0.3;
					double vz = Math.sin(angle) * 0.3;
					level.addParticle(ParticleTypes.END_ROD, center.x + Math.cos(angle) * 0.3, center.y, center.z + Math.sin(angle) * 0.3, vx, 0.05, vz);
				}
			}
			return false;
		});
	}

	// "Levitate" spell type sequence - see triggerLevitate() for the German request this implements.
	private static final int LEVITATE_DURATION_TICKS = 65; // ~3.25s
	private static final double LEVITATE_MAX_LIFT = 3.5; // blocks
	private static final double LEVITATE_RISE_END = 0.55; // rise phase covers the first 55% of the sequence
	private static final double LEVITATE_BOOM_AT = 0.75; // shockwave fires once, at 75% through
	private static final double LEVITATE_HOVER_AMPLITUDE = 0.3; // blocks - gentle bob once at the top
	private static final double LEVITATE_HOVER_SPEED = 0.07; // radians/tick - slow, smooth
	private record LevitateSequence(AbstractClientPlayer target, long startTick, Vec3 anchor, int baseRgb) {
	}
	private static final List<LevitateSequence> levitateSequences = new ArrayList<>();
	// Tracks which sequences have already fired their one-shot shockwave, keyed by the target's entity
	// id - a plain elapsed-tick equality check (like Plasma's charge-sound retrigger) is unreliable
	// here since the rise phase's particle math doesn't tick on a fixed-size step.
	private static final java.util.Set<Integer> levitateBoomFired = new java.util.HashSet<>();

	/** Shared by {@link #tickLevitateSequences} (for the particle effigy's position) and {@link #getLevitateLiftOffset} (for actually raising the real player's rendered position). */
	private static double computeLevitateLift(long startTick) {
		int elapsed = (int) (currentTick - startTick);
		double progress = Math.min(1.0, elapsed / (double) LEVITATE_DURATION_TICKS);
		double liftProgress = Math.min(1.0, progress / LEVITATE_RISE_END);
		// Eased rather than linear - starts slow (like actually lifting off against gravity), then
		// accelerates upward.
		double lift = LEVITATE_MAX_LIFT * (liftProgress * liftProgress);
		if (progress >= LEVITATE_RISE_END) {
			// Once at the top, a gentle continuous up/down bob instead of just hanging static there.
			// Starts exactly at the rise
			// phase's end height (sin(0) = 0) so there's no sudden jump into the hover.
			double hoverElapsedTicks = elapsed - LEVITATE_RISE_END * LEVITATE_DURATION_TICKS;
			lift += Math.sin(hoverElapsedTicks * LEVITATE_HOVER_SPEED) * LEVITATE_HOVER_AMPLITUDE;
		}
		return lift;
	}

	/**
	 * Where to actually RENDER {@code entity} right now, if they're the target of an in-progress
	 * Levitate sequence - {@code null} otherwise. Applied by
	 * {@link com.melloo.skymelloo.client.mixin.ForcedInvisibilityExtractionMixin} and
	 * {@link com.melloo.skymelloo.client.mixin.AvatarForcedInvisibilityExtractionMixin} (the same
	 * extraction tail-inject already used for forced invisibility) - the REAL player model now
	 * actually rises, not just a separate particle effigy while the real one stood still on the
	 * ground. Locks X/Z to the anchor too, not just raising Y -
	 * otherwise the real player (still free to walk around under their own control the whole time)
	 * could visibly wander off while the particle effigy stays behind at the original spot.
	 */
	public static Vec3 getLevitateRenderOverride(Entity entity) {
		for (LevitateSequence seq : levitateSequences) {
			if (seq.target().getId() == entity.getId()) {
				return seq.anchor().add(0, computeLevitateLift(seq.startTick()), 0);
			}
		}
		return null;
	}

	private static void tickLevitateSequences(Minecraft client) {
		if (levitateSequences.isEmpty() || !(client.level instanceof ClientLevel level)) {
			return;
		}
		ThreadLocalRandom random = ThreadLocalRandom.current();
		levitateSequences.removeIf(seq -> {
			int elapsed = (int) (currentTick - seq.startTick());
			double progress = Math.min(1.0, elapsed / (double) LEVITATE_DURATION_TICKS);
			double lift = computeLevitateLift(seq.startTick());
			Vec3 center = seq.anchor().add(0, lift, 0);

			if (elapsed >= LEVITATE_DURATION_TICKS) {
				level.addParticle(net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFF), center.x, center.y, center.z, 0, 0, 0);
				burstOnPlayerHit(client, center, seq.baseRgb());
				level.playLocalSound(center.x, center.y, center.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2.4F, 1.2F, false);
				invisibleUntilTick.put(seq.target().getId(), currentTick + HIT_INVISIBLE_TICKS);
				reassignArrowsTargeting(seq.target());
				playHitConfirmSound(client);
				recordOwnKill(client, seq.target(), center);
				levitateBoomFired.remove(seq.target().getId());
				return true;
			}

			// Rising phase, redone with a more elaborate animation
			// (REVERSE_PORTAL/SOUL read as too plain/dark). A proper rising double-helix of end-rod
			// sparkles winding around the body as it climbs, trailing below the current height, plus a
			// denser ambient scatter that thickens the higher it gets.
			float helixAngle = elapsed * 0.45F;
			double helixRadius = 0.4 + progress * 0.3;
			for (int i = 0; i < 2; i++) {
				float angle = helixAngle + i * (float) Math.PI;
				double hx = center.x + Math.cos(angle) * helixRadius;
				double hz = center.z + Math.sin(angle) * helixRadius;
				double hy = center.y - 1.0 + ((elapsed * 2 + i * 10) % 20) * 0.09;
				level.addParticle(ParticleTypes.END_ROD, hx, hy, hz, 0, 0.01, 0);
			}
			int scatterCount = 2 + (int) (progress * 5);
			for (int i = 0; i < scatterCount; i++) {
				double angle = random.nextDouble() * Math.PI * 2;
				double radius = 0.3 + random.nextDouble() * 0.4;
				double px = center.x + Math.cos(angle) * radius;
				double pz = center.z + Math.sin(angle) * radius;
				double py = center.y - 0.7 + random.nextDouble() * 1.3;
				level.addParticle(ParticleTypes.END_ROD, px, py, pz, 0, 0.04 + random.nextDouble() * 0.04, 0);
			}

			// A one-shot expanding shockwave ring right at the peak.
			if (progress >= LEVITATE_BOOM_AT && levitateBoomFired.add(seq.target().getId())) {
				level.addParticle(ParticleTypes.SONIC_BOOM, center.x, center.y, center.z, 0, 0, 0);
				level.playLocalSound(center.x, center.y, center.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 2.0F, 1.0F, false);
			}
			return false;
		});
	}

	/** Linear channel-wise interpolation between two RGB ints, {@code t} clamped to 0-1. */
	private static int lerpColor(int fromRgb, int toRgb, double t) {
		double clamped = Math.max(0, Math.min(1, t));
		int r = (int) (((fromRgb >> 16) & 0xFF) + (((toRgb >> 16) & 0xFF) - ((fromRgb >> 16) & 0xFF)) * clamped);
		int g = (int) (((fromRgb >> 8) & 0xFF) + (((toRgb >> 8) & 0xFF) - ((fromRgb >> 8) & 0xFF)) * clamped);
		int b = (int) ((fromRgb & 0xFF) + ((toRgb & 0xFF) - (fromRgb & 0xFF)) * clamped);
		return (r << 16) | (g << 8) | b;
	}

	/**
	 * Runs every tick regardless of whether a missile is currently in flight - an essence item can sit
	 * on the ground waiting to be collected long after its missile is gone. "Collecting" is just a
	 * distance check against the LOCAL player (this is a fake entity with no real pickup logic to hook
	 * into); uncollected essence quietly despawns after {@link #ESSENCE_DESPAWN_TICKS}.
	 */
	private static void tickEssenceCollection(Minecraft client) {
		if (pendingEssence.isEmpty() || !(client.level instanceof ClientLevel level) || client.player == null) {
			return;
		}
		Vec3 playerPos = client.player.position();
		pendingEssence.removeIf(entry -> {
			Entity entity = level.getEntity(entry.entityId());
			if (entity == null) {
				return true;
			}
			if (entity.position().distanceToSqr(playerPos) <= ESSENCE_COLLECT_DISTANCE_SQ) {
				SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
				config.totalSpellEssenceCollected++;
				SkyMellooConfig.HANDLER.save();
				// Just a counter tick + sound/particles, deliberately NOT the real inventory - that was
				// a misunderstanding of an earlier report about a different (now-removed) fake-gear-drop
				// feature, not essence. Essence is just "collected".
				level.playLocalSound(entity.getX(), entity.getY(), entity.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5F, 1.4F, false);
				for (int i = 0; i < 8; i++) {
					level.addParticle(new DustParticleOptions(0xAA55FF, 1.0F), entity.getX(), entity.getY() + 0.2, entity.getZ(), 0, 0.05, 0);
				}
				level.removeEntity(entry.entityId(), Entity.RemovalReason.DISCARDED);
				return true;
			}
			if (currentTick >= entry.expiryTick()) {
				level.removeEntity(entry.entityId(), Entity.RemovalReason.DISCARDED);
				return true;
			}
			return false;
		});
	}

	/**
	 * Checks the whole segment the missile just traveled, not just its new endpoint - at
	 * {@link #SPEED} 0.9 blocks/tick, a single-point check at the end of each step can tunnel
	 * straight through a player's ~1.1-block-wide (with inflate) hit zone entirely, especially at
	 * a glancing angle or against a moving target. This was the actual cause of "the missile flies
	 * through people" reports - happens to anyone, just far more noticeable on a target you can't
	 * see (e.g. one already turned invisible by a recent hit), since a visible near-miss at least
	 * looks like a miss.
	 */
	private static AbstractClientPlayer findHitPlayer(Minecraft client, Vec3 from, Vec3 to, AbstractClientPlayer shooter) {
		for (AbstractClientPlayer other : client.level.players()) {
			// Already invisible from a recent hit (still in their HIT_INVISIBLE_TICKS cooldown) - fly
			// straight through instead of re-triggering the hit, which used to just refresh their
			// invisibility timer and replay the burst/sound over and over on anyone standing still
			// nearby rather than actually needing to become hittable again first. Also excludes anyone
			// already mid-Plasma/mid-Levitate - those don't set the invisibility flag until the very
			// end of their multi-second sequence, so without this a second hit during that window
			// would start an overlapping second sequence on the same target - not hittable again
			// while already mid-animation.
			if (other == shooter || HighlightManager.isNpc(other) || isTemporarilyInvisible(other) || isInActiveSequence(other)) {
				continue;
			}
			AABB box = other.getBoundingBox().inflate(0.25);
			if (box.contains(to) || box.clip(from, to).isPresent()) {
				return other;
			}
		}
		return null;
	}

	/** The big, flashy version - only for actually hitting another player, so it reads as a real "hit" moment. */
	private static void burstOnPlayerHit(Minecraft client, Vec3 pos, int rgb) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int i = 0; i < BURST_PARTICLES; i++) {
			double yaw = random.nextDouble() * Math.PI * 2;
			double pitch = random.nextDouble() * Math.PI;
			double speed = 0.2 + random.nextDouble() * 0.4;
			double vx = Math.cos(yaw) * Math.sin(pitch) * speed;
			double vy = Math.cos(pitch) * speed;
			double vz = Math.sin(yaw) * Math.sin(pitch) * speed;
			client.level.addParticle(new DustParticleOptions(rgb, 1.6F), pos.x, pos.y, pos.z, vx, vy, vz);
		}
		// A ring of bright white sparks right at the moment of impact, on top of the colored burst,
		// for a punchier "flash" without needing the color-parameterized FLASH particle type.
		int ringPoints = 20;
		for (int i = 0; i < ringPoints; i++) {
			double angle = Math.PI * 2 * i / ringPoints;
			double vx = Math.cos(angle) * 0.4;
			double vz = Math.sin(angle) * 0.4;
			client.level.addParticle(new DustParticleOptions(0xFFFFFF, 1.3F), pos.x, pos.y, pos.z, vx, 0.05, vz);
		}
		client.level.addParticle(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 0, 0, 0);
		client.level.addParticle(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 0, 0, 0);
		for (int i = 0; i < 4; i++) {
			double ox = (random.nextDouble() - 0.5) * 0.4;
			double oy = random.nextDouble() * 0.5;
			double oz = (random.nextDouble() - 0.5) * 0.4;
			client.level.addParticle(ParticleTypes.POOF, pos.x + ox, pos.y + oy, pos.z + oz, 0, 0, 0);
		}
		// Explosion boom plays at the hit location (falls off with distance, like a real impact) - the
		// separate hit-confirm "pling" for the shooter specifically is playHitConfirmSound() below.
		client.level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.2F, 1.1F, false);
	}

	/**
	 * A "pling" hit-confirm for the shooter specifically - played AT the shooter's own position rather
	 * than the (possibly far-away) impact point, so it's always heard clearly regardless of how far
	 * the missile traveled, the same way a hit-marker sound in most shooters is a fixed UI cue rather
	 * than a positional world sound. Always the same pitch now - it used to vary based on whether the
	 * target had ever been hit before at all (not just recently), which read as an inconsistent,
	 * confusing "bling" rather than a reliable hit-confirm cue.
	 */
	private static void playHitConfirmSound(Minecraft client) {
		if (client.player == null) {
			return;
		}
		Vec3 pos = client.player.position();
		client.level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.6F, 1.0F, false);
	}

	/** A smaller, plain burst for hitting terrain - no flash ring/pling, so those stay a "you hit someone" signal. */
	private static void burstOnGroundHit(Minecraft client, Vec3 pos, int rgb) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int i = 0; i < 40; i++) {
			double yaw = random.nextDouble() * Math.PI * 2;
			double pitch = random.nextDouble() * Math.PI;
			double speed = 0.2 + random.nextDouble() * 0.35;
			double vx = Math.cos(yaw) * Math.sin(pitch) * speed;
			double vy = Math.cos(pitch) * speed;
			double vz = Math.sin(yaw) * Math.sin(pitch) * speed;
			client.level.addParticle(new DustParticleOptions(rgb, 1.6F), pos.x, pos.y, pos.z, vx, vy, vz);
		}
		client.level.addParticle(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 0, 0, 0);
		client.level.addParticle(ParticleTypes.POOF, pos.x, pos.y, pos.z, 0, 0, 0);
		client.level.addParticle(ParticleTypes.POOF, pos.x, pos.y + 0.2, pos.z, 0, 0, 0);
		client.level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.2F, 1.1F, false);
	}
}
