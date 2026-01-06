package pl.mindrush.backend.lobby;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import pl.mindrush.backend.guest.GuestSessionRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class LobbyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private LobbyRepository lobbyRepository;

    @Autowired
    private LobbyParticipantRepository participantRepository;

    @BeforeEach
    void setUp() {
        participantRepository.deleteAll();
        lobbyRepository.deleteAll();
        guestSessionRepository.deleteAll();
    }

    @Test
    void createLobby_requiresGuestSession() throws Exception {
        mockMvc.perform(post("/api/lobbies"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createAndJoinLobby_guestLimit2() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isString())
                .andExpect(jsonPath("$.maxPlayers").value(2))
                .andExpect(jsonPath("$.players.length()").value(1))
                .andExpect(jsonPath("$.players[0].displayName", not(emptyOrNullString())))
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();
        assertThat(code).hasSize(6);

        String secondSessionId = createGuestSession();

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", secondSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(2));

        String thirdSessionId = createGuestSession();
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", thirdSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/lobbies/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(2));
    }

    @Test
    void joinPasswordProtectedLobby_requiresPassword() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"secret123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        String otherSessionId = createGuestSession();

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", otherSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", otherSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(2));
    }

    @Test
    void joinSameGuestTwice_isIdempotent() throws Exception {
        String sessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", sessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.players.length()").value(1))
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", sessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(1));
    }

    private String createGuestSession() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/guest/session"))
                .andExpect(status().isCreated())
                .andReturn();
        String setCookie = res.getResponse().getHeader(HttpHeaders.SET_COOKIE);
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

    private static Optional<String> jsonValue(String json, String startToken, String endToken) {
        int start = json.indexOf(startToken);
        if (start < 0) return Optional.empty();
        int valueStart = start + startToken.length();
        int end = json.indexOf(endToken, valueStart);
        if (end < 0) return Optional.empty();
        return Optional.of(json.substring(valueStart, end));
    }
}
