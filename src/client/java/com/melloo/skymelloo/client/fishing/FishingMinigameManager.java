package com.melloo.skymelloo.client.fishing;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.fish.Pufferfish;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A little "shooting gallery" to keep your hands busy while waiting for a bite: every few seconds
 * (while the rod is cast out and not currently biting), a handful of pufferfish targets pop up 5-10
 * blocks in front of you and slowly puff up. Click one before it fully inflates for points - the
 * fatter it gets, the fewer points it's worth, and if you let it fully inflate it just pops on its
 * own for nothing. Every hit adds to a running chain/combo (see {@link FishingScoreHud} for the
 * on-screen display) that only ends if you go more than {@link #NOT_FISHING_GRACE_MS} without the
 * rod cast out - reeling in a real catch and recasting within that window keeps the chain alive,
 * with a persisted best-chain highscore. Purely cosmetic, no gameplay effect.
 */
public final class FishingMinigameManager {
	private static final int WAVE_INTERVAL_TICKS = 40;
	private static final int TARGETS_PER_WAVE = 4;
	private static final double MIN_DISTANCE = 5;
	private static final double MAX_DISTANCE = 10;
	private static final double CONE_HALF_ANGLE_DEG = 18;
	/** How long you can go without actively fishing (rod not cast) before the chain/combo ends - recasting within this window keeps it going, same as if you'd never stopped. */
	private static final long NOT_FISHING_GRACE_MS = 10_000;

	private static final int STAGE_DURATION_TICKS = 60;
	private static final int[] STAGE_POINTS = {30, 15, 5};
	private static final int SPAWN_STAGGER_TICKS = 15;
	private static final double AIM_CONE_DEGREES = 6.0;

	private static final class Target {
		final Pufferfish entity;
		int ticksAlive = 0;
		int stage = 0;

		Target(Pufferfish entity) {
			this.entity = entity;
		}
	}

	private static final Map<Integer, Target> targets = new HashMap<>();
	private static int nextMarkerId = Integer.MIN_VALUE + 200_000;
	private static int tickCounter = 0;
	private static int comboCount = 0;
	private static int chainScore = 0;
	private static long lastHitTimeMillis = 0;
	private static int lastPointsGained = 0;
	private static int pendingSpawns = 0;
	private static int nextSpawnTick = 0;
	private static long notFishingSinceMillis = -1;

	private FishingMinigameManager() {
	}

	public static boolean isTarget(Entity entity) {
		return targets.containsKey(entity.getId());
	}

	/** For {@link FishingScoreHud} - whether there's an active chain worth showing on screen. */
	public static boolean isDisplayActive() {
		return chainScore > 0;
	}

	public static int getComboCount() {
		return comboCount;
	}

	public static int getChainScore() {
		return chainScore;
	}

	public static long getLastHitTimeMillis() {
		return lastHitTimeMillis;
	}

	public static int getLastPointsGained() {
		return lastPointsGained;
	}

	public static void tick(Minecraft client) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.fishingMinigameEnabled || client.player == null || client.level == null) {
			clearAll(client);
			return;
		}

		tickCounter++;

		// Existing targets keep inflating/expiring even after you reel in - only new spawning is
		// gated on still actively fishing, so a target that was up when you reeled in doesn't just
		// vanish, it plays out (gets clicked or pops on its own) same as always.
		Iterator<Map.Entry<Integer, Target>> iterator = targets.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Integer, Target> entry = iterator.next();
			Target target = entry.getValue();
			target.ticksAlive++;

			int newStage = Math.min(2, target.ticksAlive / STAGE_DURATION_TICKS);
			if (newStage != target.stage) {
				target.stage = newStage;
				target.entity.setPuffState(newStage);
			}

			if (target.ticksAlive >= STAGE_DURATION_TICKS * 3) {
				// Fully inflated and never hit - it pops on its own, no reward.
				burst(client, target.entity.position(), 0xFFFFFFFF);
				client.level.removeEntity(entry.getKey(), Entity.RemovalReason.DISCARDED);
				iterator.remove();
			}
		}

		if (!FishingHelper.isFishing()) {
			pendingSpawns = 0;
			if (notFishingSinceMillis < 0) {
				notFishingSinceMillis = System.currentTimeMillis();
			} else if (chainScore > 0 && System.currentTimeMillis() - notFishingSinceMillis > NOT_FISHING_GRACE_MS) {
				// Reeled in and never recast within the grace window - the chain is over.
				comboCount = 0;
				chainScore = 0;
			}
			return;
		}
		notFishingSinceMillis = -1;

		if (pendingSpawns > 0 && tickCounter >= nextSpawnTick) {
			if (spawnOne(client)) {
				pendingSpawns--;
			}
			// If blocked/too close, just retry on the next tick instead of skipping this spawn.
			nextSpawnTick = tickCounter + SPAWN_STAGGER_TICKS;
		} else if (targets.isEmpty() && pendingSpawns == 0 && !FishingHelper.isBiting() && tickCounter % WAVE_INTERVAL_TICKS == 0) {
			// Don't spawn the whole wave at once - stagger them one at a time.
			pendingSpawns = TARGETS_PER_WAVE;
			nextSpawnTick = tickCounter;
		}
	}

	/** @return whether a target was actually spawned (false if blocked/too close - try again next tick). */
	private static boolean spawnOne(Minecraft client) {
		LocalPlayer player = client.player;
		RandomSource random = player.getRandom();

		double distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
		double yawDeg = player.getYRot() + (random.nextDouble() - 0.5) * 2 * CONE_HALF_ANGLE_DEG;
		double pitchDeg = player.getXRot() + (random.nextDouble() - 0.5) * 2 * CONE_HALF_ANGLE_DEG;
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		double dx = -Math.sin(yaw) * Math.cos(pitch);
		double dy = -Math.sin(pitch);
		double dz = Math.cos(yaw) * Math.cos(pitch);
		Vec3 dir = new Vec3(dx, dy, dz);
		Vec3 eye = player.getEyePosition();

		// Clamp to the first solid block along the aim direction so targets never spawn embedded
		// inside terrain/walls.
		BlockHitResult hit = client.level.clip(new ClipContext(
				eye, eye.add(dir.scale(distance)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
		));
		if (hit.getType() == HitResult.Type.BLOCK) {
			distance = Math.min(distance, eye.distanceTo(hit.getLocation()) - 0.5);
		}
		if (distance < MIN_DISTANCE * 0.5) {
			return false;
		}

		Vec3 pos = eye.add(dir.scale(distance));

		Pufferfish fish = new Pufferfish(EntityType.PUFFERFISH, client.level);
		fish.setPos(pos.x, pos.y, pos.z);
		fish.setNoAi(true);
		fish.setNoGravity(true);
		fish.setSilent(true);
		fish.setInvulnerable(true);
		fish.setPuffState(0);
		int id = nextMarkerId++;
		fish.setId(id);
		client.level.addEntity(fish);
		targets.put(id, new Target(fish));
		return true;
	}

	/**
	 * Called on every left click from {@link com.melloo.skymelloo.client.mixin.FishingTargetHitMixin}.
	 * Targets sit 5-10 blocks out, well beyond the vanilla interaction/attack reach used to compute
	 * {@code Minecraft.hitResult} - relying on that hit result never picked them up at all, so this
	 * does its own aim-cone check against the tracked targets directly instead.
	 */
	public static void tryHit(Minecraft client) {
		if (targets.isEmpty() || client.player == null) {
			return;
		}
		LocalPlayer player = client.player;
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		double cosThreshold = Math.cos(Math.toRadians(AIM_CONE_DEGREES));

		Integer bestId = null;
		double bestDot = cosThreshold;
		for (Map.Entry<Integer, Target> entry : targets.entrySet()) {
			Vec3 toTarget = entry.getValue().entity.position().add(0, 0.2, 0).subtract(eye);
			double distance = toTarget.length();
			if (distance < 0.1 || distance > MAX_DISTANCE + 3) {
				continue;
			}
			double dot = toTarget.scale(1.0 / distance).dot(look);
			if (dot > bestDot) {
				bestDot = dot;
				bestId = entry.getKey();
			}
		}

		if (bestId != null) {
			onTargetHit(client, targets.get(bestId).entity);
		}
	}

	/** Called from {@link com.melloo.skymelloo.client.mixin.FishingTargetHitMixin} when you click one. */
	public static void onTargetHit(Minecraft client, Entity entity) {
		Target target = targets.remove(entity.getId());
		if (target == null) {
			return;
		}
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		client.level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);

		int points = STAGE_POINTS[target.stage];
		comboCount++;
		chainScore += points;
		lastPointsGained = points;
		lastHitTimeMillis = System.currentTimeMillis();

		burst(client, target.entity.position(), 0xFFFFAA00);
		client.getSoundManager().play(
				net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F + comboCount * 0.05F)
		);

		// Shown on the persistent FishingScoreHud now (above the hotbar, not the vanilla actionbar -
		// Hypixel's own health/mana/defense HUD keeps overwriting the actionbar every tick, so text
		// posted there barely stays visible). A new highscore ALSO gets its own chat announcement,
		// on top of the HUD update, instead of replacing it.
		if (chainScore > config.fishingMinigameHighscore) {
			config.fishingMinigameHighscore = chainScore;
			SkyMellooConfig.HANDLER.save();
			client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.chat.fishing.new_highscore", chainScore, comboCount)));
		}
	}

	private static void burst(Minecraft client, Vec3 pos, int rgb) {
		RandomSource random = client.level.getRandom();
		for (int i = 0; i < 12; i++) {
			double vx = (random.nextDouble() - 0.5) * 0.35;
			double vy = random.nextDouble() * 0.35;
			double vz = (random.nextDouble() - 0.5) * 0.35;
			client.level.addParticle(new DustParticleOptions(rgb, 1.2F), pos.x, pos.y, pos.z, vx, vy, vz);
		}
		client.level.addParticle(ParticleTypes.POOF, pos.x, pos.y, pos.z, 0, 0, 0);
	}

	private static void clearAll(Minecraft client) {
		if (targets.isEmpty()) {
			return;
		}
		for (Integer id : targets.keySet().toArray(new Integer[0])) {
			if (client.level != null) {
				client.level.removeEntity(id, Entity.RemovalReason.DISCARDED);
			}
		}
		targets.clear();
		comboCount = 0;
		chainScore = 0;
		notFishingSinceMillis = -1;
	}
}
