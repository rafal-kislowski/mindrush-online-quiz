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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.seed.enabled=true"
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

    @Autowired
    private LobbyBanRepository lobbyBanRepository;

    @Autowired
    private LobbyService lobbyService;

    @BeforeEach
    void setUp() {
        lobbyBanRepository.deleteAll();
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
    void createLobby_guestCannotRequestMoreThan2Players() throws Exception {
        String ownerSessionId = createGuestSession();

        mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxPlayers\":3}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createLobby_authenticatedUser_canRequestUpTo5Players() throws Exception {
        String ownerSessionId = createGuestSession();
        Cookie access = registerAndGetAccessCookie();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .cookie(access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxPlayers\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxPlayers").value(5))
                .andExpect(jsonPath("$.players.length()").value(1))
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        String s2 = createGuestSession();
        String s3 = createGuestSession();
        String s4 = createGuestSession();
        String s5 = createGuestSession();
        String s6 = createGuestSession();

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", s2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", s3))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", s4))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", s5))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(5));

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", s6))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void createLobby_whenOwnerAlreadyHasOpenLobby_returnsExistingLobby() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult firstCreated = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        String firstCode = jsonValue(firstCreated.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        // Second create should reuse the same owned OPEN lobby.
        MvcResult secondCreate = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxPlayers\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(firstCode))
                .andReturn();

        String secondCode = jsonValue(secondCreate.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();
        assertThat(secondCode).isEqualTo(firstCode);
        assertThat(lobbyRepository.count()).isEqualTo(1);
    }

    @Test
    void createLobby_whenSoloGameIsInProgress_returnsConflict() throws Exception {
        String ownerSessionId = createGuestSession();
        Long quizId = firstQuizId();

        mockMvc.perform(post("/api/solo-games/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + ",\"mode\":\"STANDARD\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You already have an active game in progress. Finish it before you create a lobby."));
    }

    @Test
    void joinLobby_whenSoloGameIsInProgress_returnsConflict() throws Exception {
        String ownerSessionId = createGuestSession();
        String joiningSessionId = createGuestSession();
        Long quizId = firstQuizId();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/solo-games/start")
                        .cookie(new Cookie("guestSessionId", joiningSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + ",\"mode\":\"STANDARD\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", joiningSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You already have an active game in progress. Finish it before you join a lobby."));
    }

    @Test
    void setMaxPlayers_ownerCanChangeButNotBelowCurrentPlayers() throws Exception {
        Cookie access = registerAndGetAccessCookie();

        String ownerSessionId = createGuestSession();
        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .cookie(access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxPlayers\":2}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + code + "/max-players")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .cookie(access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxPlayers\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxPlayers").value(4));

        String s2 = createGuestSession();
        String s3 = createGuestSession();

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", s2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", s3))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(3));

        mockMvc.perform(post("/api/lobbies/" + code + "/max-players")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .cookie(access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxPlayers\":2}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/lobbies/" + code + "/max-players")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .cookie(access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxPlayers\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxPlayers").value(3));
    }

    @Test
    void joinPasswordProtectedLobby_requiresPassword() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"1234\"}"))
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
                        .content("{\"password\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(2));
    }

    @Test
    void ownerCanRejoinPasswordProtectedLobby_withoutPassword_afterRefreshGrace() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"1234\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + code + "/leave")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(1))
                .andExpect(jsonPath("$.isOwner").value(true))
                .andExpect(jsonPath("$.isParticipant").value(true));
    }

    @Test
    void setLobbyPassword_ownerCanSetAndClear_andNonParticipantSeesLimitedView() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasPassword").value(false))
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + code + "/password")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(true));

        mockMvc.perform(get("/api/lobbies/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.isOwner").value(false))
                .andExpect(jsonPath("$.isParticipant").value(false))
                .andExpect(jsonPath("$.players").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist());

        mockMvc.perform(post("/api/lobbies/" + code + "/password")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(false));

        mockMvc.perform(get("/api/lobbies/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(1))
                .andExpect(jsonPath("$.hasPassword").value(false));
    }

    @Test
    void setLobbyPassword_requiresOwner() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        String otherSessionId = createGuestSession();
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", otherSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/lobbies/" + code + "/password")
                        .cookie(new Cookie("guestSessionId", otherSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"1234\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void setLobbyPassword_whenLobbyNotOpen_isConflict() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + code + "/close")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(post("/api/lobbies/" + code + "/password")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"1234\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void join_afterPasswordIsSet_requiresPassword_thenAfterClear_allowsJoinWithoutPassword() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + code + "/password")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(true));

        String otherSessionId = createGuestSession();
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", otherSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/lobbies/" + code + "/password")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(false));

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", otherSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
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

    @Test
    void leave_freesSlotForAnotherGuest() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        String secondSessionId = createGuestSession();
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", secondSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(2));

        mockMvc.perform(post("/api/lobbies/" + code + "/leave")
                        .cookie(new Cookie("guestSessionId", secondSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(1));

        String thirdSessionId = createGuestSession();
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", thirdSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(2));
    }

    @Test
    void close_requiresOwner_andPreventsJoin() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        String otherSessionId = createGuestSession();
        mockMvc.perform(post("/api/lobbies/" + code + "/close")
                        .cookie(new Cookie("guestSessionId", otherSessionId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/lobbies/" + code + "/close")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        String thirdSessionId = createGuestSession();
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", thirdSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void leave_ownerAsLastPlayer_keepsLobbyForGracePeriod() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + code + "/leave")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/lobbies/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(0))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void leave_ownerTransfersOwnership_andLobbyStaysOpen() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        String secondSessionId = createGuestSession();
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", secondSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(2));

        // Owner sets a numeric PIN (stored as lobby password) - should be visible to the owner.
        mockMvc.perform(post("/api/lobbies/" + code + "/password")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.pin").value("1234"));

        mockMvc.perform(post("/api/lobbies/" + code + "/leave")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.isOwner").value(false))
                .andExpect(jsonPath("$.isParticipant").value(false))
                .andExpect(jsonPath("$.players").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist());

        // The remaining participant becomes the new owner and should see the currently set PIN.
        mockMvc.perform(get("/api/lobbies/" + code)
                        .cookie(new Cookie("guestSessionId", secondSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isOwner").value(true))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.pin").value("1234"));

        String thirdSessionId = createGuestSession();
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", thirdSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(2));

        mockMvc.perform(post("/api/lobbies/" + code + "/close")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/lobbies/" + code + "/close")
                        .cookie(new Cookie("guestSessionId", secondSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void handleGuestDisconnected_removesParticipantWhenLobbyOpen() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        String secondSessionId = createGuestSession();
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", secondSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow();
        assertThat(participantRepository.countByLobbyId(lobby.getId())).isEqualTo(2);

        lobbyService.handleGuestDisconnected(secondSessionId, code);
        assertThat(participantRepository.countByLobbyId(lobby.getId())).isEqualTo(1);
    }

    @Test
    void leave_duringGame_isBlocked() throws Exception {
        String ownerSessionId = createGuestSession();
        String secondSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", secondSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        Long quizId = firstQuizId();

        mockMvc.perform(post("/api/lobbies/" + code + "/game/start")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + "}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/lobbies/" + code + "/leave")
                        .cookie(new Cookie("guestSessionId", secondSessionId)))
                .andExpect(status().isConflict());
    }

    @Test
    void listActiveLobbies_returnsRowsWithOwnerTypeAndCounts() throws Exception {
        String guestOwnerSessionId = createGuestSession();
        MvcResult guestLobbyCreated = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", guestOwnerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String guestCode = jsonValue(guestLobbyCreated.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        String guestJoinerSessionId = createGuestSession();
        mockMvc.perform(post("/api/lobbies/" + guestCode + "/join")
                        .cookie(new Cookie("guestSessionId", guestJoinerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        Cookie access = registerAndGetAccessCookie();
        String authOwnerSessionId = createGuestSession();
        MvcResult authLobbyCreated = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", authOwnerSessionId))
                        .cookie(access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxPlayers\":5}"))
                .andExpect(status().isCreated())
                .andReturn();
        String authCode = jsonValue(authLobbyCreated.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + authCode + "/password")
                        .cookie(new Cookie("guestSessionId", authOwnerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"1234\"}"))
                .andExpect(status().isOk());

        String closedOwnerSessionId = createGuestSession();
        MvcResult closedLobbyCreated = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", closedOwnerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String closedCode = jsonValue(closedLobbyCreated.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + closedCode + "/close")
                        .cookie(new Cookie("guestSessionId", closedOwnerSessionId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/lobbies/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='" + guestCode + "')].ownerType", hasItem("GUEST")))
                .andExpect(jsonPath("$[?(@.code=='" + guestCode + "')].playerCount", hasItem(2)))
                .andExpect(jsonPath("$[?(@.code=='" + guestCode + "')].hasPassword", hasItem(false)))
                .andExpect(jsonPath("$[?(@.code=='" + authCode + "')].ownerType", hasItem("AUTHENTICATED")))
                .andExpect(jsonPath("$[?(@.code=='" + authCode + "')].hasPassword", hasItem(true)))
                .andExpect(jsonPath("$[?(@.code=='" + closedCode + "')]").value(hasSize(0)));
    }

    @Test
    void listActiveLobbies_marksOwnerAsAuthenticated_whenGuestSessionHasUserId() throws Exception {
        Cookie access = registerAndGetAccessCookie();
        String ownerSessionId = createGuestSession(access);

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(get("/api/lobbies/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='" + code + "')].ownerType", hasItem("AUTHENTICATED")));
    }

    @Test
    void ownedLobby_returnsCurrentOwnedOpenLobby_orNoContent() throws Exception {
        String ownerSessionId = createGuestSession();

        mockMvc.perform(get("/api/lobbies/owned")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isNoContent());

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(get("/api/lobbies/owned")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.isOwner").value(true));
    }

    @Test
    void currentLobby_returnsJoinedLobby_orNoContent() throws Exception {
        String ownerSessionId = createGuestSession();
        String participantSessionId = createGuestSession();

        mockMvc.perform(get("/api/lobbies/current")
                        .cookie(new Cookie("guestSessionId", participantSessionId)))
                .andExpect(status().isNoContent());

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

        mockMvc.perform(get("/api/lobbies/current")
                        .cookie(new Cookie("guestSessionId", participantSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.isParticipant").value(true));
    }

    @Test
    void createLobby_whenGuestAlreadyParticipatesInActiveLobby_returnsExistingLobby() throws Exception {
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code));

        mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", participantSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.isParticipant").value(true));
    }

    @Test
    void joinLobby_whenGuestOwnsAnotherActiveLobby_isBlocked() throws Exception {
        String ownerSessionId = createGuestSession();
        String secondOwnerSessionId = createGuestSession();

        MvcResult ownedCreated = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String ownedCode = jsonValue(ownedCreated.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        MvcResult otherCreated = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", secondOwnerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String otherCode = jsonValue(otherCreated.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + otherCode + "/join")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/lobbies/" + ownedCode + "/join")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ownedCode))
                .andExpect(jsonPath("$.isOwner").value(true));
    }

    @Test
    void joinLobby_whenGuestAlreadyParticipatesInAnotherActiveLobby_isBlocked() throws Exception {
        String firstOwnerSessionId = createGuestSession();
        String secondOwnerSessionId = createGuestSession();
        String participantSessionId = createGuestSession();

        MvcResult firstCreated = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", firstOwnerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String firstCode = jsonValue(firstCreated.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        MvcResult secondCreated = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", secondOwnerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String secondCode = jsonValue(secondCreated.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + firstCode + "/join")
                        .cookie(new Cookie("guestSessionId", participantSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(firstCode))
                .andExpect(jsonPath("$.isParticipant").value(true));

        mockMvc.perform(post("/api/lobbies/" + secondCode + "/join")
                        .cookie(new Cookie("guestSessionId", participantSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void kickParticipant_ownerCanKick_andKickedGuestCanJoinAgain() throws Exception {
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

        Long participantId = participantIdByLobbyCodeAndSessionId(code, participantSessionId);
        mockMvc.perform(post("/api/lobbies/" + code + "/players/" + participantId + "/kick")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(1));

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", participantSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(2));
    }

    @Test
    void banParticipant_ownerCanBan_andBannedGuestCannotRejoin() throws Exception {
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

        Long participantId = participantIdByLobbyCodeAndSessionId(code, participantSessionId);
        mockMvc.perform(post("/api/lobbies/" + code + "/players/" + participantId + "/ban")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(1));

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", participantSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You are banned from this lobby"));
    }

    @Test
    void kickAndBanParticipant_requireOwner() throws Exception {
        String ownerSessionId = createGuestSession();
        String participantSessionId = createGuestSession();
        String nonOwnerSessionId = createGuestSession();

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

        Long participantId = participantIdByLobbyCodeAndSessionId(code, participantSessionId);
        mockMvc.perform(post("/api/lobbies/" + code + "/players/" + participantId + "/kick")
                        .cookie(new Cookie("guestSessionId", nonOwnerSessionId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/lobbies/" + code + "/players/" + participantId + "/ban")
                        .cookie(new Cookie("guestSessionId", nonOwnerSessionId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void setReady_updatesParticipantReadyFlag() throws Exception {
        String ownerSessionId = createGuestSession();
        String secondSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", secondSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        Long quizId = firstQuizId();
        mockMvc.perform(post("/api/lobbies/" + code + "/selected-quiz")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/lobbies/" + code + "/ready")
                        .cookie(new Cookie("guestSessionId", secondSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ready\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players[1].ready").value(true));
    }

    @Test
    void setSelectedQuiz_resetsReadyForAllParticipants() throws Exception {
        String ownerSessionId = createGuestSession();
        String secondSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", secondSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        Long quizId = firstQuizId();
        mockMvc.perform(post("/api/lobbies/" + code + "/selected-quiz")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/lobbies/" + code + "/ready")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ready\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players[0].ready").value(true));

        mockMvc.perform(post("/api/lobbies/" + code + "/selected-quiz")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players[0].ready").value(false))
                .andExpect(jsonPath("$.players[1].ready").value(false));
    }

    @Test
    void setReady_whenQuizNotSelected_returnsConflict() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + code + "/ready")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ready\":true}"))
                .andExpect(status().isConflict());
    }

    @Test
    void setReady_whenLobbyNotFull_returnsConflict() throws Exception {
        String ownerSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        Long quizId = firstQuizId();
        mockMvc.perform(post("/api/lobbies/" + code + "/selected-quiz")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/lobbies/" + code + "/ready")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ready\":true}"))
                .andExpect(status().isConflict());
    }

    @Test
    void setReady_whenAllReadyAndQuizSelected_startsGameAutomatically() throws Exception {
        String ownerSessionId = createGuestSession();
        String secondSessionId = createGuestSession();

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", secondSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        Long quizId = firstQuizId();
        mockMvc.perform(post("/api/lobbies/" + code + "/selected-quiz")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quizId\":" + quizId + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/lobbies/" + code + "/ready")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ready\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        mockMvc.perform(post("/api/lobbies/" + code + "/ready")
                        .cookie(new Cookie("guestSessionId", secondSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ready\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_GAME"));

        mockMvc.perform(get("/api/lobbies/" + code + "/game/state")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("PRE_COUNTDOWN"));
    }

    @Test
    void ownerTransferToGuest_capsLargeLobbyAndShrinksAsPlayersLeave() throws Exception {
        Cookie access = registerAndGetAccessCookie();
        String ownerSessionId = createGuestSession(access);

        MvcResult created = mockMvc.perform(post("/api/lobbies")
                        .cookie(new Cookie("guestSessionId", ownerSessionId))
                        .cookie(access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxPlayers\":5}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = jsonValue(created.getResponse().getContentAsString(), "\"code\":\"", "\"").orElseThrow();

        String s2 = createGuestSession();
        String s3 = createGuestSession();
        String s4 = createGuestSession();
        String s5 = createGuestSession();

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", s2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", s3))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", s4))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // Owner leaves: guest owner takes over, limit should be clamped to current players (3).
        mockMvc.perform(post("/api/lobbies/" + code + "/leave")
                        .cookie(new Cookie("guestSessionId", ownerSessionId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/lobbies/" + code)
                        .cookie(new Cookie("guestSessionId", s2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isOwner").value(true))
                .andExpect(jsonPath("$.maxPlayers").value(3))
                .andExpect(jsonPath("$.players.length()").value(3));

        // New player cannot join above clamped limit.
        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", s5))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());

        // As guests leave, cap should shrink down to 2 (guest maximum).
        mockMvc.perform(post("/api/lobbies/" + code + "/leave")
                        .cookie(new Cookie("guestSessionId", s3)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/lobbies/" + code)
                        .cookie(new Cookie("guestSessionId", s2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxPlayers").value(2))
                .andExpect(jsonPath("$.players.length()").value(2));

        mockMvc.perform(post("/api/lobbies/" + code + "/join")
                        .cookie(new Cookie("guestSessionId", s5))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    private Long participantIdByLobbyCodeAndSessionId(String lobbyCode, String guestSessionId) {
        Lobby lobby = lobbyRepository.findByCode(lobbyCode).orElseThrow();
        return participantRepository.findByLobbyIdAndGuestSessionId(lobby.getId(), guestSessionId)
                .map(LobbyParticipant::getId)
                .orElseThrow();
    }

    private String createGuestSession() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/guest/session"))
                .andExpect(status().isCreated())
                .andReturn();
        String setCookie = res.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        return cookieValueFromSetCookie(setCookie, "guestSessionId").orElseThrow();
    }

    private String createGuestSession(Cookie accessCookie) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/guest/session")
                        .cookie(accessCookie))
                .andExpect(status().isCreated())
                .andReturn();
        String setCookie = res.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        return cookieValueFromSetCookie(setCookie, "guestSessionId").orElseThrow();
    }

    private Cookie registerAndGetAccessCookie() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        String body = """
                {
                  "email": "%s",
                  "displayName": "User",
                  "password": "Password123"
                }
                """.formatted(email);

        MvcResult res = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String setCookie = String.join("\n", res.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
        String access = cookieValueFromSetCookie(setCookie, "accessToken").orElseThrow();
        return new Cookie("accessToken", access);
    }

    private Long firstQuizId() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/quizzes").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        String body = list.getResponse().getContentAsString();
        int idx = body.indexOf("\"id\":");
        if (idx < 0) throw new IllegalStateException("No quiz id in response");
        int start = idx + "\"id\":".length();
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        int end = start;
        while (end < body.length() && Character.isDigit(body.charAt(end))) end++;
        return Long.parseLong(body.substring(start, end));
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
