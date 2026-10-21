package io.github.jonesjughead.devcopy.mixin;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.world.BossEvent;

import io.github.jonesjughead.devcopy.HudState;

/**
 * Injected at TAIL: handlers are first called on the network thread, which returns
 * right away through ensureRunningOnSameThread, then replayed on the client thread.
 * At TAIL we therefore run only once, on the right thread.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleTabListCustomisation", at = @At("TAIL"))
	private void devcopy$tabList(ClientboundTabListPacket packet, CallbackInfo ci) {
		HudState.tabHeader = packet.header();
		HudState.tabFooter = packet.footer();
	}

	@Inject(method = "setTitleText", at = @At("TAIL"))
	private void devcopy$title(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
		HudState.title = packet.text();
	}

	@Inject(method = "setSubtitleText", at = @At("TAIL"))
	private void devcopy$subtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
		HudState.subtitle = packet.text();
	}

	@Inject(method = "handleTitlesClear", at = @At("TAIL"))
	private void devcopy$clearTitles(ClientboundClearTitlesPacket packet, CallbackInfo ci) {
		HudState.title = Component.empty();
		HudState.subtitle = Component.empty();
	}

	@Inject(method = "handleBossUpdate", at = @At("TAIL"))
	private void devcopy$bossBar(ClientboundBossEventPacket packet, CallbackInfo ci) {
		packet.dispatch(new ClientboundBossEventPacket.Handler() {
			@Override
			public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color,
					BossEvent.BossBarOverlay overlay, boolean darkenScreen, boolean playMusic, boolean createWorldFog) {
				HudState.putBossBar(id, name);
			}

			@Override
			public void remove(UUID id) {
				HudState.removeBossBar(id);
			}

			@Override
			public void updateName(UUID id, Component name) {
				HudState.renameBossBar(id, name);
			}
		});
	}
}
