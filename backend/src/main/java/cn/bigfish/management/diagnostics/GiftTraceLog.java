package cn.bigfish.management.diagnostics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One bounded asynchronous writer. Fixed file slots are overwritten individually; nothing is deleted. */
public final class GiftTraceLog implements Consumer<GiftTrace>, AutoCloseable {
    public static final int CAPACITY=1024, FILES=4, MAX_LINE_BYTES=2048;
    public static final long FILE_BYTES=1024*1024;
    public enum FileState { DISABLED, ACTIVE, UNAVAILABLE, FAILED, CLOSED }
    public record Status(FileState files,long delivered,long persisted,long dropped,long ioFailures,int queued,boolean workerAlive) {}
    private static final Logger log=LoggerFactory.getLogger(GiftTraceLog.class);
    private record Pending(long sequence,GiftTrace trace) {}
    private final ArrayBlockingQueue<Pending> queue;
    private final AtomicLong sequence=new AtomicLong(),delivered=new AtomicLong(),persisted=new AtomicLong(),dropped=new AtomicLong(),ioFailures=new AtomicLong();
    private final Consumer<String> console;
    private final Thread worker;
    private volatile boolean closed;
    private volatile FileState fileState;
    private RingFiles files;

    public GiftTraceLog(Path dataDirectory,boolean persist) {
        this(dataDirectory,persist,CAPACITY,FILES,FILE_BYTES,line -> log.info("gift_trace {}",line));
    }
    /** Bounded test seam; production always uses the constants above. */
    public GiftTraceLog(Path dataDirectory,boolean persist,int capacity,int slots,long bytes,Consumer<String> console) {
        if(capacity<1 || capacity>CAPACITY || slots<1 || slots>FILES || bytes<MAX_LINE_BYTES || bytes>FILE_BYTES)
            throw new IllegalArgumentException("INVALID_GIFT_TRACE_LIMITS");
        this.console=console; queue=new ArrayBlockingQueue<>(capacity);
        fileState=persist ? FileState.ACTIVE : FileState.DISABLED;
        if(persist) try { files=new RingFiles(dataDirectory,slots,bytes); }
        catch(IOException | RuntimeException error) { fileState=FileState.UNAVAILABLE; ioFailures.incrementAndGet(); }
        log.info("gift_trace_configuration persistence={} directory=DATA_DIR/diagnostics/gifts files={} bytesPerFile={} queueCapacity={}",fileState,slots,bytes,capacity);
        if(fileState==FileState.UNAVAILABLE) log.warn("gift_trace_storage reason=FILE_INIT_FAILED");
        worker=Thread.ofPlatform().daemon().name("bigfish-gift-trace").start(this::writeLoop);
    }
    @Override public synchronized void accept(GiftTrace trace) {
        if(trace==null) return;
        if(closed || !queue.offer(new Pending(sequence.incrementAndGet(),trace))) dropped.incrementAndGet();
    }
    public Status status() { return new Status(fileState,delivered.get(),persisted.get(),dropped.get(),ioFailures.get(),queue.size(),worker.isAlive()); }
    private void writeLoop() {
        long reported=0;
        try {
            while(!closed || !queue.isEmpty()) {
                Pending pending=queue.poll(100,TimeUnit.MILLISECONDS);
                if(pending!=null) {
                    String line=pending.trace().json(pending.sequence());
                    byte[] bytes=(line+"\n").getBytes(StandardCharsets.UTF_8);
                    if(bytes.length>MAX_LINE_BYTES) { dropped.incrementAndGet(); continue; }
                    try { console.accept(line); delivered.incrementAndGet(); }
                    catch(RuntimeException error) { dropped.incrementAndGet(); }
                    if(files!=null) try { files.write(bytes); persisted.incrementAndGet(); }
                    catch(IOException | RuntimeException error) {
                        ioFailures.incrementAndGet(); fileState=FileState.FAILED; closeFiles();
                        log.warn("gift_trace_storage reason=FILE_WRITE_FAILED");
                    }
                }
                long lost=dropped.get();
                if(lost!=reported) { log.warn("gift_trace_loss reason=DIAGNOSTIC_BACKPRESSURE_OR_CLOSED dropped={}",lost); reported=lost; }
            }
        } catch(InterruptedException stopped) { Thread.currentThread().interrupt(); }
        finally { closeFiles(); }
    }
    private void closeFiles() {
        if(files!=null) { files.close(); files=null; }
    }
    @Override public void close() {
        synchronized(this) { closed=true; }
        try { worker.join(2000); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if(worker.isAlive()) {
            dropped.addAndGet(queue.size()); queue.clear(); worker.interrupt();
            log.warn("gift_trace_loss reason=SHUTDOWN_BUDGET dropped={}",dropped.get());
        }
        if(fileState==FileState.ACTIVE) fileState=FileState.CLOSED;
    }

    private static final class RingFiles implements AutoCloseable {
        private final Path directory;
        private final int slots;
        private final long maximum;
        private FileChannel lock,output;
        private int index;
        private long size;
        RingFiles(Path data,int slots,long maximum) throws IOException {
            this.slots=slots; this.maximum=maximum;
            Files.createDirectories(data);
            directory=privateDirectory(privateDirectory(data.toRealPath().resolve("diagnostics")).resolve("gifts"));
            try {
                lock=open(directory.resolve("gift-trace.lock"),false);
                if(lock.tryLock()==null) throw new IOException("TRACE_IN_USE");
                FileTime newest=FileTime.fromMillis(Long.MIN_VALUE);
                for(int i=0;i<slots;i++) {
                    Path path=path(i);
                    if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
                        var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                        if(!attrs.isRegularFile() || attrs.size()>maximum) throw new IOException("INVALID_TRACE_FILE");
                        if(attrs.lastModifiedTime().compareTo(newest)>0) { newest=attrs.lastModifiedTime(); index=i; }
                    }
                }
                output=open(path(index),false); size=output.size();
            } catch(IOException | RuntimeException error) { close(); throw error; }
        }
        private Path path(int slot) { return directory.resolve("gift-trace-"+slot+".jsonl"); }
        void write(byte[] bytes) throws IOException {
            if(size+bytes.length>maximum) {
                output.close(); index=(index+1)%slots;
                output=open(path(index),true); size=0;
            }
            ByteBuffer buffer=ByteBuffer.wrap(bytes);
            while(buffer.hasRemaining()) output.write(buffer);
            size+=bytes.length;
        }
        private static Path privateDirectory(Path path) throws IOException {
            boolean posix=Files.getFileStore(path.getParent()).supportsFileAttributeView("posix");
            try {
                if(posix) Files.createDirectory(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                else Files.createDirectory(path);
            } catch(FileAlreadyExistsException exists) {
                if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("INVALID_TRACE_DIRECTORY");
            }
            if(posix) Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));
            return path;
        }
        private static FileChannel open(Path path,boolean truncate) throws IOException {
            if(Files.exists(path,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("INVALID_TRACE_FILE");
            Set<OpenOption> options=Set.of(StandardOpenOption.CREATE,StandardOpenOption.WRITE,
                truncate ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND,LinkOption.NOFOLLOW_LINKS);
            boolean posix=Files.getFileStore(path.getParent()).supportsFileAttributeView("posix");
            if(!posix) return FileChannel.open(path,options);
            var permissions=PosixFilePermissions.fromString("rw-------");
            if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) Files.setPosixFilePermissions(path,permissions);
            return FileChannel.open(path,options,PosixFilePermissions.asFileAttribute(permissions));
        }
        @Override public void close() {
            if(output!=null) try { output.close(); } catch(IOException ignored) { }
            if(lock!=null) try { lock.close(); } catch(IOException ignored) { }
        }
    }
}
