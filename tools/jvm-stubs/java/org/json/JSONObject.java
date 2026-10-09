package org.json;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LOCAL-ONLY fallback for tools/run-unit-tests.sh when the real org.json jar (org.json:json:20240303)
 * cannot be downloaded. Gradle and CI always use the real library (testImplementation in app/build.gradle.kts).
 * Implements just the subset the app uses, with org.json 20240303 semantics for the opt* methods
 * (optString on a missing key or JSON null returns the fallback, numbers parse from strings).
 */
public class JSONObject {
    /** JSON null. equals(null) is true, like the real library. */
    public static final Object NULL = new Object() {
        @Override public boolean equals(Object o) { return o == null || o == this; }
        @Override public int hashCode() { return 0; }
        @Override public String toString() { return "null"; }
    };

    final Map<String, Object> map = new LinkedHashMap<>();

    public JSONObject() {}

    public JSONObject(String source) {
        JSONParser p = new JSONParser(source);
        Object v = p.value();
        p.end();
        if (!(v instanceof JSONObject)) throw new JSONException("A JSONObject text must begin with '{'");
        map.putAll(((JSONObject) v).map);
    }

    public int length() { return map.size(); }
    public boolean has(String key) { return map.containsKey(key); }
    public boolean isNull(String key) { return NULL.equals(map.get(key)); }
    public Iterator<String> keys() { return map.keySet().iterator(); }
    public Object opt(String key) { return key == null ? null : map.get(key); }

    public Object get(String key) {
        Object v = map.get(key);
        if (v == null) throw new JSONException("JSONObject[\"" + key + "\"] not found.");
        return v;
    }

    public JSONObject put(String key, Object value) {
        if (value == null) map.remove(key); else map.put(key, value);
        return this;
    }
    public JSONObject put(String key, int value) { return put(key, (Object) value); }
    public JSONObject put(String key, long value) { return put(key, (Object) value); }
    public JSONObject put(String key, double value) { return put(key, (Object) value); }
    public JSONObject put(String key, boolean value) { return put(key, (Object) value); }

    public JSONObject optJSONObject(String key) { Object v = opt(key); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray optJSONArray(String key) { Object v = opt(key); return v instanceof JSONArray ? (JSONArray) v : null; }
    public String optString(String key) { return optString(key, ""); }
    public String optString(String key, String fallback) { Object v = opt(key); return NULL.equals(v) ? fallback : v.toString(); }
    public int optInt(String key) { return optInt(key, 0); }
    public int optInt(String key, int fallback) { Number n = JSONParser.num(opt(key)); return n == null ? fallback : n.intValue(); }
    public long optLong(String key) { return optLong(key, 0L); }
    public long optLong(String key, long fallback) { Number n = JSONParser.num(opt(key)); return n == null ? fallback : n.longValue(); }
    public double optDouble(String key) { return optDouble(key, Double.NaN); }
    public double optDouble(String key, double fallback) { Number n = JSONParser.num(opt(key)); return n == null ? fallback : n.doubleValue(); }
    public boolean optBoolean(String key) { return optBoolean(key, false); }
    public boolean optBoolean(String key, boolean fallback) { Boolean b = JSONParser.bool(opt(key)); return b == null ? fallback : b; }

    public String getString(String key) {
        Object v = get(key);
        if (v instanceof String) return (String) v;
        throw new JSONException("JSONObject[\"" + key + "\"] is not a string.");
    }
    public int getInt(String key) { Number n = JSONParser.num(get(key)); if (n == null) throw new JSONException("not a number: " + key); return n.intValue(); }
    public long getLong(String key) { Number n = JSONParser.num(get(key)); if (n == null) throw new JSONException("not a number: " + key); return n.longValue(); }
    public boolean getBoolean(String key) { Boolean b = JSONParser.bool(get(key)); if (b == null) throw new JSONException("not a boolean: " + key); return b; }
    public JSONObject getJSONObject(String key) { Object v = get(key); if (v instanceof JSONObject) return (JSONObject) v; throw new JSONException("not an object: " + key); }
    public JSONArray getJSONArray(String key) { Object v = get(key); if (v instanceof JSONArray) return (JSONArray) v; throw new JSONException("not an array: " + key); }

    public static String quote(String s) { return JSONParser.quote(s); }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(JSONParser.quote(e.getKey())).append(':').append(JSONParser.write(e.getValue()));
        }
        return sb.append('}').toString();
    }
}
