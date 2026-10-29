package cn.bigfish.speech;

import java.nio.*;

/** Generated PCM tone only, not a fixture claiming to be cloud speech. Contains no provider secrets. */
final class SpeechFixtures {
    static byte[] wav(int samples) {
        ByteBuffer b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(36 + samples * 2).put(new byte[]{'W','A','V','E','f','m','t',' '});
        b.putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16);
        b.put(new byte[]{'d','a','t','a'}).putInt(samples * 2);
        for (int i = 0; i < samples; i++) b.putShort((short)(2000 * StrictMath.sin(2 * StrictMath.PI * 440 * i / 16000)));
        return b.array();
    }
}
