package mys.zstdnet.reborn.core.dictionary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryLifecycleTest {
    @TempDir Path directory;
    @Test void exportsImportsAndPreservesDictionaryOnInvalidImport() throws Exception {
        var original = DictionaryFixtures.dictionary();
        var store = new ZstdDictionaryStore(directory.resolve("config/dictionary.zdict"), DictionaryFixtures.LOGGER);
        store.save(original.bytes());
        Path exported = store.export();
        assertArrayEquals(original.bytes(), Files.readAllBytes(exported));
        var imported = new ZstdDictionaryStore(directory.resolve("other/dictionary.zdict"), DictionaryFixtures.LOGGER);
        imported.importFrom(exported);
        assertTrue(imported.load());
        assertArrayEquals(original.bytes(), imported.dictionary().bytes());
        Path invalid = directory.resolve("invalid.zdict");
        Files.write(invalid, new byte[300]);
        assertThrows(java.io.IOException.class, () -> imported.importFrom(invalid));
        assertArrayEquals(original.bytes(), imported.dictionary().bytes());
        byte[] corruptTables = new byte[300];
        System.arraycopy(original.bytes(), 0, corruptTables, 0, 8);
        assertThrows(java.io.IOException.class, () -> ZstdDictionary.fromBytes(corruptTables));
    }

    @Test void trainingFinalizesAndReportsResult() throws Exception {
        var store = new ZstdDictionaryStore(directory.resolve("dictionary.zdict"), DictionaryFixtures.LOGGER);
        try (var trainer = new ZstdDictionaryTrainer(store, DictionaryFixtures.LOGGER)) {
            assertTrue(trainer.start(null, 3));
            assertTrue(trainer.status().remainingMillis() > 590_000);
            assertFalse(trainer.start(null, 3));
            for (byte[] sample : DictionaryFixtures.samples()) trainer.capture(sample);
            assertEquals(300, trainer.status().sampleCount());
            assertTrue(trainer.stopAndFinalize());
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (trainer.status().training() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(trainer.status().training());
            assertNotNull(store.dictionary(), trainer.status().result());
            assertTrue(Files.exists(store.dictionaryPath()));
        }
    }

    @Test void closeAbortsCollectionAndCannotRestart() throws Exception {
        var store = new ZstdDictionaryStore(directory.resolve("dictionary.zdict"), DictionaryFixtures.LOGGER);
        var trainer = new ZstdDictionaryTrainer(store, DictionaryFixtures.LOGGER);
        assertTrue(trainer.start(Duration.ofMinutes(10), 3));
        for (byte[] sample : DictionaryFixtures.samples()) trainer.capture(sample);
        trainer.close();
        assertFalse(trainer.status().training());
        assertFalse(trainer.start(null, 3));
        assertFalse(trainer.stopAndFinalize());
        assertFalse(Files.exists(store.dictionaryPath()));
    }

    @Test void insufficientSamplesDoNotReplaceExistingDictionary() throws Exception {
        var store = new ZstdDictionaryStore(directory.resolve("dictionary.zdict"), DictionaryFixtures.LOGGER);
        var dictionary = store.save(DictionaryFixtures.dictionary().bytes());
        try (var trainer = new ZstdDictionaryTrainer(store, DictionaryFixtures.LOGGER)) {
            trainer.start(Duration.ofMinutes(1), 3);
            trainer.capture(new byte[]{1});
            trainer.stopAndFinalize();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (trainer.status().training() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(trainer.status().training());
            assertTrue(trainer.status().result().startsWith("failed:"));
            assertSame(dictionary, store.dictionary());
        }
    }
}
