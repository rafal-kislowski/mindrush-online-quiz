package pl.mindrush.backend.leaderboard;

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

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=test-secret-please-change"
})
@AutoConfigureMockMvc
class LeaderboardControllerTest {

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

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void leaderboard_isPublic_andReturnsSortedByRp() throws Exception {
        AppUser u1 = new AppUser("a@example.com", passwordEncoder.encode("Password123"), "RafkensA", Set.of(AppRole.USER), clock.instant());
        AppUser u2 = new AppUser("b@example.com", passwordEncoder.encode("Password123"), "RafkensB", Set.of(AppRole.USER), clock.instant());
        userRepository.save(u1);
        userRepository.save(u2);

        u1.setRankPoints(100);
        u2.setRankPoints(250);
        userRepository.save(u1);
        userRepository.save(u2);

        mockMvc.perform(get("/api/leaderboard?limit=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rankPoints").value(250))
                .andExpect(jsonPath("$[1].rankPoints").value(100))
                .andExpect(jsonPath("$[0].displayName").value("RafkensB"))
                .andExpect(jsonPath("$[1].displayName").value("RafkensA"));

        mockMvc.perform(get("/api/leaderboard?page=1&size=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rankPoints").value(250));
    }

    @Test
    void leaderboardStats_isPublic() throws Exception {
        AppUser u1 = new AppUser("a@example.com", passwordEncoder.encode("Password123"), "A", Set.of(AppRole.USER), clock.instant());
        userRepository.save(u1);

        mockMvc.perform(get("/api/leaderboard/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players").value(1))
                .andExpect(jsonPath("$.matches").value(0))
                .andExpect(jsonPath("$.answers").value(0));
    }

    @Test
    void leaderboardMe_requiresAuth() throws Exception {
        mockMvc.perform(get("/api/leaderboard/me"))
                .andExpect(status().isUnauthorized());

        AppUser u1 = new AppUser("a@example.com", passwordEncoder.encode("Password123"), "Player", Set.of(AppRole.USER), clock.instant());
        userRepository.save(u1);
        u1.setRankPoints(123);
        userRepository.save(u1);

        MvcResult loginRes = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Login("a@example.com", "Password123"))))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = String.join("\n", loginRes.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
        String access = cookieValueFromSetCookie(setCookie, "accessToken").orElseThrow();

        mockMvc.perform(get("/api/leaderboard/me").cookie(new Cookie("accessToken", access)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rankPoints").value(123))
                .andExpect(jsonPath("$.position").value(1));
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
