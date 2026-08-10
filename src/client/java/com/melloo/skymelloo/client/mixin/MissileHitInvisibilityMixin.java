package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.cosmetics.MagicMissileManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Two independent overrides of {@code isInvisible()}, kept in this ONE mixin/method (not separate
 * classes) so their priority against each other is an explicit Java if/else instead of relying on
 * undefined ordering between two different {@code @Inject(at = @At("HEAD"))} handlers on the same
 * target method:
 * <ol>
 * <li>Briefly renders a player invisible right after a magic-missile cosmetic hit. Purely visual,
 * always takes priority - checked first, and returns before the second check ever runs.</li>
 * <li>Optionally makes another player's REAL vanilla invisibility (e.g. an Invisibility Potion) not
 * apply to how THIS client renders them: overriding isInvisible() to false makes
 * every vanilla system (model rendering, nametag, armor, etc.) treat them as a completely normal,
 * visible player - blocked by walls/line-of-sight like anyone else, NOT a highlight-style glow outline
 * visible through obstacles. Off by default - see SkyMellooConfig#showInvisiblePlayersEnabled.</li>
 * </ol>
 */
@Mixin(Entity.class)
public abstract class MissileHitInvisibilityMixin {

	@Inject(method = "isInvisible", at = @At("HEAD"), cancellable = true)
	private void skymelloo$overrideInvisibility(CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		if (MagicMissileManager.isTemporarilyInvisible(self)) {
			cir.setReturnValue(true);
			return;
		}
		// Never overrides the LOCAL player's own invisibility - this is about revealing OTHER
		// players to you, not un-hiding yourself from your own client.
		if (self instanceof Player player && player != Minecraft.getInstance().player
				&& SkyMellooConfig.HANDLER.instance().showInvisiblePlayersEnabled) {
			cir.setReturnValue(false);
		}
	}
}
