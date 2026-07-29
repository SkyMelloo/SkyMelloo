package com.melloo.skymelloo.client.mixin;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Backing storage for {@link ForcedInvisibilityHolder}, set by {@link ForcedInvisibilityExtractionMixin}. */
@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateInvisibilityMixin implements ForcedInvisibilityHolder {
	@Unique
	private boolean skymelloo$forcedInvisible;

	@Override
	@Unique
	public boolean skymelloo$isForcedInvisible() {
		return this.skymelloo$forcedInvisible;
	}

	@Override
	@Unique
	public void skymelloo$setForcedInvisible(boolean value) {
		this.skymelloo$forcedInvisible = value;
	}
}
