package pl.mindrush.backend.quiz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.config.AppOpenAiProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
public class OpenAiQuizQuestionGenerationService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiQuizQuestionGenerationService.class);

    private static final int QUESTION_PROMPT_MAX = 500;
    private static final int ANSWER_TEXT_MAX = 200;
    private static final int IMAGE_URL_MAX = 500;
    private static final int EXISTING_PROMPTS_LIMIT = 12;
    private static final int DEFAULT_MAX_QUESTIONS_PER_REQUEST = 500;
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final int SOURCE_MATERIAL_MAX = 40_000;
    private static final int TRANSLATION_BATCH_SIZE = 20;

    private final AppOpenAiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiQuizQuestionGenerationService(
            AppOpenAiProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(normalizedTimeout(properties.getTimeout()))
                .build();
    }

    public int maxQuestionCount() {
        int configured = properties.getMaxQuestionsPerRequest();
        if (configured <= 0) return DEFAULT_MAX_QUESTIONS_PER_REQUEST;
        return Math.min(configured, DEFAULT_MAX_QUESTIONS_PER_REQUEST);
    }

    public List<GeneratedQuestion> generate(QuestionGenerationPrompt prompt) {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(
                    SERVICE_UNAVAILABLE,
                    "AI generation is currently disabled on the server"
            );
        }

        String apiKey = trimToNull(properties.getApiKey());
        if (apiKey == null) {
            throw new ResponseStatusException(
                    SERVICE_UNAVAILABLE,
                    "OpenAI API key is not configured"
            );
        }

        int targetCount = normalizeQuestionCount(prompt.questionCount());
        String model = trimToNull(properties.getModel());
        if (model == null) model = DEFAULT_MODEL;

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", normalizedTemperature(properties.getTemperature()));
        body.put("max_completion_tokens", recommendedGenerationCompletionTokens(targetCount));
        ArrayNode messages = body.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", systemPrompt());
        messages.addObject()
                .put("role", "user")
                .put("content", userPrompt(prompt, targetCount));
        body.putObject("response_format").put("type", "json_object");

        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (IOException ex) {
            throw new ResponseStatusException(BAD_GATEWAY, "Failed to prepare AI request");
        }

        String baseUrl = normalizedBaseUrl(properties.getBaseUrl());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(normalizedTimeout(properties.getTimeout()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException ex) {
            throw new ResponseStatusException(GATEWAY_TIMEOUT, "AI generation timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(BAD_GATEWAY, "AI generation interrupted");
        } catch (IOException ex) {
            throw new ResponseStatusException(BAD_GATEWAY, "AI provider is currently unavailable");
        }

        if (response.statusCode() >= 400) {
            String message = extractProviderMessage(response.body());
            String providerRequestId = response.headers().firstValue("x-request-id").orElse("-");
            log.warn(
                    "OpenAI chat completion failed (requestId={}) with status {}: {}",
                    providerRequestId,
                    response.statusCode(),
                    message
            );
            throw new ResponseStatusException(BAD_GATEWAY, "AI provider request failed: " + message);
        }

        JsonNode root = parseJson(response.body(), "Failed to parse AI provider response");
        String providerResponseId = trimToNull(root.path("id").asText(null));
        String content = root.path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asText("");
        if (content.isBlank()) {
            throw new ResponseStatusException(BAD_GATEWAY, "AI provider returned empty content");
        }

        JsonNode payloadJson = parseJson(extractJsonPayload(content), "AI provider returned invalid JSON payload");
        ParseDiagnostics diagnostics = new ParseDiagnostics();
        List<GeneratedQuestion> questions = parseQuestions(payloadJson, targetCount, prompt.includeImages(), diagnostics);
        logGenerationDiagnostics(model, targetCount, prompt.includeImages(), providerResponseId, diagnostics);
        if (questions.isEmpty()) {
            throw new ResponseStatusException(BAD_GATEWAY, "AI provider returned no valid questions");
        }
        return questions;
    }

    public List<TranslationQuestion> translateQuestionsToPolish(List<TranslationQuestion> rawQuestions) {
        if (rawQuestions == null || rawQuestions.isEmpty()) {
            return List.of();
        }

        if (!properties.isEnabled()) {
            throw new ResponseStatusException(
                    SERVICE_UNAVAILABLE,
                    "AI translation for OpenTDB is currently disabled on the server"
            );
        }
        String apiKey = trimToNull(properties.getApiKey());
        if (apiKey == null) {
            throw new ResponseStatusException(
                    SERVICE_UNAVAILABLE,
                    "OpenAI API key is not configured"
            );
        }

        List<TranslationQuestion> source = normalizeTranslationInput(rawQuestions);
        List<TranslationQuestion> translated = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i += TRANSLATION_BATCH_SIZE) {
            int toIndex = Math.min(source.size(), i + TRANSLATION_BATCH_SIZE);
            List<TranslationQuestion> batch = source.subList(i, toIndex);
            translated.addAll(translateBatchToPolish(batch, apiKey));
        }
        return translated;
    }

    private List<TranslationQuestion> normalizeTranslationInput(List<TranslationQuestion> rawQuestions) {
        List<TranslationQuestion> normalized = new ArrayList<>(rawQuestions.size());
        for (TranslationQuestion question : rawQuestions) {
            if (question == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Translation input contains null question");
            }
            String prompt = boundedText(question.prompt(), QUESTION_PROMPT_MAX);
            if (prompt == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Translation input contains empty question prompt");
            }
            List<String> options = question.options() == null ? List.of() : question.options();
            if (options.size() != 4) {
                throw new ResponseStatusException(BAD_REQUEST, "Translation input requires exactly 4 options per question");
            }
            List<String> normalizedOptions = new ArrayList<>(4);
            for (String optionText : options) {
                String text = boundedText(optionText, ANSWER_TEXT_MAX);
                if (text == null) {
                    throw new ResponseStatusException(BAD_REQUEST, "Translation input contains empty option text");
                }
                normalizedOptions.add(text);
            }
            int correctOptionIndex = question.correctOptionIndex();
            if (correctOptionIndex < 0 || correctOptionIndex > 3) {
                throw new ResponseStatusException(BAD_REQUEST, "Translation input contains invalid correct option index");
            }
            normalized.add(new TranslationQuestion(prompt, normalizedOptions, correctOptionIndex));
        }
        return normalized;
    }

    private List<TranslationQuestion> translateBatchToPolish(List<TranslationQuestion> sourceBatch, String apiKey) {
        if (sourceBatch == null || sourceBatch.isEmpty()) {
            return List.of();
        }

        String model = trimToNull(properties.getModel());
        if (model == null) model = DEFAULT_MODEL;

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.2d);
        body.put("max_completion_tokens", recommendedTranslationCompletionTokens(sourceBatch.size()));
        ArrayNode messages = body.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", translationSystemPrompt());
        messages.addObject()
                .put("role", "user")
                .put("content", translationUserPrompt(sourceBatch));
        body.putObject("response_format").put("type", "json_object");

        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (IOException ex) {
            throw new ResponseStatusException(BAD_GATEWAY, "Failed to prepare AI translation request");
        }

        String baseUrl = normalizedBaseUrl(properties.getBaseUrl());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(normalizedTimeout(properties.getTimeout()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException ex) {
            throw new ResponseStatusException(GATEWAY_TIMEOUT, "AI translation timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(BAD_GATEWAY, "AI translation interrupted");
        } catch (IOException ex) {
            throw new ResponseStatusException(BAD_GATEWAY, "AI translation provider is currently unavailable");
        }

        if (response.statusCode() >= 400) {
            String message = extractProviderMessage(response.body());
            throw new ResponseStatusException(BAD_GATEWAY, "AI translation request failed: " + message);
        }

        JsonNode root = parseJson(response.body(), "Failed to parse AI translation response");
        String content = root.path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asText("");
        if (content.isBlank()) {
            throw new ResponseStatusException(BAD_GATEWAY, "AI translation returned empty content");
        }

        JsonNode payloadJson = parseJson(extractJsonPayload(content), "AI translation returned invalid JSON payload");
        return parseTranslationQuestions(payloadJson, sourceBatch);
    }

    private String translationSystemPrompt() {
        return """
                You are a professional Polish localization editor for multiple-choice quizzes.
                Return exactly one JSON object, no markdown.
                Required schema:
                {
                  "questions": [
                    {
                      "prompt": "string <= 500 chars",
                      "options": [
                        { "index": 0, "text": "string <= 200 chars" },
                        { "index": 1, "text": "string <= 200 chars" },
                        { "index": 2, "text": "string <= 200 chars" },
                        { "index": 3, "text": "string <= 200 chars" }
                      ]
                    }
                  ]
                }
                Rules:
                - Preserve question order and option indexes exactly.
                - Keep meaning and difficulty level.
                - Do not change which option is correct.
                - Use natural Polish.
                - If an English term is clearer untranslated, keep it in double quotes.
                - No explanations, no extra keys.
                """;
    }

    private String translationUserPrompt(List<TranslationQuestion> sourceBatch) {
        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode questions = payload.putArray("questions");
        for (TranslationQuestion question : sourceBatch) {
            ObjectNode q = questions.addObject();
            q.put("prompt", question.prompt());
            ArrayNode options = q.putArray("options");
            for (int i = 0; i < 4; i++) {
                ObjectNode option = options.addObject();
                option.put("index", i);
                option.put("text", question.options().get(i));
            }
            q.put("correctOptionIndex", question.correctOptionIndex());
        }

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (IOException ex) {
            throw new ResponseStatusException(BAD_GATEWAY, "Failed to serialize translation payload");
        }

        return """
                Translate the following quiz questions and answer options from English to Polish.
                Keep option index mapping unchanged.
                If a term should remain in English for clarity, keep it in double quotes.
                Input JSON:
                """
                + jsonPayload;
    }

    private List<TranslationQuestion> parseTranslationQuestions(JsonNode payload, List<TranslationQuestion> sourceBatch) {
        JsonNode questionsNode = payload.path("questions");
        if (!questionsNode.isArray()) {
            throw new ResponseStatusException(BAD_GATEWAY, "AI translation payload is missing questions array");
        }
        if (questionsNode.size() != sourceBatch.size()) {
            throw new ResponseStatusException(BAD_GATEWAY, "AI translation returned unexpected number of questions");
        }

        List<TranslationQuestion> translated = new ArrayList<>(sourceBatch.size());
        for (int i = 0; i < sourceBatch.size(); i++) {
            JsonNode node = questionsNode.get(i);
            TranslationQuestion source = sourceBatch.get(i);

            String prompt = boundedText(node.path("prompt").asText(null), QUESTION_PROMPT_MAX);
            if (prompt == null) {
                throw new ResponseStatusException(BAD_GATEWAY, "AI translation returned invalid question prompt");
            }

            JsonNode optionsNode = node.path("options");
            if (!optionsNode.isArray()) {
                throw new ResponseStatusException(BAD_GATEWAY, "AI translation returned invalid options payload");
            }
            String[] translatedOptions = new String[4];
            for (JsonNode optionNode : optionsNode) {
                int idx = optionNode.path("index").asInt(-1);
                String text = boundedText(optionNode.path("text").asText(null), ANSWER_TEXT_MAX);
                if (idx < 0 || idx > 3 || text == null || translatedOptions[idx] != null) {
                    throw new ResponseStatusException(BAD_GATEWAY, "AI translation returned invalid option mapping");
                }
                translatedOptions[idx] = text;
            }
            for (String text : translatedOptions) {
                if (text == null) {
                    throw new ResponseStatusException(BAD_GATEWAY, "AI translation returned incomplete options");
                }
            }

            translated.add(new TranslationQuestion(
                    prompt,
                    List.of(translatedOptions[0], translatedOptions[1], translatedOptions[2], translatedOptions[3]),
                    source.correctOptionIndex()
            ));
        }
        return translated;
    }

    private List<GeneratedQuestion> parseQuestions(
            JsonNode payload,
            int targetCount,
            boolean includeImages,
            ParseDiagnostics diagnostics
    ) {
        JsonNode questionsNode = payload.path("questions");
        if (!questionsNode.isArray()) {
            throw new ResponseStatusException(BAD_GATEWAY, "AI provider payload is missing questions array");
        }

        List<GeneratedQuestion> result = new ArrayList<>();
        for (JsonNode node : questionsNode) {
            if (result.size() >= targetCount) break;
            GeneratedQuestion parsed = parseSingleQuestion(node, includeImages, diagnostics);
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
    }

    private GeneratedQuestion parseSingleQuestion(
            JsonNode node,
            boolean includeImages,
            ParseDiagnostics diagnostics
    ) {
        String prompt = boundedText(node.path("prompt").asText(null), QUESTION_PROMPT_MAX);
        if (prompt == null) {
            diagnostics.questionRejected();
            return null;
        }

        JsonNode optionsNode = node.path("options");
        if (!optionsNode.isArray() || optionsNode.size() < 4) {
            diagnostics.questionRejected();
            return null;
        }

        List<GeneratedOption> options = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            JsonNode optionNode = optionsNode.get(i);
            String text = boundedText(optionNode.path("text").asText(null), ANSWER_TEXT_MAX);
            ImageUrlParseResult imageParse = includeImages
                    ? boundedImageUrl(optionNode.path("imageUrl").asText(null))
                    : ImageUrlParseResult.notRequested();
            if (includeImages) {
                diagnostics.recordImageStatus(imageParse.status());
            }
            String imageUrl = imageParse.url();
            if (text == null && imageUrl == null) {
                diagnostics.questionRejected();
                return null;
            }
            options.add(new GeneratedOption(text, imageUrl));
        }

        int correctOptionIndex = node.path("correctOptionIndex").asInt(-1);
        if (correctOptionIndex < 0 || correctOptionIndex > 3) {
            correctOptionIndex = extractCorrectOptionIndex(optionsNode);
        }
        if (correctOptionIndex < 0 || correctOptionIndex > 3) {
            diagnostics.questionRejected();
            return null;
        }

        ImageUrlParseResult questionImageParse = includeImages
                ? boundedImageUrl(node.path("imageUrl").asText(null))
                : ImageUrlParseResult.notRequested();
        if (includeImages) {
            diagnostics.recordImageStatus(questionImageParse.status());
        }
        String imageUrl = questionImageParse.url();
        diagnostics.questionAccepted();
        return new GeneratedQuestion(prompt, imageUrl, options, correctOptionIndex);
    }

    private void logGenerationDiagnostics(
            String model,
            int targetCount,
            boolean includeImages,
            String providerResponseId,
            ParseDiagnostics diagnostics
    ) {
        String providerId = providerResponseId == null ? "-" : providerResponseId;
        if (diagnostics.rejectedQuestionCount() > 0) {
            log.info(
                    "OpenAI generation sanitized invalid questions (providerId={}, model={}, requested={}, accepted={}, rejected={})",
                    providerId,
                    model,
                    targetCount,
                    diagnostics.acceptedQuestionCount(),
                    diagnostics.rejectedQuestionCount()
            );
        }

        if (!includeImages) {
            return;
        }

        int rejectedImageUrls = diagnostics.rejectedImageUrlCount();
        if (rejectedImageUrls > 0) {
            log.warn(
                    "OpenAI image URLs rejected during validation (providerId={}, model={}, requested={}, acceptedImageUrls={}, rejectedImageUrls={}, invalidScheme={}, tooLong={}, emptyImageFields={})",
                    providerId,
                    model,
                    targetCount,
                    diagnostics.acceptedImageUrlCount(),
                    rejectedImageUrls,
                    diagnostics.invalidSchemeImageUrlCount(),
                    diagnostics.tooLongImageUrlCount(),
                    diagnostics.emptyImageFieldCount()
            );
            return;
        }

        if (diagnostics.acceptedImageUrlCount() == 0) {
            log.info(
                    "OpenAI returned no usable image URLs (providerId={}, model={}, requested={}, emptyImageFields={})",
                    providerId,
                    model,
                    targetCount,
                    diagnostics.emptyImageFieldCount()
            );
        }
    }

    private int extractCorrectOptionIndex(JsonNode optionsNode) {
        int idx = -1;
        int count = 0;
        for (int i = 0; i < Math.min(4, optionsNode.size()); i++) {
            if (optionsNode.get(i).path("correct").asBoolean(false)) {
                count++;
                idx = i;
            }
        }
        return count == 1 ? idx : -1;
    }

    private JsonNode parseJson(String raw, String fallbackMessage) {
        try {
            return objectMapper.readTree(raw);
        } catch (IOException ex) {
            throw new ResponseStatusException(BAD_GATEWAY, fallbackMessage);
        }
    }

    private String extractProviderMessage(String body) {
        String fallback = "Request rejected";
        if (body == null || body.isBlank()) return fallback;
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = trimToNull(root.path("error").path("message").asText(null));
            return message == null ? fallback : message;
        } catch (IOException ignored) {
            return fallback;
        }
    }

    private static Duration normalizedTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            return Duration.ofSeconds(45);
        }
        return timeout;
    }

    private String extractJsonPayload(String rawContent) {
        String content = rawContent == null ? "" : rawContent.trim();
        if (!content.startsWith("```")) return content;

        int firstBreak = content.indexOf('\n');
        int lastFence = content.lastIndexOf("```");
        if (firstBreak < 0 || lastFence <= firstBreak) return content;

        return content.substring(firstBreak + 1, lastFence).trim();
    }

    private int normalizeQuestionCount(int requested) {
        if (requested <= 0) return 1;
        return Math.min(requested, maxQuestionCount());
    }

    private double normalizedTemperature(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.4d;
        return Math.max(0d, Math.min(2d, value));
    }

    private String normalizedBaseUrl(String baseUrl) {
        String normalized = trimToNull(baseUrl);
        if (normalized == null) {
            return "https://api.openai.com/v1";
        }
        if (normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String systemPrompt() {
        return """
                You generate production-ready multiple-choice quiz questions for an online quiz platform.
                Always return a single valid JSON object and never return markdown.
                Required schema:
                {
                  "questions": [
                    {
                      "prompt": "string <= 500 chars",
                      "imageUrl": "https url or null",
                      "options": [
                        { "text": "string <= 200 chars or null", "imageUrl": "https url or null" },
                        { "text": "string <= 200 chars or null", "imageUrl": "https url or null" },
                        { "text": "string <= 200 chars or null", "imageUrl": "https url or null" },
                        { "text": "string <= 200 chars or null", "imageUrl": "https url or null" }
                      ],
                      "correctOptionIndex": 0
                    }
                  ]
                }
                Rules:
                - Generate exactly the requested number of questions.
                - Exactly 4 options per question.
                - Exactly one correct answer.
                - No explanations, no extra keys, no comments.
                """;
    }

    private String userPrompt(QuestionGenerationPrompt prompt, int targetCount) {
        String topic = trimToNull(prompt.topic());
        String categoryHint = trimToNull(prompt.categoryHint());
        String instructions = trimToNull(prompt.instructions());
        String sourceMaterial = boundedText(prompt.sourceMaterial(), SOURCE_MATERIAL_MAX);
        String quizTitle = trimToNull(prompt.quizTitle());
        String quizCategory = trimToNull(prompt.quizCategory());
        String quizDescription = trimToNull(prompt.quizDescription());

        StringBuilder sb = new StringBuilder(1400);
        sb.append("Generate ").append(targetCount).append(" quiz questions.\n");
        sb.append("Language: ").append(languageLabel(prompt.language())).append(".\n");
        sb.append("Difficulty: ").append(difficultyLabel(prompt.difficulty())).append(".\n");
        sb.append("Primary topic: ").append(topic == null ? "General knowledge" : topic).append(".\n");
        if (categoryHint != null) {
            sb.append("Category hint: ").append(categoryHint).append(".\n");
        }
        if (quizTitle != null) {
            sb.append("Target quiz title: ").append(quizTitle).append(".\n");
        }
        if (quizCategory != null) {
            sb.append("Target quiz category: ").append(quizCategory).append(".\n");
        }
        if (quizDescription != null) {
            sb.append("Target quiz description: ").append(quizDescription).append(".\n");
        }
        sb.append("Include images: ").append(prompt.includeImages() ? "yes" : "no").append(".\n");
        if (!prompt.includeImages()) {
            sb.append("Set all question and option imageUrl fields to null.\n");
        }
        if (instructions != null) {
            sb.append("Additional instructions: ").append(instructions).append(".\n");
        }
        if (sourceMaterial != null) {
            sb.append("Use the following source material as primary ground truth:\n");
            sb.append(sourceMaterial).append('\n');
            sb.append("Only ask questions answerable from this material when relevant.\n");
        }

        List<String> existingPrompts = prompt.existingPrompts() == null ? List.of() : prompt.existingPrompts();
        if (!existingPrompts.isEmpty()) {
            sb.append("Avoid near-duplicates of existing prompts:\n");
            int fromIndex = Math.max(0, existingPrompts.size() - EXISTING_PROMPTS_LIMIT);
            for (int i = fromIndex; i < existingPrompts.size(); i++) {
                String item = boundedText(existingPrompts.get(i), 120);
                if (item == null) continue;
                sb.append("- ").append(item).append('\n');
            }
        }

        return sb.toString();
    }

    private String difficultyLabel(QuestionGenerationDifficulty difficulty) {
        if (difficulty == null) return "Mixed";
        return switch (difficulty) {
            case EASY -> "Easy";
            case MEDIUM -> "Medium";
            case HARD -> "Hard";
            case MIXED -> "Mixed";
        };
    }

    private String languageLabel(QuestionGenerationLanguage language) {
        if (language == null || language == QuestionGenerationLanguage.PL) return "Polish";
        return "English";
    }

    private static String boundedText(String value, int maxLen) {
        String normalized = trimToNull(value);
        if (normalized == null) return null;
        if (normalized.length() <= maxLen) return normalized;
        return normalized.substring(0, maxLen).trim();
    }

    private static ImageUrlParseResult boundedImageUrl(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) return new ImageUrlParseResult(null, ImageUrlStatus.EMPTY);
        if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
            return new ImageUrlParseResult(null, ImageUrlStatus.INVALID_SCHEME);
        }
        if (normalized.length() > IMAGE_URL_MAX) {
            return new ImageUrlParseResult(null, ImageUrlStatus.TOO_LONG);
        }
        return new ImageUrlParseResult(normalized, ImageUrlStatus.ACCEPTED);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int recommendedGenerationCompletionTokens(int questionCount) {
        int count = Math.max(1, questionCount);
        int estimated = (count * 240) + 350;
        return Math.min(5_000, Math.max(900, estimated));
    }

    private static int recommendedTranslationCompletionTokens(int questionCount) {
        int count = Math.max(1, questionCount);
        int estimated = (count * 220) + 500;
        return Math.min(6_000, Math.max(1_000, estimated));
    }

    public record QuestionGenerationPrompt(
            String topic,
            String categoryHint,
            String instructions,
            String sourceMaterial,
            int questionCount,
            QuestionGenerationDifficulty difficulty,
            QuestionGenerationLanguage language,
            boolean includeImages,
            String quizTitle,
            String quizCategory,
            String quizDescription,
            List<String> existingPrompts
    ) {}

    public record GeneratedQuestion(
            String prompt,
            String imageUrl,
            List<GeneratedOption> options,
            int correctOptionIndex
    ) {}

    public record GeneratedOption(
            String text,
            String imageUrl
    ) {}

    public record TranslationQuestion(
            String prompt,
            List<String> options,
            int correctOptionIndex
    ) {}

    private enum ImageUrlStatus {
        NOT_REQUESTED,
        EMPTY,
        ACCEPTED,
        INVALID_SCHEME,
        TOO_LONG
    }

    private record ImageUrlParseResult(
            String url,
            ImageUrlStatus status
    ) {
        private static ImageUrlParseResult notRequested() {
            return new ImageUrlParseResult(null, ImageUrlStatus.NOT_REQUESTED);
        }
    }

    private static final class ParseDiagnostics {
        private int acceptedQuestionCount;
        private int rejectedQuestionCount;
        private int acceptedImageUrlCount;
        private int emptyImageFieldCount;
        private int invalidSchemeImageUrlCount;
        private int tooLongImageUrlCount;

        private void questionAccepted() {
            acceptedQuestionCount++;
        }

        private void questionRejected() {
            rejectedQuestionCount++;
        }

        private void recordImageStatus(ImageUrlStatus status) {
            if (status == null) {
                return;
            }
            switch (status) {
                case ACCEPTED -> acceptedImageUrlCount++;
                case EMPTY -> emptyImageFieldCount++;
                case INVALID_SCHEME -> invalidSchemeImageUrlCount++;
                case TOO_LONG -> tooLongImageUrlCount++;
                case NOT_REQUESTED -> {
                    // no-op
                }
            }
        }

        private int acceptedQuestionCount() {
            return acceptedQuestionCount;
        }

        private int rejectedQuestionCount() {
            return rejectedQuestionCount;
        }

        private int acceptedImageUrlCount() {
            return acceptedImageUrlCount;
        }

        private int emptyImageFieldCount() {
            return emptyImageFieldCount;
        }

        private int invalidSchemeImageUrlCount() {
            return invalidSchemeImageUrlCount;
        }

        private int tooLongImageUrlCount() {
            return tooLongImageUrlCount;
        }

        private int rejectedImageUrlCount() {
            return invalidSchemeImageUrlCount + tooLongImageUrlCount;
        }
    }
}
