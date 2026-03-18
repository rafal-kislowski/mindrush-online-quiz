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
import pl.mindrush.backend.notification.UserNotificationRepository;

import java.time.Clock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
        "app.library.policy.user.max-question-images-per-quiz=0",
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

    @Autowired
    private UserNotificationRepository userNotificationRepository;

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
        userNotificationRepository.deleteAll();
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
                .andExpect(jsonPath("$.maxQuestionImagesPerQuiz").value(0))
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
    void addQuestion_rejectsOptionImagesForUserQuizzes() throws Exception {
        Cookie accessCookie = loginAsUser("user-option-image@example.com");
        long quizId = createQuiz(accessCookie, "No option images quiz");

        mockMvc.perform(post("/api/library/quizzes/" + quizId + "/questions")
                        .cookie(accessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prompt": "Question with option image",
                                  "options": [
                                    { "text": "A", "imageUrl": "/media/abc.png", "correct": true },
                                    { "text": "B", "correct": false },
                                    { "text": "C", "correct": false },
                                    { "text": "D", "correct": false }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Answer option images are disabled for user quizzes"));
    }

    @Test
    void addQuestion_rejectsQuestionImageWhenTierLimitIsZero() throws Exception {
        Cookie accessCookie = loginAsUser("user-question-image-limit@example.com");
        long quizId = createQuiz(accessCookie, "Question image limit quiz");

        mockMvc.perform(post("/api/library/quizzes/" + quizId + "/questions")
                        .cookie(accessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prompt": "Question image blocked",
                                  "imageUrl": "/media/question.png",
                                  "options": [
                                    { "text": "A", "correct": true },
                                    { "text": "B", "correct": false },
                                    { "text": "C", "correct": false },
                                    { "text": "D", "correct": false }
                                  ]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Question image limit reached (0) for this quiz."));
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
    void listQuizzes_showsOwnedUnapprovedForSoloAndKeepsOthersHidden() throws Exception {
        Cookie viewerCookie = loginAsUser("viewer-list@example.com");
        AppUser viewer = userRepository.findByEmailIgnoreCase("viewer-list@example.com").orElseThrow();
        AppUser otherOwner = createUser("owner-list@example.com");

        Quiz ownedApproved = new Quiz("Owned approved", "desc", null);
        ownedApproved.setSource(QuizSource.CUSTOM);
        ownedApproved.setOwnerUserId(viewer.getId());
        ownedApproved.setStatus(QuizStatus.ACTIVE);
        ownedApproved.setModerationStatus(QuizModerationStatus.APPROVED);
        ownedApproved = quizRepository.save(ownedApproved);

        Quiz ownedPending = new Quiz("Owned pending", "desc", null);
        ownedPending.setSource(QuizSource.CUSTOM);
        ownedPending.setOwnerUserId(viewer.getId());
        ownedPending.setStatus(QuizStatus.ACTIVE);
        ownedPending.setModerationStatus(QuizModerationStatus.PENDING);
        ownedPending = quizRepository.save(ownedPending);

        Quiz otherPending = new Quiz("Other pending", "desc", null);
        otherPending.setSource(QuizSource.CUSTOM);
        otherPending.setOwnerUserId(otherOwner.getId());
        otherPending.setStatus(QuizStatus.ACTIVE);
        otherPending.setModerationStatus(QuizModerationStatus.PENDING);
        otherPending = quizRepository.save(otherPending);

        Quiz otherApproved = new Quiz("Other approved", "desc", null);
        otherApproved.setSource(QuizSource.CUSTOM);
        otherApproved.setOwnerUserId(otherOwner.getId());
        otherApproved.setStatus(QuizStatus.ACTIVE);
        otherApproved.setModerationStatus(QuizModerationStatus.APPROVED);
        otherApproved = quizRepository.save(otherApproved);

        favoriteRepository.save(QuizFavorite.create(viewer.getId(), otherApproved.getId(), clock.instant()));

        MvcResult listRes = mockMvc.perform(get("/api/quizzes").cookie(viewerCookie))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode rows = objectMapper.readTree(listRes.getResponse().getContentAsString());
        JsonNode ownedApprovedRow = quizRowById(rows, ownedApproved.getId());
        JsonNode ownedPendingRow = quizRowById(rows, ownedPending.getId());
        JsonNode otherApprovedRow = quizRowById(rows, otherApproved.getId());
        JsonNode otherPendingRow = quizRowById(rows, otherPending.getId());

        assertThat(ownedApprovedRow).isNotNull();
        assertThat(ownedApprovedRow.path("inLibrary").asBoolean(false)).isTrue();
        assertThat(ownedApprovedRow.path("publicAvailable").asBoolean(false)).isTrue();

        assertThat(ownedPendingRow).isNotNull();
        assertThat(ownedPendingRow.path("ownedByViewer").asBoolean(false)).isTrue();
        assertThat(ownedPendingRow.path("publicAvailable").asBoolean(true)).isFalse();
        assertThat(ownedPendingRow.path("inLibrary").asBoolean(true)).isFalse();

        assertThat(otherApprovedRow).isNotNull();
        assertThat(otherApprovedRow.path("favorite").asBoolean(false)).isTrue();
        assertThat(otherApprovedRow.path("inLibrary").asBoolean(true)).isFalse();

        assertThat(otherPendingRow).isNull();
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

    @Test
    void submitForModeration_createsAdminNotification() throws Exception {
        Cookie ownerCookie = loginAsUser("submit-owner@example.com");
        Cookie adminCookie = loginAsRoles("submit-admin@example.com", Set.of(AppRole.ADMIN));

        long quizId = createQuiz(ownerCookie, "Submission notify quiz");
        Quiz quiz = quizRepository.findById(quizId).orElseThrow();
        questionRepository.save(new QuizQuestion(quiz, "Prompt A", 0));
        questionRepository.save(new QuizQuestion(quiz, "Prompt B", 1));

        mockMvc.perform(post("/api/library/quizzes/" + quizId + "/submit")
                        .cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("PENDING"));

        mockMvc.perform(get("/api/notifications?limit=20").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items[0].category").value("moderation"))
                .andExpect(jsonPath("$.items[0].severity").value("warning"))
                .andExpect(jsonPath("$.items[0].title").value("New quiz for review"))
                .andExpect(jsonPath("$.items[0].subtitle").value("Submission notify quiz"))
                .andExpect(jsonPath("$.items[0].text").value(containsString("submitted a quiz for moderation review")))
                .andExpect(jsonPath("$.items[0].routePath").value("/admin/quiz-submissions"))
                .andExpect(jsonPath("$.items[0].routeQueryParams.openQuiz").value((int) quizId));
    }

    private Cookie loginAsUser(String email) throws Exception {
        return loginAsRoles(email, Set.of(AppRole.USER));
    }

    private Cookie loginAsRoles(String email, Set<AppRole> roles) throws Exception {
        AppUser user = new AppUser(
                email,
                passwordEncoder.encode("Password123"),
                displayNameFor(email),
                roles,
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
                displayNameFor(email),
                Set.of(AppRole.USER),
                clock.instant()
        );
        return userRepository.save(user);
    }

    private static String displayNameFor(String email) {
        String localPart = email == null ? "user" : email.split("@", 2)[0];
        String normalized = localPart.replaceAll("[^A-Za-z0-9_-]", "_");
        if (normalized.isBlank()) {
            normalized = "user";
        }
        String candidate = "User_" + normalized;
        return candidate.length() <= 32 ? candidate : candidate.substring(0, 32);
    }

    private record Login(String email, String password) {}

    private JsonNode quizRowById(JsonNode rows, Long id) {
        if (rows == null || !rows.isArray() || id == null) return null;
        for (JsonNode row : rows) {
            if (row.path("id").asLong(-1) == id) {
                return row;
            }
        }
        return null;
    }

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
