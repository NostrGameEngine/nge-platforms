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
package org.ngengine.platform.teavm;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSBoolean;
import org.teavm.jso.core.JSNumber;
import org.teavm.jso.core.JSObjects;
import org.teavm.jso.core.JSString;

/**
 * Helper class to convert Java collections and maps to JavaScript objects
 * that can be properly JSON serialized in TeaVM.
 */
public class TeaVMJsConverter {

    /**
     * Converts a Java object (particularly collections and maps) to a JavaScript
     * object that can be properly JSON serialized.
     */
    public static JSObject toJSObject(Object obj) {
        if (obj == null) {
            return null;
        }

        if (obj instanceof Map) {
            return mapToJSObject((Map<?, ?>) obj);
        } else if (obj instanceof Collection) {
            return collectionToJSArray((Collection<?>) obj);
        } else if (obj instanceof Object[]) {
            return arrayToJSArray((Object[]) obj);
        } else if (obj.getClass().isArray()) {
            // Handle primitive arrays
            return primitiveArrayToJSArray(obj);
        } else if (obj instanceof String) {
            return JSString.valueOf((String) obj);
        } else if (obj instanceof Number) {
            return JSNumber.valueOf(((Number) obj).doubleValue());
        } else if (obj instanceof Boolean) {
            return JSBoolean.valueOf((Boolean) obj);
        } else if (obj instanceof Date) {
            // Convert Date to a JS date (as milliseconds since epoch)
            return JSNumber.valueOf(((Date) obj).getTime());
        } else if (obj instanceof BigInteger || obj instanceof BigDecimal) {
            // For values within safe JavaScript integer range, use number
            try {
                BigDecimal bd = (obj instanceof BigInteger) ? new BigDecimal((BigInteger) obj) : (BigDecimal) obj;

                if (
                    bd.compareTo(BigDecimal.valueOf(-9007199254740991L)) >= 0 &&
                    bd.compareTo(BigDecimal.valueOf(9007199254740991L)) <= 0 &&
                    bd.scale() <= 0
                ) {
                    return JSNumber.valueOf(bd.longValue());
                }
            } catch (Exception e) {
                // Fallback to string if conversion fails
            }
            // Otherwise use string representation
            return JSString.valueOf(obj.toString());
        } else if (obj instanceof Enum<?>) {
            // Convert enums to their string representation
            return JSString.valueOf(((Enum<?>) obj).name());
        }

        // For other types, try to convert to string
        return JSString.valueOf(obj.toString());
    }

    /**
     * Converts a primitive array to a JavaScript array.
     */
    private static JSArray primitiveArrayToJSArray(Object array) {
        if (array == null) {
            return null;
        }

        Class<?> componentType = array.getClass().getComponentType();

        if (componentType == int.class) {
            int[] intArray = (int[]) array;
            JSArray result = JSArray.create(intArray.length);
            for (int i = 0; i < intArray.length; i++) {
                result.set(i, JSNumber.valueOf(intArray[i]));
            }
            return result;
        } else if (componentType == byte.class) {
            byte[] byteArray = (byte[]) array;
            JSArray result = JSArray.create(byteArray.length);
            for (int i = 0; i < byteArray.length; i++) {
                result.set(i, JSNumber.valueOf(byteArray[i]));
            }
            return result;
        } else if (componentType == short.class) {
            short[] shortArray = (short[]) array;
            JSArray result = JSArray.create(shortArray.length);
            for (int i = 0; i < shortArray.length; i++) {
                result.set(i, JSNumber.valueOf(shortArray[i]));
            }
            return result;
        } else if (componentType == long.class) {
            long[] longArray = (long[]) array;
            JSArray result = JSArray.create(longArray.length);
            for (int i = 0; i < longArray.length; i++) {
                result.set(i, JSNumber.valueOf(longArray[i]));
            }
            return result;
        } else if (componentType == float.class) {
            float[] floatArray = (float[]) array;
            JSArray result = JSArray.create(floatArray.length);
            for (int i = 0; i < floatArray.length; i++) {
                result.set(i, JSNumber.valueOf(floatArray[i]));
            }
            return result;
        } else if (componentType == double.class) {
            double[] doubleArray = (double[]) array;
            JSArray result = JSArray.create(doubleArray.length);
            for (int i = 0; i < doubleArray.length; i++) {
                result.set(i, JSNumber.valueOf(doubleArray[i]));
            }
            return result;
        } else if (componentType == boolean.class) {
            boolean[] boolArray = (boolean[]) array;
            JSArray result = JSArray.create(boolArray.length);
            for (int i = 0; i < boolArray.length; i++) {
                result.set(i, JSBoolean.valueOf(boolArray[i]));
            }
            return result;
        } else if (componentType == char.class) {
            char[] charArray = (char[]) array;
            JSArray result = JSArray.create(charArray.length);
            for (int i = 0; i < charArray.length; i++) {
                result.set(i, JSString.valueOf(String.valueOf(charArray[i])));
            }
            return result;
        }

        // Fallback - should not reach here as all primitive types are covered
        return JSArray.create(0);
    }

    /**
     * Convert a JavaScript object to a specific Java type.
     */
    @SuppressWarnings("unchecked")
    public static <T> T toJavaObject(JSObject jsObj, Class<T> targetClass) {
        if (jsObj == null) {
            return null;
        }

        // Handle common Java types
        if (targetClass == String.class) {
            return (T) jsObj.toString();
        } else if (targetClass == Integer.class || targetClass == int.class) {
            if (isNumber(jsObj)) {
                return (T) Integer.valueOf((int) getNumberValue(jsObj));
            }
            return (T) Integer.valueOf(Integer.parseInt(jsObj.toString()));
        } else if (targetClass == Double.class || targetClass == double.class) {
            if (isNumber(jsObj)) {
                return (T) Double.valueOf(getNumberValue(jsObj));
            }
            return (T) Double.valueOf(Double.parseDouble(jsObj.toString()));
        } else if (targetClass == Boolean.class || targetClass == boolean.class) {
            if (isBoolean(jsObj)) {
                return (T) Boolean.valueOf(getBooleanValue(jsObj));
            }
            return (T) Boolean.valueOf(Boolean.parseBoolean(jsObj.toString()));
        } else if (targetClass == Long.class || targetClass == long.class) {
            if (isNumber(jsObj)) {
                return (T) Long.valueOf((long) getNumberValue(jsObj));
            }
            return (T) Long.valueOf(Long.parseLong(jsObj.toString()));
        } else if (targetClass == Byte.class || targetClass == byte.class) {
            if (isNumber(jsObj)) {
                return (T) Byte.valueOf((byte) getNumberValue(jsObj));
            }
            return (T) Byte.valueOf(Byte.parseByte(jsObj.toString()));
        } else if (targetClass == Short.class || targetClass == short.class) {
            if (isNumber(jsObj)) {
                return (T) Short.valueOf((short) getNumberValue(jsObj));
            }
            return (T) Short.valueOf(Short.parseShort(jsObj.toString()));
        } else if (targetClass == Float.class || targetClass == float.class) {
            if (isNumber(jsObj)) {
                return (T) Float.valueOf((float) getNumberValue(jsObj));
            }
            return (T) Float.valueOf(Float.parseFloat(jsObj.toString()));
        } else if (targetClass == Character.class || targetClass == char.class) {
            String str = jsObj.toString();
            if (str.length() > 0) {
                return (T) Character.valueOf(str.charAt(0));
            }
            return (T) Character.valueOf('\0');
        } else if (targetClass == Date.class) {
            if (isNumber(jsObj)) {
                return (T) new Date((long) getNumberValue(jsObj));
            }
            // Try to parse as string date
            try {
                return (T) new Date(Long.parseLong(jsObj.toString()));
            } catch (NumberFormatException e) {
                // Not a timestamp, try to parse as ISO string
                return (T) new Date(parseISODate(jsObj.toString()));
            }
        } else if (targetClass == BigInteger.class) {
            return (T) new BigInteger(jsObj.toString());
        } else if (targetClass == BigDecimal.class) {
            return (T) new BigDecimal(jsObj.toString());
        }
        // Handle Enum types
        else if (targetClass.isEnum()) {
            String enumValue = jsObj.toString();
            for (Object enumConstant : targetClass.getEnumConstants()) {
                if (((Enum<?>) enumConstant).name().equals(enumValue)) {
                    return (T) enumConstant;
                }
            }
            // If no match, return first enum value or null
            Object[] constants = targetClass.getEnumConstants();
            return constants.length > 0 ? (T) constants[0] : null;
        }
        // Handle collections
        else if (List.class.isAssignableFrom(targetClass)) {
            return (T) toJavaList(jsObj);
        } else if (Set.class.isAssignableFrom(targetClass)) {
            return (T) toJavaSet(jsObj);
        } else if (Map.class.isAssignableFrom(targetClass)) {
            return (T) toJavaMap(jsObj);
        }
        // If target class is array
        else if (targetClass.isArray()) {
            return (T) toJavaArray(jsObj, targetClass.getComponentType());
        }

        // Default case - return as is
        return (T) jsObj;
    }

    /**
     * Parse ISO date string to timestamp
     */
    @JSBody(params = { "dateStr" }, script = "return new Date(dateStr).getTime();")
    private static native long parseISODate(String dateStr);

    /**
     * Converts a Java Map to a JavaScript object.
     */
    private static JSObject mapToJSObject(Map<?, ?> map) {
        JSObject result = JSObjects.create();

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = entry.getKey().toString();
            Object value = entry.getValue();

            // Use direct property setting instead of JSObjects.setProperty
            if (value == null) {
                setProperty(result, key, null);
            } else if (value instanceof Map || value instanceof Collection || value instanceof Object[]) {
                setProperty(result, key, toJSObject(value));
            } else if (value instanceof String) {
                setProperty(result, key, JSString.valueOf((String) value));
            } else if (value instanceof Number) {
                setProperty(result, key, JSNumber.valueOf(((Number) value).doubleValue()));
            } else if (value instanceof Boolean) {
                setProperty(result, key, JSBoolean.valueOf((Boolean) value));
            } else {
                setProperty(result, key, JSString.valueOf(value.toString()));
            }
        }

        return result;
    }

    /**
     * Converts a Java Collection to a JavaScript array.
     */
    private static JSArray collectionToJSArray(Collection<?> collection) {
        JSArray array = JSArray.create(collection.size());
        int index = 0;

        for (Object item : collection) {
            if (item == null) {
                array.set(index, null);
            } else if (item instanceof Map || item instanceof Collection || item instanceof Object[]) {
                array.set(index, toJSObject(item));
            } else if (item instanceof String) {
                array.set(index, JSString.valueOf((String) item));
            } else if (item instanceof Number) {
                array.set(index, JSNumber.valueOf(((Number) item).doubleValue()));
            } else if (item instanceof Boolean) {
                array.set(index, JSBoolean.valueOf((Boolean) item));
            } else {
                array.set(index, JSString.valueOf(item.toString()));
            }
            index++;
        }

        return array;
    }

    /**
     * Converts a Java array to a JavaScript array.
     */
    private static JSArray arrayToJSArray(Object[] array) {
        JSArray result = JSArray.create(array.length);

        for (int i = 0; i < array.length; i++) {
            Object item = array[i];
            if (item == null) {
                result.set(i, null);
            } else if (item instanceof Map || item instanceof Collection || item instanceof Object[]) {
                result.set(i, toJSObject(item));
            } else if (item instanceof String) {
                result.set(i, JSString.valueOf((String) item));
            } else if (item instanceof Number) {
                result.set(i, JSNumber.valueOf(((Number) item).doubleValue()));
            } else if (item instanceof Boolean) {
                result.set(i, JSBoolean.valueOf((Boolean) item));
            } else {
                result.set(i, JSString.valueOf(item.toString()));
            }
        }

        return result;
    }

    @JSBody(params = { "object", "key", "value" }, script = "object[key] = value;")
    private static native void setProperty(JSObject object, String key, JSObject value);

    /**
     * Convert a JavaScript array to a Java List.
     */
    public static List<Object> toJavaList(JSObject jsArray) {
        if (!isJSArray(jsArray)) {
            throw new IllegalArgumentException("Not a JavaScript array");
        }

        JSArray array = (JSArray) jsArray;
        List<Object> list = new ArrayList<>();

        for (int i = 0; i < array.getLength(); i++) {
            JSObject item = (JSObject) array.get(i);

            if (item == null) {
                list.add(null);
            } else if (isJSArray(item)) {
                list.add(toJavaList(item));
            } else if (isJSObject(item) && !isPrimitive(item)) {
                list.add(toJavaMap(item));
            } else {
                // Handle primitives
                list.add(convertJSPrimitive(item));
            }
        }

        return list;
    }

    /**
     * Convert a JavaScript array to a Java Set.
     */
    public static Set<Object> toJavaSet(JSObject jsArray) {
        List<Object> list = toJavaList(jsArray);
        return new HashSet<>(list);
    }

    /**
     * Convert a JavaScript object to a Java Map.
     */
    public static Map<String, Object> toJavaMap(JSObject jsObj) {
        if (jsObj == null) {
            return null;
        }

        Map<String, Object> map = new HashMap<>();

        String[] keys = getObjectKeys(jsObj);
        for (String key : keys) {
            JSObject value = getProperty(jsObj, key);

            if (value == null) {
                map.put(key, null);
            } else if (isJSArray(value)) {
                map.put(key, toJavaList(value));
            } else if (isJSObject(value) && !isPrimitive(value)) {
                map.put(key, toJavaMap(value));
            } else {
                // Handle primitives
                map.put(key, convertJSPrimitive(value));
            }
        }

        return map;
    }

    /**
     * Convert a JavaScript array to a Java array of a specific component type.
     */
    @SuppressWarnings("unchecked")
    private static Object toJavaArray(JSObject jsArray, Class<?> componentType) {
        if (!isJSArray(jsArray)) {
            throw new IllegalArgumentException("Not a JavaScript array");
        }

        JSArray array = (JSArray) jsArray;
        int length = array.getLength();

        Object result = java.lang.reflect.Array.newInstance(componentType, length);

        for (int i = 0; i < length; i++) {
            JSObject item = (JSObject) array.get(i);
            if (item != null) {
                Object converted = toJavaObject(item, componentType);
                java.lang.reflect.Array.set(result, i, converted);
            }
        }

        return result;
    }

    /**
     * Convert a JavaScript primitive value to its Java equivalent.
     */
    private static Object convertJSPrimitive(JSObject value) {
        if (value == null) {
            return null;
        }

        if (isString(value)) {
            return value.toString();
        } else if (isNumber(value)) {
            double d = getNumberValue(value);
            // Check if it's an integer
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                if (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                    return (int) d;
                } else {
                    return (long) d;
                }
            }
            return d;
        } else if (isBoolean(value)) {
            return getBooleanValue(value);
        }

        // Default case
        return value.toString();
    }

    /**
     * Check if a JSObject is a JS Array.
     */
    @JSBody(params = "obj", script = "return Array.isArray(obj);")
    private static native boolean isJSArray(JSObject obj);

    /**
     * Check if a JSObject is a JS Object (not array, not primitive).
     */
    @JSBody(params = "obj", script = "return obj !== null && typeof obj === 'object' && !Array.isArray(obj);")
    private static native boolean isJSObject(JSObject obj);

    /**
     * Check if a JSObject is a JS primitive value.
     */
    @JSBody(
        params = "obj",
        script = "return obj === null || " +
        "typeof obj === 'string' || " +
        "typeof obj === 'number' || " +
        "typeof obj === 'boolean';"
    )
    private static native boolean isPrimitive(JSObject obj);

    /**
     * Check if a JSObject is a string.
     */
    @JSBody(params = "obj", script = "return typeof obj === 'string';")
    private static native boolean isString(JSObject obj);

    /**
     * Check if a JSObject is a number.
     */
    @JSBody(params = "obj", script = "return typeof obj === 'number';")
    private static native boolean isNumber(JSObject obj);

    /**
     * Check if a JSObject is a boolean.
     */
    @JSBody(params = "obj", script = "return typeof obj === 'boolean';")
    private static native boolean isBoolean(JSObject obj);

    /**
     * Get a number value from a JSObject.
     */
    @JSBody(params = "obj", script = "return Number(obj);")
    private static native double getNumberValue(JSObject obj);

    /**
     * Get a boolean value from a JSObject.
     */
    @JSBody(params = "obj", script = "return Boolean(obj);")
    private static native boolean getBooleanValue(JSObject obj);

    /**
     * Get all property keys from a JS object.
     */
    @JSBody(params = "obj", script = "return Object.keys(obj);")
    private static native String[] getObjectKeys(JSObject obj);

    /**
     * Get a property from a JS object by key.
     */
    @JSBody(params = { "obj", "key" }, script = "return obj[key];")
    private static native JSObject getProperty(JSObject obj, String key);
}
