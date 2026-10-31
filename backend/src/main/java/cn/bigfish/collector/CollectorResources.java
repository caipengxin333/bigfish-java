package cn.bigfish.collector;

/** Owned worker threads, business queue and attached transport handles; not JVM-wide thread counts. */
public record CollectorResources(int sourceThreads, int dispatchThreads, int queuedEvents, int attachedTransports) {
    public boolean released() { return sourceThreads == 0 && dispatchThreads == 0 && queuedEvents == 0 && attachedTransports == 0; }
}
