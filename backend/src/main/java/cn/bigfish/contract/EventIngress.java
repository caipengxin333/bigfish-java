package cn.bigfish.contract;
/** Non-blocking intake; caller must not retry REJECTED_FULL in a tight loop. */
public interface EventIngress {
    Result accept(LiveEvent event);
    enum Result { ACCEPTED, DUPLICATE, REJECTED_FULL, REJECTED_SESSION, INVALID }
}
