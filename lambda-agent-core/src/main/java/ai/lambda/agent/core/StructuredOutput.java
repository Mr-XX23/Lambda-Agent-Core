package ai.lambda.agent.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The shape of the answer an agent must give, so {@code agent.run(...)} returns a Java object
 * instead of text:
 *
 * <pre>
 * record Invoice(String customer, &#64;Description("Total in euros") double total, List&lt;Line&gt; lines) {}
 *
 * Invoice invoice = agent.run("s1", "Extract the invoice: ...", StructuredOutput.of(Invoice.class)).value();
 * </pre>
 *
 * The agent gets a {@code submit_result} tool whose parameters are the answer's JSON Schema, and
 * delivers its answer by calling it. It can still use its other tools first. An answer with
 * missing fields, wrong types, unknown fields, or values rejected by a validator (or by the
 * record's own constructor) is sent back to the model with every problem listed, so it can fix
 * them, until the answer is valid or the iteration limit is reached.
 *
 * <p>Also usable as a tool's {@link TypedToolInput}, with {@link #schemaJson()} as its JSON schema.
 */
public final class StructuredOutput<T> implements TypedToolInput<T> {

    /** The name of the tool the model calls to deliver its answer. */
    public static final String TOOL_NAME = "submit_result";

    private final String schemaJson;
    private final Function<JSONObject, T> converter;
    private final List<Consumer<? super T>> validators;

    private StructuredOutput(String schemaJson, Function<JSONObject, T> converter, List<Consumer<? super T>> validators) {
        this.schemaJson = schemaJson;
        this.converter = converter;
        this.validators = List.copyOf(validators);
    }

    /** An answer shaped like the record {@code type}; see {@link Description} to explain its fields. */
    public static <T> StructuredOutput<T> of(Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        if (!type.isRecord()) {
            throw new IllegalArgumentException(type.getName() + " must be a record to be used as structured output");
        }
        String schema = JsonSchemas.schemaFor(type).toString();
        return new StructuredOutput<>(schema, json -> {
            List<String> errors = new ArrayList<>();
            Object value = JsonBinder.bind(json, type, "$", errors);
            if (!errors.isEmpty()) throw new InvalidOutputException(errors);
            return type.cast(value);
        }, List.of());
    }

    /**
     * An answer described by a raw JSON Schema (its top level must be an object), returned as a
     * map. Checked against the schema's type, properties, required, additionalProperties, items
     * and enum keywords.
     */
    public static StructuredOutput<Map<String, Object>> ofSchema(String jsonSchema) {
        JSONObject schema = new JSONObject(Objects.requireNonNull(jsonSchema, "jsonSchema must not be null"));
        if (!"object".equals(schema.optString("type"))) {
            throw new IllegalArgumentException("The schema's top-level type must be \"object\"");
        }
        return new StructuredOutput<>(schema.toString(), json -> {
            List<String> errors = new ArrayList<>();
            SchemaCheck.check(json, schema, "$", errors);
            if (!errors.isEmpty()) throw new InvalidOutputException(errors);
            return json.toMap();
        }, List.of());
    }

    /**
     * Adds a check on the parsed answer, for rules a schema cannot express. Throw an
     * {@link IllegalArgumentException} with a message for the model to reject the answer:
     *
     * <pre>
     * .withValidator(invoice -> {
     *     if (invoice.total() &lt; 0) throw new IllegalArgumentException("total must not be negative");
     * })
     * </pre>
     */
    public StructuredOutput<T> withValidator(Consumer<? super T> validator) {
        List<Consumer<? super T>> all = new ArrayList<>(validators);
        all.add(Objects.requireNonNull(validator, "validator must not be null"));
        return new StructuredOutput<>(schemaJson, converter, all);
    }

    /** The JSON Schema of the answer. */
    public String schemaJson() {
        return schemaJson;
    }

    /**
     * Parses and checks an answer. Throws {@link InvalidOutputException} listing every problem.
     */
    @Override
    public T parse(String argumentsJson) {
        JSONObject json;
        try {
            json = new JSONObject(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (JSONException e) {
            throw new InvalidOutputException(List.of("$: not valid JSON: " + e.getMessage()));
        }
        T value = converter.apply(json);
        List<String> problems = new ArrayList<>();
        for (Consumer<? super T> validator : validators) {
            try {
                validator.accept(value);
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
        if (!problems.isEmpty()) throw new InvalidOutputException(problems);
        return value;
    }

    AgentTool tool() {
        return new AgentTool() {
            public String getName() { return TOOL_NAME; }

            public String getDescription() {
                return "Delivers your final answer. Call it once you have everything you need; its arguments "
                        + "are the answer. If the answer is rejected, fix the listed problems and call it again.";
            }

            public String getJsonSchema() { return schemaJson; }

            public ToolResult execute(ToolInvocationContext context) {
                throw new UnsupportedOperationException("submit_result is handled by the agent");
            }
        };
    }

    /** An answer that does not match the expected shape; {@link #problems()} lists every problem. */
    public static final class InvalidOutputException extends IllegalArgumentException {
        private final List<String> problems;

        public InvalidOutputException(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }
}
