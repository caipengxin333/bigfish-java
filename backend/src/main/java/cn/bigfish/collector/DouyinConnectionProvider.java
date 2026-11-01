package cn.bigfish.collector;

/**
 * Integration seam for a separately authorized room resolver/signer. Called anew on each reconnect.
 * Must honor interruption and enforce its own overall timeout. Never log credentials/URLs/exceptions.
 * There is deliberately no bundled signature implementation or automatic mock fallback.
 */
@FunctionalInterface
public interface DouyinConnectionProvider {
    DouyinConnection resolve(String webRoomId) throws Exception;

    /** Allows a resolver to detect a new broadcast before doing unnecessary signing work. */
    default DouyinConnection resolve(String webRoomId, String expectedInternalRoomId) throws Exception {
        return resolve(webRoomId);
    }

    /** Permanent configuration/license/unsupported-room problem, not a transient network failure. */
    final class UnavailableException extends Exception {
        public UnavailableException() { super("Douyin connection provider unavailable"); }
    }
}
