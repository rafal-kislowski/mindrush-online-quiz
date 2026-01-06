package pl.mindrush.backend.guest;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    void post_withExistingCookie_reusesSameSession() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/guest/session"))
                .andExpect(status().isCreated())
                .andReturn();

        String sessionId = cookieValueFromSetCookie(first.getResponse().getHeader(HttpHeaders.SET_COOKIE), "guestSessionId")
                .orElseThrow();

        assertThat(repository.count()).isEqualTo(1);

        mockMvc.perform(post("/api/guest/session").cookie(new Cookie("guestSessionId", sessionId)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("guestSessionId=" + sessionId)));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void delete_revokesSessionAndClearsCookie() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/guest/session"))
                .andExpect(status().isCreated())
                .andReturn();

        String sessionId = cookieValueFromSetCookie(first.getResponse().getHeader(HttpHeaders.SET_COOKIE), "guestSessionId")
                .orElseThrow();

        mockMvc.perform(delete("/api/guest/session").cookie(new Cookie("guestSessionId", sessionId)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("guestSessionId=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));

        GuestSession session = repository.findById(sessionId).orElseThrow();
        assertThat(session.isRevoked()).isTrue();
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
