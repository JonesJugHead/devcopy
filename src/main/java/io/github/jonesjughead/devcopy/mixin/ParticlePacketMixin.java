package io.github.jonesjughead.devcopy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;

import io.github.jonesjughead.devcopy.particles.ParticleRecorder;

/**
 * Right after ensureRunningOnSameThread: on the network thread it throws and we never get here, so we
 * run only once, on the client thread. No TAIL here: handleParticleEvent has early returns
 * (tryAddParticle failing) that would lose packets.
 */
@Mixin(ClientPacketListener.class)
public abstract class ParticlePacketMixin {
	private static final String ENSURE_THREAD = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread"
			+ "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

	@Inject(method = "handleParticleEvent", at = @At(value = "INVOKE", target = ENSURE_THREAD, shift = At.Shift.AFTER))
	private void devcopy$particles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		ParticleRecorder.onParticles(packet);
	}

	@Inject(method = "handleLevelEvent", at = @At(value = "INVOKE", target = ENSURE_THREAD, shift = At.Shift.AFTER))
	private void devcopy$levelEvent(ClientboundLevelEventPacket packet, CallbackInfo ci) {
		ParticleRecorder.onLevelEvent(packet);
	}
}
