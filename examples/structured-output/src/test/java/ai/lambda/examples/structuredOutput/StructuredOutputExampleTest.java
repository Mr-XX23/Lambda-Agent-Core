package ai.lambda.examples.structuredOutput;

import ai.lambda.agent.core.StructuredOutput;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the example's types and rules without calling a model. */
class StructuredOutputExampleTest {

    private static final String ANSWER = """
            {"customer": "Globex Corp.", "invoiceDate": "2026-09-03", "currency": "USD",
             "lines": [{"item": "steel bracket", "quantity": 12, "unitPrice": 4.50},
                       {"item": "mounting kit", "quantity": 2, "unitPrice": 19.99}],
             "total": 93.98, "paymentTerms": "30 days"}
            """;

    @Test
    void aCorrectAnswerParses() {
        StructuredOutputExample.Invoice invoice = StructuredOutputExample.invoiceOutput().parse(ANSWER);

        assertEquals("Globex Corp.", invoice.customer());
        assertEquals(StructuredOutputExample.Currency.USD, invoice.currency());
        assertEquals("30 days", invoice.paymentTerms().orElseThrow());
    }

    @Test
    void totalsThatDoNotAddUpAreRejected() {
        String wrongTotal = ANSWER.replace("93.98", "100.00");

        var e = assertThrows(StructuredOutput.InvalidOutputException.class,
                () -> StructuredOutputExample.invoiceOutput().parse(wrongTotal));

        assertTrue(e.problems().get(0).contains("the lines add up to"), e.problems().toString());
    }

    @Test
    void schemaTellsTheModelWhatEachFieldMeans() {
        JSONObject schema = new JSONObject(StructuredOutputExample.invoiceOutput().schemaJson());

        assertEquals("Name of the company that must pay",
                schema.getJSONObject("properties").getJSONObject("customer").getString("description"));
    }
}
