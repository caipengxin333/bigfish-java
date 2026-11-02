package cn.bigfish.core;

import cn.bigfish.management.diagnostics.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class GiftTraceLogTest {
    @TempDir Path directory;
    static GiftTrace sample() {
        return GiftTrace.business(Instant.EPOCH,UUID.randomUUID().toString(),UUID.randomUUID().toString(),
            GiftTrace.Phase.VALIDATED,GiftTrace.Reason.NONE,"",1,2);
    }
    Path logs() { return directory.resolve("diagnostics/gifts"); }
    List<Path> files() throws Exception {
        try(var paths=Files.list(logs())) { return paths.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList(); }
    }
    @Test void persistsMoreThanTwentyAcrossRestartAndKeepsPrivateFixedFiles() throws Exception {
        try(var log=new GiftTraceLog(directory,true,1024,4,GiftTraceLog.FILE_BYTES,ignored -> {})) {
            for(int i=0;i<80;i++) log.accept(sample());
            log.close();
            assertThat(log.status().persisted()).isEqualTo(80); assertThat(log.status().dropped()).isZero();
            assertThat(log.status().workerAlive()).isFalse();
        }
        try(var log=new GiftTraceLog(directory,true,1024,4,GiftTraceLog.FILE_BYTES,ignored -> {})) { log.accept(sample()); }
        assertThat(files()).hasSize(1);
        assertThat(Files.readAllLines(files().getFirst())).hasSize(81);
        if(Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(logs())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(files().getFirst())).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
        try(var children=Files.list(logs())) { assertThat(children.map(p -> p.getFileName().toString()).toList())
            .containsExactlyInAnyOrder("gift-trace.lock","gift-trace-0.jsonl"); }
    }
    @Test void ringOverwritesOnlyFixedSlotsAndPreservesCompleteJsonLinesWithinByteLimit() throws Exception {
        try(var log=new GiftTraceLog(directory,true,1024,2,2048,ignored -> {})) {
            for(int i=0;i<200;i++) log.accept(sample());
            log.close(); assertThat(log.status().persisted()).isEqualTo(200);
            assertThat(log.status().ioFailures()).isZero();
        }
        assertThat(files()).hasSize(2); var mapper=JsonMapper.builder().build();
        List<Long> sequences=new ArrayList<>();
        for(Path path:files()) {
            assertThat(Files.size(path)).isBetween(1L,2048L);
            for(String line:Files.readAllLines(path)) sequences.add(mapper.readTree(line).get("sequence").longValue());
        }
        assertThat(sequences).contains(200L).doesNotContain(1L);
        try(var children=Files.list(logs())) { assertThat(children.count()).isEqualTo(3); }
    }
    @Test void blockedWriterCannotBlockIngressAndQueueAndLossCountersRemainBounded() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var log=new GiftTraceLog(directory,false,2,1,2048,line -> {
            entered.countDown();
            try { if(!release.await(2,TimeUnit.SECONDS)) throw new AssertionError("writer gate timeout"); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        })) {
            log.accept(sample()); assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            var producer=CompletableFuture.runAsync(() -> { for(int i=0;i<100;i++) log.accept(sample()); });
            producer.get(1,TimeUnit.SECONDS);
            assertThat(log.status().queued()).isEqualTo(2); assertThat(log.status().dropped()).isEqualTo(98);
            release.countDown(); log.close();
            assertThat(log.status().workerAlive()).isFalse(); assertThat(log.status().delivered()).isEqualTo(3);
            log.accept(sample()); assertThat(log.status().dropped()).isEqualTo(99); assertThat(log.status().queued()).isZero();
            assertThat(Files.exists(logs())).isFalse();
        } finally { release.countDown(); }
    }
    @Test void directoryAndFileSymlinksCannotReadOrOverwriteOutsideDiagnosticRoot() throws Exception {
        Path outside=Files.createDirectory(directory.resolve("outside"));
        Path first=Files.createDirectory(directory.resolve("case1"));
        Files.createSymbolicLink(first.resolve("diagnostics"),outside);
        Path second=Files.createDirectories(directory.resolve("case2/diagnostics/gifts"));
        Path sentinel=Files.writeString(outside.resolve("secret"),"synthetic-private-sentinel");
        Files.createSymbolicLink(second.resolve("gift-trace-0.jsonl"),sentinel);
        for(Path data:List.of(first,directory.resolve("case2"))) {
            var console=new AtomicInteger();
            try(var log=new GiftTraceLog(data,true,4,2,2048,line -> console.incrementAndGet())) {
                assertThat(log.status().files()).isEqualTo(GiftTraceLog.FileState.UNAVAILABLE);
                log.accept(sample()); log.close(); assertThat(console).hasValue(1);
                assertThat(log.status().ioFailures()).isEqualTo(1); assertThat(log.status().persisted()).isZero();
            }
        }
        assertThat(Files.readString(sentinel)).isEqualTo("synthetic-private-sentinel");
    }
    @Test void exclusiveLockRejectsSecondWriterThenReleasesForNextProcess() throws Exception {
        try(var first=new GiftTraceLog(directory,true,4,2,2048,ignored -> {})) {
            assertThat(first.status().files()).isEqualTo(GiftTraceLog.FileState.ACTIVE);
            try(var second=new GiftTraceLog(directory,true,4,2,2048,ignored -> {})) {
                assertThat(second.status().files()).isEqualTo(GiftTraceLog.FileState.UNAVAILABLE);
            }
        }
        try(var third=new GiftTraceLog(directory,true,4,2,2048,ignored -> {})) {
            assertThat(third.status().files()).isEqualTo(GiftTraceLog.FileState.ACTIVE);
        }
    }
    @Test void rotationWriteFailureDisablesFilesOnceButKeepsConsoleAndWorkerUsable() throws Exception {
        var console=new AtomicInteger();
        try(var log=new GiftTraceLog(directory,true,1024,2,2048,line -> console.incrementAndGet())) {
            // A known next slot becomes unusable after startup; no file is removed or replaced.
            Files.createDirectory(logs().resolve("gift-trace-1.jsonl"));
            for(int i=0;i<40;i++) log.accept(sample());
            log.close();
            assertThat(log.status().files()).isEqualTo(GiftTraceLog.FileState.FAILED);
            assertThat(log.status().ioFailures()).isEqualTo(1);
            assertThat(log.status().persisted()).isBetween(1L,10L);
            assertThat(console).hasValue(40); assertThat(log.status().dropped()).isZero();
            assertThat(log.status().workerAlive()).isFalse();
        }
    }
    @Test void runtimeHistoryExpiryAndNewSessionDoNotEraseLocalFiles() throws Exception {
        var clock=new CoreRig.MutableClock();
        try(var log=new GiftTraceLog(directory,true,1024,4,GiftTraceLog.FILE_BYTES,ignored -> {})) {
            var diagnostics=new RuntimeDiagnostics(clock,t -> log.accept(GiftTrace.lifecycle(t)));
            String session=UUID.randomUUID().toString(); diagnostics.begin(session);
            for(int i=0;i<40;i++) {
                String task=UUID.randomUUID().toString(); diagnostics.received(session,task,cn.bigfish.contract.LiveEvent.Type.GIFT,1);
                diagnostics.mark(session,task,RuntimeDiagnostics.Stage.DROPPED,RuntimeDiagnostics.Reason.QUEUE_FULL);
            }
            clock.advance(Duration.ofMinutes(6)); assertThat(diagnostics.snapshot().history()).isEmpty();
            diagnostics.begin(UUID.randomUUID().toString()); log.close();
            assertThat(Files.readAllLines(files().getFirst())).hasSize(80);
        }
    }
    @Test void arbitraryLabelsAndExternalIdentifiersCannotEnterJson() throws Exception {
        var row=GiftTrace.business(Instant.EPOCH,"secret-session","cookie-private",GiftTrace.Phase.MERGED_INTO,
            GiftTrace.Reason.GIFT_MERGED,"upstream-private",1,1);
        String json=row.json(1);
        assertThat(json).doesNotContain("secret","cookie","private","upstream");
        assertThat(JsonMapper.builder().build().readTree(json).get("taskId").textValue()).isEmpty();
        assertThatThrownBy(() -> new GiftTrace(Instant.EPOCH,"","","arbitrary-private-code","NONE","",1,0,0,0))
            .hasMessage("INVALID_GIFT_TRACE_CODE");
    }
}
