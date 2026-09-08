package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.cosmetics.MagicMissileManager;
import com.melloo.skymelloo.client.gui.SkyMellooMenuItemManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Punching empty air: holding the SkyMelloo Menu item left-click-opens the menu; an empty main
// hand instead casts the Magic Missile spell. Read-only, never touches the callback's return value.
@Mixin(Minecraft.class)
public abstract class EmptyHandAirClickMixin {

	@Inject(method = "startAttack", at = @At("HEAD"))
	private void skymelloo$onEmptyHandAirClick(CallbackInfoReturnable<Boolean> cir) {
		Minecraft client = (Minecraft) (Object) this;
		if (client.player == null || client.hitResult == null) {
			return;
		}
		boolean hitNothing = client.hitResult.getType() == HitResult.Type.MISS;
		if (!hitNothing) {
			return;
		}
		ItemStack held = client.player.getMainHandItem();
		if (SkyMellooMenuItemManager.isMarkerStack(held)) {
			SkyMellooMenuItemManager.openMenu(client);
		} else if (held.isEmpty()) {
			MagicMissileManager.trigger(client);
		}
	}
}
