package com.fixcampus.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import com.fixcampus.api.v1.ApiProblem;
import com.fixcampus.config.AttachmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@EnableConfigurationProperties(AttachmentProperties.class)
public class AttachmentStorage {
    private static final Pattern KEY = Pattern.compile("[a-f0-9]{32}\\.(png|jpg)");
    private static final String DATABASE_MARKER = ".fixcampus-database";
    private static final int MAX_PENDING_CLEANUP = 256;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final FileAttribute<?> FILE_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    private static final Logger log = LoggerFactory.getLogger(AttachmentStorage.class);
    private final Path root;
    private final boolean posix;
    private final byte[] databaseBinding;
    private final ReentrantLock writer = new ReentrantLock(true);
    private final LinkedHashSet<String> pendingCleanup = new LinkedHashSet<>();

    public AttachmentStorage(AttachmentProperties properties, @Value("${spring.datasource.url}") String databaseUrl) {
        root = properties.storageDir().toAbsolutePath().normalize();
        posix = root.getFileSystem().supportedFileAttributeViews().contains("posix");
        try {
            databaseBinding = MessageDigest.getInstance("SHA-256").digest(databaseUrl.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    // Local storage is single-node. Acquire before any DB reads/row locks and hold through commit,
    // so quota reads see preceding writes and reset cannot sweep a file from a concurrent upload.
    public <T> T serializeWrite(Supplier<T> operation) {
        writer.lock();
        try {
            if (!pendingCleanup.isEmpty()) cleanFiles(List.copyOf(pendingCleanup), false);
            return operation.get();
        } finally {
            writer.unlock();
        }
    }

    public String store(AttachmentImages.Prepared image, Predicate<String> existsInDb) {
        String key = UUID.randomUUID().toString().replace("-", "") + "." + AttachmentImages.extension(image.contentType());
        Path file = resolve(key);
        try {
            if (posix) {
                Files.createDirectories(root, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
            } else {
                Files.createDirectories(root);
            }
            validateRoot();
            bindEmptyRoot();
            // The unique file is published to readers only when the DB row commits.
            var output = createFile(file);
            try (output) {
                output.write(image.bytes());
            } catch (IOException e) {
                cleanFiles(List.of(key), false);
                throw e;
            }
        } catch (IOException | DirectoryIteratorException e) {
            throw unavailable();
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new UploadCompletionSynchronization(this, key, existsInDb));
        }
        return key;
    }

    public Optional<byte[]> read(String key) {
        Path file = resolve(key);
        try {
            validateRoot();
            boundToDatabase();
            validateRegularFile(file);
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(input.readAllBytes());
            }
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw unavailable();
        }
    }

    public void deleteAfterCommit(String key) {
        resolve(key);
        afterCommit(() -> cleanFiles(List.of(key), false));
    }

    public void clearAfterCommit(List<String> databaseKeys) {
        afterCommit(() -> {
            var keys = new LinkedHashSet<>(pendingCleanup);
            keys.addAll(databaseKeys);
            cleanFiles(keys, true);
        });
    }

    private void validateRoot() throws IOException {
        BasicFileAttributes attributes = posix
                ? Files.readAttributes(root, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                : Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || !root.equals(root.toRealPath())) {
            throw new IOException("Attachment storage must use a real directory");
        }
        if (attributes instanceof PosixFileAttributes unix) {
            var owner = root.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            if (!owner.equals(unix.owner()) || !DIRECTORY_PERMISSIONS.containsAll(unix.permissions())) {
                throw new IOException("Attachment storage directory has unsafe permissions or ownership");
            }
        }
    }

    private void bindEmptyRoot() throws IOException {
        if (boundToDatabase()) return;
        // A legacy root may contain attachments from other databases. Only adopt an empty root.
        try (var files = Files.newDirectoryStream(root)) {
            for (Path file : files) {
                if (KEY.matcher(file.getFileName().toString()).matches()) return;
            }
        }
        var marker = root.resolve(DATABASE_MARKER);
        final OutputStream output;
        try {
            output = createFile(marker);
        } catch (FileAlreadyExistsException e) {
            if (!boundToDatabase()) throw e;
            return;
        }
        try (output) {
            output.write(databaseBinding);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(marker);
            } catch (IOException cleanupFailure) {
                log.warn("Could not remove an incomplete attachment storage marker");
            }
            throw e;
        }
    }

    private boolean boundToDatabase() throws IOException {
        Path marker = root.resolve(DATABASE_MARKER);
        try {
            validateRegularFile(marker);
            try (var input = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
                if (Arrays.equals(databaseBinding, input.readNBytes(databaseBinding.length + 1))) return true;
            }
        } catch (NoSuchFileException e) {
            return false;
        }
        throw new IOException("Attachment storage is already bound to a different database");
    }

    private static void validateRegularFile(Path file) throws IOException {
        if (!Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
            throw new IOException("Attachment storage expected a regular file");
        }
    }

    private OutputStream createFile(Path file) throws IOException {
        var options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return Channels.newOutputStream(posix
                ? Files.newByteChannel(file, options, FILE_PERMISSIONS)
                : Files.newByteChannel(file, options));
    }

    private void cleanFiles(Collection<String> keys, boolean sweepBoundRoot) {
        final boolean bound;
        try {
            validateRoot();
            bound = boundToDatabase();
        } catch (NoSuchFileException e) {
            pendingCleanup.removeAll(keys);
            return;
        } catch (IOException e) {
            log.warn("Could not access attachment storage for cleanup ({})", e.getClass().getSimpleName());
            keys.forEach(this::queueCleanup);
            return;
        }
        keys.forEach(this::deleteFile);
        if (sweepBoundRoot && bound) {
            try (var files = Files.newDirectoryStream(root, path -> KEY.matcher(path.getFileName().toString()).matches())) {
                for (Path file : files) {
                    String key = file.getFileName().toString();
                    if (!keys.contains(key)) deleteFile(key);
                }
            } catch (IOException | DirectoryIteratorException e) {
                log.warn("Could not finish scanning attachment storage for cleanup ({})", e.getClass().getSimpleName());
            }
        }
    }

    private void deleteFile(String key) {
        if (!KEY.matcher(key).matches()) {
            log.warn("Skipped an invalid attachment storage key during cleanup");
            return;
        }
        Path file = resolve(key);
        try {
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("An attachment storage key is occupied by a directory");
            }
            Files.deleteIfExists(file);
            pendingCleanup.remove(key);
        } catch (IOException e) {
            log.warn("Could not remove an attachment file ({})", e.getClass().getSimpleName());
            queueCleanup(key);
        }
    }

    private void queueCleanup(String key) {
        if (!KEY.matcher(key).matches() || pendingCleanup.contains(key)) return;
        if (pendingCleanup.size() == MAX_PENDING_CLEANUP) pendingCleanup.removeFirst();
        pendingCleanup.add(key);
    }

    private static boolean confirmedAbsent(String key, Predicate<String> existsInDb) {
        try {
            if (!existsInDb.test(key)) return true;
            log.warn("Retained an attachment file after an uncertain database commit");
        } catch (RuntimeException e) {
            log.warn("Could not verify an uncertain attachment commit, retaining the file ({})", e.getClass().getSimpleName());
        }
        return false;
    }

    private Path resolve(String key) {
        if (!KEY.matcher(key).matches()) throw unavailable();
        Path file = root.resolve(key).normalize();
        if (!file.startsWith(root)) throw unavailable();
        return file;
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new AfterCommitSynchronization(action));
        } else {
            action.run();
        }
    }

    public static ApiProblem unavailable() {
        return new ApiProblem(503, "TEMPORARILY_UNAVAILABLE", "附件存储暂不可用");
    }

    private record UploadCompletionSynchronization(AttachmentStorage storage, String key,
                                                   Predicate<String> existsInDb) implements TransactionSynchronization {
        @Override
        public void afterCompletion(int status) {
            if (status == STATUS_ROLLED_BACK
                    || (status == STATUS_UNKNOWN && confirmedAbsent(key, existsInDb))) {
                storage.cleanFiles(List.of(key), false);
            }
        }
    }

    private record AfterCommitSynchronization(Runnable action) implements TransactionSynchronization {
        @Override
        public void afterCommit() {
            action.run();
        }
    }
}
