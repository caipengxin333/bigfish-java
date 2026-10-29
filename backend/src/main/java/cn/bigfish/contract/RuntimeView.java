package cn.bigfish.contract;

/** Public management view, deliberately independent of engine implementation records. */
public record RuntimeView(String sessionId, String state, String mode, String conversationMode, String speechMode,
                          String roomId, long savedConfigVersion, long appliedConfigVersion,
                          Queues queues, Player player, Collector collector, long dropped, long failures) {
    public record Queue(int depth, int capacity) {}
    public record Queues(Queue comments, Queue templates, Queue synthesis, Queue playback,
                         boolean modelActive, boolean speechActive) {}
    public record Player(boolean connected, boolean ready, boolean playing, long leaseGeneration) {}
    /** Counters refer to the most recent replay, engine counters to the application lifetime. */
    public record Collector(String mode, String state, long received, long emitted, long duplicates,
                            long rejected, String lastError) {}
}
