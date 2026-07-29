package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.gui.SkyMellooMenuItemManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clicking the SkyMelloo Menu item's slot while your real inventory (E) is open also opens the menu -
 * left OR right click, same as punching air with it in hand. {@code hoveredSlot} is whatever the
 * vanilla screen already computed this frame, read here before the real click logic (which would
 * otherwise try to pick up/move the fake item) gets a chance to run.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerMenuItemClickMixin {
	@Shadow
	protected Slot hoveredSlot;

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void skymelloo$onMenuItemSlotClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
		if (hoveredSlot != null && hoveredSlot.hasItem() && SkyMellooMenuItemManager.isMarkerStack(hoveredSlot.getItem())) {
			SkyMellooMenuItemManager.openMenu(Minecraft.getInstance());
			cir.setReturnValue(true);
			cir.cancel();
		}
	}
}
