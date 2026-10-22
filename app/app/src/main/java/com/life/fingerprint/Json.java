package com.life.fingerprint;

import org.json.*;
import java.lang.reflect.Array;
import java.util.*;

// 数据契约：长整数显式编码，避免浏览器把原始数值舍入。
public final class Json {
    private Json() {}
    public static JSONObject obj(Object... pairs) {
        JSONObject out = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) out.put(String.valueOf(pairs[i]), value(pairs[i + 1])); }
        catch (JSONException e) { throw new IllegalStateException(e); }
        return out;
    }
    public static Object value(Object v) {
        if (v == null) return JSONObject.NULL;
        if (v instanceof Long) return obj("$type", "int64", "value", String.valueOf(v));
        if (v instanceof Float || v instanceof Double) {
            double n = ((Number) v).doubleValue();
            if (!Double.isFinite(n)) return obj("$type", "float", "value", String.valueOf(v));
        }
        if (v.getClass().isArray()) { JSONArray a = new JSONArray(); for (int i = 0; i < Array.getLength(v); i++) a.put(value(Array.get(v, i))); return a; }
        if (v instanceof Collection) { JSONArray a = new JSONArray(); for (Object x : (Collection<?>) v) a.put(value(x)); return a; }
        return v;
    }
    public static void put(JSONObject o, String k, Object v) { try { o.put(k, value(v)); } catch (JSONException e) { throw new IllegalStateException(e); } }
    public static String type(Object v) {
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof Long) return "int64";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Number) return "number";
        if (v instanceof JSONArray || v.getClass().isArray() || v instanceof Collection) return "array";
        if (v instanceof JSONObject) return "object";
        return "string";
    }
    public static Object bytes(String base64) {
        byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP);
        try { return StandardCharsetsHolder.decode(bytes); }
        catch (java.nio.charset.CharacterCodingException e) { return obj("$type", "base64", "value", base64); }
    }
    private static final class StandardCharsetsHolder {
        static String decode(byte[] b) throws java.nio.charset.CharacterCodingException {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b)).toString();
        }
    }
}
