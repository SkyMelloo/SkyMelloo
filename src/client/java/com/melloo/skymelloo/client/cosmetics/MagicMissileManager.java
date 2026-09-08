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

/** Purely cosmetic "magic missile": punch empty air to fire a particle projectile. Client-side-only, no real damage/packets. */
public final class MagicMissileManager {
	private static final double SPEED = 0.9;
	private static final int MAX_AGE_TICKS = 90;
	private static final int BURST_PARTICLES = 64;
	private static final int HIT_INVISIBLE_TICKS = 100;
	private static final int TRAIL_SPIRAL_RGB = 0xFFFFFF;
	private static final double TRAIL_SPIRAL_START_RADIUS = 0.12;
	private static final double TRAIL_SPIRAL_OUTWARD_SPEED = 0.1;

	private static final int REMOTE_RGB = 0xFF5555;

	private static final class Missile {
		Vec3 pos;
		Vec3 velocity;
		final int rgb;
		final boolean own;
		final AbstractClientPlayer shooter;
		final String spellType;
		AbstractClientPlayer homingTarget;
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

	private static final long KILL_ANNOUNCE_COOLDOWN_TICKS = 60L * 60 * 20;

	private static final int MESSAGE_HISTORY_SIZE = 5;

	private static final List<Missile> active = new ArrayList<>();
	private static final Map<Integer, Long> invisibleUntilTick = new HashMap<>();
	private static final Map<UUID, Long> killAnnounceCooldownUntil = new HashMap<>();
	private static final Deque<String> recentKillMessages = new ArrayDeque<>();

	// Fake, client-only collectible dropped on an own kill; never vanilla-pickable, collected via distance check.
	private static final int ESSENCE_DESPAWN_TICKS = 400;
	// Target stays solid while rendered invisible, so the collect radius has to reach past their old standing spot.
	private static final double ESSENCE_COLLECT_DISTANCE_SQ = 2.5 * 2.5;
	private record PendingEssence(int entityId, long expiryTick) {
	}
	private static final List<PendingEssence> pendingEssence = new ArrayList<>();
	// Shared counter for essence drops and fake lightning entities, kept out of range of other fake ids.
	private static int nextFakeEntityId = Integer.MAX_VALUE - 100_000;
	private static long currentTick = 0;

	private static final double LIGHTNING_MAX_RANGE = 60;
	public record RecentKill(GameProfile profile, long timestampMillis, int victimKillNumber) {
	}
	private static final int MAX_RECENT_KILLS = 20;
	private static final Deque<RecentKill> recentKills = new ArrayDeque<>();

	private MagicMissileManager() {
	}

	public static void trigger(Minecraft client) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.magicMissileEnabled || !PermissionsManager.has("spell")) {
			return;
		}
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}
		player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		config.totalSpellsCast++;
		SkyMellooConfig.HANDLER.saveDebounced();
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

	private static final double HOMING_MAX_RANGE = 60;
	private static final int HOMING_REACQUIRE_INTERVAL_TICKS = 5;
	// Within this distance, ignore wall-proximity cost and beeline straight at the target.
	private static final double HOMING_DIRECT_APPROACH_DISTANCE_SQ = 3.5 * 3.5;
	private static final double HOMING_MIN_ALIGNMENT = Math.cos(Math.toRadians(35));
	// Targets already at this many arrows are deprioritized, not excluded.
	private static final int HOMING_MAX_PER_TARGET = 5;

	private static int countHomingArrowsTargeting(AbstractClientPlayer target) {
		int count = 0;
		for (Missile m : active) {
			if ("ARROW".equalsIgnoreCase(m.spellType) && m.homingTarget == target) {
				count++;
			}
		}
		return count;
	}

	/** Retargets any other in-flight arrows still chasing this now-hit player. */
	private static void reassignArrowsTargeting(AbstractClientPlayer deadTarget) {
		Minecraft client = Minecraft.getInstance();
		for (Missile m : active) {
			if ("ARROW".equalsIgnoreCase(m.spellType) && m.homingTarget == deadTarget) {
				m.homingTarget = findHomingTarget(client, m.pos, m.velocity.normalize(), m.shooter);
			}
		}
	}

	/** Best in-sight player within the crosshair cone for Homing Arrow; spreads targets before exceeding {@link #HOMING_MAX_PER_TARGET}. */
	private static AbstractClientPlayer findHomingTarget(Minecraft client, Vec3 origin, Vec3 look, AbstractClientPlayer shooter) {
		if (!(client.level instanceof ClientLevel level)) {
			return null;
		}
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
				continue;
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

	private static boolean hasLineOfSight(ClientLevel level, Vec3 from, Vec3 to) {
		ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
		return level.clip(ctx).getType() == HitResult.Type.MISS;
	}

	// Homing Arrow steering: cost-scored sampling around the direct line, not a real grid search.
	private static final double STEER_PROBE_DISTANCE = 1.6;
	private static final double STEER_ANGLE_WEIGHT = 4.0;
	private static final double[][] STEER_OFFSETS = {
			{0, 0},
			{0.4, 0}, {-0.4, 0}, {0, 0.4}, {0, -0.4},
			{0.3, 0.3}, {-0.3, 0.3}, {0.3, -0.3}, {-0.3, -0.3},
	};
	private static final int[][] AXIS_OFFSETS_1 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
	private static final int[][] AXIS_OFFSETS_2 = {{2, 0, 0}, {-2, 0, 0}, {0, 2, 0}, {0, -2, 0}, {0, 0, 2}, {0, 0, -2}};

	private static boolean isSolidBlock(ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
	}

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

	/** Instant crosshair hit-test, visual-only lightning bolt - no real damage. */
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

	/** Instant crosshair hit-test; kill resolves at the end of {@link #tickLevitateSequences}, not immediately. */
	private static void triggerLevitate(Minecraft client, LocalPlayer player) {
		Vec3 origin = player.getEyePosition();
		Vec3 end = origin.add(player.getLookAngle().scale(LIGHTNING_MAX_RANGE));
		AbstractClientPlayer target = findHitPlayer(client, origin, end, player);
		if (target == null || !(client.level instanceof ClientLevel level)) {
			return;
		}
		Vec3 anchor = target.position();
		levitateSequences.add(new LevitateSequence(target, currentTick, anchor, SkyMellooConfig.HANDLER.instance().magicMissileColor.getRGB() & 0xFFFFFF));
		level.playLocalSound(anchor.x, anchor.y, anchor.z, SoundEvents.SHULKER_TELEPORT, SoundSource.PLAYERS, 1.2F, 0.7F, false);
	}

	private static void recordOwnKill(Minecraft client, AbstractClientPlayer hitPlayer, Vec3 hitPos) {
		announceMissileKill(client, hitPlayer);
		spawnCollectibleEssence(client, hitPos);
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		config.totalMagicMissileKills++;
		SkyMellooConfig.HANDLER.saveDebounced();
		recentKills.addFirst(new RecentKill(hitPlayer.getGameProfile(), System.currentTimeMillis(), config.totalMagicMissileKills));
		while (recentKills.size() > MAX_RECENT_KILLS) {
			recentKills.removeLast();
		}
	}

	public static List<RecentKill> getRecentKills() {
		return new ArrayList<>(recentKills);
	}

	/** Predicted, unsynced approximation of another player's missile; always rendered red regardless of their color. */
	public static void spawnRemote(Minecraft client, AbstractClientPlayer other) {
		if (!SkyMellooConfig.HANDLER.instance().magicMissileEnabled || !PermissionsManager.has("spell")) {
			return;
		}
		Vec3 origin = other.getEyePosition();
		Vec3 velocity = other.getLookAngle().scale(SPEED);
		active.add(new Missile(origin, velocity, REMOTE_RGB, false, other, "MISSILE", null));
	}

	public static boolean isTemporarilyInvisible(Entity entity) {
		Long until = invisibleUntilTick.get(entity.getId());
		return until != null && until > currentTick;
	}

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
				// Throttled re-scan, only when the current target is no longer usable.
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
					if (toTarget.lengthSqr() < HOMING_DIRECT_APPROACH_DISTANCE_SQ
							&& hasLineOfSight(steerLevel, missile.pos, missile.homingTarget.position().add(0, missile.homingTarget.getBbHeight() / 2, 0))) {
						steeredDir = targetDir;
					} else {
						steeredDir = steerAroundObstacles(steerLevel, missile.pos, targetDir);
					}
					Vec3 desired = steeredDir.scale(SPEED);
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
			if ("ARROW".equalsIgnoreCase(missile.spellType)) {
				spawnArrowTrail(client, missile);
			} else if ("PLASMA".equalsIgnoreCase(missile.spellType)) {
				spawnPlasmaTravelTrail(client, missile);
			} else {
				spawnSpiralTrail(client, missile);
			}
			// An arrow actively chasing a target gets a longer age limit before timing out mid-chase.
			if ("ARROW".equalsIgnoreCase(missile.spellType) && missile.homingTarget != null && missile.homingTarget.isAlive()) {
				return missile.age >= MAX_AGE_TICKS * 4;
			}
			return missile.age >= MAX_AGE_TICKS;
		});
	}

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

	/** Same target only counts once per 60 minutes, since a missile hit has no real death to gate on. */
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
		SkyMellooConfig.HANDLER.saveDebounced();
		String key = pickKillMessageTemplate();
		Component text = Component.translatable(key, hitPlayer.getName().getString(), config.totalPlayersKilled);
		client.player.sendSystemMessage(ChatUtil.prefixed(text));
	}

	private static final List<String> KILL_MESSAGES = List.of(
			"skymelloo.chat.kill.message_01", "skymelloo.chat.kill.message_02", "skymelloo.chat.kill.message_03",
			"skymelloo.chat.kill.message_04", "skymelloo.chat.kill.message_05", "skymelloo.chat.kill.message_06",
			"skymelloo.chat.kill.message_07", "skymelloo.chat.kill.message_08", "skymelloo.chat.kill.message_09",
			"skymelloo.chat.kill.message_10", "skymelloo.chat.kill.message_11", "skymelloo.chat.kill.message_12",
			"skymelloo.chat.kill.message_13", "skymelloo.chat.kill.message_14", "skymelloo.chat.kill.message_15",
			"skymelloo.chat.kill.message_16", "skymelloo.chat.kill.message_17", "skymelloo.chat.kill.message_18",
			"skymelloo.chat.kill.message_19", "skymelloo.chat.kill.message_20", "skymelloo.chat.kill.message_21",
			"skymelloo.chat.kill.message_22", "skymelloo.chat.kill.message_23", "skymelloo.chat.kill.message_24",
			"skymelloo.chat.kill.message_25", "skymelloo.chat.kill.message_26", "skymelloo.chat.kill.message_27",
			"skymelloo.chat.kill.message_28", "skymelloo.chat.kill.message_29", "skymelloo.chat.kill.message_30",
			"skymelloo.chat.kill.message_31", "skymelloo.chat.kill.message_32", "skymelloo.chat.kill.message_33",
			"skymelloo.chat.kill.message_34"
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
		if (!PermissionsManager.has("spell") || !(client.level instanceof ClientLevel level)) {
			return;
		}
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int count = 1 + random.nextInt(3); // 1-3
		for (int i = 0; i < count; i++) {
			ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
			stack.set(DataComponents.CUSTOM_NAME, Component.translatable("skymelloo.item.spell_essence"));
			double vx = (random.nextDouble() - 0.5) * 0.2;
			double vz = (random.nextDouble() - 0.5) * 0.2;
			ItemEntity essence = new ItemEntity(level, pos.x, pos.y, pos.z, stack, vx, 0.15 + random.nextDouble() * 0.1, vz);
			essence.setId(nextFakeEntityId--);
			essence.setPickUpDelay(32_000); // never a real vanilla pickup - see the class-level fake-item reasoning
			level.addEntity(essence);
			pendingEssence.add(new PendingEssence(essence.getId(), currentTick + ESSENCE_DESPAWN_TICKS));
		}
	}

	// "Plasma" - impact doesn't kill immediately; the actual kill is the detonation at the end of tickPlasmaSequences.
	private static final int PLASMA_DURATION_TICKS = 110;
	private static final int PLASMA_CHARGE_SOUND_INTERVAL_TICKS = 22;
	private record PlasmaSequence(AbstractClientPlayer target, long startTick, int baseRgb) {
	}
	private static final List<PlasmaSequence> plasmaSequences = new ArrayList<>();

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
			int particleCount = 1 + (int) (progress * 10);
			double outerRadius = (0.6 + progress * 3.2) * (1.0 - progress * 0.55);
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

	private static final int LEVITATE_DURATION_TICKS = 65;
	private static final double LEVITATE_MAX_LIFT = 3.5;
	private static final double LEVITATE_RISE_END = 0.55;
	private static final double LEVITATE_BOOM_AT = 0.75;
	private static final double LEVITATE_HOVER_AMPLITUDE = 0.3;
	private static final double LEVITATE_HOVER_SPEED = 0.07;
	private record LevitateSequence(AbstractClientPlayer target, long startTick, Vec3 anchor, int baseRgb) {
	}
	private static final List<LevitateSequence> levitateSequences = new ArrayList<>();
	private static final java.util.Set<Integer> levitateBoomFired = new java.util.HashSet<>();

	private static double computeLevitateLift(long startTick) {
		int elapsed = (int) (currentTick - startTick);
		double progress = Math.min(1.0, elapsed / (double) LEVITATE_DURATION_TICKS);
		double liftProgress = Math.min(1.0, progress / LEVITATE_RISE_END);
		double lift = LEVITATE_MAX_LIFT * (liftProgress * liftProgress);
		if (progress >= LEVITATE_RISE_END) {
			double hoverElapsedTicks = elapsed - LEVITATE_RISE_END * LEVITATE_DURATION_TICKS;
			lift += Math.sin(hoverElapsedTicks * LEVITATE_HOVER_SPEED) * LEVITATE_HOVER_AMPLITUDE;
		}
		return lift;
	}

	/** Render-position override for a Levitate target, applied via the forced-invisibility extraction mixins. Null if not levitating. */
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

			if (progress >= LEVITATE_BOOM_AT && levitateBoomFired.add(seq.target().getId())) {
				level.addParticle(ParticleTypes.SONIC_BOOM, center.x, center.y, center.z, 0, 0, 0);
				level.playLocalSound(center.x, center.y, center.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 2.0F, 1.0F, false);
			}
			return false;
		});
	}

	private static int lerpColor(int fromRgb, int toRgb, double t) {
		double clamped = Math.max(0, Math.min(1, t));
		int r = (int) (((fromRgb >> 16) & 0xFF) + (((toRgb >> 16) & 0xFF) - ((fromRgb >> 16) & 0xFF)) * clamped);
		int g = (int) (((fromRgb >> 8) & 0xFF) + (((toRgb >> 8) & 0xFF) - ((fromRgb >> 8) & 0xFF)) * clamped);
		int b = (int) ((fromRgb & 0xFF) + ((toRgb & 0xFF) - (fromRgb & 0xFF)) * clamped);
		return (r << 16) | (g << 8) | b;
	}

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
				SkyMellooConfig.HANDLER.saveDebounced();
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

	/** Checks the whole travel segment, not just the endpoint, so a fast missile can't tunnel through a hit zone. */
	private static AbstractClientPlayer findHitPlayer(Minecraft client, Vec3 from, Vec3 to, AbstractClientPlayer shooter) {
		for (AbstractClientPlayer other : client.level.players()) {
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
		client.level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.2F, 1.1F, false);
	}

	/** Played at the shooter's own position, not the impact point, so it's always heard clearly. */
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
