package com.melloo.skymelloo.client.mixin;

/**
 * Implemented on {@link net.minecraft.client.renderer.entity.state.EntityRenderState} (see
 * {@link EntityRenderStateInvisibilityMixin}) to distinguish "we forced this entity invisible for
 * a magic-missile-hit/death-double effect" from vanilla's own {@code isInvisible} flag, which is
 * ALSO true for genuinely-invisible entities (a potion effect, or a Hypixel mob whose custom model
 * relies on real vanilla invisibility + visible armor/head). {@link ArmorInvisibilityMixin},
 * {@link HeldItemInvisibilityMixin} and {@link PlayerHeadInvisibilityMixin} used to check the
 * shared vanilla flag directly, which incorrectly hid armor/head/held-items on those genuinely-
 * invisible mobs too - they now check this instead, which is only ever true for our own forced
 * cases.
 */
public interface ForcedInvisibilityHolder {
	boolean skymelloo$isForcedInvisible();

	void skymelloo$setForcedInvisible(boolean value);
}
