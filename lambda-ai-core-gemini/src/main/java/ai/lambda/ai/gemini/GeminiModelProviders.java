package ai.lambda.ai.gemini;

import ai.lambda.ai.core.ModelProvider;

import java.util.List;

/** Makes Gemini available to {@link ai.lambda.ai.core.Models} as {@code gemini} or {@code google}. */
public final class GeminiModelProviders implements ModelProvider.Registry {

    @Override
    public List<ModelProvider> providers() {
        return List.of(new ModelProvider("gemini", List.of("google"), "GEMINI_API_KEY", "gemini-3.1-flash",
                GeminiModelClient::new));
    }
}
