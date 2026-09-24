package mys.zstdnet.reborn.neoforge;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryTrainer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

final class ZstdCommands {
    private final ZstdNet mod;
    private static final java.util.Map<ZstdNet, java.util.Set<String>> ANNOUNCED = new java.util.WeakHashMap<>();

    static void prompt(net.minecraft.server.level.ServerPlayer player, ZstdNet mod) {
        if (!player.createCommandSourceStack().hasPermission(2)) return;
        for (String file : mod.dictionaryStore().pendingNames()) {
            player.sendSystemMessage(namingPrompt(file));
        }
    }

    private static Component namingPrompt(String file) {
        return text(file.startsWith("temp_") ? "dictionary.name_temp" : "dictionary.name_prompt", file)
            .copy().withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                "/zstdnet dictionary name " + file + " ")));
    }

    static void tick(net.minecraft.server.MinecraftServer server, ZstdNet mod) {
        if (!server.isDedicatedServer() || server.getTickCount() % 20 != 0) return;
        try {
            for (String file : mod.dictionaryStore().expireNames(System.currentTimeMillis())) {
                server.createCommandSourceStack().sendSuccess(() -> text("dictionary.auto_named", file), true);
            }
            var announced = ANNOUNCED.computeIfAbsent(mod, ignored -> new java.util.HashSet<>());
            var pending = mod.dictionaryStore().pendingNames();
            announced.retainAll(pending);
            for (String file : pending) {
                if (announced.add(file)) {
                    server.createCommandSourceStack().sendSuccess(() -> namingPrompt(file), false);
                    for (var player : server.getPlayerList().getPlayers()) {
                        if (player.createCommandSourceStack().hasPermission(2)) player.sendSystemMessage(namingPrompt(file));
                    }
                }
            }
        } catch (java.io.IOException e) {
            mod.commandFailed("dictionary naming", e);
        }
    }

    private ZstdCommands(ZstdNet mod) {
        this.mod = mod;
    }

    static void register(RegisterCommandsEvent event, ZstdNet mod) {
        ZstdCommands handler = new ZstdCommands(mod);
        var root = Commands.literal("zstdnet").requires(s -> s.hasPermission(2))
            .executes(c -> handler.management(c.getSource(), "status"));
        for (String action : new String[]{"status", "start", "stop", "reload"}) {
            root.then(Commands.literal(action).executes(c -> handler.management(c.getSource(), action)));
        }
        var dictionary = Commands.literal("dictionary")
            .executes(c -> handler.dictionary(c.getSource(), "status", ""));
        for (String action : new String[]{"status", "stop", "cancel", "export", "unload", "list"}) {
            dictionary.then(Commands.literal(action)
                .executes(c -> handler.dictionary(c.getSource(), action, "")));
        }
        dictionary.then(Commands.literal("train")
            .executes(c -> handler.dictionary(c.getSource(), "train", "600"))
            .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86400))
                .executes(c -> handler.dictionary(c.getSource(), "train",
                    Integer.toString(IntegerArgumentType.getInteger(c, "seconds"))))));
        dictionary.then(Commands.literal("import")
            .then(Commands.argument("path", StringArgumentType.greedyString())
                .executes(c -> handler.dictionary(c.getSource(), "import",
                    StringArgumentType.getString(c, "path")))));
        dictionary.then(Commands.literal("switch")
            .executes(c -> handler.dictionary(c.getSource(), "list", ""))
            .then(Commands.argument("path", StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    try {
                        for (String path : mod.dictionaryStore().available()) {
                            if (path.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) builder.suggest(path);
                        }
                    } catch (java.io.IOException e) {
                        mod.commandFailed("dictionary suggestions", e);
                    }
                    return builder.buildFuture();
                })
                .executes(c -> handler.dictionary(c.getSource(), "switch",
                    StringArgumentType.getString(c, "path")))));
        dictionary.then(Commands.literal("name")
            .then(Commands.argument("file", StringArgumentType.word())
                .suggests((context, builder) -> {
                    for (String file : mod.dictionaryStore().pendingNames()) {
                        if (file.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) builder.suggest(file);
                    }
                    return builder.buildFuture();
                })
                .then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(c -> {
                        try {
                            Path named = mod.dictionaryStore().name(StringArgumentType.getString(c, "file"),
                                StringArgumentType.getString(c, "name"));
                            success(c.getSource(), "dictionary.named", named.getFileName().toString());
                            return 1;
                        } catch (java.io.IOException e) {
                            mod.commandFailed("dictionary name", e);
                            return fail(c.getSource(), "dictionary.name_failed");
                        }
                    }))));
        event.getDispatcher().register(root.then(dictionary));
    }

    private int management(CommandSourceStack source, String action) {
        if (!source.getServer().isDedicatedServer()) {
            return fail(source, "management.dedicated_only");
        }
        try {
            switch (action) {
                case "start" -> {
                    if (!mod.start(source.getServer())) return fail(source, "management.init_failed");
                    success(source, "management.started", source.getServer().getPort());
                }
                case "stop" -> {
                    mod.stop();
                    success(source, "management.stopped");
                }
                case "reload" -> {
                    if (!mod.reload(source.getServer())) return fail(source, "management.init_failed");
                    success(source, mod.dictionaryStore().dictionary() == null
                        ? "management.reloaded_without_dictionary" : "management.reloaded");
                }
                default -> {
                    var stats = mod.snapshot();
                    success(source, "status.state",
                        text(mod.isRunning() ? "status.running" : "status.stopped"), source.getServer().getPort());
                    success(source, "status.traffic",
                        stats.wireUpBytes() + stats.wireDownBytes(),
                        stats.rawUpBytes() + stats.rawDownBytes());
                    success(source, "status.ratio", String.format(Locale.ROOT, "%.2f", stats.ratioPercent()));
                    success(source, "status.connections", stats.connections());
                    var selected = mod.dictionaryStore().dictionary();
                    success(source, "status.dictionary", selected == null ? text("dictionary.none")
                        : text("dictionary.description", Long.toUnsignedString(selected.id()), selected.size()));
                    success(source, "status.dictionary_connections",
                        mod.dictionaryConnections(), mod.selectedDictionaryConnections());
                    success(source, "status.dictionary_note");
                }
            }
            return 1;
        } catch (Exception e) {
            mod.commandFailed(action, e);
            return fail(source, "management.failed");
        }
    }

    private int dictionary(CommandSourceStack source, String action, String argument) {
        var store = mod.dictionaryStore();
        var trainer = mod.dictionaryTrainer();
        if (store == null || trainer == null) return fail(source, "dictionary.service_unavailable");
        try {
            switch (action) {
                case "list" -> success(source, "dictionary.available", String.join(", ", store.available()));
                case "unload" -> {
                    trainer.abort();
                    store.unload();
                    success(source, "dictionary.unloaded");
                }
                case "switch" -> {
                    String path = argument.trim();
                    if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
                        path = path.substring(1, path.length() - 1);
                    }
                    if (path.isBlank()) return fail(source, "dictionary.invalid_path");
                    trainer.abort();
                    var selected = store.select(Path.of(path));
                    success(source, "dictionary.switched", store.selectedPath(), Long.toUnsignedString(selected.id()));
                }
                case "train" -> {
                    if (!mod.isRunning()) return fail(source, "dictionary.compression_stopped");
                    if (!trainer.start(Duration.ofSeconds(Integer.parseInt(argument)), 9)) {
                        return fail(source, "dictionary.busy");
                    }
                    success(source, "dictionary.collecting", argument);
                }
                case "stop" -> {
                    if (!trainer.stopAndFinalize()) return fail(source, "dictionary.not_collecting");
                    success(source, "dictionary.queued");
                }
                case "cancel" -> {
                    trainer.abort();
                    success(source, "dictionary.cancelled");
                }
                case "import" -> {
                    String path = argument.trim();
                    if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
                        path = path.substring(1, path.length() - 1).trim();
                    }
                    if (path.isEmpty()) return fail(source, "dictionary.invalid_path");
                    Path file = Path.of(path);
                    if (!file.isAbsolute()) file = store.dictionaryPath().getParent().resolve(file);
                    trainer.abort();
                    var dictionary = store.importFrom(file);
                    success(source, "dictionary.saved_pending", Long.toUnsignedString(dictionary.id()));
                }
                case "export" -> {
                    if (store.dictionary() == null) return fail(source, "dictionary.no_dictionary");
                    String path = store.export().toString();
                    success(source, "dictionary.exported");
                    source.sendSuccess(() -> text("dictionary.export_path", path).copy().withStyle(style -> style
                        .withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withInsertion("zstdnet:dictionary-export")
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            text("dictionary.copy_path_hover", path)))
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, path))), false);
                }
                default -> {
                    var state = trainer.status();
                    var dictionary = store.dictionary();
                    Component description = dictionary == null ? text("dictionary.none")
                        : text("dictionary.description", Long.toUnsignedString(dictionary.id()), dictionary.size());
                    success(source, "dictionary.status", description, trainingState(state),
                        state.sampleCount(), state.sampleBytes(), state.remainingMillis() / 1000);
                    success(source, "dictionary.selected_path",
                        store.selectedPath() == null ? text("dictionary.none") : store.selectedPath().toString());
                }
            }
            return 1;
        } catch (Exception e) {
            mod.commandFailed("dictionary " + action, e);
            return fail(source, "dictionary.failed");
        }
    }

    private static Component trainingState(ZstdDictionaryTrainer.Status state) {
        if (state.finalizing()) return text("dictionary.state.training");
        if (state.training()) return text("dictionary.state.collecting");
        String result = state.result();
        if (result.startsWith("saved dictionary ")) {
            return text("dictionary.state.saved", result.substring("saved dictionary ".length()));
        }
        if (result.startsWith("failed: ")) {
            return text(result.contains("not enough traffic samples")
                ? "dictionary.state.insufficient_samples" : "dictionary.state.failed");
        }
        return text("dictionary.state." + ("cancelled".equals(result) ? "cancelled" : "idle"));
    }

    private static Component text(String key, Object... arguments) {
        return Component.translatable("zstdnet.command." + key, arguments);
    }

    private static void success(CommandSourceStack source, String key, Object... arguments) {
        source.sendSuccess(() -> text(key, arguments), false);
    }

    private static int fail(CommandSourceStack source, String key) {
        source.sendFailure(text(key));
        return 0;
    }
}
