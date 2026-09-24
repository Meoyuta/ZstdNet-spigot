package mys.zstdnet.reborn.neoforge;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryStore;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryTrainer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.nio.file.Path;
import java.time.Duration;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class ZstdCommands {
    private static final Map<ZstdNet, Set<String>> ANNOUNCED = new WeakHashMap<>();
    private final ZstdNet mod;

    private ZstdCommands(ZstdNet mod) {
        this.mod = mod;
    }

    static void prompt(ServerPlayer player, ZstdNet mod) {
        if (!player.createCommandSourceStack().hasPermission(2)) return;
        for (String file : mod.dictionaryStore().pendingNames()) {
            player.sendSystemMessage(namingPrompt(file));
        }
    }

    private static Component namingPrompt(String file) {
        return text(file.startsWith("temp_") ? "dictionary.name_temp" : "dictionary.name_prompt", file)
                .copy().withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                        "/zstdnet dictionary name %s ".formatted(file))));
    }

    static void tick(MinecraftServer server, ZstdNet mod) {
        if (!server.isDedicatedServer() || server.getTickCount() % 20 != 0) return;
        try {
            for (String file : mod.dictionaryStore().expireNames(System.currentTimeMillis())) {
                server.createCommandSourceStack().sendSuccess(() -> text("dictionary.auto_named", file), true);
            }
            var announced = ANNOUNCED.computeIfAbsent(mod, ignored -> new HashSet<>());
            var pending = mod.dictionaryStore().pendingNames();
            announced.retainAll(pending);
            for (String file : pending) {
                if (announced.add(file)) {
                    server.createCommandSourceStack().sendSuccess(() -> namingPrompt(file), false);
                    for (var player : server.getPlayerList().getPlayers()) {
                        if (player.createCommandSourceStack().hasPermission(2))
                            player.sendSystemMessage(namingPrompt(file));
                    }
                }
            }
        } catch (IOException e) {
            mod.commandFailed("dictionary naming", e);
        }
    }

    static void register(RegisterCommandsEvent event, ZstdNet mod) {
        ZstdCommands handler = new ZstdCommands(mod);
        var root = Commands.literal("zstdnet")
                .executes(c -> handler.management(c.getSource(), "status"));
        registerManagementCommands(root, handler);
        root.then(createDictionaryCommand(handler, mod));
        event.getDispatcher().register(root);
    }

    private static void registerManagementCommands(
            LiteralArgumentBuilder<CommandSourceStack> root,
            ZstdCommands handler
    ) {
        root.then(Commands.literal("status")
                .executes(context -> handler.management(context.getSource(), "status")));
        root.then(managementCommand("start", handler));
        root.then(managementCommand("stop", handler));
        root.then(managementCommand("reload", handler));
        root.then(Commands.literal("ping")
                .executes(context -> handler.ping(context.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> managementCommand(
            String action,
            ZstdCommands handler
    ) {
        return Commands.literal(action)
                .requires(source -> source.hasPermission(2))
                .executes(context -> handler.management(context.getSource(), action));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createDictionaryCommand(
            ZstdCommands handler,
            ZstdNet mod
    ) {
        var dictionary = Commands.literal("dictionary")
                .executes(context -> handler.dictionary(context.getSource(), "status", ""));

        dictionary.then(Commands.literal("status")
                .executes(context -> handler.dictionary(context.getSource(), "status", "")));
        dictionary.then(Commands.literal("list")
                .executes(context -> handler.dictionary(context.getSource(), "list", "")));
        dictionary.then(dictionaryAction("stop", handler));
        dictionary.then(dictionaryAction("cancel", handler));
        dictionary.then(dictionaryAction("export", handler));
        dictionary.then(dictionaryAction("unload", handler));
        dictionary.then(trainingCommand(handler));
        dictionary.then(importCommand(handler));
        dictionary.then(switchCommand(handler, mod));
        dictionary.then(nameCommand(handler, mod));
        return dictionary;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> dictionaryAction(
            String action,
            ZstdCommands handler
    ) {
        return Commands.literal(action)
                .requires(source -> source.hasPermission(2))
                .executes(context -> handler.dictionary(context.getSource(), action, ""));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> trainingCommand(ZstdCommands handler) {
        var train = Commands.literal("train")
                .requires(source -> source.hasPermission(2))
                .executes(context -> handler.dictionary(context.getSource(), "train", "600"));

        train.then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86400))
                .executes(context -> handler.dictionary(
                        context.getSource(),
                        "train",
                        Integer.toString(IntegerArgumentType.getInteger(context, "seconds"))
                )));
        return train;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> importCommand(ZstdCommands handler) {
        return Commands.literal("import")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("path", StringArgumentType.greedyString())
                        .executes(context -> handler.dictionary(
                                context.getSource(),
                                "import",
                                StringArgumentType.getString(context, "path")
                        )));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> switchCommand(
            ZstdCommands handler,
            ZstdNet mod
    ) {
        var changeDictionary = Commands.literal("switch")
                .requires(source -> source.hasPermission(2))
                .executes(context -> handler.dictionary(context.getSource(), "list", ""));

        changeDictionary.then(Commands.argument("path", StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    try {
                        for (String path : mod.dictionaryStore().available()) {
                            if (path.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(path);
                            }
                        }
                    } catch (java.io.IOException e) {
                        mod.commandFailed("dictionary suggestions", e);
                    }
                    return builder.buildFuture();
                })
                .executes(context -> handler.dictionary(
                        context.getSource(),
                        "switch",
                        StringArgumentType.getString(context, "path")
                )));
        return changeDictionary;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> nameCommand(
            ZstdCommands handler,
            ZstdNet mod
    ) {
        var nameDictionary = Commands.literal("name")
                .requires(source -> source.hasPermission(2));

        nameDictionary.then(Commands.argument("file", StringArgumentType.word())
                .suggests((context, builder) -> {
                    for (String file : mod.dictionaryStore().pendingNames()) {
                        if (file.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                            builder.suggest(file);
                        }
                    }
                    return builder.buildFuture();
                })
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(context -> handler.nameDictionary(
                                context.getSource(),
                                StringArgumentType.getString(context, "file"),
                                StringArgumentType.getString(context, "name")
                        ))));
        return nameDictionary;
    }

    private static Component trainingState(ZstdDictionaryTrainer.Status state) {
        if (state.finalizing()) return text("dictionary.state.training");
        if (state.training()) return text("dictionary.state.collecting");
        var result = state.result();
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

    private int ping(CommandSourceStack source) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        success(source, "ping", player.connection.latency());
        return 1;
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
                default -> showManagementStatus(source);
            }
            return 1;
        } catch (Exception e) {
            mod.commandFailed(action, e);
            return fail(source, "management.failed");
        }
    }

    private void showManagementStatus(CommandSourceStack source) {
        var stats = mod.snapshot();
        var state = mod.isRunning() ? "status.running" : "status.stopped";
        success(source, "status.state", text(state), source.getServer().getPort());
        success(source, "status.upload", formatBytes(stats.wireUpBytes()), formatBytes(stats.rawUpBytes()));
        success(source, "status.download", formatBytes(stats.wireDownBytes()), formatBytes(stats.rawDownBytes()));
        success(source, "status.ratio", String.format(Locale.ROOT, "%.2f", stats.ratioPercent()));
        success(source, "status.connections", stats.connections());
        success(source, "status.dictionary", dictionaryDescription(mod.dictionaryStore()));
        success(source, "status.dictionary_connections",
                mod.dictionaryConnections(), mod.selectedDictionaryConnections());
        success(source, "status.dictionary_note");
    }

    private static String formatBytes(long bytes) {
        var units = new String[]{"B", "KiB", "MiB", "GiB", "TiB", "PiB"};
        var value = (double) bytes;
        var unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0 ? "%d %s".formatted(bytes, units[unit])
                : String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    private static Component dictionaryDescription(ZstdDictionaryStore store) {
        var selected = store.dictionary();
        if (selected == null) return text("dictionary.none");
        var path = store.selectedPath();
        var fileName = path == null ? null : path.getFileName().toString();
        var name = fileName == null ? text("dictionary.unknown_name")
                : Component.literal(fileName.endsWith(".zdict")
                        ? fileName.substring(0, fileName.length() - ".zdict".length()) : fileName);
        return text("dictionary.description", name, Long.toUnsignedString(selected.id()), selected.size());
    }

    private int dictionary(CommandSourceStack source, String action, String argument) {
        var store = mod.dictionaryStore();
        var trainer = mod.dictionaryTrainer();
        if (store == null || trainer == null) return fail(source, "dictionary.service_unavailable");
        try {
            return switch (action) {
                case "list" -> listDictionaries(source, store);
                case "unload" -> unloadDictionary(source, store, trainer);
                case "switch" -> switchDictionary(source, argument, store, trainer);
                case "train" -> trainDictionary(source, argument, trainer);
                case "stop" -> stopTraining(source, trainer);
                case "cancel" -> cancelTraining(source, trainer);
                case "import" -> importDictionary(source, argument, store, trainer);
                case "export" -> exportDictionary(source, store);
                default -> showDictionaryStatus(source, store, trainer);
            };
        } catch (Exception e) {
            mod.commandFailed("dictionary %s".formatted(action), e);
            return fail(source, "dictionary.failed");
        }
    }

    private int listDictionaries(CommandSourceStack source, ZstdDictionaryStore store) throws IOException {
        success(source, "dictionary.available", String.join(", ", store.available()));
        return 1;
    }

    private int unloadDictionary(
            CommandSourceStack source,
            ZstdDictionaryStore store,
            ZstdDictionaryTrainer trainer
    ) throws IOException {
        trainer.abort();
        store.unload();
        success(source, "dictionary.unloaded");
        return 1;
    }

    private int switchDictionary(
            CommandSourceStack source,
            String argument,
            ZstdDictionaryStore store,
            ZstdDictionaryTrainer trainer
    ) throws IOException {
        var path = unquotePath(argument.trim());
        if (path.isBlank()) return fail(source, "dictionary.invalid_path");

        trainer.abort();
        var selected = store.select(Path.of(path));
        success(source, "dictionary.switched", store.selectedPath(), Long.toUnsignedString(selected.id()));
        return 1;
    }

    private int trainDictionary(
            CommandSourceStack source,
            String seconds,
            ZstdDictionaryTrainer trainer
    ) {
        if (!mod.isRunning()) return fail(source, "dictionary.compression_stopped");
        if (!trainer.start(Duration.ofSeconds(Integer.parseInt(seconds)), 9)) {
            return fail(source, "dictionary.busy");
        }
        success(source, "dictionary.collecting", seconds);
        return 1;
    }

    private int stopTraining(CommandSourceStack source, ZstdDictionaryTrainer trainer) {
        if (!trainer.stopAndFinalize()) return fail(source, "dictionary.not_collecting");
        success(source, "dictionary.queued");
        return 1;
    }

    private int cancelTraining(CommandSourceStack source, ZstdDictionaryTrainer trainer) {
        trainer.abort();
        success(source, "dictionary.cancelled");
        return 1;
    }

    private int importDictionary(
            CommandSourceStack source,
            String argument,
            ZstdDictionaryStore store,
            ZstdDictionaryTrainer trainer
    ) throws IOException {
        var path = unquotePath(argument.trim()).trim();
        if (path.isEmpty()) return fail(source, "dictionary.invalid_path");

        var file = Path.of(path);
        if (!file.isAbsolute()) file = store.dictionaryPath().getParent().resolve(file);
        trainer.abort();
        var dictionary = store.importFrom(file);
        success(source, "dictionary.saved_pending", Long.toUnsignedString(dictionary.id()));
        return 1;
    }

    private int exportDictionary(
            CommandSourceStack source,
            ZstdDictionaryStore store
    ) throws IOException {
        if (store.dictionary() == null) return fail(source, "dictionary.no_dictionary");

        var path = store.export().toString();
        success(source, "dictionary.exported");
        source.sendSuccess(() -> exportPath(path), false);
        return 1;
    }

    private Component exportPath(String path) {
        return text("dictionary.export_path", path).copy().withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withInsertion("zstdnet:dictionary-export")
                .withHoverEvent(new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        text("dictionary.copy_path_hover", path)
                ))
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, path)));
    }

    private int showDictionaryStatus(
            CommandSourceStack source,
            ZstdDictionaryStore store,
            ZstdDictionaryTrainer trainer
    ) {
        var state = trainer.status();
        success(source, "dictionary.status", dictionaryDescription(store), trainingState(state),
                state.sampleCount(), state.sampleBytes(), state.remainingMillis() / 1000);
        success(source, "dictionary.selected_path", selectedDictionaryPath(store));
        return 1;
    }

    private Component selectedDictionaryPath(ZstdDictionaryStore store) {
        var path = store.selectedPath();
        return path == null ? text("dictionary.none") : Component.literal(path.toString());
    }

    private static String unquotePath(String path) {
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            return path.substring(1, path.length() - 1);
        }
        return path;
    }

    private int nameDictionary(CommandSourceStack source, String file, String name) {
        try {
            var named = mod.dictionaryStore().name(file, name);
            success(source, "dictionary.named", named.getFileName().toString());
            return 1;
        } catch (IOException e) {
            mod.commandFailed("dictionary name", e);
            return fail(source, "dictionary.name_failed");
        }
    }
}
