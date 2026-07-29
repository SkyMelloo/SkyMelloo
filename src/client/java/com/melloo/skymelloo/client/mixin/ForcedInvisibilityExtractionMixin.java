package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.cosmetics.MagicMissileManager;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records, per-entity, whether SkyMelloo itself is the reason {@code Entity.isInvisible()} is
 * returning true right now (see {@link MissileHitInvisibilityMixin}) - separately from vanilla's
 * own {@code isInvisible} render-state flag, which conflates that with genuine vanilla invisibility.
 * Also applies the Levitate spell's actual visual position lock here (see
 * {@link MagicMissileManager#getLevitateRenderOverride}) - the real player model now actually rises
 * instead of just a separate particle effigy while the real one stands still on the ground, and its
 * X/Z get locked too so they can't visibly wander off mid-animation while still under their own
 * movement control.
 * Injected at the tail of the bridge {@code extractRenderState(Entity, EntityRenderState, float)}
 * method (verified via bytecode to synchronously delegate to the real generically-typed one first),
 * so the render state is already fully populated by the time this runs.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class ForcedInvisibilityExtractionMixin {
	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
			at = @At("TAIL")
	)
	private void skymelloo$markForcedInvisible(Entity entity, EntityRenderState renderState, float partialTick, CallbackInfo ci) {
		boolean forced = MagicMissileManager.isTemporarilyInvisible(entity);
		((ForcedInvisibilityHolder) (Object) renderState).skymelloo$setForcedInvisible(forced);
		Vec3 override = MagicMissileManager.getLevitateRenderOverride(entity);
		if (override != null) {
			renderState.x = override.x;
			renderState.y = override.y;
			renderState.z = override.z;
		}
	}
}
