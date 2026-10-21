package io.github.jonesjughead.devcopy.particles;

import java.util.List;

/**
 * A finished capture. Deliberately free of any Minecraft class: export and analysis run off
 * the client thread and can be tested on their own.
 */
public record Capture(
		String startedAt,
		String server,
		String dimension,
		double originX, double originY, double originZ,
		float originYaw,
		double radius,
		boolean levelEvents,
		int requestedTicks,
		int durationTicks,
		long durationMs,
		String stopReason,
		int filteredOut,
		List<Particle> particles,
		List<LevelEvent> events) {

	/**
	 * A ClientboundLevelParticlesPacket, as-is. Positions relative to the origin.
	 * In 26.3 the speed is per axis (xMaxSpeed/yMaxSpeed/zMaxSpeed); /particle and sendParticles
	 * put the same value on all three.
	 */
	public record Particle(
			int tick, long ms, int seq,
			String type,
			double x, double y, double z,
			float xDist, float yDist, float zDist,
			float xSpeed, float ySpeed, float zSpeed,
			int count,
			boolean overrideLimiter, boolean alwaysShow,
			String randomization,
			/** RGB 0xRRGGBB, -1 if the type has no color. */
			int rgb,
			/** End color for dust_color_transition, -1 otherwise. */
			int rgbTo,
			/** Dust scale, NaN otherwise. */
			float scale,
			/** Options serialized through the ParticleOptions codec (JSON), "{}" if none. */
			String optionsJson,
			/** Same options as SNBT, ready to paste after the name in /particle. Empty if none. */
			String optionsSnbt) {

		/** count = 0: xDist/yDist/zDist are a direction, multiplied by the axis speed. */
		public boolean directional() {
			return count == 0;
		}

		/** Number of particles actually created on the client. */
		public int spawned() {
			return Math.max(1, count);
		}

		public boolean isDust() {
			return type.equals("minecraft:dust") || type.equals("minecraft:dust_color_transition");
		}
	}

	public record LevelEvent(int tick, long ms, int seq, int type, String name,
			int x, int y, int z, int data, boolean global) {}
}
