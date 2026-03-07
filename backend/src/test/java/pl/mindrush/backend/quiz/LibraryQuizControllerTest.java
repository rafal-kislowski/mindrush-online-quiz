package pl.mindrush.backend.quiz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import pl.mindrush.backend.AppRole;
import pl.mindrush.backend.AppUser;
import pl.mindrush.backend.AppUserRepository;
import pl.mindrush.backend.RefreshTokenRepository;

import java.time.Clock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.seed.enabled=false",
        "app.jwt.secret=test-secret-please-change",
        "app.library.policy.user.max-owned-quizzes=1",
        "app.library.policy.user.max-published-quizzes=1",
        "app.library.policy.user.max-pending-submissions=1",
        "app.library.policy.user.min-questions-to-submit=2",
        "app.library.policy.user.max-questions-per-quiz=1",
        "app.library.policy.user.min-question-time-limit-seconds=5",
        "app.library.policy.user.max-question-time-limit-seconds=120",
        "app.library.policy.user.max-questions-per-game=20",
        "app.media.dir=target/test-uploads"
})
@AutoConfigureMockMvc
class LibraryQuizControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private QuizFavoriteRepository favoriteRepository;

    @Autowired
    private QuizAnswerOptionRepository optionRepository;

    @Autowired
    private QuizQuestionRepository questionRepository;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private QuizModerationIssueRepository moderationIssueRepository;

    @BeforeEach
    void setUp() {
        try {
            Path uploads = Path.of("target", "test-uploads");
            if (Files.exists(uploads)) {
                Files.walk(uploads)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        });
            }
        } catch (Exception ignored) {
        }
        favoriteRepository.deleteAll();
        optionRepository.deleteAll();
        questionRepository.deleteAll();
        moderationIssueRepository.deleteAll();
        quizRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void policy_returnsConfiguredUserLimits() throws Exception {
        Cookie accessCookie = loginAsUser("user1@example.com");

        mockMvc.perform(get("/api/library/quizzes/policy").cookie(accessCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxOwnedQuizzes").value(1))
                .andExpect(jsonPath("$.maxQuestionsPerQuiz").value(1))
                .andExpect(jsonPath("$.minQuestionsToSubmit").value(2))
                .andExpect(jsonPath("$.ownedCount").value(0))
                .andExpect(jsonPath("$.pendingCount").value(0));
    }

    @Test
    void createQuiz_respectsOwnedLimit() throws Exception {
        Cookie accessCookie = loginAsUser("user2@example.com");

        mockMvc.perform(post("/api/library/quizzes")
                        .cookie(accessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Quiz One",
                                  "description": "First",
                                  "categoryName": "General"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/library/quizzes")
                        .cookie(accessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Quiz Two",
                                  "description": "Second",
                                  "categoryName": "General"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Owned quiz limit reached")));
    }

    @Test
    void addQuestion_respectsQuestionLimitPerQuiz() throws Exception {
        Cookie accessCookie = loginAsUser("user3@example.com");
        long quizId = createQuiz(accessCookie, "Limit quiz");

        mockMvc.perform(post("/api/library/quizzes/" + quizId + "/questions")
                        .cookie(accessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prompt": "Q1",
                                  "options": [
                                    { "text": "A", "correct": true },
                                    { "text": "B", "correct": false },
                                    { "text": "C", "correct": false },
                                    { "text": "D", "correct": false }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/library/quizzes/" + quizId + "/questions")
                        .cookie(accessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prompt": "Q2",
                                  "options": [
                                    { "text": "A", "correct": true },
                                    { "text": "B", "correct": false },
                                    { "text": "C", "correct": false },
                                    { "text": "D", "correct": false }
                                  ]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("maximum number of questions")));
    }

    @Test
    void toggleFavorite_updatesPublicAndFavoritesViews() throws Exception {
        Cookie viewerCookie = loginAsUser("viewer@example.com");
        AppUser owner = createUser("owner-public@example.com");

        Quiz quiz = new Quiz("Public quiz", "desc", null);
        quiz.setSource(QuizSource.CUSTOM);
        quiz.setOwnerUserId(owner.getId());
        quiz.setStatus(QuizStatus.ACTIVE);
        quiz.setModerationStatus(QuizModerationStatus.APPROVED);
        quiz = quizRepository.save(quiz);

        mockMvc.perform(get("/api/library/quizzes/public").cookie(viewerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(quiz.getId()))
                .andExpect(jsonPath("$[0].favorite").value(false));

        mockMvc.perform(post("/api/library/quizzes/" + quiz.getId() + "/favorite-toggle").cookie(viewerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(true));

        mockMvc.perform(get("/api/library/quizzes/favorites").cookie(viewerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(quiz.getId()))
                .andExpect(jsonPath("$[0].favorite").value(true));

        mockMvc.perform(get("/api/library/quizzes/public").cookie(viewerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(quiz.getId()))
                .andExpect(jsonPath("$[0].favorite").value(true));

        mockMvc.perform(post("/api/library/quizzes/" + quiz.getId() + "/favorite-toggle").cookie(viewerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(false));

        mockMvc.perform(get("/api/library/quizzes/favorites").cookie(viewerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void mediaUpload_requiresAuthenticationAndStoresImage() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.png",
                "image/png",
                new byte[]{1, 2, 3, 4}
        );

        mockMvc.perform(multipart("/api/library/media")
                        .file(file)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isUnauthorized());

        Cookie accessCookie = loginAsUser("media-user@example.com");

        MvcResult uploadRes = mockMvc.perform(multipart("/api/library/media")
                        .file(file)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .cookie(accessCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url", startsWith("/media/")))
                .andReturn();

        JsonNode json = objectMapper.readTree(uploadRes.getResponse().getContentAsString());
        String url = json.get("url").asText();
        String filename = url.replace("/media/", "");
        Path stored = Path.of("target", "test-uploads", filename);
        org.assertj.core.api.Assertions.assertThat(Files.exists(stored)).isTrue();
    }

    private Cookie loginAsUser(String email) throws Exception {
        AppUser user = new AppUser(
                email,
                passwordEncoder.encode("Password123"),
                "Player",
                Set.of(AppRole.USER),
                clock.instant()
        );
        userRepository.save(user);

        MvcResult loginRes = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Login(email, "Password123"))))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = String.join("\n", loginRes.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
        String access = cookieValueFromSetCookie(setCookie, "accessToken").orElseThrow();
        return new Cookie("accessToken", access);
    }

    private long createQuiz(Cookie accessCookie, String title) throws Exception {
        MvcResult createRes = mockMvc.perform(post("/api/library/quizzes")
                        .cookie(accessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "%s",
                                  "description": "x",
                                  "categoryName": "General"
                                }
                                """.formatted(title)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(createRes.getResponse().getContentAsString());
        return json.get("id").asLong();
    }

    private AppUser createUser(String email) {
        AppUser user = new AppUser(
                email,
                passwordEncoder.encode("Password123"),
                "Owner",
                Set.of(AppRole.USER),
                clock.instant()
        );
        return userRepository.save(user);
    }

    private record Login(String email, String password) {}

    private static Optional<String> cookieValueFromSetCookie(String setCookie, String cookieName) {
        if (setCookie == null || setCookie.isBlank()) return Optional.empty();
        String prefix = cookieName + "=";
        int start = setCookie.indexOf(prefix);
        if (start < 0) return Optional.empty();
        int valueStart = start + prefix.length();
        int end = setCookie.indexOf(';', valueStart);
        if (end < 0) end = setCookie.length();
        String value = setCookie.substring(valueStart, end).trim();
        if (value.isBlank()) return Optional.empty();
        return Optional.of(value);
    }
}
