package dev.cloudsavesync.core.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer, so the plugin jar and the upload helper need no third-party libraries.
 *
 * <p>Objects parse to {@link LinkedHashMap}, arrays to {@link ArrayList}, integral numbers to
 * {@link Long} (others to {@link Double}), plus {@link String}, {@link Boolean} and {@code null}.
 */
public final class Json {

    private Json() {
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.value();
        p.skipWhitespace();
        if (p.pos != text.length()) {
            throw p.error("unexpected trailing data");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    /** Pretty-printed with two-space indentation. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    // ---- typed accessors ----

    public static String string(Map<String, ?> obj, String key) {
        Object v = obj.get(key);
        if (v instanceof String s) {
            return s;
        }
        throw new JsonException("missing or non-string field '" + key + "'");
    }

    public static String optString(Map<String, ?> obj, String key) {
        Object v = obj.get(key);
        return v instanceof String s ? s : null;
    }

    public static long number(Map<String, ?> obj, String key) {
        Object v = obj.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        throw new JsonException("missing or non-numeric field '" + key + "'");
    }

    public static Long optNumber(Map<String, ?> obj, String key) {
        Object v = obj.get(key);
        return v instanceof Number n ? n.longValue() : null;
    }

    public static boolean optBoolean(Map<String, ?> obj, String key, boolean fallback) {
        Object v = obj.get(key);
        return v instanceof Boolean b ? b : fallback;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> optObject(Map<String, ?> obj, String key) {
        Object v = obj.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> optArray(Map<String, ?> obj, String key) {
        Object v = obj.get(key);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    // ---- writer ----

    private static void write(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long
                || v instanceof Short || v instanceof Byte) {
            sb.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JsonException("cannot encode " + d);
            }
            sb.append(n);
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                indent(sb, indent + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), indent + 1);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            indent(sb, indent);
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            if (c.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            int i = 0;
            for (Object item : c) {
                indent(sb, indent + 1);
                write(sb, item, indent + 1);
                sb.append(++i < c.size() ? ",\n" : "\n");
            }
            indent(sb, indent);
            sb.append(']');
        } else {
            throw new JsonException("cannot encode " + v.getClass().getName());
        }
    }

    private static void indent(StringBuilder sb, int level) {
        sb.append("  ".repeat(level));
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---- parser ----

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            if (pos >= s.length()) {
                throw error("unexpected end of input");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected a field name");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, value());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(value());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw error("truncated \\u escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("bad \\u escape");
                            }
                            pos += 4;
                        }
                        default -> throw error("bad escape '\\" + e + "'");
                    }
                } else if (c < 0x20) {
                    throw error("control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        private Object number() {
            int start = pos;
            boolean integral = true;
            if (peek() == '-') {
                pos++;
            }
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    integral = false;
                    pos++;
                } else {
                    break;
                }
            }
            String text = s.substring(start, pos);
            try {
                if (integral) {
                    try {
                        return Long.parseLong(text);
                    } catch (NumberFormatException overflow) {
                        return Double.parseDouble(text);
                    }
                }
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw error("bad number '" + text + "'");
            }
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw error("expected '" + word + "'");
            }
            pos += word.length();
            return value;
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private char next() {
            if (pos >= s.length()) {
                throw error("unexpected end of input");
            }
            return s.charAt(pos++);
        }

        private void expect(char c) {
            if (next() != c) {
                pos--;
                throw error("expected '" + c + "'");
            }
        }

        JsonException error(String message) {
            return new JsonException(message + " at offset " + pos);
        }
    }
}
