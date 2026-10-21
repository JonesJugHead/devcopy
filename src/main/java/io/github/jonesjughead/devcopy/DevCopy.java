package io.github.jonesjughead.devcopy;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.commands.SharedSuggestionProvider;

import io.github.jonesjughead.devcopy.particles.ParticleRecorder;

/**
 * /devcopy <scoreboard|tab|bossbar|title|actionbar|all> [plain|legacy|minimessage|json]
 * /devcopy particles record <seconds> [radius] [levelEvents] | stop | status
 */
public final class DevCopy implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("DevCopy");

	@Override
	public void onInitializeClient() {
		LOGGER.info("DevCopy init (java.net.preferIPv4Stack={}, preferIPv6Addresses={})",
				System.getProperty("java.net.preferIPv4Stack"), System.getProperty("java.net.preferIPv6Addresses"));
		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> {
			LOGGER.info("JOIN (play phase reached)");
			HudState.reset();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
			HudState.reset();
			ParticleRecorder.stop("disconnected");
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> ParticleRecorder.onEndTick());

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			LiteralArgumentBuilder<FabricClientCommandSource> root = literal("devcopy");
			target(root, "scoreboard", HudReader::scoreboard);
			target(root, "tab", HudReader::tab);
			target(root, "bossbar", HudReader::bossBars);
			target(root, "title", HudReader::titles);
			target(root, "actionbar", HudReader::actionBar);
			target(root, "all", DevCopy::all);
			root.then(particles());
			dispatcher.register(root);
		});
	}

	private static void target(LiteralArgumentBuilder<FabricClientCommandSource> root, String name,
			Function<TextFormat, List<String>> reader) {
		root.then(literal(name)
				.executes(ctx -> run(ctx, name, reader, TextFormat.PLAIN))
				.then(argument("format", StringArgumentType.word())
						.suggests((ctx, b) -> SharedSuggestionProvider.suggest(
								List.of("plain", "legacy", "minimessage", "json"), b))
						.executes(ctx -> {
							String raw = StringArgumentType.getString(ctx, "format");
							TextFormat fmt;
							try {
								fmt = TextFormat.valueOf(raw.toUpperCase(Locale.ROOT));
							} catch (IllegalArgumentException e) {
								ctx.getSource().sendError(Component.literal("Unknown format: " + raw));
								return 0;
							}
							return run(ctx, name, reader, fmt);
						})));
	}

	private static int run(CommandContext<FabricClientCommandSource> ctx, String name,
			Function<TextFormat, List<String>> reader, TextFormat fmt) {
		List<String> lines = reader.apply(fmt);
		if (lines.isEmpty()) {
			ctx.getSource().sendError(Component.literal("Nothing to copy for " + name));
			return 0;
		}
		Minecraft.getInstance().keyboardHandler.setClipboard(String.join("\n", lines));
		ctx.getSource().sendFeedback(Component.literal("[DevCopy] ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(name + " copied (" + lines.size() + " line"
						+ (lines.size() > 1 ? "s" : "") + ", " + fmt.name().toLowerCase(Locale.ROOT) + ")")
						.withStyle(ChatFormatting.GREEN)));
		return 1;
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> particles() {
		return literal("particles")
				.then(literal("record")
						.then(argument("seconds", IntegerArgumentType.integer(1, ParticleRecorder.MAX_SECONDS))
								.executes(ctx -> record(ctx, ParticleRecorder.DEFAULT_RADIUS, false))
								.then(argument("radius", DoubleArgumentType.doubleArg(0))
										.executes(ctx -> record(ctx, DoubleArgumentType.getDouble(ctx, "radius"), false))
										.then(argument("levelEvents", BoolArgumentType.bool())
												.executes(ctx -> record(ctx, DoubleArgumentType.getDouble(ctx, "radius"),
														BoolArgumentType.getBool(ctx, "levelEvents")))))))
				.then(literal("stop").executes(ctx -> {
					if (!ParticleRecorder.stop("manual stop")) {
						ctx.getSource().sendError(Component.literal("No recording in progress"));
						return 0;
					}
					return 1;
				}))
				.then(literal("status").executes(ctx -> {
					String status = ParticleRecorder.status();
					ctx.getSource().sendFeedback(prefix().append(Component.literal(
							status != null ? status : "No recording in progress").withStyle(ChatFormatting.YELLOW)));
					return 1;
				}));
	}

	private static int record(CommandContext<FabricClientCommandSource> ctx, double radius, boolean levelEvents) {
		int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
		String error = ParticleRecorder.start(seconds, radius, levelEvents);
		if (error != null) {
			ctx.getSource().sendError(Component.literal(error));
			return 0;
		}
		ctx.getSource().sendFeedback(prefix().append(Component.literal("Recording particles for "
				+ seconds + " s, radius " + (radius > 0 ? radius + " blocks" : "unlimited")
				+ (levelEvents ? ", level events included" : "") + ". Origin = your current position.")
				.withStyle(ChatFormatting.GREEN)));
		return 1;
	}

	private static MutableComponent prefix() {
		return Component.literal("[DevCopy] ").withStyle(ChatFormatting.GRAY);
	}

	private static List<String> all(TextFormat fmt) {
		List<String> out = new ArrayList<>();
		section(out, "SCOREBOARD", HudReader.scoreboard(fmt));
		section(out, "TAB", HudReader.tab(fmt));
		section(out, "BOSSBAR", HudReader.bossBars(fmt));
		section(out, "TITLE", HudReader.titles(fmt));
		section(out, "ACTIONBAR", HudReader.actionBar(fmt));
		return out;
	}

	private static void section(List<String> out, String title, List<String> lines) {
		if (lines.isEmpty()) return;
		if (!out.isEmpty()) out.add("");
		out.add("===== " + title + " =====");
		out.addAll(lines);
	}
}
