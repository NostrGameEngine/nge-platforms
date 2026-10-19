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
package org.ngengine.platform.json;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Binary64 to the shortest decimal selected by ECMAScript Number::toString. */
final class JavaScriptNumbers {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private JavaScriptNumbers() {}

    static String format(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("A finite number is required");
        if (value == 0) return "0";
        if (Math.abs(value) <= 9007199254740991L && value == (long) value) return Long.toString((long) value);

        double positive = Math.abs(value);
        // BigDecimal(double), unlike valueOf(double), gives the exact binary64 value.
        BigDecimal exact = new BigDecimal(positive);
        BigDecimal previous = new BigDecimal(Math.nextDown(positive));
        BigDecimal lower = exact.add(previous).divide(TWO);
        BigDecimal upper = positive == Double.MAX_VALUE
            ? exact.add(exact.subtract(previous).divide(TWO))
            : exact.add(new BigDecimal(Math.nextUp(positive))).divide(TWO);
        boolean inclusive = (Double.doubleToRawLongBits(positive) & 1L) == 0;
        int decimalExponent = exact.precision() - exact.scale() - 1;

        // A decimal with p significant digits is also representable with p+1.
        // Binary-search the smallest p whose rounding interval contains a decimal.
        BigDecimal best = null;
        int low = 1;
        int high = 17; // Every binary64 value round-trips with at most 17 digits.
        while (low <= high) {
            int precision = (low + high) >>> 1;
            BigDecimal unit = BigDecimal.ONE.scaleByPowerOfTen(decimalExponent - precision + 1);
            BigDecimal nearest = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            BigDecimal candidate = null;
            BigDecimal distance = null;
            // At powers of two the interval is asymmetric; consider both neighbors
            // of the nearest decimal, not just the rounded decimal itself.
            for (int offset = -1; offset <= 1; offset++) {
                BigDecimal current = nearest.add(unit.multiply(BigDecimal.valueOf(offset))).stripTrailingZeros();
                if (current.signum() <= 0 || current.precision() > precision) continue;
                int below = current.compareTo(lower);
                int above = current.compareTo(upper);
                if (below < 0 || above > 0 || (!inclusive && (below == 0 || above == 0))) continue;
                BigDecimal error = current.subtract(exact).abs();
                if (
                    candidate == null ||
                    error.compareTo(distance) < 0 ||
                    (error.compareTo(distance) == 0 && !current.unscaledValue().testBit(0))
                ) {
                    candidate = current;
                    distance = error;
                }
            }
            if (candidate == null) low = precision + 1; else {
                best = candidate;
                high = Math.min(precision, candidate.precision()) - 1;
            }
        }
        if (best == null) throw new IllegalStateException("No round-trip decimal for binary64 value");

        String digits = best.unscaledValue().toString();
        int point = digits.length() - best.scale();
        String text;
        if (point >= digits.length() && point <= 21) {
            text = digits + "0".repeat(point - digits.length());
        } else if (point > 0 && point <= 21) {
            text = digits.substring(0, point) + "." + digits.substring(point);
        } else if (point > -6 && point <= 0) {
            text = "0." + "0".repeat(-point) + digits;
        } else {
            int exponent = point - 1;
            text =
                digits.substring(0, 1) +
                (digits.length() > 1 ? "." + digits.substring(1) : "") +
                "e" +
                (exponent >= 0 ? "+" : "") +
                exponent;
        }
        return value < 0 ? "-" + text : text;
    }
}
