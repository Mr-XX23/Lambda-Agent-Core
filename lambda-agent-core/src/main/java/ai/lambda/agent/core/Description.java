package ai.lambda.agent.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explains a record or one of its components to the model. It becomes the
 * {@code description} in the JSON Schema that {@link StructuredOutput} generates.
 *
 * <pre>
 * &#64;Description("An invoice extracted from an email")
 * record Invoice(&#64;Description("Total in euros, VAT included") double total, ...) {}
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.TYPE})
public @interface Description {
    String value();
}
