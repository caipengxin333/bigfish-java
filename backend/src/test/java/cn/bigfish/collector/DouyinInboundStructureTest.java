package cn.bigfish.collector;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bigfish.collector.WireFixtures.*;
import static cn.bigfish.collector.InboundDiagnostics.Stage.*;
import static cn.bigfish.collector.InboundDiagnostics.Reason.*;
import static cn.bigfish.collector.InboundDiagnostics.Kind.*;

class DouyinInboundStructureTest {
    @Test void protobufEncodingAndHeaderCompressionAreIndependent() throws Exception {
        // Hand-written PushFrame prefix: logId=91, header compress_type=gzip, encoding=pb, type=msg.
        // Field facts checked against douyinlive-proto 0d661be (MIT); no upstream code/generated schema.
        byte[] prefix = HexFormat.of().parseHex("105b2a150a0d636f6d70726573735f747970651204677a6970320270623a036d7367");
        byte[] payload = new WireFixtures().bytes(8, gzip(new WireFixtures().bytes(1, chat(1)).build())).build();
        var bytes = new java.io.ByteArrayOutputStream(); bytes.writeBytes(prefix); bytes.writeBytes(payload);
        var batch = new DouyinProtocol(2 * 1024 * 1024, new CollectorDiagnostics()).decode(bytes.toByteArray(), "123");
        assertEquals(1, batch.messages().size()); assertEquals("COMMENT", batch.messages().getFirst().kind());
    }

    @Test void pushAckExtensionWinsAndAlsoRequestsAckWithoutResponseFlag() throws Exception {
        var protocol = new DouyinProtocol(2 * 1024 * 1024, new CollectorDiagnostics());
        for (boolean needAck : new boolean[]{false, true}) {
            var response = new WireFixtures().bytes(1, chat(1)).text(5, "response-extension").number(9, needAck ? 1 : 0);
            var push = new WireFixtures().number(2, 91).text(7, "msg")
                    .bytes(5, new WireFixtures().text(1, "im-internal_ext").text(2, "header-extension").build())
                    .bytes(8, response.build()).build();
            var ack = protocol.decode(push, "123").acknowledgement();
            assertNotNull(ack);
            assertEquals("header-extension", ProtoWire.parse(ack).text(8));
        }
    }

    @Test void independentLiteralPlainPbChatAndRepeatedScalarFollowWireSpecification() throws Exception {
        // Entire packet hand-annotated, not emitted by WireFixtures or production encoder:
        // PushFrame 2=91,6=pb,7=msg,8=Response; Response.1=Message;
        // Message.1=WebcastChatMessage,2=body; common=(msg=1,room=123,time=1), user=(7,"A"), content="x".
        byte[] bytes = HexFormat.of().parseHex("105b320270623a036d7367422a0a280a1257656263617374436861744d65737361676512120a061001187b2001120508071a01411a0178");
        var message = new DouyinProtocol(2048, new CollectorDiagnostics()).decode(bytes, "123").messages().getFirst();
        assertEquals("x", message.content()); assertEquals("A", message.username()); assertEquals("7", message.userId());
        assertEquals(1000, message.createdAtMillis());
        assertEquals(2, ProtoWire.parse(HexFormat.of().parseHex("08010802")).number(1));
    }

    @Test void malformedListEntryAndUnknownMethodPreserveAckAndNextValidComment() throws Exception {
        var d = new CollectorDiagnostics();
        var response = new WireFixtures().number(1, 9).bytes(1, new byte[]{0})
                .bytes(1, envelope("unknown-sensitive-marker", new byte[]{0})).bytes(1, chat(3))
                .number(9, 1).text(5, "synthetic-ack").build();
        var result = new DouyinProtocol(2048, d).decode(wrap(true, response), "123");
        assertNotNull(result.acknowledgement()); assertEquals(1, result.messages().size());
        assertEquals(2, d.count(CollectorDiagnostics.Counter.MALFORMED));
        assertEquals(1, d.count(CollectorDiagnostics.Counter.UNKNOWN));
        assertEquals(1, count(d, MESSAGE_LIST, WIRE_MISMATCH, NONE));
        assertEquals(1, count(d, ENVELOPE, INVALID_TAG, NONE));
        assertFalse(d.inbound().snapshot().toString().contains("sensitive-marker"));
        assertFalse(d.inbound().snapshot().toString().contains("synthetic-ack"));
    }

    @Test void exactFailureStagesDistinguishOuterCompressionResponseAckBodyAndRoom() throws Exception {
        var d = new CollectorDiagnostics(); var p = new DouyinProtocol(2048, d);
        assertThrows(IllegalArgumentException.class, () -> p.decode(new byte[]{0x42, 0x7f}, "123"));
        assertEquals(1, count(d, PUSH, LENGTH_OUT_OF_BOUNDS, NONE));
        assertThrows(java.io.IOException.class, () -> p.decode(new WireFixtures().text(6, "gzip").bytes(8, new byte[]{1}).build(), "123"));
        assertEquals(1, count(d, DECOMPRESS, INVALID_GZIP, NONE));
        assertThrows(IllegalArgumentException.class, () -> p.decode(wrap(false, new byte[]{0}), "123"));
        assertEquals(1, count(d, RESPONSE, INVALID_TAG, NONE));
        assertThrows(IllegalArgumentException.class, () -> p.decode(new WireFixtures().bytes(8, new WireFixtures().number(9, 1).build()).build(), "123"));
        assertEquals(1, count(d, ACK_META, ACK_LOG_MISSING, NONE));
        var result = p.decode(frame(false, true, envelope("WebcastChatMessage", new byte[]{0x1a, 0x7f}), end(999), chat(4)), "123");
        assertEquals(1, result.messages().size()); assertNotNull(result.acknowledgement());
        assertEquals(1, count(d, BODY, LENGTH_OUT_OF_BOUNDS, COMMENT));
        assertEquals(1, count(d, ROOM, ROOM_MISMATCH, CONTROL));
    }

    @Test void unknownCompressionConflictsAndAckCapsRemainStrict() {
        for (byte[][] headers : new byte[][][]{
                {header("compress_type", "br")},
                {header("compress_type", "gzip"), header("compress_type", "identity")},
                {header("im-internal_ext", "a"), header("im-internal_ext", "b")},
                {header("im-internal_ext", "x".repeat(8193))}}) {
            var d = new CollectorDiagnostics(); var push = new WireFixtures().number(2, 91).text(7, "msg");
            for (byte[] header : headers) push.bytes(5, header);
            push.bytes(8, new WireFixtures().bytes(1, chat(1)).build());
            assertThrows(IllegalArgumentException.class, () -> new DouyinProtocol(20000, d).decode(push.build(), "123"));
            assertTrue(d.inbound().snapshot().counts().keySet().stream().anyMatch(k -> k.stage() == PAYLOAD && k.reason() != OK));
        }
        var d = new CollectorDiagnostics();
        assertThrows(IllegalArgumentException.class, () -> new DouyinProtocol(2048, d).decode(new WireFixtures().text(6, "sensitive-encoding").build(), "123"));
        assertEquals(1, count(d, PAYLOAD, UNSUPPORTED_ENCODING, NONE));
        assertFalse(d.inbound().snapshot().toString().contains("sensitive-encoding"));
    }

    @Test void diagnosticsKeepOnlyThreeShapesPerFailureAndNeverExceptionText() {
        var d = new CollectorDiagnostics(); var p = new DouyinProtocol(2048, d);
        for (int i = 0; i < 1000; i++) assertThrows(IllegalArgumentException.class, () -> p.decode(new byte[]{0x42, 0x7f}, "123"));
        assertEquals(1000, count(d, PUSH, LENGTH_OUT_OF_BOUNDS, NONE));
        assertEquals(3, d.inbound().snapshot().samples().size());
        var field = d.inbound().snapshot().samples().getFirst().shape().fields().getFirst();
        assertEquals(8, field.number()); assertEquals(2, field.wire()); assertEquals(127, field.length());
        for (var stage : InboundDiagnostics.Stage.values()) for (var reason : InboundDiagnostics.Reason.values())
            for (var kind : InboundDiagnostics.Kind.values()) d.inbound().record(stage, reason, kind, InboundDiagnostics.Shape.bytes(1));
        assertTrue(d.inbound().snapshot().counts().size() <= 128);
        assertTrue(d.inbound().snapshot().samples().size() <= 64);
        d.inbound().failure(BODY, COMMENT, new IllegalArgumentException("sensitive-exception", new RuntimeException("sensitive-cause")), 12);
        assertFalse(d.inbound().snapshot().toString().contains("sensitive"));
    }

    private static byte[] header(String key, String value) { return new WireFixtures().text(1, key).text(2, value).build(); }
    private static long count(CollectorDiagnostics d, InboundDiagnostics.Stage stage, InboundDiagnostics.Reason reason, InboundDiagnostics.Kind kind) {
        return d.inbound().snapshot().counts().getOrDefault(new InboundDiagnostics.Key(stage, reason, kind), 0L);
    }
}
