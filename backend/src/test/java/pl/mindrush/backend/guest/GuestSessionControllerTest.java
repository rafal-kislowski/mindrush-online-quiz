package pl.mindrush.backend.guest;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import jakarta.servlet.http.Cookie;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class GuestSessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GuestSessionRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void post_createsSessionAndSetsHttpOnlyCookie() throws Exception {
        mockMvc.perform(post("/api/guest/session"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("guestSessionId=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("HttpOnly")));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void get_requiresValidSession() throws Exception {
        mockMvc.perform(get("/api/guest/session"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_thenGet_returnsGeneratedDisplayName() throws Exception {
        String sessionId = createGuestSessionId();

        mockMvc.perform(get("/api/guest/session").cookie(new Cookie("guestSessionId", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName", not(emptyOrNullString())))
                .andExpect(jsonPath("$.expiresAt", not(emptyOrNullString())));
    }

    @Test
    void post_withExistingCookie_reusesSameSession() throws Exception {
        String sessionId = createGuestSessionId();

        assertThat(repository.count()).isEqualTo(1);

        mockMvc.perform(post("/api/guest/session").cookie(new Cookie("guestSessionId", sessionId)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("guestSessionId=" + sessionId)));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void delete_revokesSessionAndClearsCookie() throws Exception {
        String sessionId = createGuestSessionId();

        mockMvc.perform(delete("/api/guest/session").cookie(new Cookie("guestSessionId", sessionId)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("guestSessionId=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));

        GuestSession session = repository.findById(sessionId).orElseThrow();
        assertThat(session.isRevoked()).isTrue();
    }

    @Test
    void postHeartbeat_updatesLastSeenAt() throws Exception {
        String sessionId = createGuestSessionId();

        GuestSession session = repository.findById(sessionId).orElseThrow();
        session.setLastSeenAt(Instant.EPOCH);
        repository.save(session);

        mockMvc.perform(post("/api/guest/session/heartbeat").cookie(new Cookie("guestSessionId", sessionId)))
                .andExpect(status().isNoContent());

        GuestSession updated = repository.findById(sessionId).orElseThrow();
        assertThat(updated.getLastSeenAt()).isAfter(Instant.EPOCH);
    }

    private String createGuestSessionId() throws Exception {
        ResultActions res = mockMvc.perform(post("/api/guest/session"))
                .andExpect(status().isCreated());

        String setCookie = res.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        return cookieValueFromSetCookie(setCookie, "guestSessionId").orElseThrow();
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
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
