package pl.mindrush.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=test-secret-please-change",
        "app.auth.require-verified-email=true"
})
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthActionTokenService authActionTokenService;

    @Autowired
    private AuthActionTokenRepository authActionTokenRepository;

    @BeforeEach
    void setUp() {
        authActionTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void login_setsCookies_andMeReturnsUser() throws Exception {
        AppUser admin = new AppUser(
                "admin@example.com",
                passwordEncoder.encode("Password123"),
                "Admin",
                Set.of(AppRole.ADMIN),
                clock.instant()
        );
        userRepository.save(admin);

        MvcResult loginRes = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Login("admin@example.com", "Password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@example.com"))
                .andExpect(jsonPath("$.displayName").value("Admin"))
                .andExpect(jsonPath("$.rankPoints").value(0))
                .andExpect(jsonPath("$.xp").value(0))
                .andExpect(jsonPath("$.coins").value(0))
                .andReturn();

        String setCookie = String.join("\n", loginRes.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
        assertThat(setCookie).contains("accessToken=");
        assertThat(setCookie).contains("refreshToken=");

        String access = cookieValueFromSetCookie(setCookie, "accessToken").orElseThrow();

        mockMvc.perform(get("/api/auth/me").cookie(new jakarta.servlet.http.Cookie("accessToken", access)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@example.com"))
                .andExpect(jsonPath("$.rankPoints").value(0))
                .andExpect(jsonPath("$.xp").value(0))
                .andExpect(jsonPath("$.coins").value(0));
    }

    @Test
    void adminEndpoint_requiresAdminRole() throws Exception {
        AppUser user = new AppUser(
                "admin@example.com",
                passwordEncoder.encode("Password123"),
                "Admin",
                Set.of(AppRole.ADMIN),
                clock.instant()
        );
        userRepository.save(user);

        MvcResult loginRes = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Login("admin@example.com", "Password123"))))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = String.join("\n", loginRes.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
        String access = cookieValueFromSetCookie(setCookie, "accessToken").orElseThrow();

        mockMvc.perform(post("/api/admin/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Test quiz\"}")
                        .cookie(new jakarta.servlet.http.Cookie("accessToken", access)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Test quiz"));
    }

    @Test
    void admin_canEditQuestionAndOptions() throws Exception {
        AppUser admin = new AppUser(
                "admin@example.com",
                passwordEncoder.encode("Password123"),
                "Admin",
                Set.of(AppRole.ADMIN),
                clock.instant()
        );
        userRepository.save(admin);

        MvcResult loginRes = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Login("admin@example.com", "Password123"))))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = String.join("\n", loginRes.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
        String access = cookieValueFromSetCookie(setCookie, "accessToken").orElseThrow();
        jakarta.servlet.http.Cookie accessCookie = new jakarta.servlet.http.Cookie("accessToken", access);

        MvcResult createQuizRes = mockMvc.perform(post("/api/admin/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Edit quiz\"}")
                        .cookie(accessCookie))
                .andExpect(status().isCreated())
                .andReturn();

        long quizId = objectMapper.readTree(createQuizRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/admin/quizzes/" + quizId + "/questions")
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
                                """)
                        .cookie(accessCookie))
                .andExpect(status().isCreated());

        MvcResult detailRes = mockMvc.perform(get("/api/admin/quizzes/" + quizId).cookie(accessCookie))
                .andExpect(status().isOk())
                .andReturn();

        var detailJson = objectMapper.readTree(detailRes.getResponse().getContentAsString());
        var question = detailJson.get("questions").get(0);
        long questionId = question.get("id").asLong();
        var options = question.get("options");

        long o1 = options.get(0).get("id").asLong();
        long o2 = options.get(1).get("id").asLong();
        long o3 = options.get(2).get("id").asLong();
        long o4 = options.get(3).get("id").asLong();

        mockMvc.perform(put("/api/admin/quizzes/" + quizId + "/questions/" + questionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prompt": "Q1 updated",
                                  "options": [
                                    { "id": %d, "text": "A1", "correct": false },
                                    { "id": %d, "text": "B1", "correct": false },
                                    { "id": %d, "text": "C1", "correct": true },
                                    { "id": %d, "text": "D1", "correct": false }
                                  ]
                                }
                                """.formatted(o1, o2, o3, o4))
                        .cookie(accessCookie))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/quizzes/" + quizId).cookie(accessCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].prompt").value("Q1 updated"))
                .andExpect(jsonPath("$.questions[0].options[2].correct").value(true));
    }

    @Test
    void forgotPassword_forUnknownEmail_returnsGenericMessage() throws Exception {
        mockMvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"missing@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("If this email is registered")));
    }

    @Test
    void forgotPassword_isRateLimitedByCooldown() throws Exception {
        String email = "forgot-cooldown@example.com";
        AppUser user = new AppUser(
                email,
                passwordEncoder.encode("Password123"),
                "ForgotCooldown",
                Set.of(AppRole.USER),
                clock.instant()
        );
        user = userRepository.save(user);

        mockMvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("If this email is registered")));

        Instant firstSentAt = userRepository.findById(user.getId())
                .map(AppUser::getLastPasswordResetEmailSentAt)
                .orElseThrow();
        assertThat(firstSentAt).isNotNull();

        mockMvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("If this email is registered")));

        Instant secondSentAt = userRepository.findById(user.getId())
                .map(AppUser::getLastPasswordResetEmailSentAt)
                .orElseThrow();
        assertThat(secondSentAt).isEqualTo(firstSentAt);
    }

    @Test
    void resendVerificationEmail_isRateLimitedByCooldown() throws Exception {
        String email = "cooldown-user@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"CooldownUser","password":"Password123"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        Instant firstSentAt = userRepository.findByEmailIgnoreCase(email)
                .map(AppUser::getLastVerificationEmailSentAt)
                .orElseThrow();
        assertThat(firstSentAt).isNotNull();

        mockMvc.perform(post("/api/auth/verification/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("If this account exists")));

        Instant secondSentAt = userRepository.findByEmailIgnoreCase(email)
                .map(AppUser::getLastVerificationEmailSentAt)
                .orElseThrow();
        assertThat(secondSentAt).isEqualTo(firstSentAt);
    }

    @Test
    void verifyEmail_tokenActivatesAccount() throws Exception {
        AppUser user = new AppUser(
                "verify-user@example.com",
                passwordEncoder.encode("Password123"),
                "VerifyUser",
                Set.of(AppRole.USER),
                clock.instant()
        );
        user.setEmailVerified(false);
        user.setEmailVerifiedAt(null);
        user = userRepository.save(user);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Login(user.getEmail(), "Password123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(containsString("verify your email")));

        AuthActionTokenService.TokenIssue tokenIssue = authActionTokenService.issue(
                user.getId(),
                AuthActionTokenType.EMAIL_VERIFY,
                Duration.ofMinutes(30)
        );

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s"}
                                """.formatted(tokenIssue.rawToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("verified")));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Login(user.getEmail(), "Password123"))))
                .andExpect(status().isOk());
    }

    @Test
    void resetPassword_changesPasswordAndRevokesOldRefreshTokens() throws Exception {
        AppUser user = new AppUser(
                "reset-user@example.com",
                passwordEncoder.encode("OldPassword123"),
                "ResetUser",
                Set.of(AppRole.USER),
                clock.instant()
        );
        user = userRepository.save(user);

        AuthActionTokenService.TokenIssue tokenIssue = authActionTokenService.issue(
                user.getId(),
                AuthActionTokenType.PASSWORD_RESET,
                Duration.ofMinutes(30)
        );

        mockMvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","password":"NewPassword123","confirmPassword":"NewPassword123"}
                                """.formatted(tokenIssue.rawToken())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Login(user.getEmail(), "OldPassword123"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Login(user.getEmail(), "NewPassword123"))))
                .andExpect(status().isOk());
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
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
