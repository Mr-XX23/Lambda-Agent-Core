package ai.lambda.agent.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.List;

/**
 * Checks parsed JSON against a JSON Schema. Covers the keywords models are usually given:
 * {@code type} (a name or a list of names), {@code properties}, {@code required},
 * {@code additionalProperties} (false or a schema), {@code items} and {@code enum}.
 * Other keywords are ignored.
 */
final class SchemaCheck {

    private SchemaCheck() {
    }

    static void check(Object value, JSONObject schema, String path, List<String> errors) {
        JSONArray allowed = schema.optJSONArray("enum");
        if (allowed != null && !contains(allowed, value)) {
            errors.add(path + ": must be one of " + allowed);
            return;
        }

        Object type = schema.opt("type");
        if (type instanceof String name && !hasType(value, name)) {
            errors.add(path + ": expected " + name);
            return;
        }
        if (type instanceof JSONArray names) {
            boolean any = false;
            for (int i = 0; i < names.length(); i++) any |= hasType(value, names.optString(i));
            if (!any) {
                errors.add(path + ": expected one of " + names);
                return;
            }
        }

        if (value instanceof JSONObject object) {
            JSONObject properties = schema.optJSONObject("properties");
            JSONArray required = schema.optJSONArray("required");
            if (required != null) {
                for (int i = 0; i < required.length(); i++) {
                    String key = required.optString(i);
                    if (!object.has(key) || object.isNull(key)) errors.add(path + "." + key + ": is required");
                }
            }
            Object additional = schema.opt("additionalProperties");
            for (String key : object.keySet()) {
                JSONObject property = properties == null ? null : properties.optJSONObject(key);
                if (property != null) {
                    check(object.get(key), property, path + "." + key, errors);
                } else if (Boolean.FALSE.equals(additional)) {
                    errors.add(path + "." + key + ": unknown field"
                            + (properties == null ? "" : "; expected fields are " + properties.keySet()));
                } else if (additional instanceof JSONObject additionalSchema) {
                    check(object.get(key), additionalSchema, path + "." + key, errors);
                }
            }
        }

        if (value instanceof JSONArray array) {
            JSONObject items = schema.optJSONObject("items");
            if (items != null) {
                for (int i = 0; i < array.length(); i++) check(array.get(i), items, path + "[" + i + "]", errors);
            }
        }
    }

    private static boolean hasType(Object value, String type) {
        return switch (type) {
            case "object" -> value instanceof JSONObject;
            case "array" -> value instanceof JSONArray;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null || value == JSONObject.NULL;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number n && isWhole(n);
            default -> true;
        };
    }

    private static boolean isWhole(Number n) {
        try {
            new BigDecimal(n.toString()).toBigIntegerExact();
            return true;
        } catch (ArithmeticException | NumberFormatException e) {
            return false;
        }
    }

    private static boolean contains(JSONArray allowed, Object value) {
        for (int i = 0; i < allowed.length(); i++) {
            Object option = allowed.get(i);
            if (option.equals(value)) return true;
            if (option instanceof Number a && value instanceof Number b
                    && new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0) return true;
        }
        return false;
    }
}
