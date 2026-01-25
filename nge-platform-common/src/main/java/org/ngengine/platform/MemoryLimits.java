/**
 * BSD 3-Clause License
 * 
 * Copyright (c) 2025, Riccardo Balbo
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.ngengine.platform;

/**
 * A last resort hardening against memory overuse.
 *
 * This is used to stop the propagation of obviously abnormal data sizes that
 * would indicate an obvious denial of service attack.
 *
 * This class can be reimplemented to be platform sensitive, eg. by checking the
 * sizes against remaining free memory.
 * The different checks are to differentiate for likely maximum memory usage
 * patterns for the different use cases, but they
 * should be kept reasonably high and they should not do any validation or check
 * beyond simple size limits, those checks
 * are to be done elsewhere.
 */
public class MemoryLimits {

    protected final long MIB = 1024L * 1024L;
    protected final long KIB = 1024L;
    protected final long B = 1L;

    protected final long JSON_LIMIT = 10L * MIB;
    protected final long BASE64_LIMIT = 50L * MIB;
    protected final long TRANSPORT_LIMIT = 10L * MIB;
    protected final long BIGDATA_LIMIT = 1000L * MIB;
    protected final long DATA_LIMIT = 100L * MIB;
    protected final long IMAGE_LIMIT = 100L * MIB;
    protected final long KEYS_LIMIT = 10L * KIB;
    protected final long STRING_LIMIT = 100L * KIB;
    protected final long RANDOM_LIMIT = 10 * KIB;

    protected boolean checkLimit(long size, long limit) {
        return size >= 0L && size <= limit;
    }

    /**
     * Check limit for json encoded data
     */
    public boolean checkForJSON(long byteLength) {
        return checkLimit(byteLength, JSON_LIMIT);
    }

    /**
     * Check limit for base64 encoded data
     */
    public boolean checkForBase64(long byteLength) {
        return checkLimit(byteLength, BASE64_LIMIT);
    }

    /**
     * Check limit for network packets
     */
    public boolean checkForTransport(long byteLength) {
        return checkLimit(byteLength, TRANSPORT_LIMIT);
    }

    /**
     * Check limit for big data blobs (eg. files, assets...)
     */
    public boolean checkForBigData(long byteLength) {
        return checkLimit(byteLength, BIGDATA_LIMIT);
    }

    /**
     * Check limit for generic data blobs (eg. events, messages...)
     */
    public boolean checkForData(long byteLength) {
        return checkLimit(byteLength, DATA_LIMIT);
    }

    /**
     * Check limit for image data
     */
    public boolean checkForImage(long byteLength) {
        return checkLimit(byteLength, IMAGE_LIMIT);
    }

    /**
     * Check limit for cryptographic keys and signatures
     */
    public boolean checkForKeys(long byteLength) {
        return checkLimit(byteLength, KEYS_LIMIT);
    }

    /**
     * Check limit for random generated data
     */
    public boolean checkForRandomData(long n) {
        return checkLimit(n, RANDOM_LIMIT);
    }

    /**
     * Check limit for human readable strings (eg. keys in key-value pairs, names...)
     */
    public boolean checkForString(int length) {
        return checkLimit(length * 2L, STRING_LIMIT);
    }
}
