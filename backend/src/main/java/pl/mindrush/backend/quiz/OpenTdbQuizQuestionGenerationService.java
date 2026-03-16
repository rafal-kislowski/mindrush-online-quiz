package pl.mindrush.backend.quiz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;

@Service
public class OpenTdbQuizQuestionGenerationService {

    private static final Logger log = LoggerFactory.getLogger(OpenTdbQuizQuestionGenerationService.class);

    private static final String API_BASE_URL = "https://opentdb.com/api.php";
    private static final String API_CATEGORIES_URL = "https://opentdb.com/api_category.php";
    private static final String API_COUNT_URL = "https://opentdb.com/api_count.php";
    private static final String API_TOKEN_URL = "https://opentdb.com/api_token.php";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);
    private static final int PROVIDER_MAX_BATCH_SIZE = 50;
    private static final int MAX_QUESTION_COUNT = 500;
    private static final int QUESTION_PROMPT_MAX = 500;
    private static final int ANSWER_TEXT_MAX = 200;
    private static final int MAX_HTTP_RETRIES = 6;
    private static final long DEFAULT_RETRY_AFTER_MS = 2_000L;
    private static final long MAX_RETRY_AFTER_MS = 20_000L;
    private static final long INTER_BATCH_DELAY_MS = 2_500L;
    private static final long MIN_REQUEST_SPACING_MS = 1_500L;
    private static final long MAX_REQUEST_SPACING_MS = 12_000L;
    private static final long REQUEST_SPACING_RECOVERY_STEP_MS = 250L;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Object rateLimitLock = new Object();
    private long nextAllowedRequestAtMs = 0L;
    private long adaptiveRequestSpacingMs = MIN_REQUEST_SPACING_MS;

    public OpenTdbQuizQuestionGenerationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    public int maxQuestionCount() {
        return MAX_QUESTION_COUNT;
    }

    public List<OpenTdbCategory> listCategories() {
        JsonNode root = requestJson(URI.create(API_CATEGORIES_URL));
        JsonNode categoriesNode = root.path("trivia_categories");
        if (!categoriesNode.isArray()) {
            return List.of();
        }

        List<OpenTdbCategory> categories = new ArrayList<>();
        for (JsonNode node : categoriesNode) {
            int id = node.path("id").asInt(-1);
            String name = trimToNull(node.path("name").asText(null));
            if (id <= 0 || name == null) {
                continue;
            }
            categories.add(new OpenTdbCategory(id, name));
        }
        categories.sort(Comparator.comparing(OpenTdbCategory::name, String.CASE_INSENSITIVE_ORDER));
        return categories;
    }

    public List<GeneratedQuestion> generate(QuestionGenerationPrompt prompt) {
        if (prompt == null) {
            throw new ResponseStatusException(BAD_REQUEST, "OpenTDB generation settings are missing");
        }

        int targetCount = normalizeQuestionCount(prompt.questionCount());
        Integer categoryId = prompt.categoryId() != null && prompt.categoryId() > 0 ? prompt.categoryId() : null;
        QuestionGenerationDifficulty difficulty = prompt.difficulty() == null
                ? QuestionGenerationDifficulty.MIXED
                : prompt.difficulty();
        Integer providerCountUpperBound = estimateQuestionUpperBound(categoryId, difficulty);
        if (providerCountUpperBound != null) {
            if (providerCountUpperBound <= 0) {
                throw new ResponseStatusException(BAD_REQUEST, "OpenTDB has no questions for the selected filters");
            }
            if (targetCount > providerCountUpperBound) {
                throw new ResponseStatusException(
                        BAD_REQUEST,
                        "OpenTDB cannot generate " + targetCount
                                + " questions for selected filters. Estimated maximum is "
                                + providerCountUpperBound
                                + "."
                );
            }
        }

        Set<String> seenPromptKeys = new HashSet<>();
        List<String> disallowedPrompts = prompt.disallowedPrompts() == null ? List.of() : prompt.disallowedPrompts();
        for (String item : disallowedPrompts) {
            String key = promptFingerprint(item);
            if (key != null) {
                seenPromptKeys.add(key);
            }
        }
        String[] sessionTokenRef = new String[]{
                shouldUseSessionToken(targetCount, disallowedPrompts)
                        ? requestSessionToken()
                        : null
        };

        List<GeneratedQuestion> result = new ArrayList<>();
        int attempts = 0;
        int maxAttempts = Math.max(10, targetCount * 4);
        int noProgressRounds = 0;
        int maxNoProgressRounds = Math.max(4, Math.min(12, targetCount / 10));

        while (result.size() < targetCount && attempts < maxAttempts) {
            attempts++;
            int remaining = targetCount - result.size();
            int amount = Math.max(1, Math.min(PROVIDER_MAX_BATCH_SIZE, remaining));

            BatchFetchResult batchResult = fetchBatch(amount, categoryId, difficulty, sessionTokenRef);
            if (batchResult.sourceExhausted()) {
                log.info(
                        "OpenTDB source exhausted for current filters (targetCount={}, generated={}, categoryId={}, difficulty={})",
                        targetCount,
                        result.size(),
                        categoryId,
                        difficulty
                );
                break;
            }

            List<GeneratedQuestion> batch = batchResult.questions();
            if (batch.isEmpty()) {
                noProgressRounds++;
                if (noProgressRounds >= maxNoProgressRounds) {
                    log.warn(
                            "OpenTDB generation stopped after {} empty/no-progress rounds (targetCount={}, generated={})",
                            noProgressRounds,
                            targetCount,
                            result.size()
                    );
                    break;
                }
                if (result.size() < targetCount) {
                    sleepWithInterruptHandling(INTER_BATCH_DELAY_MS);
                }
                continue;
            }

            int before = result.size();
            for (GeneratedQuestion question : batch) {
                String key = promptFingerprint(question.prompt());
                if (key == null || !seenPromptKeys.add(key)) {
                    continue;
                }
                result.add(question);
                if (result.size() >= targetCount) {
                    break;
                }
            }
            if (result.size() == before) {
                noProgressRounds++;
            } else {
                noProgressRounds = 0;
            }

            if (result.size() == before && batch.size() < amount) {
                break;
            }
            if (noProgressRounds >= maxNoProgressRounds) {
                log.warn(
                        "OpenTDB generation stopped due repeated duplicate batches (targetCount={}, generated={})",
                        targetCount,
                        result.size()
                );
                break;
            }

            if (result.size() < targetCount) {
                sleepWithInterruptHandling(INTER_BATCH_DELAY_MS);
            }
        }

        if (result.isEmpty()) {
            throw new ResponseStatusException(BAD_GATEWAY, "OpenTDB returned no usable questions");
        }
        return result;
    }

    private BatchFetchResult fetchBatch(
            int amount,
            Integer categoryId,
            QuestionGenerationDifficulty difficulty,
            String[] sessionTokenRef
    ) {
        boolean tokenRecoveryAttempted = false;
        while (true) {
            String sessionToken = resolveCurrentToken(sessionTokenRef);
            URI uri = buildApiUri(amount, categoryId, difficulty, sessionToken);
            JsonNode root = requestJson(uri);
            int responseCode = root.path("response_code").asInt(-1);

            if (responseCode == 0) {
                JsonNode resultsNode = root.path("results");
                if (!resultsNode.isArray()) {
                    return new BatchFetchResult(List.of(), false);
                }

                List<GeneratedQuestion> questions = new ArrayList<>();
                for (JsonNode node : resultsNode) {
                    GeneratedQuestion parsed = parseSingleQuestion(node);
                    if (parsed != null) {
                        questions.add(parsed);
                    }
                }
                return new BatchFetchResult(questions, false);
            }
            if (responseCode == 1) {
                return new BatchFetchResult(List.of(), true);
            }
            if (responseCode == 2) {
                throw new ResponseStatusException(BAD_REQUEST, "OpenTDB rejected generation parameters");
            }

            if (responseCode == 3) {
                if (sessionToken == null || tokenRecoveryAttempted) {
                    return new BatchFetchResult(List.of(), true);
                }
                tokenRecoveryAttempted = true;
                String refreshedToken = requestSessionToken();
                if (refreshedToken == null) {
                    sessionTokenRef[0] = null;
                    return new BatchFetchResult(List.of(), true);
                }
                log.info("OpenTDB session token refreshed after response_code=3");
                sessionTokenRef[0] = refreshedToken;
                continue;
            }
            if (responseCode == 4) {
                return new BatchFetchResult(List.of(), true);
            }

            throw new ResponseStatusException(BAD_GATEWAY, "OpenTDB returned response_code=" + responseCode);
        }
    }

    private GeneratedQuestion parseSingleQuestion(JsonNode node) {
        String prompt = boundedText(decodeBase64Text(node.path("question").asText(null)), QUESTION_PROMPT_MAX);
        if (prompt == null) {
            return null;
        }

        String correct = boundedText(decodeBase64Text(node.path("correct_answer").asText(null)), ANSWER_TEXT_MAX);
        if (correct == null) {
            return null;
        }

        JsonNode incorrectNode = node.path("incorrect_answers");
        if (!incorrectNode.isArray() || incorrectNode.size() < 3) {
            return null;
        }

        List<OptionCandidate> candidates = new ArrayList<>(4);
        candidates.add(new OptionCandidate(correct, true));
        for (int i = 0; i < incorrectNode.size() && candidates.size() < 4; i++) {
            String text = boundedText(decodeBase64Text(incorrectNode.get(i).asText(null)), ANSWER_TEXT_MAX);
            if (text == null) {
                continue;
            }
            candidates.add(new OptionCandidate(text, false));
        }
        if (candidates.size() != 4) {
            return null;
        }

        Set<String> optionKeys = new HashSet<>();
        for (OptionCandidate candidate : candidates) {
            String optionKey = optionFingerprint(candidate.text());
            if (optionKey == null || !optionKeys.add(optionKey)) {
                return null;
            }
        }

        Collections.shuffle(candidates);
        List<GeneratedOption> options = new ArrayList<>(4);
        int correctOptionIndex = -1;
        for (int i = 0; i < candidates.size(); i++) {
            OptionCandidate candidate = candidates.get(i);
            options.add(new GeneratedOption(candidate.text(), null));
            if (candidate.correct()) {
                correctOptionIndex = i;
            }
        }
        if (correctOptionIndex < 0 || correctOptionIndex > 3) {
            return null;
        }

        return new GeneratedQuestion(prompt, null, options, correctOptionIndex);
    }

    private JsonNode requestJson(URI uri) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        for (int attempt = 1; attempt <= MAX_HTTP_RETRIES; attempt++) {
            waitForRequestSlot();
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (HttpTimeoutException ex) {
                if (attempt >= MAX_HTTP_RETRIES) {
                    throw new ResponseStatusException(GATEWAY_TIMEOUT, "OpenTDB request timed out");
                }
                long delayMs = retryDelayMs(attempt, null);
                log.warn("OpenTDB request timed out (attempt {}), retrying after {}ms", attempt, delayMs);
                sleepWithInterruptHandling(delayMs);
                continue;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(BAD_GATEWAY, "OpenTDB request interrupted");
            } catch (IOException ex) {
                if (attempt >= MAX_HTTP_RETRIES) {
                    throw new ResponseStatusException(BAD_GATEWAY, "OpenTDB is currently unavailable");
                }
                long delayMs = retryDelayMs(attempt, null);
                log.warn("OpenTDB network error on attempt {}, retrying after {}ms", attempt, delayMs);
                sleepWithInterruptHandling(delayMs);
                continue;
            }

            int status = response.statusCode();
            if (status == 429) {
                if (attempt >= MAX_HTTP_RETRIES) {
                    throw new ResponseStatusException(BAD_GATEWAY, "OpenTDB rate limit reached. Please retry in a moment.");
                }
                Long retryAfterMs = parseRetryAfterMs(response);
                long delayMs = increaseRequestSpacing(attempt, retryAfterMs);
                log.warn("OpenTDB rate-limited request (attempt {}, status 429), retrying after {}ms", attempt, delayMs);
                sleepWithInterruptHandling(delayMs);
                continue;
            }

            if (status >= 500) {
                if (attempt >= MAX_HTTP_RETRIES) {
                    throw new ResponseStatusException(BAD_GATEWAY, "OpenTDB request failed with status " + status);
                }
                long delayMs = retryDelayMs(attempt, null);
                log.warn("OpenTDB server error (status {}, attempt {}), retrying after {}ms", status, attempt, delayMs);
                sleepWithInterruptHandling(delayMs);
                continue;
            }

            if (status >= 400) {
                throw new ResponseStatusException(BAD_GATEWAY, "OpenTDB request failed with status " + status);
            }

            registerSuccessfulRequest();
            try {
                return objectMapper.readTree(response.body());
            } catch (IOException ex) {
                throw new ResponseStatusException(BAD_GATEWAY, "OpenTDB returned invalid JSON");
            }
        }

        throw new ResponseStatusException(BAD_GATEWAY, "OpenTDB request failed after retries");
    }

    private URI buildApiUri(int amount, Integer categoryId, QuestionGenerationDifficulty difficulty, String sessionToken) {
        StringBuilder sb = new StringBuilder(220);
        sb.append(API_BASE_URL)
                .append("?amount=")
                .append(Math.max(1, Math.min(PROVIDER_MAX_BATCH_SIZE, amount)))
                .append("&type=multiple")
                .append("&encode=base64");
        if (categoryId != null && categoryId > 0) {
            sb.append("&category=").append(categoryId);
        }
        if (difficulty != null && difficulty != QuestionGenerationDifficulty.MIXED) {
            sb.append("&difficulty=").append(difficulty.name().toLowerCase(Locale.ROOT));
        }
        String token = trimToNull(sessionToken);
        if (token != null) {
            sb.append("&token=").append(token);
        }
        return URI.create(sb.toString());
    }

    private Integer estimateQuestionUpperBound(Integer categoryId, QuestionGenerationDifficulty difficulty) {
        if (categoryId == null || categoryId <= 0) {
            return null;
        }
        try {
            JsonNode root = requestJson(URI.create(API_COUNT_URL + "?category=" + categoryId));
            JsonNode countsNode = root.path("category_question_count");
            if (!countsNode.isObject()) {
                return null;
            }

            String countField;
            if (difficulty == QuestionGenerationDifficulty.EASY) {
                countField = "total_easy_question_count";
            } else if (difficulty == QuestionGenerationDifficulty.MEDIUM) {
                countField = "total_medium_question_count";
            } else if (difficulty == QuestionGenerationDifficulty.HARD) {
                countField = "total_hard_question_count";
            } else {
                countField = "total_question_count";
            }

            int availableCount = countsNode.path(countField).asInt(-1);
            return availableCount < 0 ? null : availableCount;
        } catch (ResponseStatusException ex) {
            log.warn(
                    "OpenTDB count precheck failed (categoryId={}, difficulty={}, status={}, reason={})",
                    categoryId,
                    difficulty,
                    ex.getStatusCode().value(),
                    ex.getReason()
            );
            return null;
        }
    }

    private int normalizeQuestionCount(int requested) {
        if (requested <= 0) return 1;
        return Math.min(requested, MAX_QUESTION_COUNT);
    }

    private static String decodeBase64Text(String raw) {
        String normalized = trimToNull(raw);
        if (normalized == null) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(normalized);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return normalized;
        }
    }

    private static String boundedText(String value, int maxLength) {
        String normalized = trimToNull(value);
        if (normalized == null) return null;
        if (normalized.length() <= maxLength) return normalized;
        return normalized.substring(0, maxLength).trim();
    }

    private static String promptFingerprint(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) return null;
        String reduced = normalized
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[^\\p{L}\\p{N} ]", "")
                .trim();
        return reduced.isBlank() ? null : reduced;
    }

    private static String optionFingerprint(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) return null;
        String reduced = normalized
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        return reduced.isBlank() ? null : reduced;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static boolean shouldUseSessionToken(int targetCount, List<String> disallowedPrompts) {
        if (targetCount > PROVIDER_MAX_BATCH_SIZE) {
            return true;
        }
        return disallowedPrompts != null && !disallowedPrompts.isEmpty();
    }

    private String requestSessionToken() {
        return requestSessionToken("request", null);
    }

    private String requestSessionToken(String command, String existingToken) {
        String normalizedCommand = trimToNull(command);
        if (normalizedCommand == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(128);
        sb.append(API_TOKEN_URL).append("?command=").append(normalizedCommand);
        String normalizedToken = trimToNull(existingToken);
        if (normalizedToken != null) {
            sb.append("&token=").append(normalizedToken);
        }

        JsonNode root = requestJson(URI.create(sb.toString()));
        int responseCode = root.path("response_code").asInt(-1);
        if (responseCode != 0) {
            log.warn("OpenTDB token {} returned response_code={}", normalizedCommand, responseCode);
            return null;
        }

        String token = trimToNull(root.path("token").asText(null));
        if (token == null) {
            log.warn("OpenTDB token {} returned empty token", normalizedCommand);
        }
        return token;
    }

    private static String resolveCurrentToken(String[] sessionTokenRef) {
        if (sessionTokenRef == null || sessionTokenRef.length == 0) {
            return null;
        }
        return trimToNull(sessionTokenRef[0]);
    }

    private void waitForRequestSlot() {
        while (true) {
            long delayMs;
            synchronized (rateLimitLock) {
                long now = System.currentTimeMillis();
                if (now >= nextAllowedRequestAtMs) {
                    nextAllowedRequestAtMs = now + adaptiveRequestSpacingMs;
                    return;
                }
                delayMs = nextAllowedRequestAtMs - now;
            }
            sleepWithInterruptHandling(delayMs);
        }
    }

    private long increaseRequestSpacing(int attempt, Long retryAfterMs) {
        long delayFromHeaderOrBackoff = retryDelayMs(attempt, retryAfterMs);
        synchronized (rateLimitLock) {
            adaptiveRequestSpacingMs = Math.min(
                    MAX_REQUEST_SPACING_MS,
                    Math.max(adaptiveRequestSpacingMs * 2L, delayFromHeaderOrBackoff)
            );
            long now = System.currentTimeMillis();
            nextAllowedRequestAtMs = Math.max(nextAllowedRequestAtMs, now + adaptiveRequestSpacingMs);
            return adaptiveRequestSpacingMs;
        }
    }

    private void registerSuccessfulRequest() {
        synchronized (rateLimitLock) {
            adaptiveRequestSpacingMs = Math.max(
                    MIN_REQUEST_SPACING_MS,
                    adaptiveRequestSpacingMs - REQUEST_SPACING_RECOVERY_STEP_MS
            );
        }
    }

    private static void sleepWithInterruptHandling(long delayMs) {
        long safeDelay = Math.max(0L, delayMs);
        if (safeDelay <= 0L) {
            return;
        }
        try {
            Thread.sleep(safeDelay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(BAD_GATEWAY, "OpenTDB request interrupted");
        }
    }

    private static long retryDelayMs(int attempt, Long retryAfterMs) {
        if (retryAfterMs != null && retryAfterMs > 0L) {
            return Math.min(MAX_RETRY_AFTER_MS, retryAfterMs);
        }
        long backoff = DEFAULT_RETRY_AFTER_MS * (1L << Math.max(0, attempt - 1));
        return Math.min(MAX_RETRY_AFTER_MS, backoff);
    }

    private static Long parseRetryAfterMs(HttpResponse<String> response) {
        String retryAfter = response == null
                ? null
                : response.headers().firstValue("Retry-After").orElse(null);
        String normalized = trimToNull(retryAfter);
        if (normalized == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(normalized);
            if (seconds <= 0L) {
                return null;
            }
            return Math.min(MAX_RETRY_AFTER_MS, seconds * 1_000L);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private record BatchFetchResult(List<GeneratedQuestion> questions, boolean sourceExhausted) {}

    private record OptionCandidate(String text, boolean correct) {}

    public record OpenTdbCategory(int id, String name) {}

    public record QuestionGenerationPrompt(
            int questionCount,
            Integer categoryId,
            QuestionGenerationDifficulty difficulty,
            List<String> disallowedPrompts
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
}
