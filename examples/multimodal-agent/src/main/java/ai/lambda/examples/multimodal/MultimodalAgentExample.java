package ai.lambda.examples.multimodal;

import ai.lambda.agent.core.Agent;
import ai.lambda.agent.core.AgentConfig;
import ai.lambda.agent.core.AgentEventListener;
import ai.lambda.agent.core.AgentTool;
import ai.lambda.agent.core.InMemorySessionStore;
import ai.lambda.agent.core.ToolInvocationContext;
import ai.lambda.agent.prebuilt.MediaTools;
import ai.lambda.ai.client.GeminiMedia;
import ai.lambda.ai.client.OpenAICompatibleProvider;
import ai.lambda.ai.client.OpenAITranscriber;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;
import ai.lambda.ai.core.UnsupportedMediaException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Example: one agent, any provider, with images, audio, video and PDFs.
 *
 * Choose the provider with LAMBDA_PROVIDER (openai, claude, gemini, openrouter, xai, mistral,
 * perplexity, experiential, ollama) and optionally LAMBDA_MODEL; set that provider's API key.
 *
 * Attach files by writing @path in your message:
 *   "What's in this picture? @photo.jpg"
 *   "Summarize @report.pdf in three bullet points"
 *
 * With GEMINI_API_KEY set the agent can also create images and speech; with OPENAI_API_KEY set
 * it can transcribe audio files in this folder. Generated files go to ./out.
 */
public final class MultimodalAgentExample {

    public static void main(String[] args) {
        String provider = System.getenv().getOrDefault("LAMBDA_PROVIDER", "gemini");
        ModelClient model = Providers.create(provider, System.getenv("LAMBDA_MODEL"), System::getenv);

        Path workspace = Path.of(".").toAbsolutePath().normalize();
        Path out = workspace.resolve("out");
        List<AgentTool> tools = new ArrayList<>();
        String gemini = System.getenv("GEMINI_API_KEY");
        if (gemini != null && !gemini.isBlank()) {
            tools.add(MediaTools.generateImage(GeminiMedia.images(gemini, "gemini-3.1-flash-image"), out));
            tools.add(MediaTools.generateSpeech(GeminiMedia.speech(gemini, "gemini-3.8-flash-tts"), out));
        }
        String openai = System.getenv("OPENAI_API_KEY");
        if (openai != null && !openai.isBlank()) {
            tools.add(MediaTools.transcribeAudio(new OpenAITranscriber(OpenAICompatibleProvider.OPENAI, openai, "gpt-transcribe"), workspace));
        }

        var agent = new Agent(new AgentConfig("You are a helpful assistant that can look at, listen to and create media.",
                model, tools, 8), new InMemorySessionStore());
        agent.addListener(new AgentEventListener() {
            @Override
            public void onToolStart(ai.lambda.ai.core.ToolCall call, ToolInvocationContext ctx) {
                System.out.println("  [" + call.getName() + "]");
            }
        });

        System.out.println("Provider: " + provider + " — accepts " + model.capabilities().input());
        System.out.println("Tools: " + tools.stream().map(AgentTool::getName).toList());
        System.out.println("Attach files with @path. Type 'exit' to quit.");
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nYou: ");
                String line = scanner.nextLine();
                if ("exit".equalsIgnoreCase(line)) break;
                try {
                    System.out.println("Agent: " + agent.run("media", parse(line, workspace)).getFinalText());
                } catch (UnsupportedMediaException e) {
                    System.out.println("Cannot send that: " + e.getMessage());
                }
            }
        }
    }

    /** Turns "Describe @photo.jpg" into a user message with the text and the attached file. */
    static Message parse(String line, Path workspace) {
        List<Media> media = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (String word : line.trim().split("\\s+")) {
            if (word.startsWith("@") && word.length() > 1) {
                Path file = workspace.resolve(word.substring(1)).normalize();
                if (!Files.isRegularFile(file)) throw new UnsupportedMediaException("No such file: " + word.substring(1));
                media.add(Media.fromFile(file));
            } else {
                if (!text.isEmpty()) text.append(' ');
                text.append(word);
            }
        }
        return Message.user(text.toString(), media.toArray(Media[]::new));
    }
}
