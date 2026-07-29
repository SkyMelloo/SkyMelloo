package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.gui.SkyMellooMenuItemManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Detects "punching empty air while holding the SkyMelloo Menu item" to open the menu the same way
 * a left-click on Hypixel's own SkyBlock Menu item would (not just right-click). Read-only: never
 * touches the callback's return value, so real attack behavior is untouched.
 * <p>
 * Used to also trigger the Magic Missile cosmetic on any empty-hand punch - removed (2026-07-27)
 * in favor of a "Cast Spell" button in
 * {@link com.melloo.skymelloo.client.gui.SkyMellooMenuScreen}'s Spells page, so casting is a
 * deliberate menu action instead of an incidental side effect of punching air with nothing held.
 */
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
		}
	}
}
