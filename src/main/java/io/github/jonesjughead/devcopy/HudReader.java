package io.github.jonesjughead.devcopy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/** Reads the current state of each HUD element and renders it as text. */
public final class HudReader {
	private HudReader() {}

	public static List<String> scoreboard(TextFormat fmt) {
		Minecraft mc = Minecraft.getInstance();
		List<String> out = new ArrayList<>();
		if (mc.level == null || mc.player == null) return out;

		Scoreboard sb = mc.level.getScoreboard();
		Objective objective = null;

		// Same logic as vanilla: the team color slot wins over the generic sidebar
		PlayerTeam ownTeam = sb.getPlayersTeam(mc.player.getScoreboardName());
		if (ownTeam != null) {
			objective = ownTeam.getColor()
					.map(c -> sb.getDisplayObjective(c.displaySlot()))
					.orElse(null);
		}
		if (objective == null) objective = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) return out;

		out.add(fmt.format(objective.getDisplayName()));

		var numberFormat = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
		List<PlayerScoreEntry> entries = sb.listPlayerScores(objective).stream()
				.filter(e -> !e.isHidden())
				.sorted(Comparator.comparingInt(PlayerScoreEntry::value).reversed()
						.thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER))
				.limit(15)
				.toList();

		for (PlayerScoreEntry e : entries) {
			PlayerTeam team = sb.getPlayersTeam(e.owner());
			Component name = PlayerTeam.formatNameForTeam(team, e.ownerName());
			Component score = e.formatValue(numberFormat);
			String scoreText = fmt.format(score);
			out.add(scoreText.isEmpty() ? fmt.format(name) : fmt.format(name) + "  |  " + scoreText);
		}
		return out;
	}

	public static List<String> tab(TextFormat fmt) {
		Minecraft mc = Minecraft.getInstance();
		List<String> out = new ArrayList<>();

		if (!HudState.tabHeader.getString().isEmpty()) {
			out.add("# Header");
			out.add(fmt.format(HudState.tabHeader));
		}

		if (mc.getConnection() != null) {
			out.add("# Joueurs");
			List<PlayerInfo> players = new ArrayList<>(mc.getConnection().getListedOnlinePlayers());
			players.sort(Comparator.comparingInt(PlayerInfo::getTabListOrder).reversed()
					.thenComparing(p -> p.getProfile().name(), String.CASE_INSENSITIVE_ORDER));
			for (PlayerInfo p : players) {
				Component display = p.getTabListDisplayName() != null
						? p.getTabListDisplayName()
						: PlayerTeam.formatNameForTeam(p.getTeam(), Component.literal(p.getProfile().name()));
				out.add(fmt.format(display));
			}
		}

		if (!HudState.tabFooter.getString().isEmpty()) {
			out.add("# Footer");
			out.add(fmt.format(HudState.tabFooter));
		}
		return out;
	}

	public static List<String> bossBars(TextFormat fmt) {
		return HudState.bossBars().values().stream().map(fmt::format).toList();
	}

	public static List<String> titles(TextFormat fmt) {
		List<String> out = new ArrayList<>();
		if (!HudState.title.getString().isEmpty()) out.add("Title: " + fmt.format(HudState.title));
		if (!HudState.subtitle.getString().isEmpty()) out.add("Subtitle: " + fmt.format(HudState.subtitle));
		return out;
	}

	public static List<String> actionBar(TextFormat fmt) {
		return HudState.actionBar.getString().isEmpty() ? List.of() : List.of(fmt.format(HudState.actionBar));
	}
}
