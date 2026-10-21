package io.github.jonesjughead.devcopy.particles;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Writes capture.json, viewer.html, analysis.md, replay.java and commands.mcfunction. Plain Java, no Minecraft. */
public final class ParticleExporter {
	private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();
	private static final String VIEWER_TEMPLATE = "/assets/devcopy/particles/viewer.html";
	/** A Java String constant must not exceed 65535 UTF-8 bytes in the constant pool. */
	private static final int JAVA_CHUNK = 60_000;

	private ParticleExporter() {}

	public static List<Path> export(Capture c, Path dir) throws IOException {
		Files.createDirectories(dir);
		List<Path> out = new ArrayList<>();
		out.add(write(dir.resolve("capture.json"), w -> PRETTY.toJson(captureJson(c), w)));
		out.add(write(dir.resolve("viewer.html"), w -> w.write(viewer(c))));
		out.add(write(dir.resolve("analysis.md"), w -> w.write(ParticleAnalyzer.analyze(c))));
		out.add(write(dir.resolve("replay.java"), w -> w.write(replayJava(c))));
		out.add(write(dir.resolve("commands.mcfunction"), w -> w.write(mcfunction(c))));
		return out;
	}

	private interface Body {
		void write(Writer w) throws IOException;
	}

	private static Path write(Path file, Body body) throws IOException {
		try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			body.write(w);
		}
		return file;
	}

	// ---------------------------------------------------------------- capture.json

	static JsonObject captureJson(Capture c) {
		JsonObject root = new JsonObject();
		root.add("meta", meta(c));

		JsonObject notes = new JsonObject();
		notes.addProperty("positions", "x/y/z relative to the origin (player position at start)");
		notes.addProperty("count0", "count = 0: no spread. The client spawns ONE particle with velocity "
				+ "(xDist*xSpeed, yDist*ySpeed, zDist*zSpeed): the dists are a direction. Flag 'directional'.");
		notes.addProperty("countN", "count > 0: count particles, position += gauss*dist and velocity = gauss*speed per axis "
				+ "(randomization DEFAULT). ALTERNATIVE: position += uniform[0,1)*dist, velocity = speed; "
				+ "ALTERNATIVE_WITH_SPEED: velocity = speed*uniform[0,1).");
		notes.addProperty("speed", "26.3: speed per axis (xSpeed/ySpeed/zSpeed). /particle and Paper only send one "
				+ "value, copied to all three axes.");
		notes.addProperty("tick", "client tick relative to the start (END_CLIENT_TICK), ms = real time since the start");
		root.add("notes", notes);

		JsonArray particles = new JsonArray();
		for (Capture.Particle p : c.particles()) particles.add(particleJson(p));
		root.add("particles", particles);

		JsonArray events = new JsonArray();
		for (Capture.LevelEvent e : c.events()) {
			JsonObject o = new JsonObject();
			o.addProperty("tick", e.tick());
			o.addProperty("ms", e.ms());
			o.addProperty("seq", e.seq());
			o.addProperty("type", e.type());
			o.addProperty("name", e.name());
			o.addProperty("x", e.x());
			o.addProperty("y", e.y());
			o.addProperty("z", e.z());
			o.addProperty("data", e.data());
			o.addProperty("global", e.global());
			events.add(o);
		}
		root.add("levelEvents", events);
		return root;
	}

	static JsonObject meta(Capture c) {
		JsonObject meta = new JsonObject();
		meta.addProperty("startedAt", c.startedAt());
		meta.addProperty("server", c.server());
		meta.addProperty("dimension", c.dimension());
		JsonObject origin = new JsonObject();
		origin.addProperty("x", c.originX());
		origin.addProperty("y", c.originY());
		origin.addProperty("z", c.originZ());
		origin.addProperty("yaw", c.originYaw());
		meta.add("origin", origin);
		meta.addProperty("radius", c.radius());
		meta.addProperty("levelEventsCaptured", c.levelEvents());
		meta.addProperty("requestedTicks", c.requestedTicks());
		meta.addProperty("durationTicks", c.durationTicks());
		meta.addProperty("durationMs", c.durationMs());
		meta.addProperty("stopReason", c.stopReason());
		meta.addProperty("totalPackets", c.particles().size());
		meta.addProperty("totalSpawned", c.particles().stream().mapToInt(Capture.Particle::spawned).sum());
		meta.addProperty("directionalPackets", c.particles().stream().filter(Capture.Particle::directional).count());
		meta.addProperty("totalLevelEvents", c.events().size());
		meta.addProperty("filteredOutOfRadius", c.filteredOut());
		Map<String, Integer> types = new LinkedHashMap<>();
		for (Capture.Particle p : c.particles()) types.merge(p.type(), 1, Integer::sum);
		JsonObject t = new JsonObject();
		types.forEach(t::addProperty);
		meta.add("types", t);
		return meta;
	}

	private static JsonObject particleJson(Capture.Particle p) {
		JsonObject o = new JsonObject();
		o.addProperty("tick", p.tick());
		o.addProperty("ms", p.ms());
		o.addProperty("seq", p.seq());
		o.addProperty("type", p.type());
		o.add("options", parse(p.optionsJson()));
		if (p.rgb() >= 0) o.addProperty("color", hex(p.rgb()));
		if (p.rgbTo() >= 0) o.addProperty("colorTo", hex(p.rgbTo()));
		if (!Float.isNaN(p.scale())) o.addProperty("scale", p.scale());
		o.addProperty("x", p.x());
		o.addProperty("y", p.y());
		o.addProperty("z", p.z());
		o.addProperty("xDist", p.xDist());
		o.addProperty("yDist", p.yDist());
		o.addProperty("zDist", p.zDist());
		o.addProperty("xSpeed", p.xSpeed());
		o.addProperty("ySpeed", p.ySpeed());
		o.addProperty("zSpeed", p.zSpeed());
		o.addProperty("count", p.count());
		o.addProperty("directional", p.directional());
		o.addProperty("randomization", p.randomization());
		o.addProperty("overrideLimiter", p.overrideLimiter());
		o.addProperty("alwaysShow", p.alwaysShow());
		return o;
	}

	private static JsonElement parse(String json) {
		try {
			return JsonParser.parseString(json);
		} catch (RuntimeException e) {
			return new JsonObject();
		}
	}

	// ---------------------------------------------------------------- viewer.html

	/** Compact columnar format to keep the HTML light even with tens of thousands of packets. */
	static String viewer(Capture c) throws IOException {
		String template;
		try (InputStream in = ParticleExporter.class.getResourceAsStream(VIEWER_TEMPLATE)) {
			if (in == null) throw new IOException("Template introuvable : " + VIEWER_TEMPLATE);
			template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		JsonObject data = new JsonObject();
		data.add("meta", meta(c));

		List<String> types = new ArrayList<>();
		List<String> options = new ArrayList<>();
		Map<String, Integer> typeIdx = new LinkedHashMap<>();
		Map<String, Integer> optIdx = new LinkedHashMap<>();
		JsonArray rows = new JsonArray();
		for (Capture.Particle p : c.particles()) {
			int ti = typeIdx.computeIfAbsent(p.type(), k -> { types.add(k); return types.size() - 1; });
			int oi = optIdx.computeIfAbsent(p.optionsJson(), k -> { options.add(k); return options.size() - 1; });
			JsonArray r = new JsonArray();
			r.add(p.tick());
			r.add(p.ms());
			r.add(ti);
			r.add(round(p.x()));
			r.add(round(p.y()));
			r.add(round(p.z()));
			r.add(p.xDist());
			r.add(p.yDist());
			r.add(p.zDist());
			r.add(p.xSpeed());
			r.add(p.ySpeed());
			r.add(p.zSpeed());
			r.add(p.count());
			r.add((p.overrideLimiter() ? 1 : 0) | (p.alwaysShow() ? 2 : 0));
			r.add(p.rgb());
			r.add(p.rgbTo());
			r.add(Float.isNaN(p.scale()) ? -1 : p.scale());
			r.add(oi);
			r.add(p.randomization().charAt(0) == 'D' ? 0 : p.randomization().endsWith("SPEED") ? 2 : 1);
			r.add(p.seq());
			rows.add(r);
		}
		data.add("types", COMPACT.toJsonTree(types));
		data.add("options", COMPACT.toJsonTree(options));
		data.add("p", rows);

		JsonArray ev = new JsonArray();
		for (Capture.LevelEvent e : c.events()) {
			JsonArray r = new JsonArray();
			r.add(e.tick());
			r.add(e.type());
			r.add(e.name());
			r.add(e.x());
			r.add(e.y());
			r.add(e.z());
			r.add(e.data());
			r.add(e.global());
			ev.add(r);
		}
		data.add("events", ev);

		// "</" inside a <script> would close the tag
		String json = COMPACT.toJson(data).replace("</", "<\\/");
		return template.replace("/*__CAPTURE_DATA__*/null", json);
	}

	private static double round(double v) {
		return Math.round(v * 10000.0) / 10000.0;
	}

	// ---------------------------------------------------------------- commands.mcfunction

	static String mcfunction(Capture c) {
		StringBuilder sb = new StringBuilder();
		sb.append("# DevCopy - capture of ").append(c.startedAt()).append(" (").append(c.server()).append(", ")
				.append(c.dimension()).append(")\n");
		sb.append("# ").append(c.particles().size()).append(" packets over ").append(c.durationTicks()).append(" ticks.\n");
		sb.append("# Coordinates in ~, relative to the capture origin (run from the same position).\n");
		sb.append("# 26.3 syntax: particle <name>[{options}] <pos> <delta> <speed> <count> [force|normal]\n");
		sb.append("# A .mcfunction runs in a single tick: each '# tick N' block must be called on the right tick\n");
		sb.append("# (e.g. split per tick and chain with /schedule) to keep the timing.\n");
		sb.append("# count 0 = delta is a direction (velocity = delta * speed), not a spread.\n");
		long approx = c.particles().stream().filter(ParticleExporter::notExact).count();
		if (approx > 0) {
			sb.append("# WARNING: ").append(approx).append(" packet(s) cannot be expressed exactly as a command ")
					.append("(different speed per axis or randomization != DEFAULT), marked '# approx'.\n");
		}
		int lastTick = -1;
		for (Capture.Particle p : c.particles()) {
			if (p.tick() != lastTick) {
				sb.append("\n# tick ").append(p.tick()).append(" (+").append(p.ms()).append(" ms)\n");
				lastTick = p.tick();
			}
			if (notExact(p)) {
				sb.append("# approx : speed=").append(num(p.xSpeed())).append('/').append(num(p.ySpeed())).append('/')
						.append(num(p.zSpeed())).append(", randomization=").append(p.randomization()).append('\n');
			}
			sb.append("particle ").append(p.type()).append(p.optionsSnbt())
					.append(" ~").append(num(p.x())).append(" ~").append(num(p.y())).append(" ~").append(num(p.z()))
					.append(' ').append(num(p.xDist())).append(' ').append(num(p.yDist())).append(' ').append(num(p.zDist()))
					.append(' ').append(num(p.xSpeed())).append(' ').append(p.count())
					.append(p.overrideLimiter() ? " force" : " normal").append('\n');
		}
		return sb.toString();
	}

	static boolean notExact(Capture.Particle p) {
		return p.xSpeed() != p.ySpeed() || p.ySpeed() != p.zSpeed() || !p.randomization().equals("DEFAULT");
	}

	// ---------------------------------------------------------------- replay.java

	static String replayJava(Capture c) {
		StringBuilder data = new StringBuilder();
		for (Capture.Particle p : c.particles()) {
			data.append(p.tick()).append('|').append(p.type()).append('|')
					.append(num(p.x())).append('|').append(num(p.y())).append('|').append(num(p.z())).append('|')
					.append(num(p.xDist())).append('|').append(num(p.yDist())).append('|').append(num(p.zDist())).append('|')
					.append(num(p.xSpeed())).append('|').append(p.count()).append('|').append(p.overrideLimiter() ? 1 : 0).append('|')
					.append(p.rgb() >= 0 ? hex(p.rgb()).substring(1) : "-").append('|')
					.append(p.rgbTo() >= 0 ? hex(p.rgbTo()).substring(1) : "-").append('|')
					.append(Float.isNaN(p.scale()) ? "-" : num(p.scale())).append('\n');
		}
		List<String> chunks = new ArrayList<>();
		for (int i = 0; i < data.length(); ) {
			int end = Math.min(data.length(), i + JAVA_CHUNK);
			// Split on a line end
			if (end < data.length()) end = data.lastIndexOf("\n", end - 1) + 1;
			chunks.add(data.substring(i, end));
			i = end;
		}
		long approx = c.particles().stream().filter(ParticleExporter::notExact).count();

		StringBuilder sb = new StringBuilder();
		sb.append("""
				import java.util.ArrayList;
				import java.util.List;

				import org.bukkit.Color;
				import org.bukkit.Location;
				import org.bukkit.NamespacedKey;
				import org.bukkit.Particle;
				import org.bukkit.Registry;
				import org.bukkit.plugin.Plugin;
				import org.bukkit.scheduler.BukkitRunnable;

				/**
				 * Replays the DevCopy capture of %s as-is (%s, %d packets over %d ticks).
				 * Usage : new CapturedParticleReplay(plugin, player.getLocation()).start();
				 * Positions are relative to the origin given to the constructor (= player position at capture time).
				 *
				 * Paper API limits: a single speed ("extra") for all three axes and no RandomizationType.%s
				 */
				public final class CapturedParticleReplay extends BukkitRunnable {
				""".formatted(c.startedAt(), c.server(), c.particles().size(), c.durationTicks(),
				approx > 0 ? "\n * " + approx + " packet(s) of the capture depend on it: approximate rendering for them." : ""));
		for (int i = 0; i < chunks.size(); i++) {
			sb.append("\t// tick|type|x|y|z|dx|dy|dz|speed|count|force|rgb|rgbTo|scale\n");
			sb.append("\tprivate static final String DATA_").append(i).append(" = \"\"\"\n");
			for (String line : chunks.get(i).split("\n")) sb.append("\t\t\t").append(line).append('\n');
			sb.append("\t\t\t\"\"\";\n\n");
		}
		StringBuilder all = new StringBuilder();
		for (int i = 0; i < chunks.size(); i++) all.append(i > 0 ? ", " : "").append("DATA_").append(i);
		sb.append("""
					private record Entry(int tick, Particle particle, double x, double y, double z, double dx, double dy, double dz,
							double speed, int count, boolean force, Object data) {}

					private final Plugin plugin;
					private final Location origin;
					private final List<List<Entry>> byTick = new ArrayList<>();
					private int tick;

					public CapturedParticleReplay(Plugin plugin, Location origin) {
						this.plugin = plugin;
						this.origin = origin.clone();
						for (String chunk : new String[] { %s }) {
							for (String line : chunk.split("\\n")) {
								if (line.isBlank()) continue;
								Entry e = parse(line.split("\\\\|"));
								if (e == null) continue;
								while (byTick.size() <= e.tick()) byTick.add(new ArrayList<>());
								byTick.get(e.tick()).add(e);
							}
						}
					}

					public void start() {
						runTaskTimer(plugin, 0L, 1L);
					}

					@Override
					public void run() {
						if (tick >= byTick.size()) {
							cancel();
							return;
						}
						for (Entry e : byTick.get(tick)) {
							Location loc = origin.clone().add(e.x(), e.y(), e.z());
							origin.getWorld().spawnParticle(e.particle(), loc, e.count(), e.dx(), e.dy(), e.dz(), e.speed(), e.data(), e.force());
						}
						tick++;
					}

					private Entry parse(String[] f) {
						Particle particle = Registry.PARTICLE_TYPE.get(NamespacedKey.fromString(f[1]));
						if (particle == null) {
							plugin.getLogger().warning("Particule inconnue : " + f[1]);
							return null;
						}
						Object data = null;
						Class<?> dataType = particle.getDataType();
						if (dataType == Particle.DustOptions.class) {
							data = new Particle.DustOptions(color(f[11]), Float.parseFloat(f[13]));
						} else if (dataType == Particle.DustTransition.class) {
							data = new Particle.DustTransition(color(f[11]), color(f[12]), Float.parseFloat(f[13]));
						} else if (dataType == Color.class && !f[11].equals("-")) {
							data = color(f[11]);
						} else if (dataType != Void.class) {
							plugin.getLogger().warning("Data not replayable for " + f[1] + " (" + dataType.getSimpleName() + "), skipped");
							return null;
						}
						return new Entry(Integer.parseInt(f[0]), particle,
								Double.parseDouble(f[2]), Double.parseDouble(f[3]), Double.parseDouble(f[4]),
								Double.parseDouble(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]),
								Double.parseDouble(f[8]), Integer.parseInt(f[9]), f[10].equals("1"), data);
					}

					private static Color color(String hex) {
						return Color.fromRGB(Integer.parseInt(hex, 16));
					}
				}
				""".formatted(all.length() > 0 ? all : "\"\""));
		return sb.toString();
	}

	// ---------------------------------------------------------------- utilitaires

	static String hex(int rgb) {
		return String.format("#%06x", rgb & 0xFFFFFF);
	}

	/** Short number without scientific notation, for commands and code. */
	static String num(double v) {
		if (v == Math.rint(v) && Math.abs(v) < 1e9) return Long.toString((long) v);
		String s = String.format(Locale.ROOT, "%.5f", v);
		s = s.replaceAll("0+$", "");
		return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
	}
}
