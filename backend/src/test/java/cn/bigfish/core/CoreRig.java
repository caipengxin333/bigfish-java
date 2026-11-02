package cn.bigfish.core;

import cn.bigfish.configuration.ConfigStore;
import cn.bigfish.contract.*;
import cn.bigfish.management.RuntimeEngine;
import cn.bigfish.playback.*;
import cn.bigfish.speech.LocalTestSpeech;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

final class CoreRig implements AutoCloseable {
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-29T00:00:00Z");
        public synchronized Instant instant() { return now; }
        synchronized void advance(Duration duration) { now = now.plus(duration); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
    static final class Peer implements PlaybackCoordinator.Peer {
        final String id = UUID.randomUUID().toString();
        final List<String> types = new ArrayList<>();
        final List<PlaybackTask> tasks = new ArrayList<>();
        boolean closed;
        public String id() { return id; }
        public synchronized boolean send(String type, String session, Object payload) {
            types.add(type); if (payload instanceof PlaybackTask task) tasks.add(task); return !closed;
        }
        public void close() { closed = true; }
    }
    final MutableClock clock = new MutableClock();
    final ConfigStore configs;
    final AudioAssets assets;
    final LocalTestSpeech speech;
    final PlaybackCoordinator playback;
    final RuntimeEngine engine;
    final Peer peer = new Peer();
    final String session;
    CoreRig(Path directory, ConversationPort conversation) throws Exception {
        this(directory,conversation,null);
    }
    CoreRig(Path directory, ConversationPort conversation, SpeechPort customSpeech) throws Exception {
        this(directory,conversation,customSpeech,ignored -> {});
    }
    CoreRig(Path directory, ConversationPort conversation, SpeechPort customSpeech,
            java.util.function.Consumer<cn.bigfish.management.diagnostics.GiftTrace> trace) throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("test.db")));
        jdbc.execute("CREATE TABLE app_config(id INTEGER PRIMARY KEY,version INTEGER NOT NULL,content_json TEXT NOT NULL,updated_at TEXT NOT NULL)");
        configs = new ConfigStore(jdbc,JsonMapper.builder().findAndAddModules().build());
        assets = new AudioAssets(directory.resolve("audio"),clock);
        speech = new LocalTestSpeech(directory.resolve("audio"));
        playback = new PlaybackCoordinator(clock,assets);
        engine = new RuntimeEngine(configs,conversation,() -> { throw new IllegalStateException(); },
            customSpeech == null ? speech : customSpeech,playback,assets,clock,Duration.ofSeconds(20),Duration.ofSeconds(5),trace);
        session = engine.startMock();
        connect(peer);
    }
    void connect(Peer target) {
        playback.hello(target,target.id());
        playback.ready(target,engine.status().sessionId(),target.id(),playback.status().generation());
    }
    LiveEvent event(String id, LiveEvent.Type type) {
        Map<String,Object> payload = switch (type) {
            case COMMENT -> Map.of("content","你好");
            case GIFT -> Map.of("giftId","1","giftName","小心心","quantity",1,"comboId","combo","unitValue",1,"valueUnit","DOUYIN_DIAMOND");
            case LIKE -> Map.of("count",1); default -> Map.of();
        };
        return new LiveEvent(1,id,session,"mock-room",type,"u","测试观众",Instant.MAX,payload);
    }
    void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < until) { engine.tick(); Thread.sleep(5); }
        assertThat(condition.getAsBoolean()).isTrue();
    }
    void ack(PlaybackTask task, PlaybackAck.State state) {
        playback.acknowledge(peer,new PlaybackAck(task.taskId(),session,peer.id(),playback.status().generation(),state,clock.instant(),null));
    }
    public void close() { engine.close(); }
}
