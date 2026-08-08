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

/**
 * Detects punching empty air with an empty hand for two separate things: holding the SkyMelloo Menu
 * item does the same as a left-click on Hypixel's own SkyBlock Menu item would (not just
 * right-click); a genuinely EMPTY main hand instead casts the Magic Missile spell. Read-only: never
 * touches the callback's return value, so real attack behavior is untouched.
 * <p>
 * The spell-cast side of this briefly moved into a "Cast Spell" button in
 * {@link com.melloo.skymelloo.client.gui.SkyMellooMenuScreen}'s Spells page instead, on the
 * reasoning that a deliberate menu click was cleaner than an incidental side effect of punching air -
 * reverted back to this mixin, since in practice that meant casting a spell required stopping to open
 * a whole menu first (and briefly froze movement while it was open), instead of a normal in-game
 * reflex. This never fires while any screen is open at all - {@code startAttack} isn't called with
 * one open in the first place, so it can't ever fire "from inside the menu".
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
		} else if (held.isEmpty()) {
			MagicMissileManager.trigger(client);
		}
	}
}
