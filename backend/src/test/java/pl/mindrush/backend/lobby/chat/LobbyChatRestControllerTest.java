package pl.mindrush.backend.lobby.chat;

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
import pl.mindrush.backend.lobby.LobbyParticipantRepository;
import pl.mindrush.backend.lobby.LobbyRepository;

import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.seed.enabled=true"
})
@AutoConfigureMockMvc
class LobbyChatRestControllerTest {

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
    void history_requiresValidGuestSession() throws Exception {
        mockMvc.perform(get("/api/lobbies/ABC123/chat"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void history_returnsEmptyArrayForParticipant_whenNoMessagesYet() throws Exception {
        String ownerSessionId = createGuestSession();
        String participantSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", participantSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/lobbies/" + code + "/chat")
                        .cookie(new Cookie("guestSessionId", participantSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void send_asParticipant_returnsMessage_andHistoryContainsIt() throws Exception {
        String ownerSessionId = createGuestSession();
        String participantSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", participantSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/lobbies/" + code + "/chat")
                        .cookie(new Cookie("guestSessionId", participantSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lobbyCode").value(code))
                .andExpect(jsonPath("$.displayName").isString())
                .andExpect(jsonPath("$.text").value("hello"))
                .andExpect(jsonPath("$.serverTime").isString());

        mockMvc.perform(get("/api/lobbies/" + code + "/chat")
                        .cookie(new Cookie("guestSessionId", participantSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].text").value("hello"));
    }

    @Test
    void send_emptyMessage_returnsBadRequest() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + code + "/chat")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());
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
