/*
 * Java adaptation of jwwsjlm/douyinLive internal/webcastsign/native.go
 * Revision: af34f7c2ea2c4df90c6f4659e967fb31815d9255
 * https://github.com/jwwsjlm/douyinLive/blob/af34f7c2ea2c4df90c6f4659e967fb31815d9255/internal/webcastsign/native.go
 * Changes: Java byte handling, standard Base64 translation, bounded SecureRandom calls,
 * and fixed diagnostics. No JavaScript, VM environment, fallback or protobuf sources are included.
 *
 * MIT License
 * Copyright (c) 2025 今晚打打
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package cn.bigfish.collector;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Protocol compatibility primitive; MD5/RC4 here are not used to protect stored credentials. */
final class NativeWebcastSignature {
    private static final String STANDARD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String WEBCAST = "Dkdpgh4ZKsQB80/Mfvw36XI1R25+WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe";
    private static final byte[] EMPTY_DIGEST = md5(md5(new byte[0]));
    private final SecureRandom random;
    private int counter;

    NativeWebcastSignature() { this(new SecureRandom()); }
    NativeWebcastSignature(SecureRandom random) { this.random = random; }

    synchronized String sign(byte[] stub) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        boolean flag = (random.nextInt(256) & 1) != 0;
        counter = (counter + 1) & 63;
        return withValues(stub, counter, flag, random.nextInt(255), random.nextInt(255));
    }

    static String withValues(byte[] stub, int sequence, boolean flag, int payloadRandom, int keyRandom) {
        if (stub == null || stub.length != 16 || sequence < 0 || sequence > 63
                || payloadRandom < 0 || payloadRandom > 254 || keyRandom < 0 || keyRandom > 254) {
            throw new IllegalArgumentException("Invalid signature input");
        }
        byte[] digest = md5(stub);
        byte[] payload = {(byte) sequence, 0, 1, 14, EMPTY_DIGEST[14], EMPTY_DIGEST[15],
                digest[14], digest[15], (byte) payloadRandom, 0};
        for (int index = 0; index < 9; index++) payload[9] ^= payload[index];
        int[] state = new int[256];
        for (int index = 0; index < 256; index++) state[index] = index;
        int j = 0;
        for (int index = 0; index < 256; index++) {
            j = (j + state[index] + keyRandom) & 255;
            int swap = state[index]; state[index] = state[j]; state[j] = swap;
        }
        byte[] encoded = new byte[12];
        encoded[0] = (byte) (64 | (flag ? 16 : 0)); encoded[1] = (byte) keyRandom;
        int i = 0; j = 0;
        for (int index = 0; index < payload.length; index++) {
            i = (i + 1) & 255; j = (j + state[i]) & 255;
            int swap = state[i]; state[i] = state[j]; state[j] = swap;
            encoded[index + 2] = (byte) (payload[index] ^ state[(state[i] + state[j]) & 255]);
        }
        String standard = Base64.getEncoder().withoutPadding().encodeToString(encoded);
        var signature = new StringBuilder(16);
        for (int index = 0; index < standard.length(); index++) signature.append(WEBCAST.charAt(STANDARD.indexOf(standard.charAt(index))));
        return signature.toString();
    }

    static byte[] md5(byte[] value) {
        try { return MessageDigest.getInstance("MD5").digest(value); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("Signature primitive unavailable"); }
    }
}
