package com.melloo.skymelloo.client.mixin;

// Distinguishes "we forced this entity invisible" from vanilla's own isInvisible flag, which is
// also true for genuinely-invisible entities that should keep their armor/head/held items visible.
public interface ForcedInvisibilityHolder {
	boolean skymelloo$isForcedInvisible();

	void skymelloo$setForcedInvisible(boolean value);
}
