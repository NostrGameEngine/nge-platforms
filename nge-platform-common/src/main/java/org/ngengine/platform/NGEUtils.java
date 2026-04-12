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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class NGEUtils {

    private static final Logger logger = Logger.getLogger(NGEUtils.class.getName());
    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    public static void setPlatform(NGEPlatform platform) {
        NGEPlatform.set(platform);
    }

    public static NGEPlatform getPlatform() {
        return NGEPlatform.get();
    }

    public static String bytesToHex(ByteBuffer bbf) {
        char[] hexChars = new char[bbf.limit() * 2];
        for (int j = 0; j < bbf.limit(); j++) {
            int v = bbf.get(j) & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static String bytesToHex(byte bbf[]) {
        char[] hexChars = new char[bbf.length * 2];
        for (int j = 0; j < bbf.length; j++) {
            int v = bbf[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static ByteBuffer hexToBytes(String s) {
        int len = s.length();
        ByteBuffer buf = ByteBuffer.allocate(len / 2);
        for (int i = 0; i < len; i += 2) {
            buf.put(i / 2, (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16)));
        }
        return buf;
    }

    public static byte[] hexToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    public static boolean allZeroes(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Convert an input object to a long
     *
     * @param input
     * @return
     */
    public static long safeLong(Object input) {
        if (input == null) return 0L;

        if (input instanceof BigInteger) {
            BigInteger bi = (BigInteger) input;
            try {
                return bi.longValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Input is out of range for long: " + bi);
            }
        }

        if (input instanceof BigDecimal) {
            BigDecimal bd = (BigDecimal) input;
            if (bd.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 || bd.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0) {
                throw new IllegalArgumentException("Input is out of range for long: " + bd);
            }
            return bd.longValue();
        }

        if (input instanceof Number) {
            Number n = (Number) input;
            if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                if (Double.isNaN(d)) return 0L;
                if (d == Double.POSITIVE_INFINITY) return Long.MAX_VALUE;
                if (d == Double.NEGATIVE_INFINITY) return Long.MIN_VALUE;
                if (d > Long.MAX_VALUE || d < Long.MIN_VALUE) {
                    throw new IllegalArgumentException("Input is out of range for long: " + d);
                }
                return n.longValue();
            }
            // other Number types (Integer, Long, Short, Byte) fit in long range
            return n.longValue();
        }

        try {
            Long l = Long.parseLong(String.valueOf(input));
            return l.longValue();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Input is not a number: " + input);
        }
    }

    public static BigInteger safeBigInteger(Object input) {
        if (input == null) return BigInteger.ZERO;
        if (input instanceof BigInteger) {
            return (BigInteger) input;
        } else if (input instanceof BigDecimal) {
            return ((BigDecimal) input).toBigInteger();
        } else if (input instanceof Number) {
            Number n = (Number) input;
            if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                if (Double.isNaN(d)) return BigInteger.ZERO;
                if (d == Double.POSITIVE_INFINITY) return BigInteger.valueOf(Long.MAX_VALUE);
                if (d == Double.NEGATIVE_INFINITY) return BigInteger.valueOf(Long.MIN_VALUE);
                return BigDecimal.valueOf(d).toBigInteger();
            }
            return BigInteger.valueOf(n.longValue());
        } else {
            try {
                return new BigInteger(String.valueOf(input));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Input is not a number: " + input);
            }
        }
    }

    public static BigDecimal safeBigDecimal(Object input) {
        if (input == null) {
            return BigDecimal.ZERO;
        } else if (input instanceof BigDecimal) {
            return (BigDecimal) input;
        } else if (input instanceof BigInteger) {
            return new BigDecimal((BigInteger) input);
        } else if (input instanceof Number) {
            Number n = (Number) input;
            if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
                return BigDecimal.valueOf(n.longValue());
            }
            double d = n.doubleValue();
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException("Input is not a finite number: " + input);
            }
            return BigDecimal.valueOf(d);
        }
        try {
            return new BigDecimal(String.valueOf(input));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Input is not a number: " + input);
        }
    }

    public static double safeDouble(Object input) {
        if (input == null) return 0.0;
        // BigDecimal/BigInteger inputs: precise range checks
        if (input instanceof BigDecimal || input instanceof BigInteger) {
            BigDecimal bd = safeBigDecimal(input);
            BigDecimal abs = bd.abs();
            BigDecimal max = BigDecimal.valueOf(Double.MAX_VALUE);
            if (abs.compareTo(max) > 0) {
                throw new IllegalArgumentException("Input is out of range for double: " + input);
            }

            return bd.doubleValue();
        }

        if (input instanceof Number) {
            // Preserve NaN/+Infinity/-Infinity for float<->double mapping.
            if (input instanceof Double) {
                double d = ((Double) input).doubleValue();
                if (Double.isNaN(d)) return Double.NaN;
                if (d == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY;
                if (d == Double.NEGATIVE_INFINITY) return Double.NEGATIVE_INFINITY;
                return d;
            }
            if (input instanceof Float) {
                float f = ((Float) input).floatValue();
                if (Float.isNaN(f)) return Double.NaN;
                if (f == Float.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY;
                if (f == Float.NEGATIVE_INFINITY) return Double.NEGATIVE_INFINITY;
                return (double) f;
            }
            double v = ((Number) input).doubleValue();
            if (!Double.isFinite(v)) {
                // map infinities/NaN according to Number.longValue semantics is handled by integer safe methods;
                // for floating target we keep non-finite values if they originate from Double/Float, but
                // other Number implementations shouldn't produce non-finite values — reject them.
                throw new IllegalArgumentException("Input is not a finite double: " + input);
            }

            return v;
        }

        try {
            String s = String.valueOf(input);
            double parsed = Double.parseDouble(s);
            if (Double.isFinite(parsed)) {
                return parsed;
            }

            // Non-finite fast parse can be a true literal (NaN/Infinity) or numeric overflow.
            try {
                BigDecimal bd = new BigDecimal(s);
                BigDecimal abs = bd.abs();
                BigDecimal max = BigDecimal.valueOf(Double.MAX_VALUE);
                if (abs.compareTo(max) > 0) {
                    throw new IllegalArgumentException("Input is out of range for double: " + input);
                }
                return bd.doubleValue();
            } catch (NumberFormatException ignored) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Input is not a number: " + input);
        }
    }

    public static float safeFloat(Object input) {
        if (input == null) return 0.0f;
        // BigDecimal/BigInteger inputs: precise range checks
        if (input instanceof BigDecimal || input instanceof BigInteger) {
            BigDecimal bd = safeBigDecimal(input);
            BigDecimal abs = bd.abs();
            BigDecimal max = BigDecimal.valueOf((double) Float.MAX_VALUE);
            if (abs.compareTo(max) > 0) {
                throw new IllegalArgumentException("Input is out of range for float: " + input);
            }

            return bd.floatValue();
        }

        if (input instanceof Number) {
            // Preserve NaN/+Infinity/-Infinity, but reject finite values overflowing to non-finite float.
            if (input instanceof Double) {
                double d = ((Double) input).doubleValue();
                if (Double.isNaN(d)) return Float.NaN;
                if (d == Double.POSITIVE_INFINITY) return Float.POSITIVE_INFINITY;
                if (d == Double.NEGATIVE_INFINITY) return Float.NEGATIVE_INFINITY;
                float f = (float) d;
                if (Double.isFinite(d) && !Float.isFinite(f)) {
                    throw new IllegalArgumentException("Input is out of range for float: " + input);
                }
                return f;
            }
            if (input instanceof Float) {
                return ((Float) input).floatValue();
            }
            double v = ((Number) input).doubleValue();
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("Input is not a finite float: " + input);
            }
            if (v < -Float.MAX_VALUE || v > Float.MAX_VALUE) {
                throw new IllegalArgumentException("Input is out of range for float: " + input);
            }

            return ((Number) input).floatValue();
        }

        try {
            String s = String.valueOf(input);
            float parsed = Float.parseFloat(s);
            if (Float.isFinite(parsed)) {
                return parsed;
            }

            // Non-finite fast parse can be a true literal (NaN/Infinity) or numeric overflow.
            try {
                BigDecimal bd = new BigDecimal(s);
                BigDecimal abs = bd.abs();
                BigDecimal max = BigDecimal.valueOf((double) Float.MAX_VALUE);
                if (abs.compareTo(max) > 0) {
                    throw new IllegalArgumentException("Input is out of range for float: " + input);
                }
                return bd.floatValue();
            } catch (NumberFormatException ignored) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Input is not a number: " + input);
        }
    }

    public static int safeInt(Object input) {
        long l = safeLong(input);
        if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Input is out of range for int: " + l);
        }
        return (int) l;
    }

    public static String safeString(Object input) {
        if (input == null) return "";
        MemoryLimits limits = getPlatform().getMemoryLimits();
        if (input instanceof String) {
            // nop
            input = (String) input;
        } else if (input instanceof byte[]) {
            if (!limits.checkForData(((byte[]) input).length)) {
                throw new IllegalArgumentException("Input byte array is too large: " + ((byte[]) input).length);
            }
            input = new String((byte[]) input, StandardCharsets.UTF_8);
        } else if (input instanceof char[]) {
            if (!limits.checkForData(((char[]) input).length)) {
                throw new IllegalArgumentException("Input char array is too large: " + ((char[]) input).length);
            }
            input = new String((char[]) input);
        } else if (input instanceof ByteBuffer) {
            ByteBuffer buffer = (ByteBuffer) input;
            if (!limits.checkForData(buffer.remaining())) {
                throw new IllegalArgumentException("Input byte buffer is too large: " + buffer.remaining());
            }
            byte[] bytes = new byte[buffer.remaining()];
            buffer.slice().get(bytes);
            input = new String(bytes, StandardCharsets.UTF_8);
        } else {
            input = String.valueOf(input);
        }
        if (!limits.checkForString(((String) input).length())) {
            throw new IllegalArgumentException("Input string is too large: " + ((String) input).length());
        }
        return (String) input;
    }

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    public static String[] safeStringArray(Object tags) {
        if (tags == null) {
            return EMPTY_STRING_ARRAY;
        }
        if (tags instanceof Iterable) {
            ArrayList<String> list = new ArrayList<>();
            for (Object o : (Iterable<?>) tags) {
                list.add(safeString(o));
            }
            return list.toArray(new String[0]);
        } else if (tags instanceof String[]) {
            String[] arr = (String[]) tags;
            String[] out = new String[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = safeString(arr[i]);
            }
            return out;
        } else {
            throw new IllegalArgumentException("Input is not a string array: " + tags);
        }
    }

    public static List<String> safeStringList(Object tags) {
        if (
            tags == null ||
            (tags instanceof Collection && ((Collection<?>) tags).isEmpty()) ||
            (tags instanceof String[] && ((String[]) tags).length == 0)
        ) {
            return List.of();
        }

        if (tags instanceof List || tags instanceof Collection) {
            if (!(tags instanceof List)) {
                tags = new ArrayList<>((Collection<?>) tags);
            }
            for (int i = 0; i < ((List<?>) tags).size(); i++) {
                List<Object> list = (List<Object>) tags;
                list.set(i, safeString(list.get(i)));
            }
            return (List<String>) tags;
        } else if (tags instanceof Iterable) {
            ArrayList<String> list = new ArrayList<>();
            for (Object o : (Iterable<?>) tags) {
                list.add(safeString(o));
            }
            return list;
        } else if (tags instanceof String[]) {
            ArrayList<String> list = new ArrayList<>();
            for (String o : (String[]) tags) {
                list.add(safeString(o));
            }
            return list;
        } else {
            throw new IllegalArgumentException("Input is not an int array/list: " + tags);
        }
    }

    public static List<Integer> safeIntList(Object tags) {
        if (
            tags == null ||
            (tags instanceof Collection && ((Collection<?>) tags).isEmpty()) ||
            (tags instanceof Integer[] && ((Integer[]) tags).length == 0) ||
            (tags instanceof int[] && ((int[]) tags).length == 0)
        ) {
            return List.of();
        }

        if (tags instanceof List || tags instanceof Collection) {
            if (!(tags instanceof List)) {
                tags = new ArrayList<>((Collection<?>) tags);
            }
            for (int i = 0; i < ((List<?>) tags).size(); i++) {
                List<Object> list = (List<Object>) tags;
                list.set(i, safeInt(list.get(i)));
            }
            return (List<Integer>) tags;
        } else if (tags instanceof Iterable) {
            ArrayList<Integer> list = new ArrayList<>();
            for (Object o : (Iterable<?>) tags) {
                list.add(safeInt(o));
            }
            return list;
        } else if (tags instanceof Integer[]) {
            return List.of((Integer[]) tags);
        } else if (tags instanceof int[]) {
            int[] arr = (int[]) tags;
            ArrayList<Integer> list = new ArrayList<>();
            for (int o : arr) {
                list.add(o);
            }
            return list;
        } else {
            throw new IllegalArgumentException("Input is not a string array: " + tags);
        }
    }

    public static Collection<String[]> safeCollectionOfStringArray(Object tags) {
        if (tags == null) {
            return List.of();
        }
        if (tags instanceof Collection && !(tags instanceof List)) {
            tags = new ArrayList<>((Collection<?>) tags);
        }

        if (tags instanceof List) {
            List<?> c = (List<?>) tags;
            if (c.isEmpty()) {
                return (Collection<String[]>) c;
            }
            ArrayList<String[]> list = new ArrayList<>(c.size());
            for (Object o : c) {
                list.add(safeStringArray(o));
            }
            return list;
        } else if (tags instanceof String[][]) {
            String[][] arr = (String[][]) tags;
            ArrayList<String[]> list = new ArrayList<>();
            for (String[] o : arr) {
                list.add(safeStringArray(o));
            }
            return list;
        } else {
            throw new IllegalArgumentException("Input is not a string array: " + tags);
        }
    }

    public static boolean safeBool(Object v) {
        if (v == null) return false;

        if (v instanceof Boolean) {
            return (Boolean) v;
        } else if (v instanceof Number) {
            return ((Number) v).intValue() != 0;
        } else if (v instanceof String) {
            return Boolean.parseBoolean((String) v);
        } else {
            throw new IllegalArgumentException("Input is not a boolean: " + v);
        }
    }

    public static Instant safeSecondsInstant(Object object) {
        if (object == null) return Instant.now();
        if (object instanceof Instant) {
            return (Instant) object;
        } else if (object instanceof String) {
            try {
                return Instant.parse((String) object);
            } catch (Exception e) {
                return Instant.ofEpochSecond(safeLong(object));
            }
        } else {
            return Instant.ofEpochSecond(safeLong(object));
        }
    }

    public static long safeMSats(Object v) {
        long msats = safeLong(v);
        if (msats < 0) {
            throw new IllegalArgumentException("MSats cannot be negative: " + msats);
        }
        return msats;
    }

    public static Duration safeDurationInSeconds(Object object) {
        if (object == null) return Duration.ZERO;

        Long seconds = null;

        if (object instanceof Duration) {
            seconds = ((Duration) object).getSeconds();
        } else if (object instanceof String) {
            try {
                seconds = Duration.parse((String) object).getSeconds();
            } catch (Exception e) {}
        }

        if (seconds == null) {
            seconds = safeLong(object);
        }

        if (seconds < 0) {
            throw new IllegalArgumentException("Duration cannot be negative: " + seconds);
        }

        return Duration.ofSeconds(seconds);
    }

    public static Instant safeInstantInSeconds(Object object) {
        if (object == null) return Instant.now();

        Long seconds = null;

        if (object instanceof Instant) {
            seconds = ((Instant) object).getEpochSecond();
        } else if (object instanceof String) {
            try {
                return Instant.parse((String) object);
            } catch (Exception e) {
                seconds = safeLong(object);
            }
        } else {
            seconds = safeLong(object);
        }

        if (seconds == null || seconds < 0) {
            throw new IllegalArgumentException("Invalid Instant: " + object);
        }

        return Instant.ofEpochSecond(seconds);
    }

    private static final List<String> VALID_SCHEMES = Arrays.asList(
        System
            .getProperty(
                "nge-platforms.validURISchemes",
                NGEPlatform.get().getPlatformName().contains("capacitor ") ? "https,http,capacitor,wss,ws" : "https,http,wss,ws"
            )
            .split(",")
    );

    private static final boolean ALLOW_LOCALHOST_IN_URIS = System
        .getProperty(
            "nge-platforms.allowLoopbackInURIs",
            NGEPlatform.get().getPlatformName().contains("browser") ? "true" : "false"
        )
        .equalsIgnoreCase("true");

    public static URI safeURI(Object object) {
        URI uri;
        if (object instanceof URI) {
            uri = (URI) object;
        } else {
            String str = safeString(object);
            if (str.isEmpty()) {
                throw new IllegalArgumentException("URI cannot be empty");
            }

            uri = URI.create(str);
        }

        if (uri.toString().length() > 2000) {
            throw new IllegalArgumentException("URI is too long: " + uri.toString().length());
        }

        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid URI: " + uri);
        }

        if (!VALID_SCHEMES.contains(uri.getScheme().toLowerCase())) {
            throw new IllegalArgumentException(
                "Invalid URI scheme: " + uri.getScheme() + " - valid schemes are " + VALID_SCHEMES
            );
        }

        if (!ALLOW_LOCALHOST_IN_URIS) {
            if (NGEPlatform.get().isLoopbackAddress(uri)) {
                throw new IllegalArgumentException("Loopback addresses are not allowed in URIs: " + uri);
            }
        }

        return uri;
    }

    /**
     * Wrapper to exploit assert to toggle on/off debug code
     * usage:
     *
     * <pre>{@code
     * assert NGEUtils.dbg(() -> {
     *     // heavy debug code
     * });
     * }</pre>
     */
    public static boolean dbg(Runnable r) {
        Supplier<Boolean> s = () -> {
            try {
                r.run();
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        };
        assert s.get() : "Debug statement failed";
        return true;
    }

    public static boolean equalsIgnoreOrder(Map<String, List<String>> tags, Map<String, List<String>> tags2) {
        if (tags == null && tags2 == null) return true;
        if (tags == null || tags2 == null) return false;
        if (tags.size() != tags2.size()) return false;

        for (Map.Entry<String, List<String>> entry : tags.entrySet()) {
            String key = entry.getKey();
            List<String> value = entry.getValue();
            List<String> value2 = tags2.get(key);
            if (value2 == null || value.size() != value2.size()) return false;
            for (String v : value) {
                if (!value2.contains(v)) return false;
            }
        }
        return true;
    }

    public static boolean equalsWithOrder(Map<String, List<String>> tags, Map<String, List<String>> tags2) {
        if (tags == null && tags2 == null) return true;
        if (tags == null || tags2 == null) return false;
        if (tags.size() != tags2.size()) return false;

        for (Map.Entry<String, List<String>> entry : tags.entrySet()) {
            String key = entry.getKey();
            List<String> value = entry.getValue();
            List<String> value2 = tags2.get(key);
            if (value2 == null || value.size() != value2.size()) return false;
            for (int i = 0; i < value.size(); i++) {
                if (!value.get(i).equals(value2.get(i))) return false;
            }
        }
        return true;
    }

    public static <T> T awaitNoThrow(AsyncTask<T> task) {
        try {
            return task.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // replace all non-alphanumeric characters with their hex representation
    public static String censorSpecial(String appName) {
        StringBuilder sb = new StringBuilder();
        for (char c : appName.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append(String.format("x%02X", (int) c));
            }
        }
        return sb.toString();
    }

    public static byte[] safeByteArray(Object input) {
        byte[] out = null;
        if (input instanceof byte[]) {
            out = (byte[]) input;
        } else if (input instanceof ByteBuffer) {
            ByteBuffer buffer = ((ByteBuffer) input).slice();
            out = new byte[buffer.remaining()];
            buffer.get(out);
        } else if (input instanceof String) {
            out = ((String) input).getBytes(StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("Input is not a byte array: " + input);
        }
        if (!getPlatform().getMemoryLimits().checkForData(out.length)) {
            throw new IllegalArgumentException("Input byte array is too large: " + out.length);
        }
        return out;
    }

    public static ByteBuffer safeByteBuffer(Object input) {
        ByteBuffer out = null;
        if (input instanceof ByteBuffer) {
            out = (ByteBuffer) input;
        } else if (input instanceof byte[]) {
            out = ByteBuffer.wrap((byte[]) input);
        } else if (input instanceof String) {
            out = ByteBuffer.wrap(((String) input).getBytes(StandardCharsets.UTF_8));
        } else {
            throw new IllegalArgumentException("Input is not a byte buffer: " + input);
        }
        if (!getPlatform().getMemoryLimits().checkForData(out.remaining())) {
            throw new IllegalArgumentException("Input byte buffer is too large: " + out.remaining());
        }
        return out;
    }

    public static byte[] safeBigByteArray(Object input) {
        byte[] out = null;
        if (input instanceof byte[]) {
            out = (byte[]) input;
        } else if (input instanceof ByteBuffer) {
            ByteBuffer buffer = ((ByteBuffer) input).slice();
            out = new byte[buffer.remaining()];
            buffer.get(out);
        } else if (input instanceof String) {
            out = ((String) input).getBytes(StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("Input is not a byte array: " + input);
        }
        if (!getPlatform().getMemoryLimits().checkForBigData(out.length)) {
            throw new IllegalArgumentException("Input byte array is too large: " + out.length);
        }
        return out;
    }

    public static ByteBuffer safeBigByteBuffer(Object input) {
        ByteBuffer out = null;
        if (input instanceof ByteBuffer) {
            out = (ByteBuffer) input;
        } else if (input instanceof byte[]) {
            out = ByteBuffer.wrap((byte[]) input);
        } else if (input instanceof String) {
            out = ByteBuffer.wrap(((String) input).getBytes(StandardCharsets.UTF_8));
        } else {
            throw new IllegalArgumentException("Input is not a byte buffer: " + input);
        }
        if (!getPlatform().getMemoryLimits().checkForBigData(out.remaining())) {
            throw new IllegalArgumentException("Input byte buffer is too large: " + out.remaining());
        }
        return out;
    }

    public static String safeJsonString(Object input) {
        String out = safeString(input);
        if (!getPlatform().getMemoryLimits().checkForJSON(out.getBytes(StandardCharsets.UTF_8).length)) {
            throw new IllegalArgumentException("Input JSON string is too large: " + out.length());
        }
        return out;
    }
}
