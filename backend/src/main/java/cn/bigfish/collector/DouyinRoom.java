package cn.bigfish.collector;

/** Small, in-memory room result. Anchor metadata is not included in diagnostics or probe output. */
public record DouyinRoom(String webRoomId, String internalRoomId, String anchorId, String anchorName, boolean live) {
    public DouyinRoom {
        if (webRoomId == null || !webRoomId.matches("[0-9]{1,30}")
                || internalRoomId == null || !internalRoomId.matches("[0-9]{1,30}")
                || anchorId == null || anchorName == null || anchorName.length() > 1024) {
            throw new IllegalArgumentException("Invalid room metadata");
        }
    }
    @Override public String toString() { return "DouyinRoom[redacted]"; }
}
