package ai.lambda.ai.openai;

import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Modality;
import ai.lambda.ai.generation.Transcriber;
import com.openai.client.OpenAIClient;
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.Transcription;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.audio.transcriptions.TranscriptionCreateResponse;

import java.util.Objects;

/**
 * Speech-to-text with OpenAI's transcription models (for example {@code gpt-transcribe} or
 * {@code whisper-1}), through OpenAI's official Java SDK.
 *
 * <pre>
 * var stt = new OpenAITranscriber(key, "gpt-transcribe");
 * String text = stt.transcribe(Media.fromFile(Path.of("meeting.m4a")), "en");
 * </pre>
 */
public final class OpenAITranscriber implements Transcriber {

    private final OpenAIClient client;
    private final String model;

    public OpenAITranscriber(String apiKey, String model) {
        this(apiKey, model, HttpOptions.defaults(), null);
    }

    /** @param baseUrl another API root, or null for OpenAI's own */
    public OpenAITranscriber(String apiKey, String model, HttpOptions options, String baseUrl) {
        this(OpenAIClients.create(apiKey, options, baseUrl), model);
    }

    public OpenAITranscriber(OpenAIClient client, String model) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
    }

    @Override
    public String transcribe(Media audio, String language) {
        Objects.requireNonNull(audio, "audio must not be null");
        if (audio.modality() != Modality.AUDIO && audio.modality() != Modality.VIDEO) {
            throw new IllegalArgumentException("Expected an audio file, got " + audio.mimeType());
        }
        if (!audio.hasData()) throw new IllegalArgumentException("Load the audio with Media.fromFile; URLs are not supported");

        String fileName = audio.name() != null ? audio.name() : "audio." + audio.fileExtension();
        TranscriptionCreateParams.Builder params = TranscriptionCreateParams.builder()
                .model(model)
                .file(OpenAIClients.file(audio.readBytes(OpenAIClients::stream), fileName, audio.mimeType()))
                .responseFormat(AudioResponseFormat.JSON);
        if (language != null && !language.isBlank()) params.language(language);
        TranscriptionCreateResponse response = client.audio().transcriptions().create(params.build());
        return response.transcription().map(Transcription::text)
                .orElseThrow(() -> new RuntimeException("OpenAI returned no transcription text"));
    }
}
