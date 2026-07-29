package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.cosmetics.MagicMissileManager;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Same job as {@link ForcedInvisibilityExtractionMixin}, but for real players specifically - the
 * actual root cause of armor/held-item/head staying visible on hit real players. Confirmed via
 * javap: {@code AvatarRenderer} (Mojang's renamed {@code PlayerRenderer}) declares its OWN override
 * of the untyped {@code extractRenderState(Entity, EntityRenderState, float)} bridge rather than
 * inheriting {@link net.minecraft.client.renderer.entity.LivingEntityRenderer}'s body unchanged -
 * so the TAIL inject in {@link ForcedInvisibilityExtractionMixin} (which targets that base-class
 * method) never actually runs for players at all; virtual dispatch calls AvatarRenderer's own
 * override instead. That's why the flag this sets was never true for real players, no matter how
 * clearly {@link MagicMissileManager#isTemporarilyInvisible} said they should be. Mixing directly
 * into AvatarRenderer's own override fixes that at the actual call site.
 * <p>
 * Also applies the Levitate spell's actual visual position lock here (see
 * {@link MagicMissileManager#getLevitateRenderOverride}) - THIS is the mixin that actually matters
 * for that, since Levitate only ever targets real players, which go through AvatarRenderer
 * specifically per the above. Locks X/Z as well as Y so the target can't visibly wander off
 * mid-animation while still under their own movement control.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarForcedInvisibilityExtractionMixin {
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
