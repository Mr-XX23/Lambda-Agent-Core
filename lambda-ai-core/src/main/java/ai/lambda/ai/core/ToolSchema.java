package ai.lambda.ai.core;

public final class ToolSchema {

    private final String name;
    private final String description;
    private final String jsonSchema;

    public ToolSchema(String name, String description, String jsonSchema) {
        this.name = name;
        this.description = description;
        this.jsonSchema = jsonSchema;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getJsonSchema() {
        return jsonSchema;
    }
}
