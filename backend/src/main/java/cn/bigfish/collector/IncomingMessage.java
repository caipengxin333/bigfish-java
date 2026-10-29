package cn.bigfish.collector;

/** A small allowlist of protocol fields, also used by synthetic JSONL replays. No raw metadata. */
public record IncomingMessage(String kind, String messageId, String roomId, String userId,
                              String username, long createdAtMillis, String content,
                              String giftId, String giftName, long cumulativeQuantity,
                              String comboId, Long unitValue, long likeCount) {
    public IncomingMessage {
        kind = text(kind); messageId = text(messageId); roomId = text(roomId);
        userId = text(userId); username = text(username); content = text(content);
        giftId = text(giftId); giftName = text(giftName); comboId = text(comboId);
    }

    private static String text(String value) { return value == null ? "" : value; }
}
