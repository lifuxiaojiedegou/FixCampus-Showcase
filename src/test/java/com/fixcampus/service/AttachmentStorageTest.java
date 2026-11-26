package com.fixcampus.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.function.Predicate;
import com.fixcampus.api.v1.ApiProblem;
import com.fixcampus.config.AttachmentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED;
import static org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK;
import static org.springframework.transaction.support.TransactionSynchronization.STATUS_UNKNOWN;

class AttachmentStorageTest {
    private static final AttachmentImages.Prepared IMAGE =
            new AttachmentImages.Prepared("test.png", "image/png", 1, 1, new byte[]{1, 2, 3}, "unused");

    @TempDir Path temp;

    @Test
    void confirmedRollbackCleansFileWithoutCheckingTheDatabase() {
        var root = temp.resolve("test-db");
        var storage = storage(root, "test-db");

        String key = storeThenComplete(storage, root, STATUS_ROLLED_BACK, ignored -> {
            throw new AssertionError("A confirmed rollback does not need a database read");
        });

        assertThat(root.resolve(key)).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uncertainCommitKeepsFileOnlyWhenTheRowExists(boolean rowExists) {
        var root = temp.resolve("test-db");
        var storage = storage(root, "test-db");

        String key = storeThenComplete(storage, root, STATUS_UNKNOWN, checkedKey -> {
            assertThat(root.resolve(checkedKey)).exists();
            return rowExists;
        });

        assertThat(Files.exists(root.resolve(key))).isEqualTo(rowExists);
    }

    @Test
    void uncertainCommitKeepsFileWhenTheDatabaseCannotBeChecked() {
        var root = temp.resolve("test-db");
        var storage = storage(root, "test-db");

        String key = storeThenComplete(storage, root, STATUS_UNKNOWN, ignored -> {
            throw new IllegalStateException("Test connection failure");
        });

        assertThat(root.resolve(key)).exists();
    }

    @Test
    void committedDeleteToleratesDiskFailureAndRetriesOnTheNextWrite() throws IOException {
        var root = temp.resolve("test-db");
        var storage = storage(root, "test-db");
        String key = store(storage);
        Path file = root.resolve(key);
        Files.delete(file);
        Files.createDirectory(file);

        storage.serializeWrite(() -> {
            afterCommit(() -> {
                storage.deleteAfterCommit(key);
                assertThat(file).isDirectory();
            });
            return null;
        });
        assertThat(file).isDirectory();

        Files.delete(file);
        Files.write(file, IMAGE.bytes());
        storage.serializeWrite(() -> {
            assertThat(file).doesNotExist();
            return null;
        });
    }

    @Test
    void resetContinuesAfterAFileFailureAndRetriesFailedKeys() throws IOException {
        var root = temp.resolve("test-db");
        var storage = storage(root, "test-db");
        String blocked = store(storage);
        String healthy = store(storage);
        Path blockedPath = root.resolve(blocked);
        Files.delete(blockedPath);
        Files.createDirectory(blockedPath);
        Path orphan = Files.write(root.resolve("e".repeat(32) + ".jpg"), IMAGE.bytes());
        Path unrelated = Files.writeString(root.resolve("keep.txt"), "keep");

        storage.serializeWrite(() -> {
            afterCommit(() -> {
                storage.clearAfterCommit(List.of(blocked, healthy));
                assertThat(root.resolve(healthy)).exists();
            });
            return null;
        });
        assertThat(root.resolve(healthy)).doesNotExist();
        assertThat(orphan).doesNotExist();
        assertThat(unrelated).exists();
        assertThat(blockedPath).isDirectory();

        Files.delete(blockedPath);
        Files.write(blockedPath, IMAGE.bytes());
        storage.clearAfterCommit(List.of());
        assertThat(blockedPath).doesNotExist();
    }

    @Test
    void clearingAMarkedRootCannotDeleteFilesFromAnotherDatabase() throws IOException {
        var firstRoot = temp.resolve("first-db");
        var secondRoot = temp.resolve("second-db");
        var first = storage(firstRoot, "first-db");
        var second = storage(secondRoot, "second-db");
        String firstKey = store(first);
        String secondKey = store(second);
        Path firstOrphan = Files.write(firstRoot.resolve("e".repeat(32) + ".jpg"), IMAGE.bytes());
        Path secondOrphan = Files.write(secondRoot.resolve(firstOrphan.getFileName()), IMAGE.bytes());
        var mistakenOverride = storage(firstRoot, "second-db");

        mistakenOverride.clearAfterCommit(List.of(firstKey));
        assertThat(firstRoot.resolve(firstKey)).exists();
        assertThatThrownBy(() -> mistakenOverride.read(firstKey)).satisfies(AttachmentStorageTest::assertUnavailable);
        assertThatThrownBy(() -> store(mistakenOverride)).satisfies(AttachmentStorageTest::assertUnavailable);

        first.clearAfterCommit(List.of(firstKey));
        assertThat(firstRoot).isDirectory();
        assertThat(firstRoot.resolve(firstKey)).doesNotExist();
        assertThat(firstOrphan).doesNotExist();
        assertThat(secondRoot.resolve(secondKey)).exists();
        assertThat(secondOrphan).exists();
    }

    @Test
    void clearingAnUnmarkedRootKeepsFilesOutsideTheDatabaseSnapshot() throws IOException {
        var storage = storage(temp, "test-db");
        String databaseKey = "a".repeat(32) + ".png";
        Path databaseFile = Files.write(temp.resolve(databaseKey), IMAGE.bytes());
        Path unrelated = Files.write(temp.resolve("b".repeat(32) + ".jpg"), IMAGE.bytes());

        storage.clearAfterCommit(List.of(databaseKey));

        assertThat(databaseFile).doesNotExist();
        assertThat(unrelated).exists();
    }

    @Test
    void storageWorksOnCurrentFilesystemAndEnforcesPosixPermissionsWhereSupported() throws IOException {
        var root = temp.resolve("test-db");
        var storage = storage(root, "test-db");
        String key = store(storage);
        assertThat(storage.read(key).orElseThrow()).isEqualTo(IMAGE.bytes());

        if (root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            var privateDirectory = PosixFilePermissions.fromString("rwx------");
            assertThat(Files.getPosixFilePermissions(root)).isEqualTo(privateDirectory);
            assertThat(Files.getPosixFilePermissions(root.resolve(key)))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
            try {
                Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx--x---"));
                assertThatThrownBy(() -> storage.read(key)).satisfies(AttachmentStorageTest::assertUnavailable);
                assertThatThrownBy(() -> store(storage)).satisfies(AttachmentStorageTest::assertUnavailable);
                storage.clearAfterCommit(List.of(key));
                assertThat(root.resolve(key)).exists();
            } finally {
                Files.setPosixFilePermissions(root, privateDirectory);
            }
        }
    }

    @Test
    void missingFileIsDistinctFromUnsafeStorage() throws IOException {
        var root = temp.resolve("test-db");
        var storage = storage(root, "test-db");
        String key = store(storage);
        Path file = root.resolve(key);
        Files.delete(file);
        assertThat(storage.read(key)).isEmpty();

        Files.createDirectory(file);
        assertThatThrownBy(() -> storage.read(key)).satisfies(AttachmentStorageTest::assertUnavailable);
    }

    @Test
    void rejectsAStorageRootThatIsNotARealDirectory() throws IOException {
        var realRoot = temp.resolve("real-db");
        String key = store(storage(realRoot, "test-db"));
        var otherRoot = temp.resolve("other-db");
        if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createSymbolicLink(otherRoot, realRoot);
        } else {
            Files.createFile(otherRoot);
        }
        var unsafe = storage(otherRoot, "test-db");

        assertThatThrownBy(() -> unsafe.read(key)).satisfies(AttachmentStorageTest::assertUnavailable);
        assertThatThrownBy(() -> store(unsafe)).satisfies(AttachmentStorageTest::assertUnavailable);
        unsafe.clearAfterCommit(List.of(key));
        assertThat(realRoot.resolve(key)).exists();
    }

    private static void assertUnavailable(Throwable error) {
        assertThat(error).isInstanceOfSatisfying(ApiProblem.class, problem -> {
            assertThat(problem.status()).isEqualTo(503);
            assertThat(problem.code()).isEqualTo("TEMPORARILY_UNAVAILABLE");
        });
    }

    private static AttachmentStorage storage(Path root, String database) {
        return new AttachmentStorage(new AttachmentProperties(root, 2048, 32, 32, 1024, 3, 2, 4096),
                "jdbc:mysql://localhost/" + database);
    }

    private static String store(AttachmentStorage storage) {
        return storage.serializeWrite(() -> storage.store(IMAGE, key -> false));
    }

    private static String storeThenComplete(AttachmentStorage storage, Path root, int status, Predicate<String> exists) {
        return storage.serializeWrite(() -> {
            TransactionSynchronizationManager.initSynchronization();
            try {
                String key = storage.store(IMAGE, exists);
                assertThat(root.resolve(key)).exists();
                TransactionSynchronizationManager.getSynchronizations().forEach(callback -> callback.afterCompletion(status));
                return key;
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        });
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            action.run();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(callback -> callback.afterCompletion(STATUS_COMMITTED));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
