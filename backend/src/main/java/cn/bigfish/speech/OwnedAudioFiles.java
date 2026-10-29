package cn.bigfish.speech;

import cn.bigfish.contract.SpeechPort.AudioAsset;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Owns exact paths, never scavenges/deletes pre-existing audio. One adapter per audio directory. */
final class OwnedAudioFiles implements AutoCloseable {
    static final int MAX_FILES = 8;
    record Ticket(String taskId, Path path, Object fileKey) {}
    private final Path root;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<String, Ticket> owned = new HashMap<>();
    OwnedAudioFiles(Path directory) {
        FileChannel channel = null;
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) throw new IOException();
            root = directory.toRealPath();
            channel = FileChannel.open(root.resolve(".aliyun-nls.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock acquired = channel.tryLock();
            if (acquired == null) throw new IOException();
            lockChannel = channel; lock = acquired;
        } catch (Exception ignored) {
            if (channel != null) try { channel.close(); } catch (IOException unused) { }
            throw new SpeechFailure(SpeechFailure.Code.FILE_IO);
        }
    }
    synchronized Ticket reserve(String taskId) {
        if (owned.containsKey(taskId)) throw new SpeechFailure(SpeechFailure.Code.BUSY);
        if (owned.size() >= MAX_FILES) throw new SpeechFailure(SpeechFailure.Code.FILE_LIMIT);
        try {
            // Count only; orphaned files also consume quota, so restarting cannot grow disk indefinitely.
            int count = 0;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "aliyun-nls-*.wav")) {
                for (Path ignored : entries) if (++count >= MAX_FILES) throw new SpeechFailure(SpeechFailure.Code.FILE_LIMIT);
            }
            Path path = root.resolve("aliyun-nls-" + UUID.randomUUID() + ".wav");
            Files.createFile(path);
            Ticket ticket = new Ticket(taskId, path, key(path));
            owned.put(taskId, ticket);
            return ticket;
        } catch (IOException ignored) { throw new SpeechFailure(SpeechFailure.Code.FILE_IO); }
    }
    synchronized void write(Ticket ticket, byte[] bytes) {
        if (!isOwned(ticket)) throw new SpeechFailure(SpeechFailure.Code.FILE_IO);
        try {
            Files.write(ticket.path(), bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ignored) { throw new SpeechFailure(SpeechFailure.Code.FILE_IO); }
    }
    synchronized boolean release(AudioAsset asset) {
        if (asset == null) return false;
        Ticket ticket = owned.values().stream().filter(t -> t.path().equals(asset.path())).findFirst().orElse(null);
        return ticket != null && release(ticket);
    }
    synchronized boolean releaseTask(String taskId) {
        Ticket ticket = owned.get(taskId);
        return ticket != null && release(ticket);
    }
    synchronized boolean release(Ticket ticket) {
        if (owned.get(ticket.taskId()) != ticket) return false;
        try {
            if (Files.exists(ticket.path(), LinkOption.NOFOLLOW_LINKS)) {
                if (!isOwned(ticket)) throw new SpeechFailure(SpeechFailure.Code.FILE_IO);
                Files.delete(ticket.path()); // Exactly this request's path; no directory/batch cleanup.
            }
            owned.remove(ticket.taskId(), ticket);
            return true;
        } catch (IOException ignored) { throw new SpeechFailure(SpeechFailure.Code.FILE_IO); }
    }
    private boolean isOwned(Ticket ticket) {
        if (owned.get(ticket.taskId()) != ticket || Files.isSymbolicLink(ticket.path())) return false;
        try { return Objects.equals(ticket.fileKey(), key(ticket.path())); }
        catch (IOException ignored) { return false; }
    }
    private static Object key(Path path) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.fileKey() == null) throw new IOException();
        return attrs.fileKey();
    }
    synchronized int outstanding() { return owned.size(); }
    @Override public synchronized void close() {
        try { lock.release(); lockChannel.close(); }
        catch (IOException ignored) { throw new SpeechFailure(SpeechFailure.Code.FILE_IO); }
    }
}
