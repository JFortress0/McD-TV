package org.json;

import java.math.BigDecimal;
import java.math.BigInteger;

/** LOCAL-ONLY fallback (see JSONObject.java): a small strict JSON reader/writer. Not part of the real org.json API. */
final class JSONParser {
    private final String s;
    private int i;

    JSONParser(String s) {
        if (s == null) throw new NullPointerException();
        this.s = s;
    }

    void end() {
        ws();
        if (i < s.length()) throw err("Unexpected trailing text");
    }

    Object value() {
        ws();
        if (i >= s.length()) throw err("Unexpected end of text");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            default:
                if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
                if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
                if (s.startsWith("null", i)) { i += 4; return JSONObject.NULL; }
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("Unexpected character '" + c + "'");
        }
    }

    private JSONObject object() {
        JSONObject o = new JSONObject();
        i++; // {
        ws();
        if (peek() == '}') { i++; return o; }
        while (true) {
            ws();
            if (peek() != '"') throw err("Expected a key");
            String k = string();
            ws();
            if (peek() != ':') throw err("Expected ':'");
            i++;
            o.map.put(k, value());
            ws();
            char c = next();
            if (c == '}') return o;
            if (c != ',') throw err("Expected ',' or '}'");
        }
    }

    private JSONArray array() {
        JSONArray a = new JSONArray();
        i++; // [
        ws();
        if (peek() == ']') { i++; return a; }
        while (true) {
            a.list.add(value());
            ws();
            char c = next();
            if (c == ']') return a;
            if (c != ',') throw err("Expected ',' or ']'");
        }
    }

    private String string() {
        i++; // "
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = next();
            switch (e) {
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("Bad \\u escape");
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: sb.append(e);
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String t = s.substring(start, i);
        if (t.contains(".") || t.contains("e") || t.contains("E")) {
            BigDecimal bd = new BigDecimal(t);
            double d = bd.doubleValue();
            return Double.isInfinite(d) ? bd : (Object) d;
        }
        BigInteger bi = new BigInteger(t);
        if (bi.bitLength() <= 31) return bi.intValue();
        if (bi.bitLength() <= 63) return bi.longValue();
        return bi;
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private char peek() { return i < s.length() ? s.charAt(i) : 0; }
    private char next() { if (i >= s.length()) throw err("Unexpected end of text"); return s.charAt(i++); }
    private JSONException err(String m) { return new JSONException(m + " at " + i); }

    static Number num(Object v) {
        if (v instanceof Number) return (Number) v;
        if (v instanceof String) {
            try { return new BigDecimal(((String) v).trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    static Boolean bool(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) {
            if ("true".equalsIgnoreCase((String) v)) return true;
            if ("false".equalsIgnoreCase((String) v)) return false;
        }
        return null;
    }

    static String quote(String v) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    static String write(Object v) {
        if (v instanceof String) return quote((String) v);
        return String.valueOf(v);
    }
}
