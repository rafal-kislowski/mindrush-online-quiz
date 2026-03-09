package pl.mindrush.backend.quiz;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import pl.mindrush.backend.AppRole;
import pl.mindrush.backend.AppUser;
import pl.mindrush.backend.AppUserRepository;
import pl.mindrush.backend.RefreshTokenRepository;
import pl.mindrush.backend.notification.UserNotificationRepository;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.seed.enabled=false",
        "app.jwt.secret=test-secret-please-change"
})
@AutoConfigureMockMvc
class AdminQuizSubmissionControllerTest {

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
    void approveSubmission_rejectsStaleSubmissionVersion() throws Exception {
        Cookie adminCookie = loginAs("admin@example.com", Set.of(AppRole.ADMIN));
        AppUser owner = createUser("owner@example.com", Set.of(AppRole.USER));

        Quiz pending = new Quiz("Submission v1", "desc", null);
        pending.setSource(QuizSource.CUSTOM);
        pending.setOwnerUserId(owner.getId());
        pending.setStatus(QuizStatus.DRAFT);
        pending.setModerationStatus(QuizModerationStatus.PENDING);
        pending.setModerationReason(null);
        pending = quizRepository.save(pending);
        long staleSubmissionVersion = pending.getVersion();

        // Simulate that owner changed content and re-submitted a newer revision.
        pending.setTitle("Submission v2");
        pending.setStatus(QuizStatus.DRAFT);
        pending.setModerationStatus(QuizModerationStatus.PENDING);
        pending.setModerationReason(null);
        pending = quizRepository.save(pending);

        mockMvc.perform(post("/api/admin/quiz-submissions/" + pending.getId() + "/approve")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedSubmissionVersion": %d
                                }
                                """.formatted(staleSubmissionVersion)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("Submission changed during review")));
    }

    @Test
    void rejectSubmission_rejectsStaleSubmissionVersion() throws Exception {
        Cookie adminCookie = loginAs("admin@example.com", Set.of(AppRole.ADMIN));
        AppUser owner = createUser("owner-reject@example.com", Set.of(AppRole.USER));

        Quiz pending = new Quiz("Submission reject v1", "desc", null);
        pending.setSource(QuizSource.CUSTOM);
        pending.setOwnerUserId(owner.getId());
        pending.setStatus(QuizStatus.DRAFT);
        pending.setModerationStatus(QuizModerationStatus.PENDING);
        pending.setModerationReason(null);
        pending = quizRepository.save(pending);
        long staleSubmissionVersion = pending.getVersion();

        // Simulate that owner changed content and re-submitted a newer revision.
        pending.setTitle("Submission reject v2");
        pending.setStatus(QuizStatus.DRAFT);
        pending.setModerationStatus(QuizModerationStatus.PENDING);
        pending.setModerationReason(null);
        pending = quizRepository.save(pending);

        mockMvc.perform(post("/api/admin/quiz-submissions/" + pending.getId() + "/reject")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedSubmissionVersion": %d,
                                  "reason": "Needs changes"
                                }
                                """.formatted(staleSubmissionVersion)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("Submission changed during review")));
    }

    @Test
    void undoApprove_rejectsStaleSubmissionVersion() throws Exception {
        Cookie adminCookie = loginAs("admin-undo-stale@example.com", Set.of(AppRole.ADMIN));
        AppUser owner = createUser("owner-undo-stale@example.com", Set.of(AppRole.USER));

        Quiz approved = new Quiz("Approved v1", "desc", null);
        approved.setSource(QuizSource.CUSTOM);
        approved.setOwnerUserId(owner.getId());
        approved.setStatus(QuizStatus.ACTIVE);
        approved.setModerationStatus(QuizModerationStatus.APPROVED);
        approved = quizRepository.save(approved);
        long staleSubmissionVersion = approved.getVersion();

        approved.setTitle("Approved v2");
        approved = quizRepository.save(approved);

        mockMvc.perform(post("/api/admin/quiz-submissions/" + approved.getId() + "/undo-approve")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedSubmissionVersion": %d
                                }
                                """.formatted(staleSubmissionVersion)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("Submission changed during review")));
    }

    @Test
    void undoApprove_movesSubmissionBackToPending() throws Exception {
        Cookie adminCookie = loginAs("admin-undo-ok@example.com", Set.of(AppRole.ADMIN));
        AppUser owner = createUser("owner-undo-ok@example.com", Set.of(AppRole.USER));

        Quiz approved = new Quiz("Approved quiz", "desc", null);
        approved.setSource(QuizSource.CUSTOM);
        approved.setOwnerUserId(owner.getId());
        approved.setStatus(QuizStatus.ACTIVE);
        approved.setModerationStatus(QuizModerationStatus.APPROVED);
        approved = quizRepository.save(approved);

        mockMvc.perform(post("/api/admin/quiz-submissions/" + approved.getId() + "/undo-approve")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedSubmissionVersion": %d
                                }
                                """.formatted(approved.getVersion())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("PENDING"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.moderationReason").value(nullValue()));

        Quiz stored = quizRepository.findById(approved.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(stored.getModerationStatus()).isEqualTo(QuizModerationStatus.PENDING);
        org.assertj.core.api.Assertions.assertThat(stored.getStatus()).isEqualTo(QuizStatus.DRAFT);
        org.assertj.core.api.Assertions.assertThat(stored.getModerationReason()).isNull();
        org.assertj.core.api.Assertions.assertThat(stored.getModerationUpdatedAt()).isNotNull();
    }

    @Test
    void rejectSubmission_withQuestionIssues_exposesIssueDetailsToOwnerLibrary() throws Exception {
        Cookie adminCookie = loginAs("admin-issues@example.com", Set.of(AppRole.ADMIN));
        Cookie ownerCookie = loginAs("owner-issues@example.com", Set.of(AppRole.USER));
        AppUser owner = userRepository.findAll().stream()
                .filter(user -> "owner-issues@example.com".equalsIgnoreCase(user.getEmail()))
                .findFirst()
                .orElseThrow();

        Quiz pending = new Quiz("Issue quiz", "desc", null);
        pending.setSource(QuizSource.CUSTOM);
        pending.setOwnerUserId(owner.getId());
        pending.setStatus(QuizStatus.DRAFT);
        pending.setModerationStatus(QuizModerationStatus.PENDING);
        pending = quizRepository.save(pending);

        QuizQuestion question = questionRepository.save(new QuizQuestion(pending, "Prompt", 0));
        optionRepository.save(new QuizAnswerOption(question, "A", true, 0));
        optionRepository.save(new QuizAnswerOption(question, "B", false, 1));
        optionRepository.save(new QuizAnswerOption(question, "C", false, 2));
        optionRepository.save(new QuizAnswerOption(question, "D", false, 3));

        mockMvc.perform(post("/api/admin/quiz-submissions/" + pending.getId() + "/reject")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedSubmissionVersion": %d,
                                  "reason": "Needs fixes",
                                  "questionIssues": [
                                    { "questionId": %d, "message": "Ambiguous prompt" }
                                  ]
                                }
                                """.formatted(pending.getVersion(), question.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.moderationReason").value("Needs fixes"));

        mockMvc.perform(get("/api/library/quizzes/mine/" + pending.getId()).cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.moderationReason").value("Needs fixes"))
                .andExpect(jsonPath("$.moderationQuestionIssues[0].questionId").value(question.getId()))
                .andExpect(jsonPath("$.moderationQuestionIssues[0].message").value("Ambiguous prompt"));

        mockMvc.perform(get("/api/library/quizzes/mine").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].moderationQuestionIssueCount").value(1));
    }

    @Test
    void removeQuestionImage_clearsImageFromSubmission() throws Exception {
        Cookie adminCookie = loginAs("admin-remove-question@example.com", Set.of(AppRole.ADMIN));
        AppUser owner = createUser("owner-remove-question@example.com", Set.of(AppRole.USER));

        Quiz pending = new Quiz("Question image quiz", "desc", null);
        pending.setSource(QuizSource.CUSTOM);
        pending.setOwnerUserId(owner.getId());
        pending.setStatus(QuizStatus.DRAFT);
        pending.setModerationStatus(QuizModerationStatus.PENDING);
        pending = quizRepository.save(pending);

        QuizQuestion question = questionRepository.save(new QuizQuestion(pending, "Prompt with image", 0));
        question.setImageUrl("/media/q-illegal.png");
        questionRepository.save(question);

        optionRepository.save(new QuizAnswerOption(question, "A", true, 0));
        optionRepository.save(new QuizAnswerOption(question, "B", false, 1));
        optionRepository.save(new QuizAnswerOption(question, "C", false, 2));
        optionRepository.save(new QuizAnswerOption(question, "D", false, 3));

        mockMvc.perform(delete("/api/admin/quiz-submissions/" + pending.getId() + "/questions/" + question.getId() + "/image")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].imageUrl").value(nullValue()));

        QuizQuestion stored = questionRepository.findById(question.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(stored.getImageUrl()).isNull();
    }

    @Test
    void removeOptionImage_clearsImageFromSubmissionOption() throws Exception {
        Cookie adminCookie = loginAs("admin-remove-option@example.com", Set.of(AppRole.ADMIN));
        AppUser owner = createUser("owner-remove-option@example.com", Set.of(AppRole.USER));

        Quiz pending = new Quiz("Option image quiz", "desc", null);
        pending.setSource(QuizSource.CUSTOM);
        pending.setOwnerUserId(owner.getId());
        pending.setStatus(QuizStatus.DRAFT);
        pending.setModerationStatus(QuizModerationStatus.PENDING);
        pending = quizRepository.save(pending);

        QuizQuestion question = questionRepository.save(new QuizQuestion(pending, "Prompt", 0));
        QuizAnswerOption o1 = optionRepository.save(new QuizAnswerOption(question, "A", true, 0));
        o1.setImageUrl("/media/opt-illegal.png");
        o1 = optionRepository.save(o1);
        optionRepository.save(new QuizAnswerOption(question, "B", false, 1));
        optionRepository.save(new QuizAnswerOption(question, "C", false, 2));
        optionRepository.save(new QuizAnswerOption(question, "D", false, 3));

                mockMvc.perform(delete("/api/admin/quiz-submissions/" + pending.getId()
                        + "/questions/" + question.getId()
                        + "/options/" + o1.getId()
                        + "/image")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].options[0].imageUrl").value(nullValue()));

        QuizAnswerOption stored = optionRepository.findById(o1.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(stored.getImageUrl()).isNull();
    }

    @Test
    void removeAvatarImage_clearsAvatarFromSubmission() throws Exception {
        Cookie adminCookie = loginAs("admin-remove-avatar@example.com", Set.of(AppRole.ADMIN));
        AppUser owner = createUser("owner-remove-avatar@example.com", Set.of(AppRole.USER));

        Quiz pending = new Quiz("Avatar image quiz", "desc", null);
        pending.setSource(QuizSource.CUSTOM);
        pending.setOwnerUserId(owner.getId());
        pending.setStatus(QuizStatus.DRAFT);
        pending.setModerationStatus(QuizModerationStatus.PENDING);
        pending.setAvatarImageUrl("/media/avatar-illegal.png");
        pending = quizRepository.save(pending);

        mockMvc.perform(delete("/api/admin/quiz-submissions/" + pending.getId() + "/avatar")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarImageUrl").value(nullValue()));

        Quiz stored = quizRepository.findById(pending.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(stored.getAvatarImageUrl()).isNull();
    }

    @Test
    void banOwner_marksOwnerAsBannedAndBlocksLogin() throws Exception {
        Cookie adminCookie = loginAs("admin-ban-owner@example.com", Set.of(AppRole.ADMIN));
        AppUser owner = createUser("owner-ban-owner@example.com", Set.of(AppRole.USER));

        Quiz pending = new Quiz("Ban owner quiz", "desc", null);
        pending.setSource(QuizSource.CUSTOM);
        pending.setOwnerUserId(owner.getId());
        pending.setStatus(QuizStatus.DRAFT);
        pending.setModerationStatus(QuizModerationStatus.PENDING);
        pending = quizRepository.save(pending);

        mockMvc.perform(post("/api/admin/quiz-submissions/" + pending.getId() + "/owner/ban")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.banned").value(true))
                .andExpect(jsonPath("$.roles", hasItem("BANNED")));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Login(owner.getEmail(), "Password123"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unbanOwner_removesBanAndAllowsLogin() throws Exception {
        Cookie adminCookie = loginAs("admin-unban-owner@example.com", Set.of(AppRole.ADMIN));
        AppUser owner = createUser("owner-unban-owner@example.com", Set.of(AppRole.USER));

        Quiz pending = new Quiz("Unban owner quiz", "desc", null);
        pending.setSource(QuizSource.CUSTOM);
        pending.setOwnerUserId(owner.getId());
        pending.setStatus(QuizStatus.DRAFT);
        pending.setModerationStatus(QuizModerationStatus.PENDING);
        pending = quizRepository.save(pending);

        mockMvc.perform(post("/api/admin/quiz-submissions/" + pending.getId() + "/owner/ban")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.banned").value(true));

        mockMvc.perform(post("/api/admin/quiz-submissions/" + pending.getId() + "/owner/unban")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.banned").value(false));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Login(owner.getEmail(), "Password123"))))
                .andExpect(status().isOk());
    }

    @Test
    void rejectSubmission_createsNotificationAndAllowsDismiss() throws Exception {
        Cookie adminCookie = loginAs("admin-notify@example.com", Set.of(AppRole.ADMIN));
        AppUser owner = createUser("owner-notify@example.com", Set.of(AppRole.USER));
        Cookie ownerCookie = loginExisting(owner.getEmail());

        Quiz pending = new Quiz("Notify quiz", "desc", null);
        pending.setSource(QuizSource.CUSTOM);
        pending.setOwnerUserId(owner.getId());
        pending.setStatus(QuizStatus.DRAFT);
        pending.setModerationStatus(QuizModerationStatus.PENDING);
        pending = quizRepository.save(pending);

        mockMvc.perform(post("/api/admin/quiz-submissions/" + pending.getId() + "/reject")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedSubmissionVersion":%d,"reason":"Needs fixes","questionIssues":[]}
                                """.formatted(pending.getVersion())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications?limit=20").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items[0].category").value("moderation"))
                .andExpect(jsonPath("$.items[0].decision").value("rejected"));

        Long notificationId = userNotificationRepository.findAll().stream()
                .filter(n -> owner.getId().equals(n.getUserId()))
                .findFirst()
                .orElseThrow()
                .getId();

        mockMvc.perform(post("/api/notifications/" + notificationId + "/dismiss").cookie(ownerCookie))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/notifications?limit=20").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    private Cookie loginAs(String email, Set<AppRole> roles) throws Exception {
        createUser(email, roles);

        return loginExisting(email);
    }

    private Cookie loginExisting(String email) throws Exception {

        MvcResult loginRes = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Login(email, "Password123"))))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = String.join("\n", loginRes.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
        String access = cookieValueFromSetCookie(setCookie, "accessToken").orElseThrow();
        return new Cookie("accessToken", access);
    }

    private AppUser createUser(String email, Set<AppRole> roles) {
        AppUser user = new AppUser(
                email,
                passwordEncoder.encode("Password123"),
                "Test User",
                roles,
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
