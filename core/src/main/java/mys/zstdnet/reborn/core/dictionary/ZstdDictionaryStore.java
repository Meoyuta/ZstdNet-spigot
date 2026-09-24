package mys.zstdnet.reborn.core.dictionary;

import mys.zstdnet.reborn.core.utils.ZstdNetLogger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public final class ZstdDictionaryStore {
    private final Path dictionaryPath;
    private final ZstdNetLogger logger;
    private volatile ZstdDictionary dictionary;
    private volatile Path selectedPath;
    private boolean managesSelection;
    private boolean namingEnabled;
    private boolean shuttingDown;
    private final java.util.Map<String, ZstdDictionary> pendingInstances = new java.util.HashMap<>();
    private final java.util.Properties pending = new java.util.Properties();

    public synchronized void enableNaming() throws IOException {
        namingEnabled = true;
        pending.clear();
        var metadata = dictionaryPath.resolveSibling("dictionary-naming.properties");
        if (Files.isRegularFile(metadata)) {
            try (var input = Files.newInputStream(metadata)) { pending.load(input); }
        }
        shuttingDown = false;
    }

    public synchronized void beginShutdown() { shuttingDown = true; }

    public synchronized java.util.List<String> pendingNames() {
        return pending.stringPropertyNames().stream().sorted().toList();
    }

    private void persistPending() throws IOException {
        var metadata = dictionaryPath.resolveSibling("dictionary-naming.properties");
        Files.createDirectories(metadata.getParent());
        var temporary = metadata.resolveSibling(metadata.getFileName() + ".tmp");
        try (var output = Files.newOutputStream(temporary)) { pending.store(output, "Dictionary naming deadlines; 0 = shutdown dictionary"); }
        Files.move(temporary, metadata, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String timestamp() {
        return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HH-mm-ss.SSS"));
    }

    public synchronized Path name(String file, String name) throws IOException {
        if (!pending.containsKey(file)) throw new IOException("No pending dictionary: " + file);
        name = name.trim();
        if (name.endsWith(".zdict")) name = name.substring(0, name.length() - 6);
        if (name.isBlank() || name.length() > 100 || name.equals(".") || name.equals("..")
            || name.chars().anyMatch(c -> c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0)
            || name.endsWith(".") || name.endsWith(" ")) throw new IOException("Invalid dictionary name");
        var source = dictionaryPath.resolveSibling(file);
        var target = dictionaryPath.resolveSibling(name + ".zdict");
        if (Files.exists(target)) throw new IOException("Dictionary name already exists");
        var instance = pendingInstances.get(file);
        if (instance == null) instance = source.equals(selectedPath) && dictionary != null
            ? dictionary : ZstdDictionary.fromBytes(readBounded(source));
        Files.move(source, target);
        try {
            persistSelection(target.toString());
        } catch (IOException e) {
            Files.move(target, source);
            throw e;
        }
        dictionary = instance;
        selectedPath = target;
        pending.remove(file);
        pendingInstances.remove(file);
        persistPending();
        logger.info("Dictionary named and applied: " + target);
        return target;
    }

    public synchronized java.util.List<String> expireNames(long now) throws IOException {
        var applied = new java.util.ArrayList<String>();
        for (String file : pendingNames()) {
            var deadline = Long.parseLong(pending.getProperty(file));
            if (deadline > 0 && now >= deadline) {
                applied.add(name(file, "untitled_" + timestamp()).getFileName().toString());
            }
        }
        return applied;
    }

    public ZstdDictionaryStore(Path dictionaryPath, ZstdNetLogger logger) {
        this.dictionaryPath = Objects.requireNonNull(dictionaryPath, "dictionaryPath").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Path dictionaryPath() {
        return dictionaryPath;
    }

    public ZstdDictionary dictionary() {
        return dictionary;
    }

    public Path selectedPath() { return selectedPath; }

    public java.util.List<String> available() throws IOException {
        var files = new java.util.TreeSet<String>();
        var base = dictionaryPath.getParent();
        if (base != null && Files.isDirectory(base)) {
            try (var stream = Files.walk(base)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".zdict"))
                    .forEach(p -> files.add(base.relativize(p).toString()));
            }
        }
        return java.util.List.copyOf(files);
    }

    public synchronized boolean loadSelected() {
        managesSelection = true;
        var selection = dictionaryPath.resolveSibling("dictionary-selection.txt");
        try {
            if (Files.isRegularFile(selection)) {
                var value = Files.readString(selection).trim();
                if (value.equals("none")) {
                    dictionary = null;
                    selectedPath = null;
                    logger.info("ZstdNet dictionary mode: disabled (saved selection)");
                    return false;
                }
                select(Path.of(value));
                return true;
            }
            if (Files.isRegularFile(dictionaryPath)) {
                select(dictionaryPath);
                return true;
            }
            for (var candidate : available()) {
                try {
                    select(Path.of(candidate));
                    return true;
                } catch (IOException | RuntimeException e) {
                    logger.warn("Ignoring dictionary " + candidate + ": " + e.getMessage());
                }
            }
            logger.info("No server dictionary found in " + dictionaryPath.getParent());
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not restore selected dictionary; current dictionary retained: " + e.getMessage());
        }
        return false;
    }

    public synchronized ZstdDictionary select(Path path) throws IOException {
        var file = path.isAbsolute() ? path : dictionaryPath.getParent().resolve(path);
        file = file.toAbsolutePath().normalize();
        var next = pendingInstances.get(file.getFileName().toString());
        if (next == null) next = file.equals(selectedPath) && dictionary != null
            ? dictionary : ZstdDictionary.fromBytes(readBounded(file));
        persistSelection(file.toString());
        dictionary = next;
        selectedPath = file;
        logger.info("Selected dictionary " + file + ", id=" + Long.toUnsignedString(next.id()));
        return next;
    }

    public synchronized void unload() throws IOException {
        persistSelection("none");
        dictionary = null;
        selectedPath = null;
        logger.info("Dictionary disabled for new connections; existing connections retain negotiated dictionaries");
    }

    private void persistSelection(String value) throws IOException {
        var selection = dictionaryPath.resolveSibling("dictionary-selection.txt");
        Files.createDirectories(selection.getParent());
        var temporary = selection.resolveSibling(selection.getFileName() + ".tmp");
        Files.writeString(temporary, value);
        try {
            Files.move(temporary, selection, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, selection, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized boolean load() {
        if (!Files.isRegularFile(dictionaryPath)) {
            dictionary = null;
            return false;
        }
        try {
            dictionary = ZstdDictionary.fromBytes(readBounded(dictionaryPath));
            logger.info("loaded ZstdNet dictionary id=" + Long.toUnsignedString(dictionary.id()) + " size=" + dictionary.size());
            return true;
        } catch (IOException | RuntimeException e) {
            dictionary = null;
            logger.warn("ignored invalid ZstdNet dictionary at " + dictionaryPath + ": " + e.getMessage());
            return false;
        }
    }

    public synchronized ZstdDictionary save(byte[] bytes) throws IOException {
        logger.info("Dictionary save [1/3]: validating " + bytes.length + " bytes");
        ZstdDictionary next = ZstdDictionary.fromBytes(bytes);
        if (namingEnabled) {
            String prefix = shuttingDown ? "temp_" : "pending_";
            Path file = dictionaryPath.resolveSibling(prefix + timestamp() + ".zdict");
            int suffix = 1;
            while (Files.exists(file)) file = dictionaryPath.resolveSibling(prefix + timestamp() + "_" + suffix++ + ".zdict");
            logger.info("Dictionary save [2/3]: writing " + file);
            Files.createDirectories(file.getParent());
            Files.write(file, next.bytes(), java.nio.file.StandardOpenOption.CREATE_NEW);
            String key = file.getFileName().toString();
            pending.setProperty(key, shuttingDown ? "0" : Long.toString(System.currentTimeMillis() + 60_000));
            pendingInstances.put(key, next);
            persistPending();
            if (shuttingDown) {
                persistSelection(file.toString());
            }
            logger.info("Dictionary save [3/3]: saved " + file + "; awaiting naming");
            return next;
        }
        logger.info("Dictionary save [2/3]: writing " + dictionaryPath);
        write(next.bytes());
        if (managesSelection) persistSelection(dictionaryPath.toString());
        dictionary = next;
        selectedPath = dictionaryPath;
        logger.info("Dictionary save [3/3]: complete, id=" + Long.toUnsignedString(next.id()) + " size=" + next.size());
        return next;
    }

    public synchronized ZstdDictionary importFrom(Path source) throws IOException {
        Path normalized = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("dictionary file does not exist: " + normalized);
        }
        return save(readBounded(normalized));
    }

    public synchronized Path export() throws IOException {
        if (dictionary == null) throw new IOException("No dictionary is loaded; train or import one first");
        Path exported = selectedPath == null ? dictionaryPath : selectedPath;
        if (!Files.isRegularFile(exported)) throw new IOException("Selected dictionary file does not exist: " + exported);
        return exported.toAbsolutePath().normalize();
    }

    private static byte[] readBounded(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(ZstdDictionary.MAX_BYTES + 1);
            if (bytes.length > ZstdDictionary.MAX_BYTES) throw new IOException("Dictionary is too large");
            return bytes;
        }
    }

    private void write(byte[] bytes) throws IOException {
        Path parent = dictionaryPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
            var temporary = dictionaryPath.resolveSibling(dictionaryPath.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, dictionaryPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, dictionaryPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
