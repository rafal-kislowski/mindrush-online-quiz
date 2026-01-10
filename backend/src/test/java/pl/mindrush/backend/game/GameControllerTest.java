package pl.mindrush.backend.game;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import pl.mindrush.backend.guest.GuestSessionRepository;
import pl.mindrush.backend.lobby.LobbyParticipantRepository;
import pl.mindrush.backend.lobby.LobbyRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.seed.enabled=true"
})
@AutoConfigureMockMvc
@Import(GameControllerTest.ClockTestConfig.class)
class GameControllerTest {

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));
        }
    }

    static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public synchronized Instant instant() {
            return instant;
        }

        synchronized void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private LobbyRepository lobbyRepository;

    @Autowired
    private LobbyParticipantRepository lobbyParticipantRepository;

    @BeforeEach
    void setUp() {
        lobbyParticipantRepository.deleteAll();
        lobbyRepository.deleteAll();
        guestSessionRepository.deleteAll();
    }

    @Test
    void startAnswerRevealNextEnd_happyPath() throws Exception {
        String ownerSessionId = createGuestSession();
        String secondSessionId = createGuestSession();

        String lobbyCode = createLobby(ownerSessionId);
        joinLobby(lobbyCode, secondSessionId);

        Long quizId = firstQuizId();

        mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stage").value("PRE_COUNTDOWN"))
                .andExpect(jsonPath("$.questionIndex").value(1))
                .andExpect(jsonPath("$.totalQuestions").value(2));

        clock.advance(Duration.ofSeconds(4));

        MvcResult stateOwner1 = mockMvc.perform(get("/api/lobbies/" + lobbyCode + "/game/state")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("QUESTION"))
                .andExpect(jsonPath("$.question.id").isNumber())
                .andReturn();

        Number qIdNum = JsonPath.read(stateOwner1.getResponse().getContentAsString(), "$.question.id");
        long qId = qIdNum.longValue();
        List<Integer> ownerOptions1 = JsonPath.read(stateOwner1.getResponse().getContentAsString(), "$.question.options[*].id");

        MvcResult stateOwner2 = mockMvc.perform(get("/api/lobbies/" + lobbyCode + "/game/state")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andReturn();

        List<Integer> ownerOptions2 = JsonPath.read(stateOwner2.getResponse().getContentAsString(), "$.question.options[*].id");
        assertThat(ownerOptions2).containsExactlyElementsOf(ownerOptions1);

        MvcResult stateSecond = mockMvc.perform(get("/api/lobbies/" + lobbyCode + "/game/state")
                        .cookie(new Cookie("guestSessionId", secondSessionId)))
                .andExpect(status().isOk())
                .andReturn();

        List<Integer> secondOptions = JsonPath.read(stateSecond.getResponse().getContentAsString(), "$.question.options[*].id");
        assertThat(secondOptions).hasSize(ownerOptions1.size());
        assertThat(sorted(secondOptions)).containsExactlyElementsOf(sorted(ownerOptions1));

        int ownerOptionId = ownerOptions1.get(0);
        int secondOptionId = secondOptions.get(0);

        mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/answer")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":" + qId + ",\"optionId\":" + ownerOptionId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("QUESTION"));

        mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/answer")
                        .cookie(new Cookie("guestSessionId", secondSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":" + qId + ",\"optionId\":" + secondOptionId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("REVEAL"))
                .andExpect(jsonPath("$.players[0].correct").exists())
                .andExpect(jsonPath("$.players[1].correct").exists());

        clock.advance(Duration.ofSeconds(4));

        mockMvc.perform(get("/api/lobbies/" + lobbyCode + "/game/state")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("QUESTION"))
                .andExpect(jsonPath("$.questionIndex").value(2));

        mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/end")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("FINISHED"))
                .andExpect(jsonPath("$.lobbyStatus").value("OPEN"));

        mockMvc.perform(get("/api/lobbies/" + lobbyCode + "/game/state")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("FINISHED"))
                .andExpect(jsonPath("$.players.length()").value(2))
                .andExpect(jsonPath("$.players[0].score").isNumber())
                .andExpect(jsonPath("$.players[1].score").isNumber());
    }

    @Test
    void timeout_countsNoAnswerAsWrong_andAutoAdvances() throws Exception {
        String ownerSessionId = createGuestSession();
        String secondSessionId = createGuestSession();

        String lobbyCode = createLobby(ownerSessionId);
        joinLobby(lobbyCode, secondSessionId);

        Long quizId = firstQuizId();

        mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stage").value("PRE_COUNTDOWN"));

        clock.advance(Duration.ofSeconds(4));

        MvcResult state = mockMvc.perform(get("/api/lobbies/" + lobbyCode + "/game/state")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("QUESTION"))
                .andReturn();

        Number qIdNum = JsonPath.read(state.getResponse().getContentAsString(), "$.question.id");
        long qId = qIdNum.longValue();
        List<Integer> ownerOptions = JsonPath.read(state.getResponse().getContentAsString(), "$.question.options[*].id");

        mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/answer")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":" + qId + ",\"optionId\":" + ownerOptions.get(0) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("QUESTION"));

        clock.advance(Duration.ofSeconds(11));

        mockMvc.perform(get("/api/lobbies/" + lobbyCode + "/game/state")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("REVEAL"))
                .andExpect(jsonPath("$.players[0].correct").exists())
                .andExpect(jsonPath("$.players[1].correct").exists());

        clock.advance(Duration.ofSeconds(4));

        mockMvc.perform(get("/api/lobbies/" + lobbyCode + "/game/state")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("QUESTION"))
                .andExpect(jsonPath("$.questionIndex").value(2));
    }

    private Long firstQuizId() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/quizzes").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        Number id = JsonPath.read(list.getResponse().getContentAsString(), "$[0].id");
        return id.longValue();
    }

    private String createGuestSession() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/guest/session"))
                .andExpect(status().isCreated())
                .andReturn();
        String setCookie = res.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String sessionId = cookieValueFromSetCookie(setCookie, "guestSessionId");
        assertThat(sessionId).isNotBlank();
        return sessionId;
    }

    private String createLobby(String ownerSessionId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isString())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.code");
    }

    private void joinLobby(String code, String sessionId) throws Exception {
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", sessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(2));
    }

    private static String cookieValueFromSetCookie(String setCookie, String cookieName) {
        String prefix = cookieName + "=";
        int start = setCookie.indexOf(prefix);
        int valueStart = start + prefix.length();
        int end = setCookie.indexOf(';', valueStart);
        if (end < 0) end = setCookie.length();
        return setCookie.substring(valueStart, end).trim();
    }

    private static List<Integer> sorted(List<Integer> list) {
        return list.stream().sorted(Comparator.naturalOrder()).toList();
    }
}
