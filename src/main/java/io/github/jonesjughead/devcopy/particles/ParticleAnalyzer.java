package io.github.jonesjughead.devcopy.particles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

/**
 * Generates analysis.md: emission rate, palette and gradients, per-frame geometry, motion between frames.
 * Simple, readable heuristics: the goal is to give numbers to work from when rewriting the animation.
 *
 * Axes: X east, Y up, Z south. Angles are atan2(z, x) in degrees, so an increasing angle
 * turns clockwise seen from above.
 */
public final class ParticleAnalyzer {
	private static final int MAX_TABLE_ROWS = 40;

	private ParticleAnalyzer() {}

	/** A packet seen as a point. idx = emission rank within its frame. */
	private record Pt(Capture.Particle p, double x, double y, double z, int tick, int idx) {}

	private record Frame(int tick, List<Pt> pts) {}

	private record Circle(double cx, double cz, double r, double relResidual) {}

	private record Shape(String name, String detail) {}

	public static String analyze(Capture c) {
		StringBuilder md = new StringBuilder();
		md.append("# Particle analysis\n\n");
		md.append("Capture of ").append(c.startedAt()).append(" on `").append(c.server()).append("` (`")
				.append(c.dimension()).append("`). Origin ").append(vec(c.originX(), c.originY(), c.originZ()))
				.append(", yaw ").append(f1(c.originYaw())).append("°. Stopped: ").append(c.stopReason()).append(".\n\n");
		md.append("> Axes: positions relative to the origin, X = east, Y = up, Z = south. Angles = atan2(z, x): ")
				.append("an increasing angle = clockwise seen from above.\n\n");

		overview(md, c);
		if (c.particles().isEmpty()) {
			md.append("\nNo particle captured.\n");
			levelEvents(md, c);
			return md.toString();
		}

		// Dust particles are analyzed first (the main use case), otherwise everything
		List<Capture.Particle> focus = c.particles().stream().filter(Capture.Particle::isDust).toList();
		String focusLabel = "dust / dust_color_transition";
		if (focus.isEmpty()) {
			focus = c.particles();
			focusLabel = "all types";
		}
		md.append("\nThe following sections cover: **").append(focusLabel).append("** (").append(focus.size())
				.append(" packets).\n");

		List<Frame> frames = frames(focus);
		List<Pt> all = frames.stream().flatMap(fr -> fr.pts().stream()).toList();
		int interval = emission(md, frames, focus);
		double[] center = center(md, all);
		palette(md, focus, all, frames, center);
		geometry(md, frames, all, center);
		motion(md, frames, center, interval);
		directional(md, all, center);
		levelEvents(md, c);
		return md.toString();
	}

	// ================================================================ overview

	private static void overview(StringBuilder md, Capture c) {
		int spawned = c.particles().stream().mapToInt(Capture.Particle::spawned).sum();
		md.append("## Overview\n\n");
		md.append("| | |\n|---|---|\n");
		md.append("| Duration | ").append(c.durationTicks()).append(" ticks (").append(c.durationMs()).append(" ms, requested ")
				.append(c.requestedTicks()).append(")|\n");
		md.append("| Packets | ").append(c.particles().size()).append(" |\n");
		md.append("| Particles created on the client | ").append(spawned).append(" (count = 0 counts as 1) |\n");
		md.append("| Capture radius | ").append(c.radius() > 0 ? f2(c.radius()) + " blocks" : "unlimited").append(" |\n");
		md.append("| Out of radius (ignored) | ").append(c.filteredOut()).append(" |\n");
		md.append("| Level events | ").append(c.levelEvents() ? String.valueOf(c.events().size()) : "not captured").append(" |\n\n");

		Map<String, int[]> types = new TreeMap<>();
		for (Capture.Particle p : c.particles()) {
			int[] t = types.computeIfAbsent(p.type(), k -> new int[3]);
			t[0]++;
			t[1] += p.spawned();
			if (p.directional()) t[2]++;
		}
		md.append("| Type | Packets | Particles | count = 0 |\n|---|---:|---:|---:|\n");
		types.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).forEach(e ->
				md.append("| `").append(e.getKey()).append("` | ").append(e.getValue()[0]).append(" | ")
						.append(e.getValue()[1]).append(" | ").append(e.getValue()[2]).append(" |\n"));

		Map<String, Integer> rando = new TreeMap<>();
		int force = 0, always = 0, perAxis = 0;
		for (Capture.Particle p : c.particles()) {
			rando.merge(p.randomization(), 1, Integer::sum);
			if (p.overrideLimiter()) force++;
			if (p.alwaysShow()) always++;
			if (p.xSpeed() != p.ySpeed() || p.ySpeed() != p.zSpeed()) perAxis++;
		}
		md.append("\nRandomization: ").append(rando).append(". overrideLimiter (force): ").append(force)
				.append(", alwaysShow: ").append(always).append(", different speed per axis: ").append(perAxis).append(".\n");
		long directional = c.particles().stream().filter(Capture.Particle::directional).count();
		if (directional > 0) {
			md.append("\n> **count = 0 on ").append(directional).append(" packet(s)**: there, xDist/yDist/zDist are not a ")
					.append("spread but a direction. The client creates a single particle, at the exact position, with the ")
					.append("initial velocity (xDist·xSpeed, yDist·ySpeed, zDist·zSpeed). See the *Directional packets* section.\n");
		}
	}

	// ================================================================ emission rate

	private static List<Frame> frames(List<Capture.Particle> ps) {
		Map<Integer, List<Pt>> byTick = new TreeMap<>();
		for (Capture.Particle p : ps) {
			List<Pt> l = byTick.computeIfAbsent(p.tick(), k -> new ArrayList<>());
			l.add(new Pt(p, p.x(), p.y(), p.z(), p.tick(), l.size()));
		}
		List<Frame> out = new ArrayList<>();
		byTick.forEach((t, l) -> out.add(new Frame(t, l)));
		return out;
	}

	/** Returns the dominant interval between emissions, in ticks. */
	private static int emission(StringBuilder md, List<Frame> frames, List<Capture.Particle> focus) {
		md.append("\n## Emission rate\n\n");
		Map<Integer, Integer> gaps = new TreeMap<>();
		for (int i = 1; i < frames.size(); i++) gaps.merge(frames.get(i).tick() - frames.get(i - 1).tick(), 1, Integer::sum);
		int dominant = gaps.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(1);

		int[] perFrame = frames.stream().mapToInt(f -> f.pts().size()).toArray();
		int[] spawnedPerFrame = frames.stream().mapToInt(f -> f.pts().stream().mapToInt(p -> p.p().spawned()).sum()).toArray();
		Map<Integer, Integer> sizes = new TreeMap<>();
		for (int n : perFrame) sizes.merge(n, 1, Integer::sum);
		int modeSize = sizes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);

		md.append("- Ticks with emission: **").append(frames.size()).append("**, from tick ").append(frames.getFirst().tick())
				.append(" to tick ").append(frames.getLast().tick()).append(".\n");
		if (frames.size() > 1) {
			int share = gaps.get(dominant) * 100 / (frames.size() - 1);
			md.append("- Emission **every ").append(dominant).append(" tick").append(dominant > 1 ? "s" : "")
					.append("** (").append(share).append("% of the intervals, i.e. ").append(f2(20.0 / dominant))
					.append(" emissions/s).\n");
			md.append("- Interval distribution (ticks → occurrences): ").append(gaps).append(".\n");
			if (share < 80) {
				md.append("- Irregular intervals: server/network lag or several unsynchronized emitters. ")
						.append("The client can also process the packets of two server ticks in the same client tick.\n");
			}
		}
		md.append("- Packets per emission: min ").append(Arrays.stream(perFrame).min().orElse(0))
				.append(", mean ").append(f2(Arrays.stream(perFrame).average().orElse(0)))
				.append(", max ").append(Arrays.stream(perFrame).max().orElse(0))
				.append(", most frequent **").append(modeSize).append("**.\n");
		md.append("- Particles created per emission (count included): mean ")
				.append(f2(Arrays.stream(spawnedPerFrame).average().orElse(0))).append(".\n");

		// Grouping per frame, as in the viewer
		md.append("\n| Ticks per frame | Non-empty frames | Packets per frame (mean) |\n|---:|---:|---:|\n");
		for (int g : new int[] { 1, 2, 4 }) {
			Map<Integer, Integer> grouped = new TreeMap<>();
			for (Capture.Particle p : focus) grouped.merge(p.tick() / g, 1, Integer::sum);
			md.append("| ").append(g).append(" | ").append(grouped.size()).append(" | ")
					.append(f2(grouped.values().stream().mapToInt(Integer::intValue).average().orElse(0))).append(" |\n");
		}
		return dominant;
	}

	// ================================================================ center

	private static double[] center(StringBuilder md, List<Pt> all) {
		md.append("\n## Animation center\n\n");
		double[] centroid = centroid(all);
		Circle fit = fitCircleXZ(all);
		double[] center;
		if (fit != null && fit.relResidual() < 0.2 && fit.r() > 0.05) {
			center = new double[] { fit.cx(), centroid[1], fit.cz() };
			md.append("Horizontal circle fitted on all points: center ").append(vec(fit.cx(), centroid[1], fit.cz()))
					.append(", radius **").append(f3(fit.r())).append("**, relative residual ").append(pct(fit.relResidual()))
					.append(". This center is the reference for angles.\n");
		} else {
			center = centroid;
			md.append("No clear global horizontal circle, reference = centroid ").append(vec(centroid[0], centroid[1], centroid[2]))
					.append(".\n");
		}
		md.append("Offset from the player: ").append(f2(Math.hypot(center[0], center[2])))
				.append(" block(s) horizontally, ").append(f2(center[1])).append(" in Y (the player is at Y = 0, their feet).\n");
		return center;
	}

	// ================================================================ palette

	private static void palette(StringBuilder md, List<Capture.Particle> focus, List<Pt> all, List<Frame> frames, double[] center) {
		md.append("\n## Palette\n\n");
		record Key(int rgb, int rgbTo) {}
		Map<Key, int[]> colors = new LinkedHashMap<>();
		Map<Key, List<Float>> scales = new LinkedHashMap<>();
		for (Capture.Particle p : focus) {
			if (p.rgb() < 0) continue;
			Key k = new Key(p.rgb(), p.rgbTo());
			int[] s = colors.computeIfAbsent(k, x -> new int[] { 0, p.tick(), p.tick() });
			s[0]++;
			s[2] = p.tick();
			List<Float> sc = scales.computeIfAbsent(k, x -> new ArrayList<>());
			if (!Float.isNaN(p.scale()) && !sc.contains(p.scale())) sc.add(p.scale());
		}
		if (colors.isEmpty()) {
			md.append("No color in these packets.\n");
			return;
		}
		int total = colors.values().stream().mapToInt(s -> s[0]).sum();
		md.append(colors.size()).append(" unique color(s).\n\n");
		md.append("| Color | RGB | Hue | Occurrences | % | Ticks | Scale |\n|---|---|---:|---:|---:|---|---|\n");
		List<Map.Entry<Key, int[]>> sorted = new ArrayList<>(colors.entrySet());
		sorted.sort((a, b) -> b.getValue()[0] - a.getValue()[0]);
		int shown = 0;
		for (Map.Entry<Key, int[]> e : sorted) {
			if (shown++ >= MAX_TABLE_ROWS * 2) {
				md.append("| … ").append(sorted.size() - MAX_TABLE_ROWS * 2).append(" more | | | | | | |\n");
				break;
			}
			Key k = e.getKey();
			int[] s = e.getValue();
			md.append("| `").append(ParticleExporter.hex(k.rgb())).append(k.rgbTo() >= 0 ? " → " + ParticleExporter.hex(k.rgbTo()) : "")
					.append("` | ").append(rgbText(k.rgb())).append(k.rgbTo() >= 0 ? " → " + rgbText(k.rgbTo()) : "")
					.append(" | ").append(f1(hue(k.rgb()))).append("° | ").append(s[0]).append(" | ")
					.append(f1(s[0] * 100.0 / total)).append(" | ").append(s[1]).append("–").append(s[2]).append(" | ")
					.append(scales.get(k).stream().map(ParticleAnalyzer::f2).toList()).append(" |\n");
		}

		List<Pt> colored = all.stream().filter(p -> p.p().rgb() >= 0).toList();
		if (colors.size() < 2) {
			md.append("\nSingle color: no gradient.\n");
			return;
		}
		md.append("\n### Gradient\n\n");
		md.append("For each variable, share of the color (RGB) variance explained by slicing the variable into bins ")
				.append("(η², 1 = the color is entirely determined by the variable). Also works for non-linear gradients ")
				.append("and looping rainbows.\n\n");
		md.append("| Variable | η² | Bins |\n|---|---:|---:|\n");

		List<Var> vars = List.of(
				new Var("time (tick)", "tick", Pt::tick, 0, 0),
				new Var("angle around the center", "°", p -> angle(p, center), 0, 360),
				new Var("height (Y)", "block", Pt::y, 0, 0),
				new Var("rank in the frame", "rank", Pt::idx, 0, 0));
		Var best = null;
		Binning bestBins = null;
		double bestEta = -1;
		for (Var v : vars) {
			Binning b = bin(colored, v);
			double eta = b.eta2(colored);
			md.append("| ").append(v.name()).append(" | ").append(f3(eta)).append(" | ").append(b.count()).append(" |\n");
			if (eta > bestEta + 1e-9) {
				bestEta = eta;
				best = v;
				bestBins = b;
			}
		}
		if (best == null || bestEta < 0.7) {
			md.append("\nNo clear gradient: random colors, per emitter, or alternating (see the order in the palette).\n");
			return;
		}
		md.append("\n**The color follows: ").append(best.name()).append("** (η² ").append(f3(bestEta)).append(").\n\n");

		// Dominant color per bin, in variable order, merging repeats
		List<double[]> seq = new ArrayList<>(); // {bin center, rgb}
		for (int i = 0; i < bestBins.count(); i++) {
			int mode = bestBins.modalColor(colored, i);
			if (mode < 0) continue;
			if (seq.isEmpty() || (int) seq.getLast()[1] != mode) seq.add(new double[] { bestBins.center(i), mode });
		}
		int first = (int) seq.getFirst()[1], last = (int) seq.getLast()[1];
		md.append("- Start: `").append(ParticleExporter.hex(first)).append("` ").append(rgbText(first))
				.append(" at ").append(f2(seq.getFirst()[0])).append(' ').append(best.unit()).append('\n');
		md.append("- End: `").append(ParticleExporter.hex(last)).append("` ").append(rgbText(last))
				.append(" at ").append(f2(seq.getLast()[0])).append(' ').append(best.unit()).append('\n');
		if (seq.size() > 1) {
			int n = seq.size() - 1;
			double span = seq.getLast()[0] - seq.getFirst()[0];
			int[] a = rgb(first), b = rgb(last);
			double[] hueSteps = new double[n];
			for (int i = 0; i < n; i++) hueSteps[i] = wrap(hue((int) seq.get(i + 1)[1]) - hue((int) seq.get(i)[1]));
			md.append("- ").append(seq.size()).append(" color steps, a new one every ~").append(f2(span / n))
					.append(' ').append(best.unit()).append('\n');
			md.append("- Mean RGB step (start → end): ΔR ").append(signed((b[0] - a[0]) / (double) n)).append(", ΔG ")
					.append(signed((b[1] - a[1]) / (double) n)).append(", ΔB ").append(signed((b[2] - a[2]) / (double) n)).append('\n');
			double hueStep = median(hueSteps);
			boolean hueCycle = Math.abs(hueStep) > 2 && Arrays.stream(hueSteps).allMatch(h -> Math.signum(h) == Math.signum(hueStep));
			md.append("- Median hue step: ").append(signed(hueStep)).append("° per step")
					.append(hueCycle ? " → **hue cycle** (rainbow), " + f1(Math.abs(hueStep) * seq.size()) + "° covered" : "")
					.append('\n');
			md.append("\nSequence: ");
			for (int i = 0; i < Math.min(seq.size(), 48); i++) {
				md.append(i > 0 ? " → " : "").append('`').append(ParticleExporter.hex((int) seq.get(i)[1])).append('`');
			}
			if (seq.size() > 48) md.append(" → …");
			md.append('\n');
		}
	}

	/** lo/hi set the range of a bounded variable (angle); 0/0 = bounds taken from the data. */
	private record Var(String name, String unit, ToDoubleFunction<Pt> fn, double lo, double hi) {}

	/** Slices a variable into equal-width bins, with at least ~4 points per bin. */
	private record Binning(Var var, double lo, double width, int count) {
		int of(Pt p) {
			return (int) Math.clamp((long) Math.floor((var.fn().applyAsDouble(p) - lo) / width), 0, count - 1);
		}

		double center(int i) {
			return lo + (i + 0.5) * width;
		}

		/** Share of the RGB variance explained by the bins. */
		double eta2(List<Pt> pts) {
			double[] mean = new double[3];
			double[][] sum = new double[count][3];
			int[] n = new int[count];
			for (Pt p : pts) {
				int[] c = rgb(p.p().rgb());
				int b = of(p);
				n[b]++;
				for (int k = 0; k < 3; k++) {
					sum[b][k] += c[k];
					mean[k] += c[k];
				}
			}
			for (int k = 0; k < 3; k++) mean[k] /= pts.size();
			double total = 0, between = 0;
			for (Pt p : pts) {
				int[] c = rgb(p.p().rgb());
				for (int k = 0; k < 3; k++) total += sq(c[k] - mean[k]);
			}
			for (int b = 0; b < count; b++) {
				if (n[b] == 0) continue;
				for (int k = 0; k < 3; k++) between += n[b] * sq(sum[b][k] / n[b] - mean[k]);
			}
			return total == 0 ? 0 : between / total;
		}

		int modalColor(List<Pt> pts, int bin) {
			Map<Integer, Integer> counts = new LinkedHashMap<>();
			for (Pt p : pts) if (of(p) == bin) counts.merge(p.p().rgb(), 1, Integer::sum);
			return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(-1);
		}
	}

	private static Binning bin(List<Pt> pts, Var v) {
		double lo = v.lo(), hi = v.hi();
		if (lo == hi) {
			double[] x = pts.stream().mapToDouble(v.fn()).toArray();
			lo = min(x);
			hi = max(x);
		}
		long distinct = pts.stream().mapToDouble(v.fn()).distinct().count();
		// At least ~4 points per bin, otherwise η² tends to 1 without proving anything
		int count = (int) Math.max(1, Math.min(Math.min(distinct, 64), pts.size() / 4));
		double width = hi > lo ? (hi - lo) / count : 1;
		// Integer values (tick, rank): one bin per value when possible
		boolean integers = v.hi() == 0 && pts.stream().allMatch(p -> v.fn().applyAsDouble(p) == Math.rint(v.fn().applyAsDouble(p)));
		if (integers && hi - lo + 1 <= count) {
			width = 1;
			count = (int) (hi - lo) + 1;
			lo -= 0.5;
		}
		return new Binning(v, lo, width, count);
	}

	// ================================================================ geometry

	private static void geometry(StringBuilder md, List<Frame> frames, List<Pt> all, double[] center) {
		md.append("\n## Geometry\n\n");
		Shape cumul = classify(all, center);
		md.append("**Cumulative shape over the whole capture: ").append(cumul.name()).append("**");
		if (!cumul.detail().isEmpty()) md.append(" — ").append(cumul.detail());
		md.append(".\n\n");

		double[] ys = all.stream().mapToDouble(Pt::y).toArray();
		md.append("Height (relative to the feet): ").append(f3(min(ys))).append(" → ").append(f3(max(ys)))
				.append(" (range ").append(f3(max(ys) - min(ys))).append(").\n\n");

		Map<String, Integer> shapes = new LinkedHashMap<>();
		List<String> rows = new ArrayList<>();
		for (Frame fr : frames) {
			Shape s = classify(fr.pts(), center);
			shapes.merge(s.name(), 1, Integer::sum);
			if (rows.size() < MAX_TABLE_ROWS) {
				double[] c = centroid(fr.pts());
				double[] r = radii(fr.pts(), center);
				double[] y = fr.pts().stream().mapToDouble(Pt::y).toArray();
				rows.add("| " + fr.tick() + " | " + fr.pts().size() + " | " + vec(c[0], c[1], c[2]) + " | " + f3(mean(r))
						+ " | " + f3(min(y)) + " → " + f3(max(y)) + " | " + s.name() + (s.detail().isEmpty() ? "" : " (" + s.detail() + ")")
						+ " |");
			}
		}
		md.append("Shapes per frame (1 frame = 1 tick with emission): ").append(shapes).append(".\n\n");
		md.append("| Tick | Points | Centroid | Mean radius | Height | Shape |\n|---:|---:|---|---:|---|---|\n");
		rows.forEach(r -> md.append(r).append('\n'));
		if (frames.size() > MAX_TABLE_ROWS) md.append("| … | ").append(frames.size() - MAX_TABLE_ROWS).append(" more frames | | | | |\n");

		// Angular spacing in a typical frame
		// Frame of the most frequent size, without the points sitting on the center (no angle)
		Map<Integer, Integer> sizeCounts = new TreeMap<>();
		for (Frame fr : frames) sizeCounts.merge(fr.pts().size(), 1, Integer::sum);
		int modeSize = sizeCounts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
		Frame typical = frames.stream().filter(f -> f.pts().size() == modeSize).findFirst().orElseThrow();
		double typicalR = median(radii(typical.pts(), center));
		double[] angles = typical.pts().stream()
				.filter(p -> Math.hypot(p.x() - center[0], p.z() - center[2]) > 0.2 * typicalR)
				.mapToDouble(p -> angle(p, center)).sorted().toArray();
		if (angles.length >= 3) {
			double[] gaps = new double[angles.length];
			for (int i = 0; i < angles.length; i++) {
				gaps[i] = (i + 1 < angles.length ? angles[i + 1] : angles[0] + 360) - angles[i];
			}
			double gap = median(gaps);
			md.append("\nTypical frame (tick ").append(typical.tick()).append(", ").append(angles.length)
					.append(" off-center points): median angular gap **").append(f2(gap)).append("°** between neighbours");
			double full = 360.0 / angles.length;
			if (Math.abs(gap - full) < full * 0.05 && max(gaps) < full * 1.5) {
				md.append(" = 360° / ").append(angles.length).append(", regular full circle");
			} else if (max(gaps) > 2 * gap) {
				md.append(", arc of ~").append(f1(360 - max(gaps) + gap)).append("° (gap of ").append(f1(max(gaps))).append("°)");
			}
			md.append(".\n");
		}
	}

	/** Deliberately simple classification, meant as a hint. */
	private static Shape classify(List<Pt> pts, double[] refCenter) {
		int n = pts.size();
		if (n == 1) return new Shape("point", "");
		if (n == 2) return new Shape("2 points", "distance " + f3(dist(pts.get(0), pts.get(1))));
		double[] c = centroid(pts);
		double extent = 0;
		for (Pt p : pts) extent = Math.max(extent, Math.sqrt(sq(p.x() - c[0]) + sq(p.y() - c[1]) + sq(p.z() - c[2])));
		if (extent < 0.05) return new Shape("cluster", "all within < 0.05 block");

		double[][] cov = covariance(pts, c);
		double[][] vecs = new double[3][3];
		double[] eig = jacobi(cov, vecs); // sorted descending
		if (Math.sqrt(Math.max(eig[1], 0)) < 0.04 * extent) {
			double[] d = { vecs[0][0], vecs[1][0], vecs[2][0] };
			return new Shape("line", "length ~" + f2(2 * extent) + ", direction " + vec(d[0], d[1], d[2]));
		}

		double[] ys = pts.stream().mapToDouble(Pt::y).toArray();
		double ySpread = max(ys) - min(ys);
		Circle circle = fitCircleXZ(pts);
		if (circle != null && circle.relResidual() < 0.12) {
			String base = "radius " + f3(circle.r()) + ", center " + vec(circle.cx(), c[1], circle.cz());
			if (ySpread < Math.max(0.1, 0.2 * circle.r())) return new Shape("circle", base);
			// Helix: the (unwrapped) angle changes linearly with height
			List<Pt> byY = new ArrayList<>(pts);
			byY.sort(Comparator.comparingDouble(Pt::y));
			double[] yy = byY.stream().mapToDouble(Pt::y).toArray();
			double[] aa = unwrap(byY.stream().mapToDouble(p -> Math.toDegrees(Math.atan2(p.z() - circle.cz(), p.x() - circle.cx()))).toArray());
			double[] fit = linreg(yy, aa);
			if (fit[2] > 0.9 && Math.abs(fit[0]) > 1) {
				return new Shape("helix", base + ", pitch " + f2(360 / Math.abs(fit[0])) + " block/turn, "
						+ (fit[0] > 0 ? "clockwise" : "counter-clockwise") + " going up");
			}
			return new Shape("cylinder / stacked rings", base + ", height " + f2(ySpread));
		}

		Circle sphere = fitSphere(pts);
		if (sphere != null && sphere.relResidual() < 0.1 && ySpread > sphere.r()) {
			return new Shape("sphere", "radius " + f3(sphere.r()));
		}

		double planarity = Math.sqrt(Math.max(eig[2], 0)) / extent;
		if (planarity < 0.04) {
			double[] r = pts.stream().mapToDouble(p -> Math.sqrt(sq(p.x() - c[0]) + sq(p.y() - c[1]) + sq(p.z() - c[2]))).toArray();
			double[] nrm = { vecs[0][2], vecs[1][2], vecs[2][2] };
			if (std(r) / mean(r) < 0.1) {
				return new Shape("tilted circle", "radius " + f3(mean(r)) + ", normal " + vec(nrm[0], nrm[1], nrm[2]));
			}
			return new Shape("planar shape", "normal " + vec(nrm[0], nrm[1], nrm[2]));
		}
		return new Shape("cloud / free shape", "extent " + f2(extent));
	}

	// ================================================================ motion

	private static void motion(StringBuilder md, List<Frame> frames, double[] center, int interval) {
		md.append("\n## Motion between frames\n\n");
		if (frames.size() < 3) {
			md.append("Not enough frames.\n");
			return;
		}
		// Rotation: pairing by rank when frames have the same number of points, otherwise circular mean
		List<Double> omegas = new ArrayList<>();
		List<Double> dys = new ArrayList<>();
		for (int i = 1; i < frames.size(); i++) {
			Frame a = frames.get(i - 1), b = frames.get(i);
			int dt = b.tick() - a.tick();
			if (dt <= 0) continue;
			int na = a.pts().size(), nb = b.pts().size();
			// Close sizes (an extra point now and then): pair the first ranks
			if (Math.abs(na - nb) <= Math.max(1, Math.max(na, nb) / 5)) {
				List<Double> da = new ArrayList<>(), dy = new ArrayList<>();
				for (int k = 0; k < Math.min(na, nb); k++) {
					Pt pa = a.pts().get(k), pb = b.pts().get(k);
					dy.add(pb.y() - pa.y());
					// A point on the center has no angle
					if (Math.hypot(pa.x() - center[0], pa.z() - center[2]) < 0.05
							|| Math.hypot(pb.x() - center[0], pb.z() - center[2]) < 0.05) continue;
					da.add(wrap(angle(pb, center) - angle(pa, center)));
				}
				if (!da.isEmpty()) omegas.add(median(da.stream().mapToDouble(Double::doubleValue).toArray()) / dt);
				dys.add(median(dy.stream().mapToDouble(Double::doubleValue).toArray()) / dt);
			} else {
				omegas.add(wrap(circularMean(b.pts(), center) - circularMean(a.pts(), center)) / dt);
			}
		}
		double[] om = omegas.stream().mapToDouble(Double::doubleValue).toArray();
		double w = median(om);
		// Median absolute deviation: robust to a few badly paired frames
		double spread = median(Arrays.stream(om).map(o -> Math.abs(o - w)).toArray());
		md.append("### Rotation\n\n");
		if (Math.abs(w) < 0.2) {
			md.append("No measurable rotation (median ").append(f2(w)).append("°/tick).\n");
		} else {
			md.append("- Median angular velocity: **").append(signed(w)).append("°/tick** (")
					.append(w > 0 ? "clockwise" : "counter-clockwise").append(" seen from above), i.e. ").append(f1(w * 20)).append("°/s.\n");
			md.append("- Median deviation between frames: ").append(f2(spread)).append("°/tick")
					.append(spread < Math.abs(w) * 0.15 ? " → steady rotation." : " → irregular (or aliasing: more than ~180° between two emissions cannot be told apart).").append('\n');
			md.append("- A full turn in ").append(f1(360 / Math.abs(w))).append(" ticks (").append(f2(360 / Math.abs(w) / 20)).append(" s)")
					.append(interval > 1 ? ", i.e. " + f2(w * interval) + "° per emission (every " + interval + " ticks)" : "").append(".\n");
		}

		md.append("\n### Rise in Y\n\n");
		double[] ticks = frames.stream().mapToDouble(Frame::tick).toArray();
		double[] meanY = frames.stream().mapToDouble(f -> mean(f.pts().stream().mapToDouble(Pt::y).toArray())).toArray();
		double[] fy = linreg(ticks, meanY);
		md.append("- Mean height per frame: slope **").append(signed(fy[0])).append(" block/tick** (R² ").append(f3(fy[2]))
				.append("), from ").append(f3(meanY[0])).append(" to ").append(f3(meanY[meanY.length - 1])).append(".\n");
		if (!dys.isEmpty()) {
			md.append("- Per paired point (same rank from one frame to the next): median ")
					.append(signed(median(dys.stream().mapToDouble(Double::doubleValue).toArray()))).append(" block/tick.\n");
		}
		if (fy[2] < 0.5 && max(meanY) - min(meanY) > 0.1) {
			md.append("- Non-linear height: ").append(periodText(ticks, meanY)).append('\n');
		}

		md.append("\n### Radius\n\n");
		// Median rather than mean: a point on the center must not look like a pulsation
		double[] meanR = frames.stream().mapToDouble(f -> median(radii(f.pts(), center))).toArray();
		double rMean = mean(meanR), amp = (max(meanR) - min(meanR)) / 2;
		md.append("- Median radius per frame: min ").append(f3(min(meanR))).append(", max ").append(f3(max(meanR)))
				.append(", mean ").append(f3(rMean)).append(".\n");
		double[] fr = linreg(ticks, meanR);
		if (amp < Math.max(0.02, rMean * 0.05)) {
			md.append("- Constant radius (no pulsation).\n");
		} else if (fr[2] > 0.8) {
			md.append("- Radius ").append(fr[0] > 0 ? "growing" : "shrinking").append(" linearly: ")
					.append(signed(fr[0])).append(" block/tick (R² ").append(f3(fr[2])).append(").\n");
		} else {
			md.append("- **Pulsation**: amplitude ±").append(f3(amp)).append(" around ").append(f3((max(meanR) + min(meanR)) / 2))
					.append(". ").append(periodText(ticks, meanR)).append('\n');
		}
	}

	private static String periodText(double[] ticks, double[] values) {
		double period = period(ticks, values);
		return period > 0 ? "Estimated period **" + f1(period) + " ticks** (" + f2(period / 20) + " s)."
				: "No clear period over the capture.";
	}

	/** Autocorrelation on the series resampled per tick. Returns 0 if nothing clear. */
	private static double period(double[] ticks, double[] values) {
		int len = (int) (ticks[ticks.length - 1] - ticks[0]) + 1;
		if (len < 8) return 0;
		double[] s = new double[len];
		int j = 0;
		for (int t = 0; t < len; t++) {
			double tt = ticks[0] + t;
			while (j + 1 < ticks.length && ticks[j + 1] <= tt) j++;
			if (j + 1 < ticks.length && ticks[j] < tt) {
				double k = (tt - ticks[j]) / (ticks[j + 1] - ticks[j]);
				s[t] = values[j] + k * (values[j + 1] - values[j]);
			} else {
				s[t] = values[j];
			}
		}
		double m = mean(s);
		double var = 0;
		for (double v : s) var += sq(v - m);
		if (var == 0) return 0;
		int maxLag = len * 2 / 3;
		double[] r = new double[maxLag + 1];
		for (int lag = 0; lag <= maxLag; lag++) {
			double acc = 0;
			for (int t = 0; t + lag < len; t++) acc += (s[t] - m) * (s[t + lag] - m);
			r[lag] = acc / var * len / (len - lag);
		}
		// First negative trough, then first peak above 0.3 after it
		int lag = 1;
		while (lag < maxLag && !(r[lag] < 0 && r[lag] <= r[lag + 1])) lag++;
		for (; lag < maxLag; lag++) {
			if (r[lag] > 0.3 && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) return lag;
		}
		return 0;
	}

	// ================================================================ count = 0

	private static void directional(StringBuilder md, List<Pt> all, double[] center) {
		List<Pt> dir = all.stream().filter(p -> p.p().directional()).toList();
		if (dir.isEmpty()) return;
		md.append("\n## Directional packets (count = 0)\n\n");
		md.append(dir.size()).append(" packet(s). Initial velocity = (xDist·xSpeed, yDist·ySpeed, zDist·zSpeed), in block/tick.\n\n");
		double[] speed = new double[dir.size()], radial = new double[dir.size()], up = new double[dir.size()];
		for (int i = 0; i < dir.size(); i++) {
			Capture.Particle p = dir.get(i).p();
			double vx = p.xDist() * p.xSpeed(), vy = p.yDist() * p.ySpeed(), vz = p.zDist() * p.zSpeed();
			double v = Math.sqrt(vx * vx + vy * vy + vz * vz);
			speed[i] = v;
			double rx = dir.get(i).x() - center[0], rz = dir.get(i).z() - center[2];
			double rl = Math.hypot(rx, rz);
			radial[i] = v > 0 && rl > 1e-6 ? (vx * rx + vz * rz) / (v * rl) : 0;
			up[i] = v > 0 ? vy / v : 0;
		}
		md.append("- Magnitude: min ").append(f3(min(speed))).append(", mean ").append(f3(mean(speed))).append(", max ").append(f3(max(speed))).append('\n');
		md.append("- Mean cosine with the radial direction (center → point): ").append(f2(mean(radial)))
				.append(mean(radial) > 0.8 ? " → thrown outwards" : mean(radial) < -0.8 ? " → pulled towards the center" : "").append('\n');
		md.append("- Mean vertical component (cos with +Y): ").append(f2(mean(up)))
				.append(mean(up) > 0.8 ? " → upwards" : mean(up) < -0.8 ? " → downwards" : "").append('\n');
	}

	// ================================================================ level events

	private static void levelEvents(StringBuilder md, Capture c) {
		if (!c.levelEvents()) return;
		md.append("\n## Level events\n\n");
		if (c.events().isEmpty()) {
			md.append("No ClientboundLevelEventPacket during the capture: the effect does not go through it.\n");
			return;
		}
		Map<String, int[]> byType = new TreeMap<>();
		Map<String, List<Integer>> data = new TreeMap<>();
		for (Capture.LevelEvent e : c.events()) {
			String k = e.type() + " " + e.name();
			int[] s = byType.computeIfAbsent(k, x -> new int[] { 0, e.tick(), e.tick() });
			s[0]++;
			s[2] = e.tick();
			List<Integer> d = data.computeIfAbsent(k, x -> new ArrayList<>());
			if (d.size() < 8 && !d.contains(e.data())) d.add(e.data());
		}
		md.append("| Type | Occurrences | Ticks | data (sample) |\n|---|---:|---|---|\n");
		byType.forEach((k, s) -> md.append("| `").append(k).append("` | ").append(s[0]).append(" | ").append(s[1]).append("–")
				.append(s[2]).append(" | ").append(data.get(k)).append(" |\n"));
	}

	// ================================================================ maths

	private static double angle(Pt p, double[] center) {
		double a = Math.toDegrees(Math.atan2(p.z() - center[2], p.x() - center[0]));
		return a < 0 ? a + 360 : a;
	}

	private static double circularMean(List<Pt> pts, double[] center) {
		double s = 0, c = 0;
		for (Pt p : pts) {
			double a = Math.toRadians(angle(p, center));
			s += Math.sin(a);
			c += Math.cos(a);
		}
		return Math.toDegrees(Math.atan2(s, c));
	}

	private static double wrap(double deg) {
		deg = ((deg + 180) % 360 + 360) % 360 - 180;
		return deg;
	}

	private static double[] unwrap(double[] deg) {
		double[] out = deg.clone();
		for (int i = 1; i < out.length; i++) out[i] = out[i - 1] + wrap(deg[i] - deg[i - 1]);
		return out;
	}

	private static double[] radii(List<Pt> pts, double[] center) {
		return pts.stream().mapToDouble(p -> Math.hypot(p.x() - center[0], p.z() - center[2])).toArray();
	}

	private static double[] centroid(List<Pt> pts) {
		double x = 0, y = 0, z = 0;
		for (Pt p : pts) {
			x += p.x();
			y += p.y();
			z += p.z();
		}
		int n = Math.max(1, pts.size());
		return new double[] { x / n, y / n, z / n };
	}

	/** Algebraic (Kåsa) circle fit in the XZ plane. */
	private static Circle fitCircleXZ(List<Pt> pts) {
		if (pts.size() < 3) return null;
		double[][] a = new double[3][4];
		for (Pt p : pts) {
			double[] row = { p.x(), p.z(), 1 };
			double rhs = -(p.x() * p.x() + p.z() * p.z());
			accumulate(a, row, rhs);
		}
		double[] s = solve(a);
		if (s == null) return null;
		double cx = -s[0] / 2, cz = -s[1] / 2;
		double r2 = cx * cx + cz * cz - s[2];
		if (r2 <= 0) return null;
		double r = Math.sqrt(r2);
		double res = 0;
		for (Pt p : pts) res += sq(Math.hypot(p.x() - cx, p.z() - cz) - r);
		return new Circle(cx, cz, r, Math.sqrt(res / pts.size()) / r);
	}

	private static Circle fitSphere(List<Pt> pts) {
		if (pts.size() < 4) return null;
		double[][] a = new double[4][5];
		for (Pt p : pts) {
			double[] row = { p.x(), p.y(), p.z(), 1 };
			accumulate(a, row, -(p.x() * p.x() + p.y() * p.y() + p.z() * p.z()));
		}
		double[] s = solve(a);
		if (s == null) return null;
		double cx = -s[0] / 2, cy = -s[1] / 2, cz = -s[2] / 2;
		double r2 = cx * cx + cy * cy + cz * cz - s[3];
		if (r2 <= 0) return null;
		double r = Math.sqrt(r2), res = 0;
		for (Pt p : pts) res += sq(Math.sqrt(sq(p.x() - cx) + sq(p.y() - cy) + sq(p.z() - cz)) - r);
		return new Circle(cx, cz, r, Math.sqrt(res / pts.size()) / r);
	}

	/** Adds a row to the normal equations (augmented n x n+1 matrix). */
	private static void accumulate(double[][] a, double[] row, double rhs) {
		int n = row.length;
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) a[i][j] += row[i] * row[j];
			a[i][n] += row[i] * rhs;
		}
	}

	private static double[] solve(double[][] a) {
		int n = a.length;
		for (int col = 0; col < n; col++) {
			int piv = col;
			for (int r = col + 1; r < n; r++) if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) piv = r;
			if (Math.abs(a[piv][col]) < 1e-12) return null;
			double[] t = a[col];
			a[col] = a[piv];
			a[piv] = t;
			for (int r = 0; r < n; r++) {
				if (r == col) continue;
				double f = a[r][col] / a[col][col];
				for (int k = col; k <= n; k++) a[r][k] -= f * a[col][k];
			}
		}
		double[] x = new double[n];
		for (int i = 0; i < n; i++) x[i] = a[i][n] / a[i][i];
		return x;
	}

	private static double[][] covariance(List<Pt> pts, double[] c) {
		double[][] m = new double[3][3];
		for (Pt p : pts) {
			double[] d = { p.x() - c[0], p.y() - c[1], p.z() - c[2] };
			for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) m[i][j] += d[i] * d[j];
		}
		for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) m[i][j] /= pts.size();
		return m;
	}

	/** Eigenvalues of a symmetric 3x3 matrix (Jacobi), sorted descending; eigenvectors in columns. */
	private static double[] jacobi(double[][] src, double[][] v) {
		double[][] a = new double[3][3];
		for (int i = 0; i < 3; i++) {
			a[i] = src[i].clone();
			Arrays.fill(v[i], 0);
			v[i][i] = 1;
		}
		for (int sweep = 0; sweep < 50; sweep++) {
			double off = sq(a[0][1]) + sq(a[0][2]) + sq(a[1][2]);
			if (off < 1e-18) break;
			for (int p = 0; p < 2; p++) {
				for (int q = p + 1; q < 3; q++) {
					if (Math.abs(a[p][q]) < 1e-15) continue;
					double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
					double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
					if (theta == 0) t = 1;
					double cs = 1 / Math.sqrt(t * t + 1), sn = t * cs;
					for (int k = 0; k < 3; k++) {
						double akp = a[k][p], akq = a[k][q];
						a[k][p] = cs * akp - sn * akq;
						a[k][q] = sn * akp + cs * akq;
					}
					for (int k = 0; k < 3; k++) {
						double apk = a[p][k], aqk = a[q][k];
						a[p][k] = cs * apk - sn * aqk;
						a[q][k] = sn * apk + cs * aqk;
					}
					for (int k = 0; k < 3; k++) {
						double vkp = v[k][p], vkq = v[k][q];
						v[k][p] = cs * vkp - sn * vkq;
						v[k][q] = sn * vkp + cs * vkq;
					}
				}
			}
		}
		Integer[] order = { 0, 1, 2 };
		Arrays.sort(order, (i, j) -> Double.compare(a[j][j], a[i][i]));
		double[] eig = new double[3];
		double[][] sortedV = new double[3][3];
		for (int c = 0; c < 3; c++) {
			eig[c] = a[order[c]][order[c]];
			for (int r = 0; r < 3; r++) sortedV[r][c] = v[r][order[c]];
		}
		for (int r = 0; r < 3; r++) v[r] = sortedV[r];
		return eig;
	}

	/** {slope, intercept, R²} */
	private static double[] linreg(double[] x, double[] y) {
		int n = x.length;
		if (n < 2) return new double[] { 0, n == 1 ? y[0] : 0, 0 };
		double mx = mean(x), my = mean(y), sxy = 0, sxx = 0, syy = 0;
		for (int i = 0; i < n; i++) {
			sxy += (x[i] - mx) * (y[i] - my);
			sxx += sq(x[i] - mx);
			syy += sq(y[i] - my);
		}
		if (sxx == 0) return new double[] { 0, my, 0 };
		double slope = sxy / sxx;
		double r2 = syy == 0 ? 0 : sxy * sxy / (sxx * syy);
		return new double[] { slope, my - slope * mx, r2 };
	}

	private static double hue(int rgb) {
		double r = ((rgb >> 16) & 0xFF) / 255.0, g = ((rgb >> 8) & 0xFF) / 255.0, b = (rgb & 0xFF) / 255.0;
		double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
		if (d == 0) return 0;
		double h;
		if (max == r) h = ((g - b) / d) % 6;
		else if (max == g) h = (b - r) / d + 2;
		else h = (r - g) / d + 4;
		h *= 60;
		return h < 0 ? h + 360 : h;
	}

	private static int[] rgb(int c) {
		return new int[] { (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF };
	}

	private static String rgbText(int c) {
		int[] v = rgb(c);
		return "(" + v[0] + ", " + v[1] + ", " + v[2] + ")";
	}

	private static double dist(Pt a, Pt b) {
		return Math.sqrt(sq(a.x() - b.x()) + sq(a.y() - b.y()) + sq(a.z() - b.z()));
	}

	private static double sq(double v) {
		return v * v;
	}

	private static double mean(double[] v) {
		return v.length == 0 ? 0 : Arrays.stream(v).average().orElse(0);
	}

	private static double variance(double[] v) {
		double m = mean(v), s = 0;
		for (double d : v) s += sq(d - m);
		return v.length == 0 ? 0 : s / v.length;
	}

	private static double std(double[] v) {
		return Math.sqrt(variance(v));
	}

	private static double median(double[] v) {
		if (v.length == 0) return 0;
		double[] s = v.clone();
		Arrays.sort(s);
		return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
	}

	private static double min(double[] v) {
		return Arrays.stream(v).min().orElse(0);
	}

	private static double max(double[] v) {
		return Arrays.stream(v).max().orElse(0);
	}

	private static String vec(double x, double y, double z) {
		return "(" + f3(x) + ", " + f3(y) + ", " + f3(z) + ")";
	}

	private static String pct(double v) {
		return f1(v * 100) + "%";
	}

	private static String signed(double v) {
		return (v >= 0 ? "+" : "") + f3(v);
	}

	private static String f1(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}

	private static String f2(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}

	private static String f3(double v) {
		return String.format(Locale.ROOT, "%.3f", v);
	}
}
