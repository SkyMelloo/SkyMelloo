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

// Same job as ForcedInvisibilityExtractionMixin, but for real players: AvatarRenderer overrides
// extractRenderState itself, so a base-class inject never fires. Also locks Levitate's X/Y/Z here.
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
