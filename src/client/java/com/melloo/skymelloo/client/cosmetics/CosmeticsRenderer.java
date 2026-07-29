package com.melloo.skymelloo.client.cosmetics;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.social.ModPresenceManager;
import com.melloo.skymelloo.client.social.PermissionsManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Purely cosmetic, client-side-only particle effects (no gameplay impact, nothing sent to the server).
 * Off by default, toggled per-effect in the config screen. Also renders these same effects around
 * OTHER players who are running SkyMelloo too (detected via {@link ModPresenceManager}) and have
 * them enabled, so two SkyMelloo users can see each other's cosmetics - the "enabled" flags/colors
 * for other players come from their reported presence data, not the local config.
 */
public final class CosmeticsRenderer {
	private static float haloAngle = 0F;
	private static float helixAngle = 0F;
	private static float starWeaveAngle = 0F;
	private static float starVeilAngle = 0F;
	private static int radiantPulseTimer = 0;

	private CosmeticsRenderer() {
	}

	public static void tick(Minecraft client) {
		if (client.player == null || client.level == null || !PermissionsManager.has("cosmetics")) {
			return;
		}
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		AbstractClientPlayer self = client.player;

		if (config.haloEnabled) {
			renderHalo(client, self, rgb(config.haloColor));
		}
		if (config.cherryBlossomEnabled) {
			renderCherryBlossom(client, self);
		}
		if (config.rainbowHelixEnabled) {
			renderRainbowHelix(client, self, rgb(config.rainbowHelixColor));
		}
		if (config.auraEnabled) {
			renderAura(client, self, rgb(config.auraColor));
		}
		if (config.waveEnabled) {
			renderWave(client, self, rgb(config.waveColor));
		}
		if (config.rainCloudEnabled) {
			renderRainCloud(client, self);
		}
		if (config.fireRingEnabled) {
			renderFireRing(client, self);
		}
		if (config.starRainEnabled) {
			renderStarRain(client, self);
		}
		if (config.sparkAuraEnabled) {
			renderSparkAura(client, self);
		}
		if (config.lissajousEnabled) {
			renderLissajous(client, self, rgb(config.lissajousColor));
		}
		if (config.roseCurveEnabled) {
			renderRoseCurve(client, self, rgb(config.roseCurveColor));
		}
		if (config.fireworkBurstEnabled) {
			renderFireworkBurst(client, self);
		}
		if (config.frostAuraEnabled) {
			renderFrostAura(client, self, rgb(config.frostAuraColor));
		}
		if (config.noteMelodyEnabled) {
			renderNoteMelody(client, self);
		}
		if (config.portalVortexEnabled) {
			renderPortalVortex(client, self, rgb(config.portalVortexColor));
		}
		if (config.heartTrailEnabled) {
			renderHeartTrail(client, self);
		}
		if (config.spiralGalaxyEnabled) {
			renderSpiralGalaxy(client, self, rgb(config.spiralGalaxyColor));
		}
		if (config.jumpTrailEnabled) {
			renderJumpTrail(client, self, rgb(config.jumpTrailColor));
		}
		if (config.totemFlashEnabled) {
			renderTotemFlash(client, self);
		}
		if (config.sculkPulseEnabled) {
			renderSculkPulse(client, self);
		}
		if (config.omenAuraEnabled) {
			renderOmenAura(client, self);
		}
		if (config.gustAuraEnabled) {
			renderGustAura(client, self, rgb(config.gustAuraColor));
		}
		if (config.ashFallEnabled) {
			renderAshFall(client, self, rgb(config.ashFallColor));
		}
		if (config.campfireSmokeEnabled) {
			renderCampfireSmoke(client, self);
		}
		if (config.enchantedCritSparkleEnabled) {
			renderEnchantedCritSparkle(client, self);
		}
		if (config.dustPlumeTrailEnabled) {
			renderDustPlumeTrail(client, self);
		}
		if (config.tornadoEnabled) {
			renderTornado(client, self, rgb(config.tornadoColor));
		}
		if (config.blackHoleEnabled) {
			renderBlackHole(client, self, rgb(config.blackHoleColor));
		}
		if (config.twinVortexEnabled) {
			renderTwinVortex(client, self, rgb(config.twinVortexColor));
		}
		if (config.chargeUpEnabled) {
			renderChargeUp(client, self, rgb(config.chargeUpColor));
		}
		if (config.orbitRingsEnabled) {
			renderOrbitRings(client, self, rgb(config.orbitRingsColor));
		}
		if (config.lightningAuraEnabled) {
			renderLightningAura(client, self);
		}
		if (config.confettiBurstEnabled) {
			renderConfettiBurst(client, self);
		}
		if (config.phoenixWingsEnabled) {
			renderPhoenixWings(client, self, rgb(config.phoenixWingsColor));
		}
		if (config.voidRiftEnabled) {
			renderVoidRift(client, self);
		}
		if (config.starWeaveEnabled) {
			renderStarWeave(client, self);
		}
		if (config.ascendingSparklesEnabled) {
			renderAscendingSparkles(client, self);
		}
		if (config.cometTrailEnabled) {
			renderCometTrail(client, self);
		}
		if (config.starVeilEnabled) {
			renderStarVeil(client, self);
		}
		if (config.radiantPulseEnabled) {
			renderRadiantPulse(client, self);
		}
		tickLandingShockwave(client, self, config.landingShockwaveEnabled, rgb(config.landingShockwaveColor));

		tickOthers(client);
	}

	/** Same effects, driven by other SkyMelloo users' reported presence instead of the local config. */
	private static void tickOthers(Minecraft client) {
		for (AbstractClientPlayer other : client.level.players()) {
			if (other == client.player) {
				continue;
			}
			UUID uuid = other.getUUID();
			if (!ModPresenceManager.isModUser(uuid)) {
				continue;
			}

			if (ModPresenceManager.hasCosmetic(uuid, "halo")) {
				renderHalo(client, other, ModPresenceManager.getCosmeticColor(uuid, "halo"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "cherryBlossom")) {
				renderCherryBlossom(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "rainbowHelix")) {
				renderRainbowHelix(client, other, ModPresenceManager.getCosmeticColor(uuid, "rainbowHelix"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "aura")) {
				renderAura(client, other, ModPresenceManager.getCosmeticColor(uuid, "aura"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "wave")) {
				renderWave(client, other, ModPresenceManager.getCosmeticColor(uuid, "wave"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "rainCloud")) {
				renderRainCloud(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "fireRing")) {
				renderFireRing(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "starRain")) {
				renderStarRain(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "sparkAura")) {
				renderSparkAura(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "lissajous")) {
				renderLissajous(client, other, ModPresenceManager.getCosmeticColor(uuid, "lissajous"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "roseCurve")) {
				renderRoseCurve(client, other, ModPresenceManager.getCosmeticColor(uuid, "roseCurve"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "fireworkBurst")) {
				renderFireworkBurst(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "frostAura")) {
				renderFrostAura(client, other, ModPresenceManager.getCosmeticColor(uuid, "frostAura"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "noteMelody")) {
				renderNoteMelody(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "portalVortex")) {
				renderPortalVortex(client, other, ModPresenceManager.getCosmeticColor(uuid, "portalVortex"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "heartTrail")) {
				renderHeartTrail(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "spiralGalaxy")) {
				renderSpiralGalaxy(client, other, ModPresenceManager.getCosmeticColor(uuid, "spiralGalaxy"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "jumpTrail")) {
				renderJumpTrail(client, other, ModPresenceManager.getCosmeticColor(uuid, "jumpTrail"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "totemFlash")) {
				renderTotemFlash(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "sculkPulse")) {
				renderSculkPulse(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "omenAura")) {
				renderOmenAura(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "gustAura")) {
				renderGustAura(client, other, ModPresenceManager.getCosmeticColor(uuid, "gustAura"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "ashFall")) {
				renderAshFall(client, other, ModPresenceManager.getCosmeticColor(uuid, "ashFall"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "campfireSmoke")) {
				renderCampfireSmoke(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "enchantedCritSparkle")) {
				renderEnchantedCritSparkle(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "dustPlumeTrail")) {
				renderDustPlumeTrail(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "tornado")) {
				renderTornado(client, other, ModPresenceManager.getCosmeticColor(uuid, "tornado"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "blackHole")) {
				renderBlackHole(client, other, ModPresenceManager.getCosmeticColor(uuid, "blackHole"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "twinVortex")) {
				renderTwinVortex(client, other, ModPresenceManager.getCosmeticColor(uuid, "twinVortex"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "chargeUp")) {
				renderChargeUp(client, other, ModPresenceManager.getCosmeticColor(uuid, "chargeUp"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "orbitRings")) {
				renderOrbitRings(client, other, ModPresenceManager.getCosmeticColor(uuid, "orbitRings"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "lightningAura")) {
				renderLightningAura(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "confettiBurst")) {
				renderConfettiBurst(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "phoenixWings")) {
				renderPhoenixWings(client, other, ModPresenceManager.getCosmeticColor(uuid, "phoenixWings"));
			}
			if (ModPresenceManager.hasCosmetic(uuid, "voidRift")) {
				renderVoidRift(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "starWeave")) {
				renderStarWeave(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "ascendingSparkles")) {
				renderAscendingSparkles(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "cometTrail")) {
				renderCometTrail(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "starVeil")) {
				renderStarVeil(client, other);
			}
			if (ModPresenceManager.hasCosmetic(uuid, "radiantPulse")) {
				renderRadiantPulse(client, other);
			}
			tickLandingShockwave(client, other, ModPresenceManager.hasCosmetic(uuid, "landingShockwave"), ModPresenceManager.getCosmeticColor(uuid, "landingShockwave"));
		}
	}

	private static int rgb(Color color) {
		return color.getRGB() & 0xFFFFFF;
	}

	private static void renderHalo(Minecraft client, AbstractClientPlayer player, int rgb) {
		haloAngle += 0.2F;
		if (haloAngle > (float) (Math.PI * 2)) {
			haloAngle -= (float) (Math.PI * 2);
		}

		double radius = 0.7;
		double x = player.getX() + Math.cos(haloAngle) * radius;
		double z = player.getZ() + Math.sin(haloAngle) * radius;
		double y = player.getY() + player.getBbHeight() + 0.3;

		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		ParticleKind kindOverride = ParticleKind.byNameOr(config.haloParticleKind, null);
		glowyDust(client, rgb, x, y, z, 1.0F, config.haloGlow, kindOverride);
	}

	/**
	 * Spawns a cosmetic's normal colored dust particle - or, if {@code glowing}, vanilla's actual
	 * {@link ParticleTypes#GLOW} particle instead (same one glow squid ink uses) for a real glowing
	 * look, not just a bigger dust particle. Trade-off: GLOW ignores the cosmetic's chosen color
	 * while this is on, since it's not colorable like dust particles are - there's no vanilla
	 * particle that's both truly glowing AND custom-colored without registering a whole new one.
	 */
	private static void glowyDust(Minecraft client, int rgb, double x, double y, double z, float size, boolean glowing) {
		glowyDust(client, rgb, x, y, z, size, glowing, null);
	}

	/**
	 * Same as the 6-arg overload, plus an optional fixed {@code kindOverride} - if set, it wins over
	 * both color AND glow, spawning that particle kind exactly as-is instead. Backs the "Color /
	 * Particle" picker's non-color entries (see {@code ColorPickerPage}), letting a cosmetic use a
	 * different particle kind instead of just a recolored redstone dust.
	 */
	private static void glowyDust(Minecraft client, int rgb, double x, double y, double z, float size, boolean glowing, ParticleKind kindOverride) {
		if (kindOverride != null) {
			client.level.addParticle(kindOverride.options, x, y, z, 0, 0, 0);
			return;
		}
		if (glowing) {
			client.level.addParticle(ParticleTypes.GLOW, x, y, z, 0, 0, 0);
			return;
		}
		client.level.addParticle(new DustParticleOptions(rgb, size), x, y, z, 0, 0, 0);
	}

	private static void renderCherryBlossom(Minecraft client, AbstractClientPlayer player) {
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.3F) {
			return;
		}

		double x = player.getRandomX(1.5);
		double y = player.getY() + player.getBbHeight() + 0.5 + random.nextDouble();
		double z = player.getRandomZ(1.5);
		ParticleKind kind = ParticleKind.byNameOr(SkyMellooConfig.HANDLER.instance().cherryBlossomParticle, ParticleKind.CHERRY_BLOSSOM);
		client.level.addParticle(kind.options, x, y, z, 0, -0.05, 0);
	}

	private static final int HELIX_STRANDS = 2;

	/** Colored particle strands spiraling around your whole body like a DNA helix. */
	private static void renderRainbowHelix(Minecraft client, AbstractClientPlayer player, int rgb) {
		helixAngle += 0.12F;
		if (helixAngle > (float) (Math.PI * 2)) {
			helixAngle -= (float) (Math.PI * 2);
		}

		double radius = 0.65;
		float bodyHeight = player.getBbHeight();

		for (int strand = 0; strand < HELIX_STRANDS; strand++) {
			float angle = helixAngle + strand * (float) (Math.PI * 2 / HELIX_STRANDS);
			double x = player.getX() + Math.cos(angle) * radius;
			double z = player.getZ() + Math.sin(angle) * radius;

			// Each strand's height phase is offset so together they cover the whole body top-to-bottom.
			float heightPhase = helixAngle * 1.5F + strand * (float) (Math.PI / 2);
			double y = player.getY() + (Math.sin(heightPhase) * 0.5 + 0.5) * bodyHeight;

			client.level.addParticle(new DustParticleOptions(rgb, 1.1F), x, y, z, 0, 0, 0);
		}
	}

	private static float auraAngle = 0F;

	/** A slow, wide double ring of particles orbiting around your body at chest height. */
	private static void renderAura(Minecraft client, AbstractClientPlayer player, int rgb) {
		auraAngle += 0.1F;
		if (auraAngle > (float) (Math.PI * 2)) {
			auraAngle -= (float) (Math.PI * 2);
		}

		double radius = 0.9;
		float bodyMid = player.getBbHeight() * 0.5F;

		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		boolean glowing = config.auraGlow;
		ParticleKind kindOverride = ParticleKind.byNameOr(config.auraParticleKind, null);
		for (int ring = 0; ring < 2; ring++) {
			float angle = auraAngle * (ring == 0 ? 1F : -1F) + ring * (float) Math.PI;
			double x = player.getX() + Math.cos(angle) * radius;
			double z = player.getZ() + Math.sin(angle) * radius;
			double y = player.getY() + bodyMid + Math.sin(angle * 2F) * 0.3;
			glowyDust(client, rgb, x, y, z, 1.0F, glowing, kindOverride);
		}
	}

	private static final class Pulse {
		final double originX, originZ, originY;
		int age = 0;

		Pulse(double originX, double originY, double originZ) {
			this.originX = originX;
			this.originY = originY;
			this.originZ = originZ;
		}
	}

	private static final List<Pulse> wavePulses = new ArrayList<>();
	private static final Map<UUID, Integer> waveSpawnTimers = new HashMap<>();
	private static final int WAVE_SPAWN_INTERVAL_TICKS = 25;
	private static final int WAVE_DURATION_TICKS = 22;
	private static final float WAVE_MAX_RADIUS = 2.6F;

	/**
	 * Shockwave rings that expand outward from a fixed spot (where they spawned), not the player's
	 * current position - otherwise a ring visually "drags" along if they keep moving while it's
	 * still expanding. The spawn timer is tracked per-player so this works independently for
	 * multiple people (yourself and any other SkyMelloo user) with Wave enabled at once.
	 */
	private static void renderWave(Minecraft client, AbstractClientPlayer player, int rgb) {
		UUID uuid = player.getUUID();
		int timer = waveSpawnTimers.getOrDefault(uuid, 0) + 1;
		if (timer >= WAVE_SPAWN_INTERVAL_TICKS) {
			timer = 0;
			wavePulses.add(new Pulse(player.getX(), player.getY(), player.getZ()));
		}
		waveSpawnTimers.put(uuid, timer);

		int points = 28;
		wavePulses.removeIf(pulse -> {
			pulse.age++;
			float progress = (float) pulse.age / WAVE_DURATION_TICKS;
			float radius = progress * WAVE_MAX_RADIUS;
			double bounce = Math.sin(progress * Math.PI) * 0.4;

			for (int i = 0; i < points; i++) {
				float angle = (float) (Math.PI * 2 * i / points);
				double x = pulse.originX + Math.cos(angle) * radius;
				double z = pulse.originZ + Math.sin(angle) * radius;
				double y = pulse.originY + 0.1 + bounce;
				client.level.addParticle(new DustParticleOptions(rgb, 1.2F), x, y, z, 0, 0, 0);
			}
			return pulse.age >= WAVE_DURATION_TICKS;
		});
	}

	/** A small cloud hovering above your head that continuously rains on you. */
	/** A proper volumetric cloud (wide radius, puffs at varying heights within a band) with rain actually spawning from inside its own footprint, not a small offset area below it. */
	private static void renderRainCloud(Minecraft client, AbstractClientPlayer player) {
		RandomSource random = player.getRandom();
		double cloudY = player.getY() + player.getBbHeight() + 1.3;
		double cloudRadius = 1.8; // was a ~0.4-block spread, widened for more visual presence

		// Several puffs per tick at varying heights within a band, spread across the full radius -
		// reads as an actual volumetric cloud instead of a thin, sparse flat disc.
		for (int i = 0; i < 3; i++) {
			if (random.nextFloat() < 0.6F) {
				double angle = random.nextDouble() * Math.PI * 2;
				double dist = random.nextDouble() * cloudRadius;
				double cx = player.getX() + Math.cos(angle) * dist;
				double cz = player.getZ() + Math.sin(angle) * dist;
				double cy = cloudY + (random.nextDouble() - 0.5) * 0.6;
				client.level.addParticle(ParticleTypes.CLOUD, cx, cy, cz, 0, 0, 0);
			}
		}
		// Rain scattered throughout the cloud's own footprint, not a separate small area below it.
		for (int i = 0; i < 2; i++) {
			if (random.nextFloat() < 0.6F) {
				double angle = random.nextDouble() * Math.PI * 2;
				double dist = random.nextDouble() * cloudRadius * 0.9;
				double rx = player.getX() + Math.cos(angle) * dist;
				double rz = player.getZ() + Math.sin(angle) * dist;
				double ry = cloudY + (random.nextDouble() - 0.5) * 0.5;
				client.level.addParticle(ParticleTypes.RAIN, rx, ry, rz, 0, -0.2, 0);
			}
		}
	}

	private static float fireRingAngle = 0F;

	/** A rotating ring of flame particles at your feet. */
	private static void renderFireRing(Minecraft client, AbstractClientPlayer player) {
		fireRingAngle += 0.25F;
		if (fireRingAngle > (float) (Math.PI * 2)) {
			fireRingAngle -= (float) (Math.PI * 2);
		}

		double radius = 0.6;
		int flames = 6;
		ParticleKind kind = ParticleKind.byNameOr(SkyMellooConfig.HANDLER.instance().fireRingParticle, ParticleKind.FLAME);
		for (int i = 0; i < flames; i++) {
			float angle = fireRingAngle + i * (float) (Math.PI * 2 / flames);
			double x = player.getX() + Math.cos(angle) * radius;
			double z = player.getZ() + Math.sin(angle) * radius;
			client.level.addParticle(kind.options, x, player.getY() + 0.1, z, 0, 0.01, 0);
		}
	}

	/** Sparkling particles drifting slowly down from above your head. */
	private static void renderStarRain(Minecraft client, AbstractClientPlayer player) {
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.5F) {
			return;
		}
		double x = player.getRandomX(1.0);
		double y = player.getY() + player.getBbHeight() + 1.5;
		double z = player.getRandomZ(1.0);
		ParticleKind kind = ParticleKind.byNameOr(SkyMellooConfig.HANDLER.instance().starRainParticle, ParticleKind.SPARKLE);
		client.level.addParticle(kind.options, x, y, z, 0, -0.03, 0);
	}

	/** Occasional electric sparks crackling randomly around your body. */
	private static void renderSparkAura(Minecraft client, AbstractClientPlayer player) {
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.4F) {
			return;
		}
		double x = player.getRandomX(2.2);
		double y = player.getY() + random.nextDouble() * player.getBbHeight();
		double z = player.getRandomZ(2.2);
		ParticleKind kind = ParticleKind.byNameOr(SkyMellooConfig.HANDLER.instance().sparkAuraParticle, ParticleKind.SPARK);
		client.level.addParticle(kind.options, x, y, z, 0, 0, 0);
	}

	private static float lissajousT = 0F;

	/**
	 * A 3D Lissajous curve traced around your body - three sine waves on different axes/frequencies
	 * weave a constantly-shifting knot pattern instead of a simple circle or spiral.
	 */
	private static void renderLissajous(Minecraft client, AbstractClientPlayer player, int rgb) {
		lissajousT += 0.05F;
		if (lissajousT > (float) (Math.PI * 2)) {
			lissajousT -= (float) (Math.PI * 2);
		}

		double radiusXZ = 1.1;
		double radiusY = 0.9;
		float bodyMid = player.getBbHeight() * 0.5F;

		// Two points traced simultaneously (offset phase) on x=sin(3t), y=sin(2t), z=sin(4t) - a
		// classic Lissajous knot - for a denser, more intricate weave than a single trace point.
		for (int i = 0; i < 2; i++) {
			float t = lissajousT + i * (float) Math.PI;
			double x = player.getX() + Math.sin(3 * t) * radiusXZ;
			double z = player.getZ() + Math.sin(4 * t + Math.PI / 2) * radiusXZ;
			double y = player.getY() + bodyMid + Math.sin(2 * t) * radiusY;

			client.level.addParticle(new DustParticleOptions(rgb, 1.2F), x, y, z, 0, 0, 0);
		}
	}

	private static float roseAngle = 0F;
	private static float roseK = 2F;

	/**
	 * A rose/rhodonea curve (r = radius * cos(k * theta)) traced with many simultaneous points at
	 * once instead of a sparse handful - the whole flower-petal shape is visible every frame, slowly
	 * morphing its petal count (k) and spinning, for a much denser/busier effect than Lissajous.
	 */
	private static void renderRoseCurve(Minecraft client, AbstractClientPlayer player, int rgb) {
		roseAngle += 0.04F;
		if (roseAngle > (float) (Math.PI * 2)) {
			roseAngle -= (float) (Math.PI * 2);
		}
		roseK += 0.003F;
		if (roseK > 7F) {
			roseK = 2F;
		}

		double radius = 1.6;
		float bodyMid = player.getBbHeight() * 0.5F;
		int points = 60;

		// Keep the chosen color's hue/saturation but vary its brightness per-point for a subtle
		// shimmer instead of a completely flat wash of one color.
		float[] hsb = new float[3];
		Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsb);
		float baseBrightness = Math.max(0.6F, hsb[2]);

		for (int i = 0; i < points; i++) {
			float theta = (float) (Math.PI * 2 * i / points) + roseAngle;
			double r = radius * Math.cos(roseK * theta);
			double x = player.getX() + r * Math.cos(theta);
			double z = player.getZ() + r * Math.sin(theta);
			double y = player.getY() + bodyMid + Math.sin(theta * 2 + roseAngle * 3) * 0.8;

			float brightness = baseBrightness * (0.75F + 0.25F * (float) Math.sin(theta * 3 + roseAngle * 2));
			int pointRgb = Color.HSBtoRGB(hsb[0], hsb[1], brightness) & 0xFFFFFF;
			client.level.addParticle(new DustParticleOptions(pointRgb, 1.1F), x, y, z, 0, 0, 0);
		}
	}

	private static final Map<UUID, Boolean> wasOnGround = new HashMap<>();
	private static final Map<UUID, Integer> landingShockwaveAge = new HashMap<>();
	private static final Map<UUID, double[]> landingOrigin = new HashMap<>();
	private static final int LANDING_SHOCKWAVE_DURATION_TICKS = 14;
	private static final float LANDING_SHOCKWAVE_MAX_RADIUS = 3.5F;

	/** One-shot expanding ring triggered the instant a player lands from a jump/fall - anchored to the landing spot, not their current position. Tracked per-player so it works for multiple people at once. */
	private static void tickLandingShockwave(Minecraft client, AbstractClientPlayer player, boolean enabled, int rgb) {
		UUID uuid = player.getUUID();
		boolean onGround = player.onGround();
		boolean previouslyOnGround = wasOnGround.getOrDefault(uuid, true);
		if (enabled && onGround && !previouslyOnGround) {
			landingShockwaveAge.put(uuid, 0);
			landingOrigin.put(uuid, new double[]{player.getX(), player.getY(), player.getZ()});
		}
		wasOnGround.put(uuid, onGround);

		Integer age = landingShockwaveAge.get(uuid);
		if (age == null || age < 0) {
			return;
		}
		double[] origin = landingOrigin.get(uuid);

		float progress = (float) age / LANDING_SHOCKWAVE_DURATION_TICKS;
		float radius = progress * LANDING_SHOCKWAVE_MAX_RADIUS;
		int points = 28;
		for (int i = 0; i < points; i++) {
			float angle = (float) (Math.PI * 2 * i / points);
			double x = origin[0] + Math.cos(angle) * radius;
			double z = origin[2] + Math.sin(angle) * radius;
			client.level.addParticle(new DustParticleOptions(rgb, 1.4F * (1F - progress) + 0.4F), x, origin[1] + 0.1, z, 0, 0, 0);
		}

		age++;
		landingShockwaveAge.put(uuid, age > LANDING_SHOCKWAVE_DURATION_TICKS ? -1 : age);
	}

	/** Occasional sparkle bursts overhead, like distant celebratory fireworks. */
	private static void renderFireworkBurst(Minecraft client, AbstractClientPlayer player) {
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.03F) {
			return;
		}
		double cx = player.getX();
		double cy = player.getY() + player.getBbHeight() + 2.0;
		double cz = player.getZ();
		ParticleKind kind = ParticleKind.byNameOr(SkyMellooConfig.HANDLER.instance().fireworkBurstParticle, ParticleKind.FIREWORK);
		for (int i = 0; i < 16; i++) {
			double yaw = random.nextDouble() * Math.PI * 2;
			double pitch = random.nextDouble() * Math.PI;
			double speed = 0.15 + random.nextDouble() * 0.2;
			double vx = Math.cos(yaw) * Math.sin(pitch) * speed;
			double vy = Math.cos(pitch) * speed;
			double vz = Math.sin(yaw) * Math.sin(pitch) * speed;
			client.level.addParticle(kind.options, cx, cy, cz, vx, vy, vz);
		}
	}

	private static float frostAngle = 0F;

	/** Colored particles swirling tight and close around your body, like a personal cold aura. */
	private static void renderFrostAura(Minecraft client, AbstractClientPlayer player, int rgb) {
		frostAngle += 0.22F;
		if (frostAngle > (float) (Math.PI * 2)) {
			frostAngle -= (float) (Math.PI * 2);
		}
		double radius = 0.5;
		int flakes = 5;
		for (int i = 0; i < flakes; i++) {
			float angle = frostAngle + i * (float) (Math.PI * 2 / flakes);
			double x = player.getX() + Math.cos(angle) * radius;
			double z = player.getZ() + Math.sin(angle) * radius;
			double y = player.getY() + ((frostAngle * 0.3 + i) % player.getBbHeight());
			client.level.addParticle(new DustParticleOptions(rgb, 1.0F), x, y, z, 0, -0.01, 0);
		}
	}

	/** Musical notes floating up above your head occasionally. */
	private static void renderNoteMelody(Minecraft client, AbstractClientPlayer player) {
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.08F) {
			return;
		}
		double x = player.getRandomX(0.5);
		double y = player.getY() + player.getBbHeight() + 0.3;
		double z = player.getRandomZ(0.5);
		ParticleKind kind = ParticleKind.byNameOr(SkyMellooConfig.HANDLER.instance().noteMelodyParticle, ParticleKind.NOTE);
		client.level.addParticle(kind.options, x, y, z, 0, 0, 0);
	}

	private static float portalAngle = 0F;

	/** A fast-spinning double vortex of colored particles around your whole body. */
	private static void renderPortalVortex(Minecraft client, AbstractClientPlayer player, int rgb) {
		portalAngle += 0.5F;
		if (portalAngle > (float) (Math.PI * 2)) {
			portalAngle -= (float) (Math.PI * 2);
		}
		double radius = 0.8;
		float bodyHeight = player.getBbHeight();
		for (int i = 0; i < 2; i++) {
			float angle = portalAngle + i * (float) Math.PI;
			double x = player.getX() + Math.cos(angle) * radius;
			double z = player.getZ() + Math.sin(angle) * radius;
			double y = player.getY() + ((portalAngle * 0.4 + i * 0.5) % 1.0) * bodyHeight;
			client.level.addParticle(new DustParticleOptions(rgb, 1.1F), x, y, z, 0, 0, 0);
		}
	}

	/** Floating hearts drifting up occasionally - a playful, wholesome cosmetic. */
	private static void renderHeartTrail(Minecraft client, AbstractClientPlayer player) {
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.06F) {
			return;
		}
		double x = player.getRandomX(0.6);
		double y = player.getY() + player.getBbHeight() + 0.3;
		double z = player.getRandomZ(0.6);
		ParticleKind kind = ParticleKind.byNameOr(SkyMellooConfig.HANDLER.instance().heartTrailParticle, ParticleKind.HEART);
		client.level.addParticle(kind.options, x, y, z, 0, 0.05, 0);
	}

	private static float galaxyRot = 0F;
	private static final int GALAXY_ARMS = 3;
	private static final int GALAXY_POINTS_PER_ARM = 10;

	/**
	 * A proper multi-arm spiral: radius grows linearly from 0 (feet) to a max (above your head) along
	 * each rotating arm, so it always reads as a spiral fanning outward - the earlier version used an
	 * unwrapped rotation angle that grew without bound, which loses float precision over time and
	 * made every point collapse toward the same spot after a while.
	 */
	private static void renderSpiralGalaxy(Minecraft client, AbstractClientPlayer player, int rgb) {
		galaxyRot += 0.12F;
		if (galaxyRot > (float) (Math.PI * 2)) {
			galaxyRot -= (float) (Math.PI * 2);
		}

		float bodyHeight = player.getBbHeight();
		double maxRadius = 1.3;

		for (int arm = 0; arm < GALAXY_ARMS; arm++) {
			float armPhase = arm * (float) (Math.PI * 2 / GALAXY_ARMS);
			for (int p = 0; p < GALAXY_POINTS_PER_ARM; p++) {
				float t = (float) p / (GALAXY_POINTS_PER_ARM - 1);
				double angle = galaxyRot + armPhase + t * Math.PI * 2.5;
				double r = maxRadius * t;
				double x = player.getX() + r * Math.cos(angle);
				double z = player.getZ() + r * Math.sin(angle);
				double y = player.getY() + t * bodyHeight;

				client.level.addParticle(new DustParticleOptions(rgb, 1.1F), x, y, z, 0, 0, 0);
			}
		}
	}

	/** While airborne (jumping/falling), leaves a particle trail along the player's actual arc. */
	private static void renderJumpTrail(Minecraft client, AbstractClientPlayer player, int rgb) {
		if (!player.onGround()) {
			client.level.addParticle(new DustParticleOptions(rgb, 1.0F), player.getX(), player.getY() + 0.1, player.getZ(), 0, 0, 0);
		}
	}

	/** Occasional Totem-of-Undying-style flash burst above your head. */
	private static void renderTotemFlash(Minecraft client, AbstractClientPlayer player) {
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.02F) {
			return;
		}
		double cx = player.getX();
		double cy = player.getY() + player.getBbHeight() + 1.5;
		double cz = player.getZ();
		ParticleKind kind = ParticleKind.byNameOr(SkyMellooConfig.HANDLER.instance().totemFlashParticle, ParticleKind.TOTEM);
		for (int i = 0; i < 10; i++) {
			double vx = (random.nextDouble() - 0.5) * 0.3;
			double vy = random.nextDouble() * 0.3;
			double vz = (random.nextDouble() - 0.5) * 0.3;
			client.level.addParticle(kind.options, cx, cy, cz, vx, vy, vz);
		}
	}

	private static float sculkAngle = 0F;

	/** A dark, spooky pulsing ring of sculk particles at your feet. */
	private static void renderSculkPulse(Minecraft client, AbstractClientPlayer player) {
		sculkAngle += 0.15F;
		if (sculkAngle > (float) (Math.PI * 2)) {
			sculkAngle -= (float) (Math.PI * 2);
		}
		double radius = 0.7 + Math.sin(sculkAngle) * 0.2;
		int points = 8;
		ParticleKind kind = ParticleKind.byNameOr(SkyMellooConfig.HANDLER.instance().sculkPulseParticle, ParticleKind.SCULK);
		for (int i = 0; i < points; i++) {
			float angle = i * (float) (Math.PI * 2 / points);
			double x = player.getX() + Math.cos(angle) * radius;
			double z = player.getZ() + Math.sin(angle) * radius;
			client.level.addParticle(kind.options, x, player.getY() + 0.1, z, 0, 0, 0);
		}
	}

	/** An ominous swirling aura, like the Raid/Trial Omen status effects. */
	private static void renderOmenAura(Minecraft client, AbstractClientPlayer player) {
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.3F) {
			return;
		}
		double x = player.getRandomX(0.9);
		double y = player.getY() + random.nextDouble() * player.getBbHeight();
		double z = player.getRandomZ(0.9);
		ParticleKind kind = ParticleKind.byNameOr(SkyMellooConfig.HANDLER.instance().omenAuraParticle, ParticleKind.OMEN);
		client.level.addParticle(kind.options, x, y, z, 0, 0.02, 0);
	}

	private static float gustAngle = 0F;

	/** Colored wind gusts swirling around your body, like you're standing in your own personal breeze. */
	private static void renderGustAura(Minecraft client, AbstractClientPlayer player, int rgb) {
		gustAngle += 0.35F;
		if (gustAngle > (float) (Math.PI * 2)) {
			gustAngle -= (float) (Math.PI * 2);
		}
		double radius = 1.0;
		float bodyMid = player.getBbHeight() * 0.5F;
		for (int i = 0; i < 3; i++) {
			float angle = gustAngle + i * (float) (Math.PI * 2 / 3);
			double x = player.getX() + Math.cos(angle) * radius;
			double z = player.getZ() + Math.sin(angle) * radius;
			client.level.addParticle(new DustParticleOptions(rgb, 1.2F), x, player.getY() + bodyMid, z, 0, 0, 0);
		}
	}

	/** Colored fine particles gently falling around you, like ash near a volcano. */
	private static void renderAshFall(Minecraft client, AbstractClientPlayer player, int rgb) {
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.4F) {
			return;
		}
		double x = player.getRandomX(1.6);
		double y = player.getY() + player.getBbHeight() + 0.8 + random.nextDouble();
		double z = player.getRandomZ(1.6);
		client.level.addParticle(new DustParticleOptions(rgb, 0.9F), x, y, z, 0, -0.03, 0);
	}

	/** Cozy smoke wisps trailing gently from your feet. */
	private static void renderCampfireSmoke(Minecraft client, AbstractClientPlayer player) {
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.2F) {
			return;
		}
		double x = player.getRandomX(0.4);
		double y = player.getY() + 0.1;
		double z = player.getRandomZ(0.4);
		var type = random.nextBoolean() ? ParticleTypes.CAMPFIRE_COSY_SMOKE : ParticleTypes.CAMPFIRE_SIGNAL_SMOKE;
		client.level.addParticle(type, x, y, z, 0, 0.03, 0);
	}

	/** Enchanted-hit sparkles bursting around you occasionally, like a magic critical hit. */
	private static void renderEnchantedCritSparkle(Minecraft client, AbstractClientPlayer player) {
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.12F) {
			return;
		}
		double x = player.getRandomX(1.0);
		double y = player.getY() + random.nextDouble() * player.getBbHeight();
		double z = player.getRandomZ(1.0);
		ParticleKind kind = ParticleKind.byNameOr(SkyMellooConfig.HANDLER.instance().enchantedCritSparkleParticle, ParticleKind.ENCHANTED_CRIT);
		client.level.addParticle(kind.options, x, y, z, 0, 0, 0);
	}

	/** A trailing plume of dust drifting up behind you, like sneaking through smoke. */
	private static void renderDustPlumeTrail(Minecraft client, AbstractClientPlayer player) {
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.15F) {
			return;
		}
		double x = player.getRandomX(0.5);
		double y = player.getY() + 0.1;
		double z = player.getRandomZ(0.5);
		ParticleKind kind = ParticleKind.byNameOr(SkyMellooConfig.HANDLER.instance().dustPlumeTrailParticle, ParticleKind.DUST_PLUME);
		client.level.addParticle(kind.options, x, y, z, 0, 0.03, 0);
	}

	private static float tornadoAngle = 0F;

	/** A widening funnel of particles from your feet up to above your head, like a personal tornado. */
	private static void renderTornado(Minecraft client, AbstractClientPlayer player, int rgb) {
		tornadoAngle += 0.4F;
		if (tornadoAngle > (float) (Math.PI * 2)) {
			tornadoAngle -= (float) (Math.PI * 2);
		}
		float bodyHeight = player.getBbHeight();
		int layers = 8;
		for (int layer = 0; layer < layers; layer++) {
			float t = (float) layer / (layers - 1);
			double radius = 0.15 + t * 0.9;
			float angle = tornadoAngle + t * 10F;
			double x = player.getX() + Math.cos(angle) * radius;
			double z = player.getZ() + Math.sin(angle) * radius;
			double y = player.getY() + t * bodyHeight * 1.3;
			client.level.addParticle(new DustParticleOptions(rgb, 1.0F), x, y, z, 0, 0, 0);
		}
	}

	private static final float[] blackHoleRingAngle = new float[4];

	/**
	 * A solid-looking sphere of black particles at your center, with colored particles orbiting it
	 * in concentric rings - inner rings spin faster than outer ones, like a real accretion disk.
	 * Unlike the old spiral-inward version, nothing here ever resets: ring angles just accumulate
	 * and wrap at 2π (seamless, since sin/cos don't care), so it runs as a genuinely infinite loop
	 * with no visible "restart" pop.
	 */
	private static void renderBlackHole(Minecraft client, AbstractClientPlayer player, int rgb) {
		float bodyMid = player.getBbHeight() * 0.5F;
		double centerY = player.getY() + bodyMid;

		int coreLatitudes = 4;
		int corePointsPerLat = 5;
		double coreRadius = 0.32;
		for (int lat = 0; lat < coreLatitudes; lat++) {
			double latAngle = Math.PI * (lat + 0.5) / coreLatitudes - Math.PI / 2;
			double ringRadius = coreRadius * Math.cos(latAngle);
			double yOffset = coreRadius * Math.sin(latAngle);
			for (int i = 0; i < corePointsPerLat; i++) {
				double angle = i * (Math.PI * 2 / corePointsPerLat) + lat * 0.4;
				double x = player.getX() + Math.cos(angle) * ringRadius;
				double z = player.getZ() + Math.sin(angle) * ringRadius;
				client.level.addParticle(new DustParticleOptions(0x0A0A0A, 1.1F), x, centerY + yOffset, z, 0, 0, 0);
			}
		}

		int rings = blackHoleRingAngle.length;
		for (int ring = 0; ring < rings; ring++) {
			double radius = 0.55 + ring * 0.3;
			// Slow enough that the rotation is actually visible frame-to-frame instead of blurring
			// into a static ring - inner rings still noticeably faster than outer ones.
			float speed = 0.05F / (float) radius;
			blackHoleRingAngle[ring] += speed;
			if (blackHoleRingAngle[ring] > (float) (Math.PI * 2)) {
				blackHoleRingAngle[ring] -= (float) (Math.PI * 2);
			}
			int pointsInRing = 3 + ring;
			for (int i = 0; i < pointsInRing; i++) {
				float angle = blackHoleRingAngle[ring] + i * (float) (Math.PI * 2 / pointsInRing);
				double x = player.getX() + Math.cos(angle) * radius;
				double z = player.getZ() + Math.sin(angle) * radius;
				double y = centerY + Math.sin(angle * 2F + ring) * 0.08;
				client.level.addParticle(new DustParticleOptions(rgb, 1.0F), x, y, z, 0, 0, 0);
			}
		}
	}

	private static float twinVortexAngle = 0F;

	/** Two tight, contra-rotating spirals weaving around your whole body. */
	private static void renderTwinVortex(Minecraft client, AbstractClientPlayer player, int rgb) {
		twinVortexAngle += 0.45F;
		if (twinVortexAngle > (float) (Math.PI * 2)) {
			twinVortexAngle -= (float) (Math.PI * 2);
		}
		float bodyHeight = player.getBbHeight();
		int pointsPerArm = 6;
		for (int arm = 0; arm < 2; arm++) {
			float direction = arm == 0 ? 1F : -1F;
			for (int p = 0; p < pointsPerArm; p++) {
				float t = (float) p / (pointsPerArm - 1);
				float angle = twinVortexAngle * direction + t * (float) Math.PI * 3;
				double radius = 0.3 + t * 0.4;
				double x = player.getX() + Math.cos(angle) * radius;
				double z = player.getZ() + Math.sin(angle) * radius;
				double y = player.getY() + t * bodyHeight;

				client.level.addParticle(new DustParticleOptions(rgb, 1.0F), x, y, z, 0, 0, 0);
			}
		}
	}

	private static float chargeUpAngle = 0F;
	private static final Map<UUID, Integer> chargeUpCycleTicks = new HashMap<>();
	private static final int CHARGE_PULL_TICKS = 36;
	private static final int CHARGE_BURST_TICKS = 8;
	private static final int CHARGE_SETTLE_TICKS = 24;
	private static final int CHARGE_CYCLE_TICKS = CHARGE_PULL_TICKS + CHARGE_BURST_TICKS + CHARGE_SETTLE_TICKS;

	/** A ring of particles that pulls in tight to your chest, bursts back out, wobbles briefly, then repeats - like charging up energy. */
	private static void renderChargeUp(Minecraft client, AbstractClientPlayer player, int rgb) {
		chargeUpAngle += 0.2F;
		if (chargeUpAngle > (float) (Math.PI * 2)) {
			chargeUpAngle -= (float) (Math.PI * 2);
		}
		int cycleTick = chargeUpCycleTicks.getOrDefault(player.getUUID(), 0);
		chargeUpCycleTicks.put(player.getUUID(), (cycleTick + 1) % CHARGE_CYCLE_TICKS);

		int points = 8;
		double maxRadius = 1.1;
		double footY = player.getY() + 0.1;
		double chestY = player.getY() + player.getBbHeight() * 0.6;

		if (cycleTick < CHARGE_PULL_TICKS) {
			// Contract: ring shrinks from full radius down to the player's center, rising from feet to chest.
			float progress = (float) cycleTick / CHARGE_PULL_TICKS;
			double radius = maxRadius * (1.0 - progress);
			double y = footY + (chestY - footY) * progress;
			for (int i = 0; i < points; i++) {
				float angle = chargeUpAngle + i * (float) (Math.PI * 2 / points);
				double x = player.getX() + Math.cos(angle) * radius;
				double z = player.getZ() + Math.sin(angle) * radius;
				double vx = (player.getX() - x) * 0.35;
				double vz = (player.getZ() - z) * 0.35;
				client.level.addParticle(new DustParticleOptions(rgb, 1.1F), x, y, z, vx, 0.02, vz);
			}
		} else if (cycleTick < CHARGE_PULL_TICKS + CHARGE_BURST_TICKS) {
			// Burst: rapid outward release from the chest, much faster than the contraction was.
			int burstTick = cycleTick - CHARGE_PULL_TICKS;
			float progress = (float) burstTick / CHARGE_BURST_TICKS;
			double radius = maxRadius * progress;
			for (int i = 0; i < points; i++) {
				float angle = chargeUpAngle * 2F + i * (float) (Math.PI * 2 / points);
				double x = player.getX() + Math.cos(angle) * radius;
				double z = player.getZ() + Math.sin(angle) * radius;
				double vx = Math.cos(angle) * 0.35;
				double vz = Math.sin(angle) * 0.35;
				client.level.addParticle(new DustParticleOptions(rgb, 1.3F), x, chestY, z, vx, 0.05, vz);
			}
		} else {
			// Settle: gentle wobble at full radius before the next contraction begins.
			int settleTick = cycleTick - CHARGE_PULL_TICKS - CHARGE_BURST_TICKS;
			double radius = maxRadius + Math.sin(settleTick * 0.9) * 0.12;
			for (int i = 0; i < points; i++) {
				float angle = chargeUpAngle + i * (float) (Math.PI * 2 / points);
				double x = player.getX() + Math.cos(angle) * radius;
				double z = player.getZ() + Math.sin(angle) * radius;
				client.level.addParticle(new DustParticleOptions(rgb, 1.0F), x, footY, z, 0, 0, 0);
			}
		}
	}

	private static float orbitRingsAngle = 0F;

	/** Two tilted, counter-rotating rings of particles around your waist, like Saturn's rings seen from an angle. */
	private static void renderOrbitRings(Minecraft client, AbstractClientPlayer player, int rgb) {
		orbitRingsAngle += 0.08F;
		if (orbitRingsAngle > (float) (Math.PI * 2)) {
			orbitRingsAngle -= (float) (Math.PI * 2);
		}
		float ringHeight = player.getBbHeight() * 0.45F;
		float tilt = 0.35F;
		renderTiltedRing(client, player, rgb, orbitRingsAngle, 1.3, ringHeight, tilt, 24, 0.9F);
		renderTiltedRing(client, player, rgb, -orbitRingsAngle * 1.4F, 0.85, ringHeight, tilt, 16, 0.7F);
	}

	private static void renderTiltedRing(Minecraft client, AbstractClientPlayer player, int rgb, float baseAngle, double radius, float height, float tilt, int points, float particleSize) {
		for (int i = 0; i < points; i++) {
			float angle = baseAngle + i * (float) (Math.PI * 2 / points);
			double x = player.getX() + Math.cos(angle) * radius;
			double z = player.getZ() + Math.sin(angle) * radius;
			double y = player.getY() + height + Math.sin(angle) * radius * tilt;
			client.level.addParticle(new DustParticleOptions(rgb, particleSize), x, y, z, 0, 0, 0);
		}
	}

	/**
	 * A proper lightning strike, arcing all the way down from well above you to your feet - long,
	 * densely segmented (double-thick main bolt), and with the occasional fork branching off partway
	 * down before tapering out, like real lightning's fractal look rather than one clean line.
	 */
	private static void renderLightningAura(Minecraft client, AbstractClientPlayer player) {
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.05F) {
			return;
		}
		double angle = random.nextDouble() * Math.PI * 2;
		double radius = 0.4 + random.nextDouble() * 0.5;
		double startX = player.getX() + Math.cos(angle) * radius;
		double startZ = player.getZ() + Math.sin(angle) * radius;
		double startY = player.getY() + 8.0 + random.nextDouble() * 4.0; // struck from well above, not just over the head
		double endX = player.getX();
		double endZ = player.getZ();
		double endY = player.getY();

		int segments = 16;
		double prevX = startX;
		double prevY = startY;
		double prevZ = startZ;
		for (int i = 1; i <= segments; i++) {
			double t = (double) i / segments;
			double x = startX + (endX - startX) * t + (random.nextDouble() - 0.5) * 0.35;
			double z = startZ + (endZ - startZ) * t + (random.nextDouble() - 0.5) * 0.35;
			double y = startY + (endY - startY) * t;
			client.level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, 0, 0, 0);
			// A second particle halfway to the previous node - a denser main bolt, not just one point per segment.
			client.level.addParticle(ParticleTypes.ELECTRIC_SPARK, (prevX + x) / 2, (prevY + y) / 2, (prevZ + z) / 2, 0, 0, 0);

			if (t > 0.2 && t < 0.8 && random.nextFloat() < 0.12F) {
				double branchAngle = random.nextDouble() * Math.PI * 2;
				double bx = x;
				double by = y;
				double bz = z;
				int branchLength = 3 + random.nextInt(3);
				for (int b = 0; b < branchLength; b++) {
					bx += Math.cos(branchAngle) * 0.3 + (random.nextDouble() - 0.5) * 0.2;
					bz += Math.sin(branchAngle) * 0.3 + (random.nextDouble() - 0.5) * 0.2;
					by -= 0.25 + random.nextDouble() * 0.15;
					client.level.addParticle(ParticleTypes.ELECTRIC_SPARK, bx, by, bz, 0, 0, 0);
				}
			}
			prevX = x;
			prevY = y;
			prevZ = z;
		}
	}

	private static final int[] CONFETTI_COLORS = {0xFFFF5555, 0xFF55FF55, 0xFF5599FF, 0xFFFFFF55, 0xFFFF55FF, 0xFF55FFFF};

	/** Occasional multi-colored confetti burst at a random spot around you - always rainbow-ish by nature, so no single accent color to pick. */
	private static void renderConfettiBurst(Minecraft client, AbstractClientPlayer player) {
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.08F) {
			return;
		}
		// Random point in a sphere-ish volume around the player instead of always dead-center
		// above the head, so bursts feel like they're happening all around you.
		double burstAngle = random.nextDouble() * Math.PI * 2;
		double burstRadius = random.nextDouble() * 1.5;
		double cx = player.getX() + Math.cos(burstAngle) * burstRadius;
		double cy = player.getY() + 0.3 + random.nextDouble() * (player.getBbHeight() + 1.5);
		double cz = player.getZ() + Math.sin(burstAngle) * burstRadius;
		for (int i = 0; i < 30; i++) {
			int color = CONFETTI_COLORS[random.nextInt(CONFETTI_COLORS.length)] & 0xFFFFFF;
			double vx = (random.nextDouble() - 0.5) * 0.4;
			double vy = random.nextDouble() * 0.18;
			double vz = (random.nextDouble() - 0.5) * 0.4;
			client.level.addParticle(new DustParticleOptions(color, 1.0F), cx, cy, cz, vx, vy, vz);
		}
	}

	private static float phoenixWingFlap = 0F;

	/**
	 * A pair of wings trailing from your back - shorter and set higher (shoulder-level, not mid-back)
	 * than the previous attempt, which read as bare ribs/a skeleton rather than wings. A SPINE line
	 * (the leading edge) with only a shallow droop, plus dense FEATHER
	 * lines branching off it (more of them, longer, so the wing area actually fills in instead of
	 * being a thin bone with sparse spikes). The two wings aren't a perfect mirror of each other - a
	 * slightly different flap phase per side.
	 * <p>
	 * The flap itself is a genuine TRAVELING WAVE along the spine now, not a rigid fan
	 * (2026-07-26): the old version used ONE shared phase for the whole wing, just scaled by
	 * distance from the root, which meant every point on the spine moved in perfect lockstep (only the
	 * amplitude differed) - reading as a stiff fan opening/closing rather than a real wing. A real
	 * wing's tip lags behind its root during a flap (the classic S-curve/whip shape) - modeled here by
	 * subtracting a phase delay proportional to how far out along the spine a point sits, so at any
	 * given instant different points are at genuinely different stages of their own oscillation.
	 */
	private static void renderPhoenixWings(Minecraft client, AbstractClientPlayer player, int rgb) {
		phoenixWingFlap += 0.15F;
		if (phoenixWingFlap > (float) (Math.PI * 2)) {
			phoenixWingFlap -= (float) (Math.PI * 2);
		}
		float bodyMid = player.getBbHeight() * 0.78F; // shoulder-level, not mid-back
		// yBodyRot (not getYRot(), which follows your look direction) - otherwise the wings twist
		// with your head every time you look around instead of staying attached to your back.
		float yaw = player.yBodyRot * (float) (Math.PI / 180F);
		double backX = Math.sin(yaw);
		double backZ = -Math.cos(yaw);
		double sideX = -backZ;
		double sideZ = backX;

		int spineSteps = 10;
		int featherCount = 8;
		int featherLength = 5;
		float[] phaseOffsetBySide = {0F, 0.35F};
		// How far (in radians) the wingtip's own oscillation trails behind the root's - the actual
		// "wave" of the flap. 0 would be the old rigid-fan behavior.
		float waveLagPerT = 1.1F;

		for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
			int side = sideIndex == 0 ? -1 : 1;

			double[] spineLateral = new double[spineSteps + 1];
			double[] spineVertical = new double[spineSteps + 1];
			double[] spineBack = new double[spineSteps + 1];
			for (int i = 0; i <= spineSteps; i++) {
				double t = (double) i / spineSteps;
				// Each point along the spine oscillates at its OWN phase (lagging further behind the
				// root the further out it is), not just a shared angle scaled by amplitude - this is
				// what actually produces the traveling-wave/whip shape instead of a rigid swing.
				float localPhase = phoenixWingFlap + phaseOffsetBySide[sideIndex] - (float) (t * waveLagPerT);
				float localFlap = (float) Math.sin(localPhase) * 0.6F;
				double hingeAmount = localFlap * t;
				double lateral = t * 1.1; // shorter span than before
				double vertical = -t * t * 0.22 + hingeAmount * 0.25; // shallow droop, small flap component
				double back = 0.3 + t * 0.55 + hingeAmount * 0.9; // flap mainly sweeps back/forward
				spineLateral[i] = lateral;
				spineVertical[i] = vertical;
				spineBack[i] = back;
				spawnWingParticle(client, player, backX, backZ, sideX, sideZ, bodyMid, lateral, vertical, back, side, rgb, 1.1F);
			}

			for (int f = 1; f <= featherCount; f++) {
				int i = Math.min(spineSteps, (int) Math.round((double) f / featherCount * spineSteps));
				double t = (double) i / spineSteps;
				double rootLateral = spineLateral[i];
				double rootVertical = spineVertical[i];
				double rootBack = spineBack[i];
				// Feathers sweep further back and down the farther out they branch off, and are
				// longer toward the wingtip - overlapping covert feathers filling the wing area in,
				// not sparse spikes off a bare bone.
				double featherReach = 0.4 + t * 0.55;
				for (int step = 1; step <= featherLength; step++) {
					double lt = (double) step / featherLength;
					double lateral = rootLateral - lt * featherReach * 0.25;
					double vertical = rootVertical - lt * featherReach * 0.35;
					double back = rootBack + lt * featherReach * 0.55;
					float size = 0.7F + 0.3F * (float) t;
					spawnWingParticle(client, player, backX, backZ, sideX, sideZ, bodyMid, lateral, vertical, back, side, rgb, size);
				}
			}
		}
	}

	private static void spawnWingParticle(Minecraft client, AbstractClientPlayer player, double backX, double backZ, double sideX, double sideZ, float bodyMid, double lateral, double vertical, double back, int side, int rgb, float size) {
		double x = player.getX() + backX * back + sideX * (lateral * side);
		double z = player.getZ() + backZ * back + sideZ * (lateral * side);
		double y = player.getY() + bodyMid + vertical;
		client.level.addParticle(new DustParticleOptions(rgb, size), x, y, z, 0, 0, 0);
	}

	private static float voidRiftPhase = 0F;

	/**
	 * An actual jagged, slowly writhing vertical crack hovering just off your back - drawn as a dense
	 * zigzag LINE of particles (not scattered random points), with nearby ambient particles visibly
	 * getting pulled INTO the slit (the same inward-pull idea as the Plasma spell), plus unstable puffs
	 * of smoke escaping outward. A real "tear in reality" with actual structure and motion to it, not
	 * just sparse random drift near the player.
	 * <p>
	 * Made noticeably denser/busier (2026-07-26, "viel cooler machen mehr partikel mehr animieren mehr
	 * füllen"): nearly double the points along the crack itself, a second haze layer just off to the
	 * side of the line so it reads as an actual torn OPENING with some width to it (not a single-pixel
	 * line), up to 2 simultaneous suction pulls per tick instead of one 50%-chance pull, and more
	 * frequent/bigger unstable puffs.
	 */
	private static void renderVoidRift(Minecraft client, AbstractClientPlayer player) {
		voidRiftPhase += 0.06F;
		if (voidRiftPhase > (float) (Math.PI * 2)) {
			voidRiftPhase -= (float) (Math.PI * 2);
		}
		RandomSource random = player.getRandom();
		ParticleKind kind = ParticleKind.byNameOr(SkyMellooConfig.HANDLER.instance().voidRiftParticle, ParticleKind.VOID_RIFT);

		// Slowly orbits all the way around the player instead of sitting glued directly behind their
		// facing direction - a real drifting tear, not a fixed decal.
		float orbitAngle = voidRiftPhase * 0.3F;
		double orbitX = Math.cos(orbitAngle);
		double orbitZ = Math.sin(orbitAngle);
		double perpX = -orbitZ;
		double perpZ = orbitX;

		double riftHeight = player.getBbHeight() + 0.4;
		int points = 19;
		Vec3[] line = new Vec3[points];
		for (int i = 0; i < points; i++) {
			double t = (double) i / (points - 1);
			// Jagged on TWO horizontal axes now, not just side-to-side - plus
			// a little vertical jitter so it doesn't read as a perfectly straight line with only one
			// wobble direction. Different frequencies/phases per axis so it looks like a real fractured
			// crack, not one clean sine wave traced twice.
			double sideJag = Math.sin(t * Math.PI * 3 + voidRiftPhase * 2) * 0.22;
			double backJag = Math.cos(t * Math.PI * 2.3 + voidRiftPhase * 1.6) * 0.18;
			double verticalJitter = Math.sin(t * Math.PI * 5 + voidRiftPhase * 2.5) * 0.12;
			double back = 0.9 + backJag;
			double y = player.getY() + t * riftHeight + verticalJitter;
			double x = player.getX() + orbitX * back + perpX * sideJag;
			double z = player.getZ() + orbitZ * back + perpZ * sideJag;
			line[i] = new Vec3(x, y, z);
			client.level.addParticle(kind.options, x, y, z, 0, 0, 0);
			// A dim haze particle just off to the side of the crack at every other point, so the rift
			// reads as an actual opening with some width/depth instead of a hairline.
			if (i % 2 == 0) {
				double hazeSide = 0.08 + random.nextDouble() * 0.06;
				double hx = x + perpX * hazeSide;
				double hz = z + perpZ * hazeSide;
				client.level.addParticle(ParticleTypes.SQUID_INK, hx, y, hz, 0, 0, 0);
			}
		}

		// Nearby ambient particles visibly get sucked into the slit - now up to 2 pulls per tick
		// instead of a single 50%-chance one, so the pull actually reads as continuous.
		int pulls = random.nextFloat() < 0.7F ? 2 : 1;
		for (int p = 0; p < pulls; p++) {
			Vec3 target = line[1 + random.nextInt(points - 2)];
			double angle = random.nextDouble() * Math.PI * 2;
			double radius = 1.3 + random.nextDouble() * 1.2;
			double sx = target.x + Math.cos(angle) * radius;
			double sz = target.z + Math.sin(angle) * radius;
			double sy = target.y + (random.nextDouble() - 0.5) * 0.6;
			double vx = (target.x - sx) * 0.12;
			double vy = (target.y - sy) * 0.12;
			double vz = (target.z - sz) * 0.12;
			client.level.addParticle(ParticleTypes.SQUID_INK, sx, sy, sz, vx, vy, vz);
		}

		// Unstable puff escaping outward, like the rift briefly losing containment - more frequent and
		// more particles per puff than before.
		if (random.nextFloat() < 0.06F) {
			Vec3 origin = line[random.nextInt(points)];
			for (int i = 0; i < 3; i++) {
				double vx = (random.nextDouble() - 0.5) * 0.15;
				double vz = (random.nextDouble() - 0.5) * 0.15;
				client.level.addParticle(ParticleTypes.LARGE_SMOKE, origin.x, origin.y, origin.z, vx, 0.05, vz);
			}
		}
	}

	/**
	 * Full redesign (2026-07-26) - the old
	 * version was just two flat tilted rings spinning at chest height, which didn't read as anything
	 * "woven" at all. Now a genuine 3-strand BRAID of star sparkles running the whole body height
	 * (feet to above the head): each strand orbits the player at the same vertical rate but offset
	 * 120° apart, so at any given height the 3 strands are evenly spaced and visibly swap positions as
	 * they rise - the actual over/under crossing look of a real braid, not just a static ring shape.
	 */
	private static void renderStarWeave(Minecraft client, AbstractClientPlayer player) {
		starWeaveAngle += 0.1F;
		if (starWeaveAngle > (float) (Math.PI * 2)) {
			starWeaveAngle -= (float) (Math.PI * 2);
		}
		float bodyHeight = player.getBbHeight();
		int strands = 3;
		int pointsPerStrand = 16;
		double radius = 0.45;
		// How many full crossings the braid makes from feet to head - the actual "weave" density.
		double turnsOverHeight = 2.0;

		for (int strand = 0; strand < strands; strand++) {
			float strandPhase = strand * (float) (Math.PI * 2 / strands);
			for (int p = 0; p < pointsPerStrand; p++) {
				double t = (double) p / (pointsPerStrand - 1);
				double y = player.getY() + t * bodyHeight;
				float angle = starWeaveAngle + strandPhase + (float) (t * Math.PI * 2 * turnsOverHeight);
				double x = player.getX() + Math.cos(angle) * radius;
				double z = player.getZ() + Math.sin(angle) * radius;
				client.level.addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0, 0);
			}
		}
	}

	/** A steady stream of end-rod sparkles spawned at your feet with upward velocity - vanilla's own particle drift carries them all the way up past your head on their own. */
	private static void renderAscendingSparkles(Minecraft client, AbstractClientPlayer player) {
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.5F) {
			return;
		}
		double angle = random.nextDouble() * Math.PI * 2;
		double radius = 0.15 + random.nextDouble() * 0.25;
		double x = player.getX() + Math.cos(angle) * radius;
		double z = player.getZ() + Math.sin(angle) * radius;
		client.level.addParticle(ParticleTypes.END_ROD, x, player.getY(), z, 0, 0.06, 0);
	}

	/** A stretched-out tail of end-rod sparkles streaming behind you - only while actually moving, tied to real movement direction/speed rather than always-on. */
	private static void renderCometTrail(Minecraft client, AbstractClientPlayer player) {
		Vec3 velocity = player.getDeltaMovement();
		double speed = velocity.horizontalDistance();
		if (speed < 0.02) {
			return;
		}
		RandomSource random = player.getRandom();
		Vec3 back = velocity.normalize().scale(-1);
		for (int i = 0; i < 3; i++) {
			double dist = 0.2 + random.nextDouble() * 0.8;
			double spread = (random.nextDouble() - 0.5) * 0.2;
			double x = player.getX() + back.x * dist + spread;
			double y = player.getY() + 0.3 + player.getBbHeight() * 0.5 * random.nextDouble();
			double z = player.getZ() + back.z * dist + spread;
			client.level.addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0, 0);
		}
	}

	/** A soft, slow-drifting cloud of end-rod sparkles at varying radii/heights around you, unlike Aura's tight fixed-radius orbit - reads as a gentle veil rather than a ring. */
	private static void renderStarVeil(Minecraft client, AbstractClientPlayer player) {
		starVeilAngle += 0.03F;
		if (starVeilAngle > (float) (Math.PI * 2)) {
			starVeilAngle -= (float) (Math.PI * 2);
		}
		RandomSource random = player.getRandom();
		if (random.nextFloat() > 0.4F) {
			return;
		}
		float angle = starVeilAngle + random.nextFloat() * (float) (Math.PI * 2);
		double radius = 0.6 + random.nextDouble() * 0.8;
		double height = random.nextDouble() * (player.getBbHeight() + 0.5);
		double x = player.getX() + Math.cos(angle) * radius;
		double z = player.getZ() + Math.sin(angle) * radius;
		client.level.addParticle(ParticleTypes.END_ROD, x, player.getY() + height, z, 0, 0.01, 0);
	}

	/** A ring of end-rod sparkles that pulses outward from your chest every ~1.5 seconds. */
	private static void renderRadiantPulse(Minecraft client, AbstractClientPlayer player) {
		radiantPulseTimer++;
		if (radiantPulseTimer < 30) {
			return;
		}
		radiantPulseTimer = 0;
		double y = player.getY() + player.getBbHeight() * 0.5;
		int points = 24;
		for (int i = 0; i < points; i++) {
			double angle = Math.PI * 2 * i / points;
			double vx = Math.cos(angle) * 0.15;
			double vz = Math.sin(angle) * 0.15;
			client.level.addParticle(ParticleTypes.END_ROD, player.getX(), y, player.getZ(), vx, 0, vz);
		}
	}
}
