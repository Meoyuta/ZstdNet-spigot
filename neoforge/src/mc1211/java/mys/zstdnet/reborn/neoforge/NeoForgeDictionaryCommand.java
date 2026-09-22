package mys.zstdnet.reborn.neoforge;

import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryStore;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryTrainer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

final class NeoForgeDictionaryCommand {
    static void register(RegisterCommandsEvent event, Supplier<ZstdDictionaryStore> stores,
                         Supplier<ZstdDictionaryTrainer> trainers) {
        var root = Commands.literal("dictionary")
            .executes(c -> execute(c.getSource(), stores.get(), trainers.get(), "status", ""));
        for (String action : new String[]{"status", "stop", "cancel", "export"}) {
            root.then(Commands.literal(action)
                .executes(c -> execute(c.getSource(), stores.get(), trainers.get(), action, "")));
        }
        root.then(Commands.literal("train")
            .executes(c -> execute(c.getSource(), stores.get(), trainers.get(), "train", "600"))
            .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86400))
                .executes(c -> execute(c.getSource(), stores.get(), trainers.get(), "train",
                    Integer.toString(IntegerArgumentType.getInteger(c, "seconds"))))));
        root.then(Commands.literal("import")
            .then(Commands.argument("path", StringArgumentType.greedyString())
                .executes(c -> execute(c.getSource(), stores.get(), trainers.get(), "import",
                    StringArgumentType.getString(c, "path")))));
        event.getDispatcher().register(Commands.literal("zstdnet").requires(s -> s.hasPermission(2)).then(root));
    }

    private static int execute(CommandSourceStack source, ZstdDictionaryStore store,
                               ZstdDictionaryTrainer trainer, String action, String argument) {
        if (store == null || trainer == null) {
            source.sendFailure(Component.literal("ZstdNet dictionary service is not running on this server."));
            return 0;
        }
        try {
            String message;
            switch (action) {
                case "train" -> {
                    if (!trainer.start(Duration.ofSeconds(Integer.parseInt(argument)), 9)) {
                        throw new IllegalStateException("Training is already running or shutting down.");
                    }
                    message = "Collecting traffic for " + argument + " seconds. Use dictionary status to check progress.";
                }
                case "stop" -> {
                    if (!trainer.stopAndFinalize()) throw new IllegalStateException("No collecting session to finalize.");
                    message = "Training queued. Use dictionary status to view the result.";
                }
                case "cancel" -> {
                    trainer.abort();
                    message = "Training cancelled. The current dictionary is unchanged.";
                }
                case "import" -> {
                    String path = argument.trim();
                    if (path.startsWith("\"") && path.endsWith("\"")) path = path.substring(1, path.length() - 1);
                    Path file = Path.of(path);
                    if (!file.isAbsolute()) file = store.dictionaryPath().getParent().resolve(file);
                    trainer.abort();
                    var dictionary = store.importFrom(file);
                    message = "Imported dictionary " + Long.toUnsignedString(dictionary.id())
                        + ". New connections synchronize automatically; reconnect existing clients.";
                }
                case "export" -> {
                    String path = store.export().toString();
                    source.sendSuccess(() -> Component.literal("Exported dictionary (click to copy):"), false);
                    source.sendSuccess(() -> Component.literal(path).withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, path))), false);
                    return 1;
                }
                default -> {
                    var state = trainer.status();
                    var dictionary = store.dictionary();
                    message = "Dictionary: " + (dictionary == null ? "none" : Long.toUnsignedString(dictionary.id())
                        + " (" + dictionary.size() + " bytes)") + "; "
                        + (state.finalizing() ? "training" : state.training() ? "collecting" : state.result())
                        + "; samples=" + state.sampleCount() + ", bytes=" + state.sampleBytes()
                        + ", remaining=" + (state.remainingMillis() / 1000) + "s";
                }
            }
            source.sendSuccess(() -> Component.literal(message), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Dictionary operation failed: " + e.getMessage()));
            return 0;
        }
    }
}
