package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.highlight.HighlightManager;
import com.melloo.skymelloo.client.hp.DistanceDisplayManager;
import com.melloo.skymelloo.client.social.StatusTextDisplayManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Targets both Entity and Player: Player.getDisplayName() is a full override, so a mixin on
// Entity.class alone never runs for Player instances at all.
@Mixin({Entity.class, Player.class})
public abstract class EntityDisplayNameMixin {

	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void skymelloo$appendHp(CallbackInfoReturnable<Component> cir) {
		Entity self = (Entity) (Object) this;
		Component result = cir.getReturnValue();
		if (self instanceof ItemEntity item) {
			// Falls back to the generic item-type name otherwise, losing anvil renames/enchants/rarity.
			result = item.getItem().getHoverName();
		}
		if (self instanceof Player player) {
			result = HighlightManager.colorizeName(player, result);
			result = StatusTextDisplayManager.apply(player, result);
		}
		if (HighlightManager.isHighlightTarget(self)) {
			result = DistanceDisplayManager.apply(self, result);
		}
		cir.setReturnValue(result);
	}
}
