package ai.lambda.agent.core;

import ai.lambda.agent.prebuilt.EchoTool;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Role;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ai.lambda.agent.core.RoutingModelClient.callTool;
import static ai.lambda.agent.core.RoutingModelClient.text;
import static org.junit.jupiter.api.Assertions.*;

class StructuredOutputTest {

    enum Status { PAID, OPEN }

    record Line(String item, int quantity, double price) {
    }

    @Description("An invoice from an email")
    record Invoice(
            @Description("Who pays") String customer,
            LocalDate date,
            Status status,
            List<Line> lines,
            Map<String, Integer> tags,
            Optional<String> note) {
    }

    record Percent(int value) {
        Percent {
            if (value < 0 || value > 100) throw new IllegalArgumentException("value must be between 0 and 100");
        }
    }

    record Node(String name, List<Node> children) {
    }

    private static final String VALID_INVOICE = """
            {"customer": "ACME", "date": "2026-09-29", "status": "PAID",
             "lines": [{"item": "bolt", "quantity": 10, "price": 0.25}],
             "tags": {"priority": 1}}
            """;

    private static List<String> problems(StructuredOutput<?> output, String json) {
        return assertThrows(StructuredOutput.InvalidOutputException.class, () -> output.parse(json)).problems();
    }

    // --- Schema generation ---

    @Test
    void schemaDescribesTheRecord() {
        JSONObject schema = new JSONObject(StructuredOutput.of(Invoice.class).schemaJson());
        JSONObject properties = schema.getJSONObject("properties");

        assertEquals("object", schema.getString("type"));
        assertEquals("An invoice from an email", schema.getString("description"));
        assertFalse(schema.getBoolean("additionalProperties"));
        assertEquals(List.of("customer", "date", "status", "lines", "tags"),
                schema.getJSONArray("required").toList(), "Optional components are not required");
        assertEquals("Who pays", properties.getJSONObject("customer").getString("description"));
        assertEquals("date", properties.getJSONObject("date").getString("format"));
        assertEquals(List.of("PAID", "OPEN"), properties.getJSONObject("status").getJSONArray("enum").toList());
        JSONObject line = properties.getJSONObject("lines").getJSONObject("items");
        assertEquals("integer", line.getJSONObject("properties").getJSONObject("quantity").getString("type"));
        assertEquals("number", line.getJSONObject("properties").getJSONObject("price").getString("type"));
        assertEquals("integer", properties.getJSONObject("tags").getJSONObject("additionalProperties").getString("type"));
        assertEquals("string", properties.getJSONObject("note").getString("type"));
    }

    @Test
    void unsupportedTypesFailEarlyWithAClearMessage() {
        assertThrows(IllegalArgumentException.class, () -> StructuredOutput.of(String.class));
        IllegalArgumentException recursive = assertThrows(IllegalArgumentException.class,
                () -> StructuredOutput.of(Node.class));
        assertTrue(recursive.getMessage().contains("contain themselves"), recursive.getMessage());
    }

    // --- Parsing and checking ---

    @Test
    void parsesAValidAnswer() {
        Invoice invoice = StructuredOutput.of(Invoice.class).parse(VALID_INVOICE);

        assertEquals(new Invoice("ACME", LocalDate.of(2026, 9, 29), Status.PAID,
                List.of(new Line("bolt", 10, 0.25)), Map.of("priority", 1), Optional.empty()), invoice);
    }

    @Test
    void listsEveryProblemWithItsPath() {
        List<String> problems = problems(StructuredOutput.of(Invoice.class), """
                {"date": "29/09/2026", "status": "LATE", "extra": 1,
                 "lines": [{"item": "bolt", "quantity": 1, "price": 1}, {"item": "nut", "quantity": 2.5, "price": "x"}],
                 "tags": {}}
                """);

        assertTrue(problems.contains("$.customer: is required"), problems.toString());
        assertTrue(problems.contains("$.date: expected an ISO date like 2026-09-29"), problems.toString());
        assertTrue(problems.contains("$.status: must be one of [PAID, OPEN]"), problems.toString());
        assertTrue(problems.contains("$.lines[1].quantity: expected a whole number, got 2.5"), problems.toString());
        assertTrue(problems.contains("$.lines[1].price: expected a number"), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("$.extra: unknown field")), problems.toString());
        assertEquals(6, problems.size(), problems.toString());
    }

    @Test
    void acceptsCommonModelQuirks() {
        Invoice invoice = StructuredOutput.of(Invoice.class).parse("""
                {"customer": "ACME", "date": "2026-09-29", "status": "paid",
                 "lines": [{"item": "bolt", "quantity": "10", "price": "0.25"}], "tags": {}, "note": null}
                """);

        assertEquals(Status.PAID, invoice.status());
        assertEquals(10, invoice.lines().get(0).quantity());
        assertEquals(Optional.empty(), invoice.note());
    }

    @Test
    void recordConstructorChecksAndValidatorsBecomeProblems() {
        assertEquals(List.of("$: value must be between 0 and 100"),
                problems(StructuredOutput.of(Percent.class), "{\"value\": 150}"));

        var output = StructuredOutput.of(Percent.class).withValidator(p -> {
            if (p.value() % 5 != 0) throw new IllegalArgumentException("value must be a multiple of 5");
        });
        assertEquals(List.of("value must be a multiple of 5"), problems(output, "{\"value\": 42}"));
        assertEquals(new Percent(40), output.parse("{\"value\": 40}"));
    }

    @Test
    void invalidJsonIsAProblemToo() {
        List<String> problems = problems(StructuredOutput.of(Percent.class), "{not json");
        assertTrue(problems.get(0).startsWith("$: not valid JSON"), problems.toString());
    }

    @Test
    void rawSchemasAreCheckedAndReturnMaps() {
        var output = StructuredOutput.ofSchema("""
                {"type": "object",
                 "properties": {"label": {"type": "string", "enum": ["spam", "ham"]},
                                "score": {"type": "integer"},
                                "reasons": {"type": "array", "items": {"type": "string"}}},
                 "required": ["label", "score"],
                 "additionalProperties": false}
                """);

        assertEquals(Map.of("label", "spam", "score", 3, "reasons", List.of("links")),
                output.parse("{\"label\": \"spam\", \"score\": 3, \"reasons\": [\"links\"]}"));
        List<String> problems = problems(output, "{\"label\": \"eggs\", \"reasons\": [1], \"x\": true}");
        assertTrue(problems.contains("$.score: is required"), problems.toString());
        assertTrue(problems.contains("$.label: must be one of [\"spam\",\"ham\"]"), problems.toString());
        assertTrue(problems.contains("$.reasons[0]: expected string"), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("$.x: unknown field")), problems.toString());
        assertThrows(IllegalArgumentException.class, () -> StructuredOutput.ofSchema("{\"type\": \"array\"}"));
    }

    // --- With an agent ---

    private static Agent agent(RoutingModelClient model, List<AgentTool> tools, int maxIterations) {
        return new Agent(new AgentConfig("sys", model, tools, maxIterations), new InMemorySessionStore());
    }

    @Test
    void agentReturnsTheParsedAnswer() {
        var model = new RoutingModelClient(r -> callTool("submit_result", VALID_INVOICE));

        StructuredResult<Invoice> result = agent(model, List.of(), 5)
                .run("s", "extract the invoice", StructuredOutput.of(Invoice.class));

        assertEquals("ACME", result.value().customer());
        assertEquals(1, result.run().getIterations());
        assertEquals(VALID_INVOICE, result.run().getFinalText());
        assertTrue(model.requests.get(0).toolNames().contains("submit_result"));
    }

    @Test
    void rejectedAnswersAreSentBackAndFixed() {
        var model = new RoutingModelClient(r -> r.lastToolResult() == null
                ? callTool("submit_result", "{\"value\": \"lots\"}")
                : callTool("submit_result", "{\"value\": 60}"));

        StructuredResult<Percent> result = agent(model, List.of(), 5).run("s", "go", StructuredOutput.of(Percent.class));

        assertEquals(new Percent(60), result.value());
        assertEquals(2, result.run().getIterations());
        String feedback = model.requests.get(1).lastToolResult();
        assertTrue(feedback.startsWith("The answer was rejected."), feedback);
        assertTrue(feedback.contains("- $.value: expected a whole number"), feedback);
    }

    @Test
    void aPlainTextReplyIsAnsweredWithAReminder() {
        var model = new RoutingModelClient(r -> r.lastUser().contains("submit_result")
                ? callTool("submit_result", "{\"value\": 5}")
                : text("The answer is 5."));

        StructuredResult<Percent> result = agent(model, List.of(), 5).run("s", "go", StructuredOutput.of(Percent.class));

        assertEquals(new Percent(5), result.value());
        assertTrue(model.requests.get(1).lastUser().startsWith("Give your final answer by calling the submit_result tool"));
    }

    @Test
    void otherToolsCanBeUsedBeforeAnswering() {
        var model = new RoutingModelClient(r -> r.lastToolResult() == null
                ? callTool("echo", "{\"text\":\"look it up\"}")
                : callTool("submit_result", "{\"value\": 7}"));

        StructuredResult<Percent> result = agent(model, List.of(new EchoTool()), 5)
                .run("s", "go", StructuredOutput.of(Percent.class));

        assertEquals(new Percent(7), result.value());
        List<Message> session = result.run().getSession().getMessages();
        assertTrue(session.stream().anyMatch(m -> m.getRole() == Role.TOOL && m.getContent().equals("[echo] look it up")));
        assertEquals("Answer accepted.", session.get(session.size() - 1).getContent());
        ContextStrategyTest.assertValidForModels(session);
    }

    @Test
    void twoAnswersInOneReplyKeepTheFirstValidOneAndAnswerEveryCall() {
        var first = new ai.lambda.ai.core.ToolCall("a", "submit_result", "{\"value\": 10}");
        var second = new ai.lambda.ai.core.ToolCall("b", "submit_result", "{\"value\": 20}");
        var model = new RoutingModelClient(r -> new ai.lambda.ai.core.ChatResponse(
                new Message(Role.ASSISTANT, "", null, null, List.of(first, second)), List.of(first, second)));

        StructuredResult<Percent> result = agent(model, List.of(), 5).run("s", "go", StructuredOutput.of(Percent.class));

        assertEquals(new Percent(10), result.value());
        List<Message> session = result.run().getSession().getMessages();
        assertEquals("Ignored: an answer was already accepted.", session.get(session.size() - 1).getContent());
        ContextStrategyTest.assertValidForModels(session);
    }

    @Test
    void givingUpThrowsWithTheLastProblemsAndSavesTheSession() {
        var model = new RoutingModelClient(r -> callTool("submit_result", "{\"value\": 500}"));
        var store = new InMemorySessionStore();
        var agent = new Agent(new AgentConfig("sys", model, List.of(), 3), store);

        StructuredOutputException e = assertThrows(StructuredOutputException.class,
                () -> agent.run("s", "go", StructuredOutput.of(Percent.class)));

        assertEquals(List.of("$: value must be between 0 and 100"), e.getLastProblems());
        assertTrue(e.getMessage().contains("No valid answer after 3 iterations"), e.getMessage());
        assertEquals(3, model.requests.size());
        assertFalse(store.loadOrCreate("s").getMessages().isEmpty(), "the session was saved");
    }

    @Test
    void aToolNamedSubmitResultIsAConflict() {
        AgentTool clash = new AgentTool() {
            public String getName() { return "submit_result"; }
            public String getDescription() { return "clash"; }
            public String getJsonSchema() { return "{\"type\":\"object\"}"; }
            public ToolResult execute(ToolInvocationContext context) { return ToolResult.of(""); }
        };
        var model = new RoutingModelClient(r -> text("unused"));

        assertThrows(IllegalArgumentException.class,
                () -> agent(model, List.of(clash), 5).run("s", "go", StructuredOutput.of(Percent.class)));
    }

    @Test
    void plainRunsDoNotOfferSubmitResult() {
        var model = new RoutingModelClient(r -> text("hello"));

        agent(model, List.of(), 5).run("s", "hi");

        assertFalse(model.requests.get(0).toolNames().contains("submit_result"));
    }

    // --- As a tool's typed input ---

    record SearchArgs(String query, int limit) {
        SearchArgs {
            if (limit < 1) throw new IllegalArgumentException("limit must be at least 1");
        }
    }

    @Test
    void worksAsATypedToolInput() {
        StructuredOutput<SearchArgs> args = StructuredOutput.of(SearchArgs.class);
        AgentTool search = new AgentTool() {
            public String getName() { return "search"; }
            public String getDescription() { return "Searches."; }
            public String getJsonSchema() { return args.schemaJson(); }
            public TypedToolInput<?> getTypedInputSchema() { return args; }
            public ToolResult execute(ToolInvocationContext context) {
                return ToolResult.of("found for " + args.parse(context.getArgumentsJson()).query());
            }
        };
        var model = new RoutingModelClient(r -> r.lastToolResult() == null
                ? callTool("search", "{\"query\": \"java\", \"limit\": 0}")
                : text(r.lastToolResult()));

        String out = agent(model, List.of(search), 5).run("s", "go").getFinalText();

        assertTrue(out.contains("arguments were rejected") && out.contains("limit must be at least 1"), out);
    }
}
