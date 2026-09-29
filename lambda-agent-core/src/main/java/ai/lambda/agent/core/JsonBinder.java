package ai.lambda.agent.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Turns parsed JSON (org.json values) into Java objects of the types {@link JsonSchemas} supports.
 * Instead of stopping at the first problem, it collects every problem with its path
 * (like {@code $.items[2].price}), so the model can fix them all in one go.
 */
final class JsonBinder {

    private JsonBinder() {
    }

    /** Returns the value, or null if there were errors (they are added to {@code errors}). */
    static Object bind(Object json, Type type, String path, List<String> errors) {
        if (json == null || json == JSONObject.NULL) {
            errors.add(path + ": is required");
            return null;
        }
        if (type instanceof ParameterizedType p) {
            Class<?> raw = (Class<?>) p.getRawType();
            Type[] args = p.getActualTypeArguments();
            if (raw == List.class || raw == java.util.Collection.class) return list(json, args[0], path, errors, false);
            if (raw == Set.class) return list(json, args[0], path, errors, true);
            if (raw == Map.class) return map(json, args[1], path, errors);
            if (raw == Optional.class) return Optional.ofNullable(bind(json, args[0], path, errors));
        }
        Class<?> c = (Class<?>) type;

        if (c == String.class) {
            if (json instanceof String s) return s;
            return fail(errors, path, "expected a string");
        }
        if (c == char.class || c == Character.class) {
            if (json instanceof String s && s.length() == 1) return s.charAt(0);
            return fail(errors, path, "expected a single character");
        }
        if (c == boolean.class || c == Boolean.class) {
            if (json instanceof Boolean b) return b;
            if ("true".equals(json) || "false".equals(json)) return Boolean.valueOf((String) json);
            return fail(errors, path, "expected true or false");
        }
        if (c == int.class || c == Integer.class) return integer(json, path, errors, Integer.MIN_VALUE, Integer.MAX_VALUE, BigInteger::intValue);
        if (c == long.class || c == Long.class) return integer(json, path, errors, Long.MIN_VALUE, Long.MAX_VALUE, BigInteger::longValue);
        if (c == short.class || c == Short.class) return integer(json, path, errors, Short.MIN_VALUE, Short.MAX_VALUE, BigInteger::shortValue);
        if (c == byte.class || c == Byte.class) return integer(json, path, errors, Byte.MIN_VALUE, Byte.MAX_VALUE, BigInteger::byteValue);
        if (c == BigInteger.class) return integer(json, path, errors, null, null, n -> n);
        if (c == double.class || c == Double.class) return number(json, path, errors, BigDecimal::doubleValue);
        if (c == float.class || c == Float.class) return number(json, path, errors, BigDecimal::floatValue);
        if (c == BigDecimal.class) return number(json, path, errors, n -> n);
        if (c.isEnum()) return enumValue(json, c, path, errors);
        if (c == LocalDate.class) return parse(json, path, errors, LocalDate::parse, "an ISO date like 2026-09-29");
        if (c == LocalTime.class) return parse(json, path, errors, LocalTime::parse, "a time like 14:30:00");
        if (c == LocalDateTime.class) return parse(json, path, errors, LocalDateTime::parse, "a date and time like 2026-09-29T14:30:00");
        if (c == Instant.class) return parse(json, path, errors, Instant::parse, "a UTC timestamp like 2026-09-29T14:30:00Z");
        if (c == OffsetDateTime.class) return parse(json, path, errors, OffsetDateTime::parse, "a timestamp like 2026-09-29T14:30:00+02:00");
        if (c == UUID.class) return parse(json, path, errors, UUID::fromString, "a UUID");
        if (c == URI.class) return parse(json, path, errors, URI::new, "a URI");
        if (c.isArray()) return array(json, c.getComponentType(), path, errors);
        if (c.isRecord()) return record(json, c, path, errors);
        return fail(errors, path, "unsupported type " + c.getName());
    }

    private static Object fail(List<String> errors, String path, String message) {
        errors.add(path + ": " + message);
        return null;
    }

    private static Object integer(Object json, String path, List<String> errors, Number min, Number max,
                                  Function<BigInteger, Object> convert) {
        BigDecimal decimal = decimal(json);
        if (decimal == null) return fail(errors, path, "expected a whole number");
        BigInteger whole;
        try {
            whole = decimal.toBigIntegerExact();
        } catch (ArithmeticException e) {
            return fail(errors, path, "expected a whole number, got " + decimal.toPlainString());
        }
        if (min != null && (whole.compareTo(BigInteger.valueOf(min.longValue())) < 0
                || whole.compareTo(BigInteger.valueOf(max.longValue())) > 0)) {
            return fail(errors, path, "must be between " + min + " and " + max);
        }
        return convert.apply(whole);
    }

    private static Object number(Object json, String path, List<String> errors, Function<BigDecimal, Object> convert) {
        BigDecimal decimal = decimal(json);
        if (decimal == null) return fail(errors, path, "expected a number");
        return convert.apply(decimal);
    }

    // Numbers may also arrive as numeric strings ("42"); models do that now and then.
    private static BigDecimal decimal(Object json) {
        try {
            if (json instanceof Number n) return new BigDecimal(n.toString());
            if (json instanceof String s) return new BigDecimal(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static Object enumValue(Object json, Class<?> type, String path, List<String> errors) {
        List<String> names = new ArrayList<>();
        for (Object constant : type.getEnumConstants()) {
            String name = ((Enum<?>) constant).name();
            names.add(name);
            if (name.equals(json)) return constant;
        }
        if (json instanceof String s) {
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(s.strip())) return constant;
            }
        }
        return fail(errors, path, "must be one of " + names);
    }

    private interface Parser {
        Object parse(String text) throws Exception;
    }

    private static Object parse(Object json, String path, List<String> errors, Parser parser, String expected) {
        if (json instanceof String s) {
            try {
                return parser.parse(s.strip());
            } catch (Exception e) {
                // fall through to the error below
            }
        }
        return fail(errors, path, "expected " + expected);
    }

    private static Object list(Object json, Type element, String path, List<String> errors, boolean set) {
        if (!(json instanceof JSONArray array)) return fail(errors, path, "expected an array");
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            values.add(bind(array.opt(i), element, path + "[" + i + "]", errors));
        }
        return set ? Collections.unmodifiableSet(new LinkedHashSet<>(values)) : Collections.unmodifiableList(values);
    }

    private static Object array(Object json, Class<?> element, String path, List<String> errors) {
        if (!(json instanceof JSONArray array)) return fail(errors, path, "expected an array");
        int before = errors.size();
        Object result = Array.newInstance(element, array.length());
        for (int i = 0; i < array.length(); i++) {
            Object value = bind(array.opt(i), element, path + "[" + i + "]", errors);
            if (errors.size() == before) Array.set(result, i, value);
        }
        return result;
    }

    private static Object map(Object json, Type valueType, String path, List<String> errors) {
        if (!(json instanceof JSONObject object)) return fail(errors, path, "expected an object");
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : object.keySet()) {
            values.put(key, bind(object.opt(key), valueType, path + "." + key, errors));
        }
        return Collections.unmodifiableMap(values);
    }

    private static Object record(Object json, Class<?> type, String path, List<String> errors) {
        if (!(json instanceof JSONObject object)) return fail(errors, path, "expected an object");
        RecordComponent[] components = type.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        Set<String> known = new LinkedHashSet<>();
        int before = errors.size();

        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            String name = component.getName();
            known.add(name);
            types[i] = component.getType();
            Object value = object.opt(name);
            boolean missing = value == null || value == JSONObject.NULL;
            if (component.getType() == Optional.class) {
                Type inner = ((ParameterizedType) component.getGenericType()).getActualTypeArguments()[0];
                args[i] = missing ? Optional.empty() : Optional.ofNullable(bind(value, inner, path + "." + name, errors));
            } else if (missing) {
                errors.add(path + "." + name + ": is required");
            } else {
                args[i] = bind(value, component.getGenericType(), path + "." + name, errors);
            }
        }
        for (String key : object.keySet()) {
            if (!known.contains(key)) errors.add(path + "." + key + ": unknown field; expected fields are " + known);
        }
        if (errors.size() > before) return null;

        try {
            Constructor<?> constructor = type.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            // The record's own checks (a compact constructor) rejected the values.
            Throwable cause = e.getCause();
            return fail(errors, path, cause.getMessage() != null ? cause.getMessage() : cause.toString());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create " + type.getName(), e);
        }
    }
}
