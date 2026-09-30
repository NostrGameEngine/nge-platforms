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
import java.util.Arrays;
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

        // JSON trees contain mostly strings and numbers. Avoid reflection and
        // collection checks for every primitive leaf, especially across Wasm.
        if (obj instanceof String) {
            return JSString.valueOf((String) obj);
        } else if (obj instanceof Number) {
            return JSNumber.valueOf(((Number) obj).doubleValue());
        } else if (obj instanceof Boolean) {
            return JSBoolean.valueOf((Boolean) obj);
        } else if (obj instanceof Map) {
            return mapToJSObject((Map<?, ?>) obj);
        } else if (obj instanceof Collection) {
            return collectionToJSArray((Collection<?>) obj);
        } else if (obj instanceof Object[]) {
            return arrayToJSArray((Object[]) obj);
        } else if (obj.getClass().isArray()) {
            // Handle primitive arrays
            return primitiveArrayToJSArray(obj);
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
            return JSString.valueOf(String.valueOf(obj));
        } else if (obj instanceof Enum<?>) {
            // Convert enums to their string representation
            return JSString.valueOf(((Enum<?>) obj).name());
        }

        // For other types, try to convert to string
        return JSString.valueOf(String.valueOf(obj));
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
                setElement(result, i, (double) intArray[i]);
            }
            return result;
        } else if (componentType == byte.class) {
            byte[] byteArray = (byte[]) array;
            JSArray result = JSArray.create(byteArray.length);
            for (int i = 0; i < byteArray.length; i++) {
                setElement(result, i, (double) byteArray[i]);
            }
            return result;
        } else if (componentType == short.class) {
            short[] shortArray = (short[]) array;
            JSArray result = JSArray.create(shortArray.length);
            for (int i = 0; i < shortArray.length; i++) {
                setElement(result, i, (double) shortArray[i]);
            }
            return result;
        } else if (componentType == long.class) {
            long[] longArray = (long[]) array;
            JSArray result = JSArray.create(longArray.length);
            for (int i = 0; i < longArray.length; i++) {
                setElement(result, i, (double) longArray[i]);
            }
            return result;
        } else if (componentType == float.class) {
            float[] floatArray = (float[]) array;
            JSArray result = JSArray.create(floatArray.length);
            for (int i = 0; i < floatArray.length; i++) {
                setElement(result, i, (double) floatArray[i]);
            }
            return result;
        } else if (componentType == double.class) {
            double[] doubleArray = (double[]) array;
            JSArray result = JSArray.create(doubleArray.length);
            for (int i = 0; i < doubleArray.length; i++) {
                setElement(result, i, (double) doubleArray[i]);
            }
            return result;
        } else if (componentType == boolean.class) {
            boolean[] boolArray = (boolean[]) array;
            JSArray result = JSArray.create(boolArray.length);
            for (int i = 0; i < boolArray.length; i++) {
                setElement(result, i, boolArray[i]);
            }
            return result;
        } else if (componentType == char.class) {
            char[] charArray = (char[]) array;
            JSArray result = JSArray.create(charArray.length);
            for (int i = 0; i < charArray.length; i++) {
                setElement(result, i, String.valueOf(charArray[i]));
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
        return toJavaObject(jsObj, targetClass, false);
    }

    // Only JSON.parse trees owned by the platform can use repeated typed reads.
    // Public interop objects may have getters or proxies with observable effects.
    @SuppressWarnings("unchecked")
    static <T> T toJavaObject(JSObject jsObj, Class<T> targetClass, boolean ownedJson) {
        if (jsObj == null) {
            return null;
        }

        // Handle common Java types
        if (targetClass == String.class) {
            return (T) String.valueOf(jsObj);
        } else if (targetClass == Integer.class || targetClass == int.class) {
            if (isNumber(jsObj)) {
                return (T) Integer.valueOf((int) getNumberValue(jsObj));
            }
            return (T) Integer.valueOf(Integer.parseInt(String.valueOf(jsObj)));
        } else if (targetClass == Double.class || targetClass == double.class) {
            if (isNumber(jsObj)) {
                return (T) Double.valueOf(getNumberValue(jsObj));
            }
            return (T) Double.valueOf(Double.parseDouble(String.valueOf(jsObj)));
        } else if (targetClass == Boolean.class || targetClass == boolean.class) {
            if (isBoolean(jsObj)) {
                return (T) Boolean.valueOf(getBooleanValue(jsObj));
            }
            return (T) Boolean.valueOf(Boolean.parseBoolean(String.valueOf(jsObj)));
        } else if (targetClass == Long.class || targetClass == long.class) {
            if (isNumber(jsObj)) {
                return (T) Long.valueOf((long) getNumberValue(jsObj));
            }
            return (T) Long.valueOf(Long.parseLong(String.valueOf(jsObj)));
        } else if (targetClass == Byte.class || targetClass == byte.class) {
            if (isNumber(jsObj)) {
                return (T) Byte.valueOf((byte) getNumberValue(jsObj));
            }
            return (T) Byte.valueOf(Byte.parseByte(String.valueOf(jsObj)));
        } else if (targetClass == Short.class || targetClass == short.class) {
            if (isNumber(jsObj)) {
                return (T) Short.valueOf((short) getNumberValue(jsObj));
            }
            return (T) Short.valueOf(Short.parseShort(String.valueOf(jsObj)));
        } else if (targetClass == Float.class || targetClass == float.class) {
            if (isNumber(jsObj)) {
                return (T) Float.valueOf((float) getNumberValue(jsObj));
            }
            return (T) Float.valueOf(Float.parseFloat(String.valueOf(jsObj)));
        } else if (targetClass == Character.class || targetClass == char.class) {
            String str = String.valueOf(jsObj);
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
                return (T) new Date(Long.parseLong(String.valueOf(jsObj)));
            } catch (NumberFormatException e) {
                // Not a timestamp, try to parse as ISO string
                return (T) new Date(parseISODate(String.valueOf(jsObj)));
            }
        } else if (targetClass == BigInteger.class) {
            return (T) new BigInteger(String.valueOf(jsObj));
        } else if (targetClass == BigDecimal.class) {
            return (T) new BigDecimal(String.valueOf(jsObj));
        }
        // Handle Enum types
        else if (targetClass.isEnum()) {
            String enumValue = String.valueOf(jsObj);
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
            return (T) toJavaList(jsObj, ownedJson);
        } else if (Set.class.isAssignableFrom(targetClass)) {
            return (T) new HashSet<>(toJavaList(jsObj, ownedJson));
        } else if (Map.class.isAssignableFrom(targetClass)) {
            return (T) toJavaMap(jsObj, ownedJson);
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
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();

            // Use direct property setting instead of JSObjects.setProperty
            if (value == null) {
                setProperty(result, key, (JSObject) null);
            } else if (value instanceof String) {
                setProperty(result, key, (String) value);
            } else if (value instanceof Number) {
                setProperty(result, key, ((Number) value).doubleValue());
            } else if (value instanceof Boolean) {
                setProperty(result, key, (Boolean) value);
            } else if (value instanceof Map || value instanceof Collection || value instanceof Object[]) {
                setProperty(result, key, toJSObject(value));
            } else {
                setProperty(result, key, String.valueOf(value));
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
            } else if (item instanceof String) {
                setElement(array, index, (String) item);
            } else if (item instanceof Number) {
                setElement(array, index, ((Number) item).doubleValue());
            } else if (item instanceof Boolean) {
                setElement(array, index, (Boolean) item);
            } else if (item instanceof Map || item instanceof Collection || item instanceof Object[]) {
                array.set(index, toJSObject(item));
            } else {
                setElement(array, index, String.valueOf(item));
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
            } else if (item instanceof String) {
                setElement(result, i, (String) item);
            } else if (item instanceof Number) {
                setElement(result, i, ((Number) item).doubleValue());
            } else if (item instanceof Boolean) {
                setElement(result, i, (Boolean) item);
            } else if (item instanceof Map || item instanceof Collection || item instanceof Object[]) {
                result.set(i, toJSObject(item));
            } else {
                setElement(result, i, String.valueOf(item));
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
        return toJavaList(jsArray, false);
    }

    private static List<Object> toJavaList(JSObject jsArray, boolean ownedJson) {
        if (!isJSArray(jsArray)) {
            throw new IllegalArgumentException("Not a JavaScript array");
        }

        // Owned JSON arrays have no accessors. A typed transfer avoids interop
        // wrappers for string leaves while keeping the Java copy independent.
        if (ownedJson) {
            String[] strings = getStringArray(jsArray);
            if (strings != null) return new ArrayList<>(Arrays.asList(strings));
        }

        JSArray array = (JSArray) jsArray;
        int length = array.getLength();
        List<Object> list = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            list.add(ownedJson ? convertElement(array, i) : convertJSValue(getElement(array, i)));
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
        return toJavaMap(jsObj, false);
    }

    private static Map<String, Object> toJavaMap(JSObject jsObj, boolean ownedJson) {
        if (jsObj == null) {
            return null;
        }

        String[] keys = getObjectKeys(jsObj);
        Map<String, Object> map = new HashMap<>(Math.max(16, keys.length));
        for (String key : keys) {
            map.put(key, ownedJson ? convertProperty(jsObj, key) : convertJSValue(getProperty(jsObj, key)));
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

    // Read primitive values through typed interop. Generic JSObject values in
    // Wasm need WeakRef-backed wrappers, even for a string consumed immediately.
    // This path is exclusively for owned JSON trees without getters or proxies.
    private static Object convertProperty(JSObject object, String key) {
        switch (propertyType(object, key)) {
            case 0:
                return null;
            case 1:
                return getStringProperty(object, key);
            case 2:
                return boxNumber(getNumberProperty(object, key));
            case 3:
                return getBooleanProperty(object, key);
            case 4:
                return toJavaList(getProperty(object, key), true);
            case 5:
                return toJavaMap(getProperty(object, key), true);
            default:
                return String.valueOf(getProperty(object, key));
        }
    }

    @JSBody(
        params = "array",
        script = "for (let i = 0; i < array.length; i++) { " + "if (typeof array[i] !== 'string') return null; } return array;"
    )
    private static native String[] getStringArray(JSObject array);

    private static Object convertElement(JSObject array, int index) {
        switch (elementType(array, index)) {
            case 0:
                return null;
            case 1:
                return getStringElement(array, index);
            case 2:
                return boxNumber(getNumberElement(array, index));
            case 3:
                return getBooleanElement(array, index);
            case 4:
                return toJavaList(getElement(array, index), true);
            case 5:
                return toJavaMap(getElement(array, index), true);
            default:
                return String.valueOf(getElement(array, index));
        }
    }

    // Convert the captured value, so public conversion evaluates each accessor once.
    private static Object convertJSValue(JSObject value) {
        switch (valueType(value)) {
            case 0:
                return null;
            case 1:
                return ((JSString) value).stringValue();
            case 2:
                return boxNumber(getNumberValue(value));
            case 3:
                return getBooleanValue(value);
            case 4:
                return toJavaList(value);
            case 5:
                return toJavaMap(value);
            default:
                return String.valueOf(value);
        }
    }

    @JSBody(
        params = "value",
        script = "if (value == null) return 0; " +
        "switch (typeof value) { case 'string': return 1; case 'number': return 2; case 'boolean': return 3; " +
        "case 'object': return Array.isArray(value) ? 4 : 5; default: return 6; }"
    )
    private static native int valueType(JSObject value);

    private static Number boxNumber(double value) {
        // Preserve the existing Integer/Long/Double conversion policy.
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) return Integer.valueOf((int) value);
            return Long.valueOf((long) value);
        }
        return Double.valueOf(value);
    }

    @JSBody(
        params = { "obj", "key" },
        script = "const value = obj[key]; if (value == null) return 0; " +
        "switch (typeof value) { case 'string': return 1; case 'number': return 2; case 'boolean': return 3; " +
        "case 'object': return Array.isArray(value) ? 4 : 5; default: return 6; }"
    )
    private static native int propertyType(JSObject obj, String key);

    @JSBody(
        params = { "obj", "index" },
        script = "const value = obj[index]; if (value == null) return 0; " +
        "switch (typeof value) { case 'string': return 1; case 'number': return 2; case 'boolean': return 3; " +
        "case 'object': return Array.isArray(value) ? 4 : 5; default: return 6; }"
    )
    private static native int elementType(JSObject obj, int index);

    @JSBody(params = { "obj", "key" }, script = "return obj[key];")
    private static native String getStringProperty(JSObject obj, String key);

    @JSBody(params = { "obj", "key" }, script = "return obj[key];")
    private static native double getNumberProperty(JSObject obj, String key);

    @JSBody(params = { "obj", "key" }, script = "return obj[key];")
    private static native boolean getBooleanProperty(JSObject obj, String key);

    @JSBody(params = { "obj", "index" }, script = "return obj[index];")
    private static native String getStringElement(JSObject obj, int index);

    @JSBody(params = { "obj", "index" }, script = "return obj[index];")
    private static native double getNumberElement(JSObject obj, int index);

    @JSBody(params = { "obj", "index" }, script = "return obj[index];")
    private static native boolean getBooleanElement(JSObject obj, int index);

    @JSBody(params = { "obj", "index" }, script = "return obj[index];")
    private static native JSObject getElement(JSObject obj, int index);

    @JSBody(params = { "obj", "key", "value" }, script = "obj[key] = value;")
    private static native void setProperty(JSObject obj, String key, String value);

    @JSBody(params = { "obj", "key", "value" }, script = "obj[key] = value;")
    private static native void setProperty(JSObject obj, String key, double value);

    @JSBody(params = { "obj", "key", "value" }, script = "obj[key] = value;")
    private static native void setProperty(JSObject obj, String key, boolean value);

    @JSBody(params = { "obj", "index", "value" }, script = "obj[index] = value;")
    private static native void setElement(JSObject obj, int index, String value);

    @JSBody(params = { "obj", "index", "value" }, script = "obj[index] = value;")
    private static native void setElement(JSObject obj, int index, double value);

    @JSBody(params = { "obj", "index", "value" }, script = "obj[index] = value;")
    private static native void setElement(JSObject obj, int index, boolean value);

    /**
     * Check if a JSObject is a JS Array.
     */
    @JSBody(params = "obj", script = "return Array.isArray(obj);")
    private static native boolean isJSArray(JSObject obj);

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
