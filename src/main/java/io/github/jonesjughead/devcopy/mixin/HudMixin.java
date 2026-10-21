package io.github.jonesjughead.devcopy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;

import io.github.jonesjughead.devcopy.HudState;

/**
 * The action bar arrives through two different packets (SetActionBarText and SystemChat overlay),
 * both end up in setOverlayMessage: that is where we capture it.
 */
@Mixin(Hud.class)
public abstract class HudMixin {
	@Inject(method = "setOverlayMessage", at = @At("HEAD"))
	private void devcopy$actionBar(Component message, boolean animateColor, CallbackInfo ci) {
		HudState.actionBar = message;
	}
}
