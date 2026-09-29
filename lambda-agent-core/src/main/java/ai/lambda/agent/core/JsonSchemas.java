package ai.lambda.agent.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Generates a JSON Schema from a Java type. Supported: records (every component is required
 * unless it is an {@code Optional}), strings, numbers, booleans, enums, lists, sets, arrays,
 * {@code Map<String, V>}, {@code Optional}, {@code LocalDate}, {@code LocalDateTime},
 * {@code LocalTime}, {@code Instant}, {@code OffsetDateTime}, {@code UUID} and {@code URI}.
 */
final class JsonSchemas {

    private static final Set<Class<?>> INTEGERS = Set.of(int.class, Integer.class, long.class, Long.class,
            short.class, Short.class, byte.class, Byte.class, BigInteger.class);
    private static final Set<Class<?>> NUMBERS = Set.of(double.class, Double.class, float.class, Float.class,
            BigDecimal.class);

    private JsonSchemas() {
    }

    static JSONObject schemaFor(Type type) {
        return schema(type, new HashSet<>());
    }

    private static JSONObject schema(Type type, Set<Class<?>> visiting) {
        if (type instanceof ParameterizedType p) {
            Class<?> raw = (Class<?>) p.getRawType();
            Type[] args = p.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw) && (raw == List.class || raw == Set.class || raw == Collection.class)) {
                JSONObject array = new JSONObject().put("type", "array").put("items", schema(args[0], visiting));
                if (raw == Set.class) array.put("uniqueItems", true);
                return array;
            }
            if (raw == Map.class) {
                if (args[0] != String.class) throw unsupported(type, "map keys must be String");
                return new JSONObject().put("type", "object").put("additionalProperties", schema(args[1], visiting));
            }
            if (raw == Optional.class) return schema(args[0], visiting);
            throw unsupported(type, "use List, Set, Map<String, V> or Optional");
        }
        if (!(type instanceof Class<?> c)) throw unsupported(type, "use a concrete type");

        if (c == String.class) return new JSONObject().put("type", "string");
        if (c == char.class || c == Character.class) {
            return new JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 1);
        }
        if (INTEGERS.contains(c)) return new JSONObject().put("type", "integer");
        if (NUMBERS.contains(c)) return new JSONObject().put("type", "number");
        if (c == boolean.class || c == Boolean.class) return new JSONObject().put("type", "boolean");
        if (c.isEnum()) {
            JSONArray values = new JSONArray();
            for (Object constant : c.getEnumConstants()) values.put(((Enum<?>) constant).name());
            return new JSONObject().put("type", "string").put("enum", values);
        }
        if (c == LocalDate.class) return string("date", null);
        if (c == LocalTime.class) return string("time", null);
        if (c == Instant.class || c == OffsetDateTime.class) return string("date-time", null);
        if (c == LocalDateTime.class) return string(null, "Local date and time without a zone, like 2026-09-29T14:30:00");
        if (c == UUID.class) return string("uuid", null);
        if (c == URI.class) return string("uri", null);
        if (c.isArray()) return new JSONObject().put("type", "array").put("items", schema(c.getComponentType(), visiting));
        if (c.isRecord()) return record(c, visiting);
        throw unsupported(type, "use a record for objects");
    }

    private static JSONObject string(String format, String description) {
        JSONObject s = new JSONObject().put("type", "string");
        if (format != null) s.put("format", format);
        if (description != null) s.put("description", description);
        return s;
    }

    private static JSONObject record(Class<?> type, Set<Class<?>> visiting) {
        if (!visiting.add(type)) throw unsupported(type, "records that contain themselves are not supported");
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        for (RecordComponent component : type.getRecordComponents()) {
            JSONObject property = schema(component.getGenericType(), visiting);
            Description description = component.getAnnotation(Description.class);
            if (description != null) property.put("description", description.value());
            properties.put(component.getName(), property);
            if (component.getType() != Optional.class) required.put(component.getName());
        }
        visiting.remove(type);

        JSONObject schema = new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", required)
                .put("additionalProperties", false);
        Description description = type.getAnnotation(Description.class);
        if (description != null) schema.put("description", description.value());
        return schema;
    }

    private static IllegalArgumentException unsupported(Type type, String hint) {
        return new IllegalArgumentException("Cannot describe " + type.getTypeName() + " as JSON Schema: " + hint);
    }
}
