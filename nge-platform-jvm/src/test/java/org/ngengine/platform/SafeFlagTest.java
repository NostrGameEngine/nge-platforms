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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class SafeFlagTest {

    @Test
    public void roundTripsWithoutLocking() {
        SafeFlag flag = new SafeFlag(false);
        assertFalse(flag.get());
        flag.set(true);
        assertTrue(flag.get());
        flag.set(false);
        assertFalse(flag.get());
    }

    @Test
    public void validEncodingsHaveLargeHammingDistance() throws Exception {
        Field enabledField = SafeFlag.class.getDeclaredField("ENABLED");
        Field disabledField = SafeFlag.class.getDeclaredField("DISABLED");
        enabledField.setAccessible(true);
        disabledField.setAccessible(true);

        long enabled = enabledField.getLong(null);
        long disabled = disabledField.getLong(null);
        assertEquals(46, Long.bitCount(enabled ^ disabled));
    }

    @Test
    public void corruptedEncodingTerminatesForkedJvm() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            CorruptionProbe.class.getName()
        )
            .redirectErrorStream(true)
            .start();

        assertTrue("Corruption probe timed out", process.waitFor(10, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 1, process.exitValue());
        assertTrue(output, output.contains("Memory corruption detected in boolean flag"));
    }

    public static final class CorruptionProbe {

        public static void main(String[] args) throws Exception {
            SafeFlag flag = new SafeFlag(true);
            Field encodedField = SafeFlag.class.getDeclaredField("encoded");
            encodedField.setAccessible(true);
            encodedField.setLong(flag, encodedField.getLong(flag) ^ 1L);
            flag.get();
            throw new AssertionError("Corrupted SafeFlag unexpectedly returned");
        }
    }
}
