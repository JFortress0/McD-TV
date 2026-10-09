package org.json;

import java.util.ArrayList;
import java.util.List;

/** LOCAL-ONLY fallback (see JSONObject.java). Gradle and CI use the real org.json:json:20240303. */
public class JSONArray {
    final List<Object> list = new ArrayList<>();

    public JSONArray() {}

    public JSONArray(String source) {
        JSONParser p = new JSONParser(source);
        Object v = p.value();
        p.end();
        if (!(v instanceof JSONArray)) throw new JSONException("A JSONArray text must start with '['");
        list.addAll(((JSONArray) v).list);
    }

    public int length() { return list.size(); }
    public Object opt(int i) { return i < 0 || i >= list.size() ? null : list.get(i); }
    public boolean isNull(int i) { return JSONObject.NULL.equals(opt(i)); }

    public Object get(int i) {
        Object v = opt(i);
        if (v == null) throw new JSONException("JSONArray[" + i + "] not found.");
        return v;
    }

    public JSONArray put(Object value) { list.add(value == null ? JSONObject.NULL : value); return this; }
    public JSONArray put(int value) { return put((Object) value); }
    public JSONArray put(long value) { return put((Object) value); }
    public JSONArray put(double value) { return put((Object) value); }
    public JSONArray put(boolean value) { return put((Object) value); }

    public JSONObject optJSONObject(int i) { Object v = opt(i); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray optJSONArray(int i) { Object v = opt(i); return v instanceof JSONArray ? (JSONArray) v : null; }
    public String optString(int i) { return optString(i, ""); }
    public String optString(int i, String fallback) { Object v = opt(i); return JSONObject.NULL.equals(v) ? fallback : v.toString(); }
    public int optInt(int i) { return optInt(i, 0); }
    public int optInt(int i, int fallback) { Number n = JSONParser.num(opt(i)); return n == null ? fallback : n.intValue(); }
    public long optLong(int i) { return optLong(i, 0L); }
    public long optLong(int i, long fallback) { Number n = JSONParser.num(opt(i)); return n == null ? fallback : n.longValue(); }
    public double optDouble(int i) { return optDouble(i, Double.NaN); }
    public double optDouble(int i, double fallback) { Number n = JSONParser.num(opt(i)); return n == null ? fallback : n.doubleValue(); }
    public boolean optBoolean(int i) { return optBoolean(i, false); }
    public boolean optBoolean(int i, boolean fallback) { Boolean b = JSONParser.bool(opt(i)); return b == null ? fallback : b; }

    public String getString(int i) { Object v = get(i); if (v instanceof String) return (String) v; throw new JSONException("not a string: " + i); }
    public int getInt(int i) { Number n = JSONParser.num(get(i)); if (n == null) throw new JSONException("not a number: " + i); return n.intValue(); }
    public long getLong(int i) { Number n = JSONParser.num(get(i)); if (n == null) throw new JSONException("not a number: " + i); return n.longValue(); }
    public JSONObject getJSONObject(int i) { Object v = get(i); if (v instanceof JSONObject) return (JSONObject) v; throw new JSONException("not an object: " + i); }
    public JSONArray getJSONArray(int i) { Object v = get(i); if (v instanceof JSONArray) return (JSONArray) v; throw new JSONException("not an array: " + i); }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(JSONParser.write(list.get(i)));
        }
        return sb.append(']').toString();
    }
}
