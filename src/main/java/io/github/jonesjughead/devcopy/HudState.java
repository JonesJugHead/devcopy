package io.github.jonesjughead.devcopy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.chat.Component;

/**
 * What the server sent, captured when packets are received.
 * Keeping our own copy instead of digging into the HUD's private fields is more
 * stable from one version to the next.
 */
public final class HudState {
	public static Component tabHeader = Component.empty();
	public static Component tabFooter = Component.empty();
	public static Component title = Component.empty();
	public static Component subtitle = Component.empty();
	public static Component actionBar = Component.empty();

	private static final Map<UUID, Component> BOSS_BARS = new LinkedHashMap<>();

	private HudState() {}

	public static void putBossBar(UUID id, Component name) {
		BOSS_BARS.put(id, name);
	}

	public static void renameBossBar(UUID id, Component name) {
		BOSS_BARS.computeIfPresent(id, (k, v) -> name);
	}

	public static void removeBossBar(UUID id) {
		BOSS_BARS.remove(id);
	}

	public static Map<UUID, Component> bossBars() {
		return Collections.unmodifiableMap(BOSS_BARS);
	}

	public static void reset() {
		tabHeader = Component.empty();
		tabFooter = Component.empty();
		title = Component.empty();
		subtitle = Component.empty();
		actionBar = Component.empty();
		BOSS_BARS.clear();
	}
}
