package com.melloo.skymelloo.client.cosmetics;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;

/**
 * A curated set of vanilla particle types the player can pick between for the "spawn one kind of
 * particle" cosmetics - lets every one of those be reflavored (heart, note, sparkle, ...) instead
 * of being permanently locked to whichever single particle it originally shipped with.
 */
public enum ParticleKind {
	HEART("Heart", ParticleTypes.HEART),
	NOTE("Note", ParticleTypes.NOTE),
	SPARKLE("Sparkle", ParticleTypes.END_ROD),
	FLAME("Flame", ParticleTypes.SMALL_FLAME),
	SPARK("Spark", ParticleTypes.ELECTRIC_SPARK),
	CHERRY_BLOSSOM("Cherry Blossom", ParticleTypes.CHERRY_LEAVES),
	SNOWFLAKE("Snowflake", ParticleTypes.SNOWFLAKE),
	SOUL("Soul", ParticleTypes.SOUL),
	ENCHANTED_CRIT("Enchanted Crit", ParticleTypes.ENCHANTED_HIT),
	TOTEM("Totem", ParticleTypes.TOTEM_OF_UNDYING),
	SCULK("Sculk", ParticleTypes.SCULK_CHARGE_POP),
	OMEN("Omen", ParticleTypes.RAID_OMEN),
	DUST_PLUME("Dust Plume", ParticleTypes.DUST_PLUME),
	FIREWORK("Firework", ParticleTypes.FIREWORK),
	VOID_RIFT("Void", ParticleTypes.REVERSE_PORTAL),
	HAPPY_VILLAGER("Happy Villager", ParticleTypes.HAPPY_VILLAGER),
	POOF("Poof", ParticleTypes.POOF);

	public final String label;
	public final ParticleOptions options;

	ParticleKind(String label, ParticleOptions options) {
		this.label = label;
		this.options = options;
	}

	/** Config stores the enum name as a plain string - falls back cleanly if it's missing/unrecognized (e.g. an older config). */
	public static ParticleKind byNameOr(String name, ParticleKind fallback) {
		if (name == null) {
			return fallback;
		}
		for (ParticleKind kind : values()) {
			if (kind.name().equals(name)) {
				return kind;
			}
		}
		return fallback;
	}
}
