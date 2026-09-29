package ai.lambda.examples.structuredOutput;

import ai.lambda.agent.core.*;
import ai.lambda.ai.client.GoogleModelClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Example: turn a messy email into a typed Java object.
 *
 * The agent must answer with an {@link Invoice}. If its answer has a missing field, a wrong
 * type, or breaks a rule (the line totals must add up), the problems are sent back to it and
 * it tries again.
 */
public final class StructuredOutputExample {

    enum Currency { EUR, USD, GBP }

    record Line(
            @Description("What was bought") String item,
            @Description("How many") int quantity,
            @Description("Price of one item") double unitPrice) {
    }

    @Description("An invoice found in an email")
    record Invoice(
            @Description("Name of the company that must pay") String customer,
            LocalDate invoiceDate,
            Currency currency,
            List<Line> lines,
            @Description("Grand total as written in the email") double total,
            @Description("Payment terms, if the email mentions any") Optional<String> paymentTerms) {

        Invoice {
            if (lines.isEmpty()) throw new IllegalArgumentException("an invoice needs at least one line");
        }
    }

    static final String EMAIL = """
            Hi team,

            Quick note from Globex Corp. accounts: we got your delivery on 3 Sept 2026, thanks!
            As agreed: 12 steel brackets at 4.50 each and 2 mounting kits for 19.99 apiece,
            so 93.98 dollars in total. We'll pay within 30 days of receipt as usual.

            Best, Dana
            """;

    static StructuredOutput<Invoice> invoiceOutput() {
        return StructuredOutput.of(Invoice.class).withValidator(invoice -> {
            double sum = invoice.lines().stream().mapToDouble(l -> l.quantity() * l.unitPrice()).sum();
            if (Math.abs(sum - invoice.total()) > 0.01) {
                throw new IllegalArgumentException("the lines add up to " + sum + " but total is " + invoice.total()
                        + "; check the quantities and prices");
            }
        });
    }

    public static void main(String[] args) {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Please set GEMINI_API_KEY environment variable.");
            return;
        }
        var model = new GoogleModelClient(apiKey, "gemini-3.1-flash-lite-preview");
        var agent = new Agent(new AgentConfig("You extract business documents from emails.", model),
                new InMemorySessionStore());

        StructuredResult<Invoice> result = agent.run("invoice", "Extract the invoice:\n" + EMAIL, invoiceOutput());

        Invoice invoice = result.value();
        System.out.println("Customer : " + invoice.customer());
        System.out.println("Date     : " + invoice.invoiceDate());
        System.out.println("Currency : " + invoice.currency());
        invoice.lines().forEach(l -> System.out.printf("  %2d x %-16s %8.2f%n", l.quantity(), l.item(), l.unitPrice()));
        System.out.println("Total    : " + invoice.total());
        System.out.println("Terms    : " + invoice.paymentTerms().orElse("(none)"));
        System.out.println("(answered in " + result.run().getIterations() + " step(s))");
    }
}
