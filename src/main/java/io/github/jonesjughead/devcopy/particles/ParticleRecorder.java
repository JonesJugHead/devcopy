package io.github.jonesjughead.devcopy.particles;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ScalableParticleOptionsBase;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.level.block.LevelEvent;

/**
 * Records every particle packet received (no sampling), timestamped in client ticks relative to
 * the start. Everything runs on the client thread: the mixin hooks in after ensureRunningOnSameThread
 * and the counter advances on END_CLIENT_TICK, so no synchronization is needed.
 */
public final class ParticleRecorder {
	private static final Logger LOGGER = LoggerFactory.getLogger("DevCopy/Particles");
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final DateTimeFormatter DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	public static final double DEFAULT_RADIUS = 64;
	public static final int MAX_SECONDS = 600;

	private static Session session;
	private static Map<Integer, String> levelEventNames;

	private ParticleRecorder() {}

	private static final class Session {
		final LocalDateTime startedAt = LocalDateTime.now();
		final long startNanos = System.nanoTime();
		final double ox, oy, oz;
		final float yaw;
		final double radius;
		final boolean levelEvents;
		final int maxTicks;
		final String server;
		final String dimension;
		final HolderLookup.Provider registries;
		final List<Capture.Particle> particles = new ArrayList<>();
		final List<Capture.LevelEvent> events = new ArrayList<>();
		int tick;
		int seq;
		int filteredOut;

		Session(Minecraft mc, int seconds, double radius, boolean levelEvents) {
			this.ox = mc.player.getX();
			this.oy = mc.player.getY();
			this.oz = mc.player.getZ();
			this.yaw = mc.player.getYRot();
			this.radius = radius;
			this.levelEvents = levelEvents;
			this.maxTicks = seconds * 20;
			ServerData data = mc.getCurrentServer();
			this.server = data != null ? data.ip : "solo";
			this.dimension = mc.level.dimension().identifier().toString();
			this.registries = mc.level.registryAccess();
		}

		long elapsedMs() {
			return (System.nanoTime() - startNanos) / 1_000_000;
		}

		boolean inRadius(double x, double y, double z) {
			if (radius <= 0) return true;
			double dx = x - ox, dy = y - oy, dz = z - oz;
			return dx * dx + dy * dy + dz * dz <= radius * radius;
		}
	}

	public static boolean isRecording() {
		return session != null;
	}

	public static String start(int seconds, double radius, boolean levelEvents) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return "Not in game";
		if (session != null) return "A recording is already running (/devcopy particles stop)";
		session = new Session(mc, seconds, radius, levelEvents);
		return null;
	}

	/** Called at the end of every client tick. */
	public static void onEndTick() {
		Session s = session;
		if (s == null) return;
		s.tick++;
		if (s.tick >= s.maxTicks) stop("duration reached");
	}

	public static void onParticles(ClientboundLevelParticlesPacket p) {
		Session s = session;
		if (s == null) return;
		if (!s.inRadius(p.x(), p.y(), p.z())) {
			s.filteredOut++;
			return;
		}
		ParticleOptions opts = p.particle();
		int rgb = -1, rgbTo = -1;
		float scale = Float.NaN;
		switch (opts) {
			case DustParticleOptions dust -> rgb = toRgb(dust.getColor());
			case DustColorTransitionOptions t -> {
				rgb = toRgb(t.getFromColor());
				rgbTo = toRgb(t.getToColor());
			}
			case ColorParticleOption c -> rgb = toRgb(c.getRed(), c.getGreen(), c.getBlue());
			default -> {}
		}
		if (opts instanceof ScalableParticleOptionsBase scalable) scale = scalable.getScale();

		String type = String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(opts.getType()));
		s.particles.add(new Capture.Particle(
				s.tick, s.elapsedMs(), s.seq++, type,
				p.x() - s.ox, p.y() - s.oy, p.z() - s.oz,
				p.xDist(), p.yDist(), p.zDist(),
				p.xMaxSpeed(), p.yMaxSpeed(), p.zMaxSpeed(),
				p.count(), p.overrideLimiter(), p.alwaysShow(),
				p.randomizationType().name(),
				rgb, rgbTo, scale,
				encodeJson(s.registries, opts), encodeSnbt(s.registries, opts)));
	}

	public static void onLevelEvent(ClientboundLevelEventPacket p) {
		Session s = session;
		if (s == null || !s.levelEvents) return;
		BlockPos pos = p.getPos();
		if (!p.isGlobalEvent() && !s.inRadius(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
			s.filteredOut++;
			return;
		}
		s.events.add(new Capture.LevelEvent(s.tick, s.elapsedMs(), s.seq++, p.getType(), levelEventName(p.getType()),
				pos.getX() - (int) Math.floor(s.ox), pos.getY() - (int) Math.floor(s.oy), pos.getZ() - (int) Math.floor(s.oz),
				p.getData(), p.isGlobalEvent()));
	}

	/** Stops and exports. Returns false if nothing was running. */
	public static boolean stop(String reason) {
		Session s = session;
		if (s == null) return false;
		session = null;

		Capture capture = new Capture(
				s.startedAt.toString(), s.server, s.dimension,
				s.ox, s.oy, s.oz, s.yaw, s.radius, s.levelEvents,
				s.maxTicks, s.tick, s.elapsedMs(), reason, s.filteredOut,
				List.copyOf(s.particles), List.copyOf(s.events));
		Path dir = FabricLoader.getInstance().getGameDir()
				.resolve("devcopy").resolve("particles").resolve(s.startedAt.format(DIR_FORMAT));

		Minecraft mc = Minecraft.getInstance();
		message(Component.literal("Recording finished (" + reason + "): " + capture.particles().size()
				+ " packets in " + capture.durationTicks() + " ticks, exporting…").withStyle(ChatFormatting.GRAY));
		CompletableFuture.supplyAsync(() -> {
			try {
				return ParticleExporter.export(capture, dir);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}).whenComplete((written, err) -> mc.execute(() -> {
			if (err != null) {
				LOGGER.error("Particle export failed", err);
				message(Component.literal("Export failed: " + err.getMessage()).withStyle(ChatFormatting.RED));
				return;
			}
			LOGGER.info("Particle capture exported to {}", dir);
			message(Component.literal("Exported: ").withStyle(ChatFormatting.GREEN)
					.append(link("[folder]", dir))
					.append(" ").append(link("[viewer]", dir.resolve("viewer.html")))
					.append(" ").append(link("[analysis]", dir.resolve("analysis.md"))));
		}));
		return true;
	}

	public static String status() {
		Session s = session;
		if (s == null) return null;
		return "Recording: tick " + s.tick + "/" + s.maxTicks + ", " + s.particles.size() + " packets"
				+ (s.levelEvents ? ", " + s.events.size() + " level events" : "")
				+ (s.filteredOut > 0 ? ", " + s.filteredOut + " out of radius" : "");
	}

	private static Component link(String label, Path file) {
		return Component.literal(label).withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenFile(file.toAbsolutePath()))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(file.toAbsolutePath().toString()))));
	}

	private static void message(Component c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("[DevCopy] ").withStyle(ChatFormatting.GRAY).append(c));
		}
	}

	private static int toRgb(Vector3f v) {
		return toRgb(v.x(), v.y(), v.z());
	}

	/** Colors arrive as ints over the network, then are divided by 255: rounding lands exactly. */
	private static int toRgb(float r, float g, float b) {
		return (channel(r) << 16) | (channel(g) << 8) | channel(b);
	}

	private static int channel(float f) {
		return Math.clamp(Math.round(f * 255f), 0, 255);
	}

	@SuppressWarnings("unchecked")
	private static <T extends ParticleOptions, O> O encode(HolderLookup.Provider registries, DynamicOps<O> ops, ParticleOptions opts) {
		MapCodec<T> codec = ((ParticleType<T>) opts.getType()).codec();
		return codec.codec().encodeStart(registries.createSerializationContext(ops), (T) opts).result().orElse(null);
	}

	private static String encodeJson(HolderLookup.Provider registries, ParticleOptions opts) {
		try {
			var json = encode(registries, JsonOps.INSTANCE, opts);
			return json != null ? GSON.toJson(json) : "{}";
		} catch (Exception e) {
			return "{\"error\":" + GSON.toJson(String.valueOf(e.getMessage())) + "}";
		}
	}

	private static String encodeSnbt(HolderLookup.Provider registries, ParticleOptions opts) {
		try {
			Tag tag = encode(registries, NbtOps.INSTANCE, opts);
			// Simple types encode an empty compound: nothing is written after the name
			if (tag == null || (tag instanceof CompoundTag c && c.isEmpty())) return "";
			return tag.toString();
		} catch (Exception e) {
			return "";
		}
	}

	/** Name of the LevelEvent constant (the 26.3 jar is no longer obfuscated, reflection is enough). */
	private static String levelEventName(int id) {
		if (levelEventNames == null) {
			Map<Integer, String> names = new HashMap<>();
			for (Field f : LevelEvent.class.getFields()) {
				if (f.getType() == int.class && Modifier.isStatic(f.getModifiers())) {
					try {
						names.putIfAbsent(f.getInt(null), f.getName());
					} catch (IllegalAccessException ignored) {
					}
				}
			}
			levelEventNames = names;
		}
		return levelEventNames.getOrDefault(id, "?");
	}
}
