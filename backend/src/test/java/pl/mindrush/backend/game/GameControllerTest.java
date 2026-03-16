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
import pl.mindrush.backend.quiz.QuizAnswerOptionRepository;
import pl.mindrush.backend.quiz.QuizRepository;

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

    @Autowired
    private GameService gameService;

    @Autowired
    private QuizAnswerOptionRepository quizAnswerOptionRepository;

    @Autowired
    private QuizRepository quizRepository;

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
        int expectedTotalQuestions = firstQuizQuestionCount();

        mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stage").value("PRE_COUNTDOWN"))
                .andExpect(jsonPath("$.questionIndex").value(1))
                .andExpect(jsonPath("$.totalQuestions").value(expectedTotalQuestions));

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
                .andExpect(jsonPath("$.players[1].score").isNumber())
                .andExpect(jsonPath("$.players[0].xpDelta").isNumber())
                .andExpect(jsonPath("$.players[1].xpDelta").isNumber())
                .andExpect(jsonPath("$.players[0].coinsDelta").isNumber())
                .andExpect(jsonPath("$.players[1].coinsDelta").isNumber());
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
        Number stageTotalMs = JsonPath.read(state.getResponse().getContentAsString(), "$.stageTotalMs");
        long timeoutMs = Math.max(1_000L, stageTotalMs.longValue() + 100L);

        mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/answer")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":" + qId + ",\"optionId\":" + ownerOptions.get(0) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("QUESTION"));

        clock.advance(Duration.ofMillis(timeoutMs));
        gameService.tickDueSessions();

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

    @Test
    void threeLivesMode_runAndBestRecordSaved() throws Exception {
        String ownerSessionId = createGuestSession();
        String lobbyCode = createLobby(ownerSessionId);
        Long quizId = firstQuizId();
        int expectedTotalQuestions = firstQuizQuestionCount();

        mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + ",\"mode\":\"THREE_LIVES\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("THREE_LIVES"))
                .andExpect(jsonPath("$.stage").value("PRE_COUNTDOWN"))
                .andExpect(jsonPath("$.totalQuestions").value(expectedTotalQuestions))
                .andExpect(jsonPath("$.livesRemaining").value(3));

        clock.advance(Duration.ofSeconds(4));

        for (int round = 1; round <= 3; round++) {
            MvcResult questionState = mockMvc.perform(get("/api/lobbies/" + lobbyCode + "/game/state")
                            .cookie(new Cookie("guestSessionId", ownerSessionId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("THREE_LIVES"))
                    .andExpect(jsonPath("$.stage").value("QUESTION"))
                    .andReturn();

            Number stageTotalMs = JsonPath.read(questionState.getResponse().getContentAsString(), "$.stageTotalMs");
            long timeoutMs = Math.max(1_000L, stageTotalMs.longValue() + 100L);

            // Timeout answer -> wrong answer in 3-lives mode.
            clock.advance(Duration.ofMillis(timeoutMs));
            gameService.tickDueSessions();

            int expectedLives = 3 - round;
            mockMvc.perform(get("/api/lobbies/" + lobbyCode + "/game/state")
                            .cookie(new Cookie("guestSessionId", ownerSessionId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stage").value("REVEAL"))
                    .andExpect(jsonPath("$.livesRemaining").value(expectedLives))
                    .andExpect(jsonPath("$.wrongAnswers").value(round));

            if (round < 3) {
                clock.advance(Duration.ofSeconds(4));
                gameService.tickDueSessions();
            }
        }

        clock.advance(Duration.ofSeconds(3));

        mockMvc.perform(get("/api/lobbies/" + lobbyCode + "/game/state")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("THREE_LIVES"))
                .andExpect(jsonPath("$.stage").value("FINISHED"))
                .andExpect(jsonPath("$.livesRemaining").value(0))
                .andExpect(jsonPath("$.wrongAnswers").value(3))
                .andExpect(jsonPath("$.players[0].xpDelta").isNumber())
                .andExpect(jsonPath("$.players[0].coinsDelta").isNumber())
                .andExpect(jsonPath("$.players[0].rankPointsDelta").value(org.hamcrest.Matchers.nullValue()));

        var guest = guestSessionRepository.findById(ownerSessionId).orElseThrow();
        assertThat(guest.getXp()).isGreaterThan(0);
        assertThat(guest.getCoins()).isGreaterThan(0);
        assertThat(guest.getRankPoints()).isEqualTo(0);

        mockMvc.perform(get("/api/casual/three-lives/best")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isNumber())
                .andExpect(jsonPath("$.answered").value(3))
                .andExpect(jsonPath("$.durationMs").isNumber())
                .andExpect(jsonPath("$.updatedAt").isString());
    }

    @Test
    void threeLivesMode_finishesAfterAllSelectedQuizQuestionsAnswered() throws Exception {
        String ownerSessionId = createGuestSession();
        String lobbyCode = createLobby(ownerSessionId);
        Long quizId = firstQuizId();
        int expectedTotalQuestions = firstQuizQuestionCount();

        mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + ",\"mode\":\"THREE_LIVES\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("THREE_LIVES"))
                .andExpect(jsonPath("$.stage").value("PRE_COUNTDOWN"))
                .andExpect(jsonPath("$.totalQuestions").value(expectedTotalQuestions))
                .andExpect(jsonPath("$.livesRemaining").value(3));

        clock.advance(Duration.ofSeconds(4));
        gameService.tickDueSessions();

        for (int round = 1; round <= expectedTotalQuestions; round++) {
            MvcResult state = mockMvc.perform(get("/api/lobbies/" + lobbyCode + "/game/state")
                            .cookie(new Cookie("guestSessionId", ownerSessionId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("THREE_LIVES"))
                    .andExpect(jsonPath("$.stage").value("QUESTION"))
                    .andExpect(jsonPath("$.questionIndex").value(round))
                    .andExpect(jsonPath("$.totalQuestions").value(expectedTotalQuestions))
                    .andReturn();

            Number qIdNum = JsonPath.read(state.getResponse().getContentAsString(), "$.question.id");
            long qId = qIdNum.longValue();
            List<Integer> optionIds = JsonPath.read(state.getResponse().getContentAsString(), "$.question.options[*].id");
            Integer correctOptionId = optionIds.stream()
                    .filter(id -> quizAnswerOptionRepository.findById(id.longValue()).map(o -> o.isCorrect()).orElse(false))
                    .findFirst()
                    .orElseThrow();

            mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/answer")
                            .cookie(new Cookie("guestSessionId", ownerSessionId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"questionId\":" + qId + ",\"optionId\":" + correctOptionId + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stage").value("REVEAL"))
                    .andExpect(jsonPath("$.livesRemaining").value(3))
                    .andExpect(jsonPath("$.wrongAnswers").value(0));

            if (round < expectedTotalQuestions) {
                clock.advance(Duration.ofSeconds(4));
                gameService.tickDueSessions();
            }
        }

        clock.advance(Duration.ofSeconds(3));
        gameService.tickDueSessions();

        mockMvc.perform(get("/api/lobbies/" + lobbyCode + "/game/state")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("THREE_LIVES"))
                .andExpect(jsonPath("$.stage").value("FINISHED"))
                .andExpect(jsonPath("$.totalQuestions").value(expectedTotalQuestions))
                .andExpect(jsonPath("$.questionIndex").value(expectedTotalQuestions))
                .andExpect(jsonPath("$.livesRemaining").value(3))
                .andExpect(jsonPath("$.wrongAnswers").value(0));
    }

    @Test
    void trainingMode_questionHasNoTimer() throws Exception {
        String ownerSessionId = createGuestSession();
        String lobbyCode = createLobby(ownerSessionId);
        Long quizId = firstQuizId();

        mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + ",\"mode\":\"TRAINING\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("TRAINING"))
                .andExpect(jsonPath("$.stage").value("PRE_COUNTDOWN"));

        clock.advance(Duration.ofSeconds(4));
        gameService.tickDueSessions();

        MvcResult state = mockMvc.perform(get("/api/lobbies/" + lobbyCode + "/game/state")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("TRAINING"))
                .andExpect(jsonPath("$.stage").value("QUESTION"))
                .andExpect(jsonPath("$.stageEndsAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.stageTotalMs").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();

        Number qIdNum = JsonPath.read(state.getResponse().getContentAsString(), "$.question.id");
        long qId = qIdNum.longValue();
        List<Integer> options = JsonPath.read(state.getResponse().getContentAsString(), "$.question.options[*].id");

        mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/answer")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":" + qId + ",\"optionId\":" + options.get(0) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("TRAINING"))
                .andExpect(jsonPath("$.stage").value("REVEAL"));

        mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/end")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("TRAINING"))
                .andExpect(jsonPath("$.stage").value("FINISHED"))
                .andExpect(jsonPath("$.players[0].xpDelta").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.players[0].coinsDelta").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.players[0].rankPointsDelta").value(org.hamcrest.Matchers.nullValue()));

        var guest = guestSessionRepository.findById(ownerSessionId).orElseThrow();
        assertThat(guest.getXp()).isEqualTo(0);
        assertThat(guest.getCoins()).isEqualTo(0);
        assertThat(guest.getRankPoints()).isEqualTo(0);
    }

    @Test
    void soloTrainingMode_expiresAfterInactivity_andAllowsStartingNewGame() throws Exception {
        String ownerSessionId = createGuestSession();
        Long quizId = firstQuizId();

        MvcResult started = mockMvc.perform(post("/api/solo-games/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + ",\"mode\":\"TRAINING\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("TRAINING"))
                .andReturn();

        String sessionId = JsonPath.read(started.getResponse().getContentAsString(), "$.gameSessionId");
        assertThat(sessionId).isNotBlank();

        clock.advance(Duration.ofHours(2).plusSeconds(1));
        gameService.tickDueSessions();

        mockMvc.perform(get("/api/solo-games/" + sessionId + "/state")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("TRAINING"))
                .andExpect(jsonPath("$.stage").value("FINISHED"))
                .andExpect(jsonPath("$.finishReason").value("EXPIRED"));

        mockMvc.perform(get("/api/games/current")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/solo-games/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + ",\"mode\":\"STANDARD\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("STANDARD"));
    }

    @Test
    void soloThreeLivesMode_acceptsSessionQuestionIdAnswerPayload() throws Exception {
        String ownerSessionId = createGuestSession();
        Long quizId = firstQuizId();

        MvcResult started = mockMvc.perform(post("/api/solo-games/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + ",\"mode\":\"THREE_LIVES\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("THREE_LIVES"))
                .andExpect(jsonPath("$.stage").value("PRE_COUNTDOWN"))
                .andReturn();

        String sessionId = JsonPath.read(started.getResponse().getContentAsString(), "$.gameSessionId");
        assertThat(sessionId).isNotBlank();

        clock.advance(Duration.ofSeconds(4));
        gameService.tickDueSessions();

        MvcResult state = mockMvc.perform(get("/api/solo-games/" + sessionId + "/state")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("THREE_LIVES"))
                .andExpect(jsonPath("$.stage").value("QUESTION"))
                .andReturn();

        Number qIdNum = JsonPath.read(state.getResponse().getContentAsString(), "$.question.id");
        long qId = qIdNum.longValue();
        assertThat(qId).isNegative();
        List<Integer> options = JsonPath.read(state.getResponse().getContentAsString(), "$.question.options[*].id");

        mockMvc.perform(post("/api/solo-games/" + sessionId + "/answer")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":" + qId + ",\"optionId\":" + options.get(0) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("THREE_LIVES"))
                .andExpect(jsonPath("$.stage").value("REVEAL"));
    }

    @Test
    void soloThreeLivesMode_usesAllQuizQuestionsEvenWhenQuestionsPerGameIsLower() throws Exception {
        String ownerSessionId = createGuestSession();
        Long quizId = firstQuizId();
        int expectedTotalQuestions = firstQuizQuestionCount();
        assertThat(expectedTotalQuestions).isGreaterThan(1);

        var quiz = quizRepository.findById(quizId).orElseThrow();
        int originalQuestionsPerGame = quiz.getQuestionsPerGame();
        quiz.setQuestionsPerGame(1);
        quizRepository.saveAndFlush(quiz);

        try {
            MvcResult started = mockMvc.perform(post("/api/solo-games/start")
                            .cookie(new Cookie("guestSessionId", ownerSessionId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quizId\":" + quizId + ",\"mode\":\"THREE_LIVES\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.mode").value("THREE_LIVES"))
                    .andExpect(jsonPath("$.totalQuestions").value(expectedTotalQuestions))
                    .andReturn();

            String sessionId = JsonPath.read(started.getResponse().getContentAsString(), "$.gameSessionId");

            clock.advance(Duration.ofSeconds(4));
            gameService.tickDueSessions();

            mockMvc.perform(get("/api/solo-games/" + sessionId + "/state")
                            .cookie(new Cookie("guestSessionId", ownerSessionId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("THREE_LIVES"))
                    .andExpect(jsonPath("$.stage").value("QUESTION"))
                    .andExpect(jsonPath("$.totalQuestions").value(expectedTotalQuestions));
        } finally {
            quizRepository.findById(quizId).ifPresent(currentQuiz -> {
                currentQuiz.setQuestionsPerGame(originalQuestionsPerGame);
                quizRepository.saveAndFlush(currentQuiz);
            });
        }
    }

    @Test
    void threeLivesMode_requiresSoloLobby() throws Exception {
        String ownerSessionId = createGuestSession();
        String secondSessionId = createGuestSession();

        String lobbyCode = createLobby(ownerSessionId);
        joinLobby(lobbyCode, secondSessionId);

        Long quizId = firstQuizId();

        mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + ",\"mode\":\"THREE_LIVES\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This mode is available only for solo lobbies"));
    }

    @Test
    void startSoloGame_whenAlreadyInLobby_returnsConflict() throws Exception {
        String ownerSessionId = createGuestSession();
        String lobbyCode = createLobby(ownerSessionId);
        assertThat(lobbyCode).isNotBlank();
        Long quizId = firstQuizId();

        mockMvc.perform(post("/api/solo-games/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + ",\"mode\":\"STANDARD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You are already in lobby " + lobbyCode + ". Leave the lobby before starting a solo game."));
    }

    @Test
    void startSoloGame_whenAnotherSoloIsInProgress_returnsConflict() throws Exception {
        String ownerSessionId = createGuestSession();
        Long quizId = firstQuizId();

        mockMvc.perform(post("/api/solo-games/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + ",\"mode\":\"STANDARD\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/solo-games/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + ",\"mode\":\"STANDARD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You already have an active game in progress. Finish it before starting another one."));
    }

    @Test
    void currentGame_returnsSoloSessionWhenSoloGameIsActive() throws Exception {
        String ownerSessionId = createGuestSession();
        Long quizId = firstQuizId();

        MvcResult started = mockMvc.perform(post("/api/solo-games/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + ",\"mode\":\"STANDARD\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String gameSessionId = JsonPath.read(started.getResponse().getContentAsString(), "$.gameSessionId");

        mockMvc.perform(get("/api/games/current")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SOLO"))
                .andExpect(jsonPath("$.gameSessionId").value(gameSessionId))
                .andExpect(jsonPath("$.lobbyCode").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void currentGame_returnsLobbyGameWhenLobbyGameIsActive() throws Exception {
        String ownerSessionId = createGuestSession();
        String lobbyCode = createLobby(ownerSessionId);
        Long quizId = firstQuizId();

        MvcResult started = mockMvc.perform(post("/api/lobbies/" + lobbyCode + "/game/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        String gameSessionId = JsonPath.read(started.getResponse().getContentAsString(), "$.gameSessionId");

        mockMvc.perform(get("/api/games/current")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("LOBBY"))
                .andExpect(jsonPath("$.gameSessionId").value(gameSessionId))
                .andExpect(jsonPath("$.lobbyCode").value(lobbyCode));
    }

    @Test
    void endSoloGame_marksFinishReasonAsManualEnd() throws Exception {
        String ownerSessionId = createGuestSession();
        Long quizId = firstQuizId();

        MvcResult started = mockMvc.perform(post("/api/solo-games/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + ",\"mode\":\"STANDARD\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String sessionId = JsonPath.read(started.getResponse().getContentAsString(), "$.gameSessionId");

        mockMvc.perform(post("/api/solo-games/" + sessionId + "/end")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("FINISHED"))
                .andExpect(jsonPath("$.finishReason").value("MANUAL_END"));
    }

    @Test
    void currentGame_returnsNoContentWhenNoActiveGame() throws Exception {
        String ownerSessionId = createGuestSession();

        mockMvc.perform(get("/api/games/current")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isNoContent());
    }

    private Long firstQuizId() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/quizzes").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        Number id = JsonPath.read(list.getResponse().getContentAsString(), "$[0].id");
        return id.longValue();
    }

    private int firstQuizQuestionCount() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/quizzes").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        Number questionCount = JsonPath.read(list.getResponse().getContentAsString(), "$[0].questionCount");
        return questionCount.intValue();
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
