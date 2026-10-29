package cn.bigfish.collector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/** Synthetic wire data only. This writer does not call the production protocol encoder. */
final class WireFixtures {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    WireFixtures number(int field, long value) { varint(field * 8L); varint(value); return this; }
    WireFixtures text(int field, String value) { return bytes(field, value.getBytes(StandardCharsets.UTF_8)); }
    WireFixtures bytes(int field, byte[] value) {
        varint(field * 8L + 2); varint(value.length); out.writeBytes(value); return this;
    }
    byte[] build() { return out.toByteArray(); }
    private void varint(long value) {
        do {
            int next = (int) (value & 127); value >>>= 7;
            out.write(value == 0 ? next : next | 128);
        } while (value != 0);
    }
    static byte[] common(long id, long room) {
        return new WireFixtures().number(2, id).number(3, room).number(4, 1_793_203_200L).build();
    }
    static byte[] user(long id) { return new WireFixtures().number(1, id).text(3, "测试观众").build(); }
    static byte[] envelope(String method, byte[] payload) {
        return new WireFixtures().text(1, method).bytes(2, payload).build();
    }
    static byte[] gift(long id, long user, long group, long quantity, boolean textCounts) {
        var body = new WireFixtures().bytes(1, common(id, 123)).number(2, 8).bytes(7, user(user))
                .bytes(15, new WireFixtures().number(5, 8).number(12, 1).text(16, "测试礼物").build());
        if (textCounts) body.text(4, "1").text(5, Long.toString(quantity)).text(6, "0").text(11, Long.toString(group));
        else body.number(4, 1).number(5, quantity).number(6, 0).number(11, group);
        return envelope("WebcastGiftMessage", body.build());
    }
    static byte[] chat(long id) {
        return envelope("WebcastChatMessage", new WireFixtures().bytes(1, common(id, 123))
                .bytes(2, user(456)).text(3, "你好").build());
    }
    static byte[] end(long room) {
        return envelope("WebcastControlMessage", new WireFixtures().bytes(1, common(999, room)).number(2, 3).build());
    }
    static byte[] frame(boolean gzip, boolean ack, byte[]... messages) {
        var response = new WireFixtures();
        for (byte[] message : messages) response.bytes(1, message);
        if (ack) response.number(9, 1).text(5, "synthetic-extension");
        return wrap(gzip, response.build());
    }
    static byte[] wrap(boolean gzip, byte[] response) {
        var push = new WireFixtures().number(2, 91).text(7, "msg");
        if (gzip) push.text(6, "gzip");
        return push.bytes(8, gzip ? gzip(response) : response).build();
    }
    static byte[] gzip(byte[] payload) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(payload); }
            return bytes.toByteArray();
        } catch (IOException exception) { throw new AssertionError(exception); }
    }
}
