package pl.mindrush.backend.lobby;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.guest.GuestSession;
import pl.mindrush.backend.guest.GuestSessionRepository;
import pl.mindrush.backend.guest.GuestSessionService;
import pl.mindrush.backend.game.GameService;
import pl.mindrush.backend.lobby.chat.LobbySystemMessageService;
import pl.mindrush.backend.lobby.events.LobbyEventPublisher;
import pl.mindrush.backend.quiz.Quiz;
import pl.mindrush.backend.quiz.QuizRepository;
import pl.mindrush.backend.quiz.QuizStatus;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class LobbyService {

    public static final int GUEST_LOBBY_MAX_PLAYERS = 2;
    public static final int AUTH_LOBBY_MIN_PLAYERS = 2;
    public static final int AUTH_LOBBY_MAX_PLAYERS = 5;
    private static final List<LobbyStatus> ACTIVE_OWNED_STATUSES = List.of(LobbyStatus.OPEN, LobbyStatus.IN_GAME);
    private static final List<LobbyStatus> ACTIVE_PARTICIPATION_STATUSES = List.of(LobbyStatus.OPEN, LobbyStatus.IN_GAME);

    private static final char[] CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final int CODE_LENGTH = 6;

    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final GuestSessionService guestSessionService;
    private final GuestSessionRepository guestSessionRepository;
    private final LobbyRepository lobbyRepository;
    private final LobbyParticipantRepository participantRepository;
    private final LobbyBanRepository lobbyBanRepository;
    private final LobbySummaryMapper lobbySummaryMapper;
    private final LobbyEventPublisher lobbyEventPublisher;
    private final QuizRepository quizRepository;
    private final GameService gameService;
    private final LobbySystemMessageService lobbySystemMessageService;

    public LobbyService(
            GuestSessionService guestSessionService,
            GuestSessionRepository guestSessionRepository,
            LobbyRepository lobbyRepository,
            LobbyParticipantRepository participantRepository,
            LobbyBanRepository lobbyBanRepository,
            LobbySummaryMapper lobbySummaryMapper,
            LobbyEventPublisher lobbyEventPublisher,
            QuizRepository quizRepository,
            GameService gameService,
            LobbySystemMessageService lobbySystemMessageService
    ) {
        this.guestSessionService = guestSessionService;
        this.guestSessionRepository = guestSessionRepository;
        this.lobbyRepository = lobbyRepository;
        this.participantRepository = participantRepository;
        this.lobbyBanRepository = lobbyBanRepository;
        this.lobbySummaryMapper = lobbySummaryMapper;
        this.lobbyEventPublisher = lobbyEventPublisher;
        this.quizRepository = quizRepository;
        this.gameService = gameService;
        this.lobbySystemMessageService = lobbySystemMessageService;
    }

    public Map<String, Object> createLobby(
            HttpServletRequest request,
            String rawPassword,
            Integer requestedMaxPlayers,
            boolean authenticatedUser
    ) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Optional<Lobby> existingJoinedLobby = findActiveLobbyForGuestSession(guestSession.getId());
        if (existingJoinedLobby.isPresent()) {
            Lobby lobby = existingJoinedLobby.get();
            boolean shouldBeAuthenticatedOwner = authenticatedUser || guestSession.getUserId() != null;
            if (guestSession.getId().equals(lobby.getOwnerGuestSessionId())
                    && lobby.isOwnerAuthenticated() != shouldBeAuthenticatedOwner) {
                lobby.setOwnerAuthenticated(shouldBeAuthenticatedOwner);
                lobbyRepository.save(lobby);
                lobbyEventPublisher.lobbyUpdated(lobby.getCode());
            }
            return lobbySummary(lobby, guestSession.getId());
        }

        Instant now = Instant.now();

        String code = generateUniqueCode();
        String trimmedPassword = rawPassword == null ? null : rawPassword.trim();
        String pinCode = normalizePin(trimmedPassword);
        if (trimmedPassword != null && !trimmedPassword.isBlank() && pinCode == null) {
            throw new ResponseStatusException(BAD_REQUEST, "PIN must be exactly 4 digits");
        }
        String passwordHash = pinCode == null ? null : passwordEncoder.encode(pinCode);

        int maxPlayers = resolveMaxPlayers(requestedMaxPlayers, authenticatedUser);
        boolean ownerAuthenticated = authenticatedUser || guestSession.getUserId() != null;
        Lobby lobby = Lobby.createNew(
                code,
                guestSession.getId(),
                ownerAuthenticated,
                maxPlayers,
                passwordHash,
                pinCode,
                now
        );
        lobbyRepository.save(lobby);

        String displayName = uniqueDisplayNameForLobby(lobby.getId(), guestSession.getDisplayName());
        participantRepository.save(LobbyParticipant.createGuest(lobby, guestSession.getId(), displayName, now));
        lobbySystemMessageService.publish(lobby.getCode(), displayName + " created the lobby.");

        lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        return lobbySummary(lobby, guestSession.getId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOwnedOpenLobby(HttpServletRequest request) {
        String viewerGuestSessionId = guestSessionService.findValidSession(request)
                .map(GuestSession::getId)
                .orElse(null);
        if (viewerGuestSessionId == null) return null;

        Lobby lobby = lobbyRepository
                .findFirstByOwnerGuestSessionIdAndStatusInOrderByCreatedAtDesc(viewerGuestSessionId, ACTIVE_OWNED_STATUSES)
                .orElse(null);
        if (lobby == null) return null;
        return lobbySummary(lobby, viewerGuestSessionId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findCurrentLobby(HttpServletRequest request) {
        String viewerGuestSessionId = guestSessionService.findValidSession(request)
                .map(GuestSession::getId)
                .orElse(null);
        if (viewerGuestSessionId == null) return null;

        Lobby lobby = findActiveLobbyForGuestSession(viewerGuestSessionId).orElse(null);
        if (lobby == null) return null;
        return lobbySummary(lobby, viewerGuestSessionId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listActiveLobbies(HttpServletRequest request) {
        String viewerGuestSessionId = guestSessionService.findValidSession(request)
                .map(GuestSession::getId)
                .orElse(null);

        List<Lobby> lobbies = lobbyRepository.findAllByStatusOrderByCreatedAtDesc(LobbyStatus.OPEN);
        if (lobbies.isEmpty()) return List.of();

        List<String> lobbyIds = lobbies.stream().map(Lobby::getId).toList();
        List<LobbyParticipant> participants = participantRepository.findAllByLobbyIdInOrderByLobbyIdAscJoinedAtAsc(lobbyIds);

        Map<String, List<LobbyParticipant>> participantsByLobbyId = new HashMap<>();
        for (LobbyParticipant participant : participants) {
            String lobbyId = participant.getLobby().getId();
            participantsByLobbyId.computeIfAbsent(lobbyId, __ -> new ArrayList<>()).add(participant);
        }

        Set<String> ownerGuestSessionIds = new HashSet<>();
        for (Lobby lobby : lobbies) {
            ownerGuestSessionIds.add(lobby.getOwnerGuestSessionId());
        }
        Map<String, GuestSession> ownerSessionById = new HashMap<>();
        for (GuestSession session : guestSessionRepository.findAllById(ownerGuestSessionIds)) {
            ownerSessionById.put(session.getId(), session);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Lobby lobby : lobbies) {
            List<LobbyParticipant> lobbyParticipants = participantsByLobbyId.getOrDefault(lobby.getId(), List.of());
            if (lobbyParticipants.isEmpty()) continue;

            boolean isOwner = viewerGuestSessionId != null && viewerGuestSessionId.equals(lobby.getOwnerGuestSessionId());
            boolean isParticipant = viewerGuestSessionId != null
                    && lobbyParticipants.stream().anyMatch(p -> viewerGuestSessionId.equals(p.getGuestSessionId()));

            String leaderDisplayName = lobbyParticipants.stream()
                    .filter(p -> lobby.getOwnerGuestSessionId().equals(p.getGuestSessionId()))
                    .map(LobbyParticipant::getDisplayName)
                    .findFirst()
                    .orElse(lobbyParticipants.get(0).getDisplayName());

            GuestSession ownerSession = ownerSessionById.get(lobby.getOwnerGuestSessionId());
            String ownerType = lobby.isOwnerAuthenticated()
                    || (ownerSession != null && ownerSession.getUserId() != null)
                    ? "AUTHENTICATED"
                    : "GUEST";

            Map<String, Object> row = new HashMap<>();
            row.put("code", lobby.getCode());
            row.put("status", lobby.getStatus().name());
            row.put("createdAt", lobby.getCreatedAt().toString());
            row.put("hasPassword", lobby.hasPassword());
            row.put("maxPlayers", lobby.getMaxPlayers());
            row.put("playerCount", lobbyParticipants.size());
            row.put("leaderDisplayName", leaderDisplayName);
            row.put("ownerType", ownerType);
            row.put("isOwner", isOwner);
            row.put("isParticipant", isParticipant);
            rows.add(row);
        }

        return rows;
    }

    public Map<String, Object> setLobbyMaxPlayers(
            HttpServletRequest request,
            String code,
            int maxPlayers,
            boolean authenticatedUser
    ) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));

        if (!guestSession.getId().equals(lobby.getOwnerGuestSessionId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the lobby owner can change lobby settings");
        }
        if (lobby.getStatus() != LobbyStatus.OPEN) {
            throw new ResponseStatusException(CONFLICT, "Lobby is not open");
        }

        if (!authenticatedUser && maxPlayers > GUEST_LOBBY_MAX_PLAYERS) {
            throw new ResponseStatusException(FORBIDDEN, "Login required for lobbies larger than 2 players");
        }
        if (maxPlayers < AUTH_LOBBY_MIN_PLAYERS || maxPlayers > AUTH_LOBBY_MAX_PLAYERS) {
            throw new ResponseStatusException(BAD_REQUEST, "maxPlayers must be between 2 and 5");
        }

        long current = participantRepository.countByLobbyId(lobby.getId());
        if (maxPlayers < current) {
            throw new ResponseStatusException(CONFLICT, "maxPlayers cannot be lower than current player count");
        }

        if (lobby.getMaxPlayers() != maxPlayers) {
            lobby.setMaxPlayers(maxPlayers);
            lobbyRepository.save(lobby);
            lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        }

        return lobbySummary(lobby, guestSession.getId());
    }

    public Map<String, Object> getLobby(HttpServletRequest request, String code) {
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));
        String viewerId = guestSessionService.findValidSession(request).map(GuestSession::getId).orElse(null);
        return lobbySummary(lobby, viewerId);
    }

    public Map<String, Object> joinLobby(HttpServletRequest request, String code, String rawPassword) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));
        boolean isOwner = guestSession.getId().equals(lobby.getOwnerGuestSessionId());

        Optional<Lobby> existingJoinedLobby = findActiveLobbyForGuestSession(guestSession.getId());
        if (existingJoinedLobby.isPresent() && !existingJoinedLobby.get().getId().equals(lobby.getId())) {
            throw new ResponseStatusException(CONFLICT, "You are already in active lobby " + existingJoinedLobby.get().getCode());
        }

        if (lobby.getStatus() != LobbyStatus.OPEN) {
            throw new ResponseStatusException(CONFLICT, "Lobby is closed");
        }

        if (participantRepository.existsByLobbyIdAndGuestSessionId(lobby.getId(), guestSession.getId())) {
            return lobbySummary(lobby, guestSession.getId());
        }

        if (isBannedFromLobby(lobby.getId(), guestSession)) {
            throw new ResponseStatusException(FORBIDDEN, "You are banned from this lobby");
        }

        if (lobby.hasPassword()) {
            String trimmedPassword = rawPassword == null ? null : rawPassword.trim();
            boolean passwordOk = isOwner || (trimmedPassword != null && !trimmedPassword.isBlank() && passwordEncoder.matches(trimmedPassword, lobby.getPasswordHash()));
            if (!passwordOk) {
                throw new ResponseStatusException(FORBIDDEN, "Invalid lobby password");
            }

            // Backfill PIN for older lobbies (or in case pinCode was missing) once a valid PIN is provided.
            if (lobby.getPinCode() == null && trimmedPassword != null) {
                String normalizedPin = normalizePin(trimmedPassword);
                if (normalizedPin != null) {
                    lobby.setPinCode(normalizedPin);
                    lobbyRepository.save(lobby);
                }
            }
        }

        long count = participantRepository.countByLobbyId(lobby.getId());
        if (count >= lobby.getMaxPlayers()) {
            throw new ResponseStatusException(CONFLICT, "Lobby is full");
        }

        Instant now = Instant.now();
        if (count == 0 && lobby.getEmptySince() != null) {
            boolean lobbyChanged = false;
            lobby.setEmptySince(null);
            lobbyChanged = true;
            if (!isOwner) {
                lobby.setOwnerGuestSessionId(guestSession.getId());
                lobby.setOwnerAuthenticated(guestSession.getUserId() != null);
                lobbyChanged = true;
            }
            if (enforceGuestOwnerCapacity(lobby, count)) {
                lobbyChanged = true;
            }
            if (lobbyChanged) {
                lobbyRepository.save(lobby);
            }
        }
        String displayName = uniqueDisplayNameForLobby(lobby.getId(), guestSession.getDisplayName());
        participantRepository.save(LobbyParticipant.createGuest(lobby, guestSession.getId(), displayName, now));

        lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        return lobbySummary(lobby, guestSession.getId());
    }

    public Map<String, Object> setSelectedQuiz(HttpServletRequest request, String code, Long quizId) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));

        if (!guestSession.getId().equals(lobby.getOwnerGuestSessionId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the lobby owner can change lobby settings");
        }
        if (lobby.getStatus() != LobbyStatus.OPEN) {
            throw new ResponseStatusException(CONFLICT, "Lobby is not open");
        }

        Long normalizedQuizId = quizId;
        String selectedCategoryLabel = null;
        if (normalizedQuizId != null) {
            Quiz quiz = quizRepository.findById(normalizedQuizId)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));
            if (quiz.getStatus() != QuizStatus.ACTIVE) {
                throw new ResponseStatusException(NOT_FOUND, "Quiz not found");
            }
            selectedCategoryLabel = quiz.getCategory() == null || quiz.getCategory().getName() == null || quiz.getCategory().getName().isBlank()
                    ? "Uncategorized"
                    : quiz.getCategory().getName().trim();
        }

        if (normalizedQuizId == null && lobby.getSelectedQuizId() == null) {
            return lobbySummary(lobby, guestSession.getId());
        }
        if (normalizedQuizId != null && normalizedQuizId.equals(lobby.getSelectedQuizId())) {
            return lobbySummary(lobby, guestSession.getId());
        }

        lobby.setSelectedQuizId(normalizedQuizId);
        lobbyRepository.save(lobby);
        participantRepository.clearReadyByLobbyId(lobby.getId());
        String ownerName = resolveParticipantDisplayName(lobby.getId(), guestSession.getId(), "Owner");
        String categoryText = normalizedQuizId == null ? "none" : selectedCategoryLabel;
        lobbySystemMessageService.publish(lobby.getCode(), ownerName + " set quiz category to " + categoryText + ".");
        lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        maybeStartGameWhenAllPlayersReady(lobby);
        return lobbySummary(lobby, guestSession.getId());
    }

    public Map<String, Object> setPlayerReady(HttpServletRequest request, String code, boolean ready) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));

        if (lobby.getStatus() != LobbyStatus.OPEN) {
            throw new ResponseStatusException(CONFLICT, "Lobby is not open");
        }
        if (ready && lobby.getSelectedQuizId() == null) {
            throw new ResponseStatusException(CONFLICT, "Select quiz before marking ready");
        }

        LobbyParticipant participant = participantRepository.findByLobbyIdAndGuestSessionId(lobby.getId(), guestSession.getId())
                .orElseThrow(() -> new ResponseStatusException(FORBIDDEN, "Only lobby participants can change readiness"));

        if (ready) {
            long playersCount = participantRepository.countByLobbyId(lobby.getId());
            if (playersCount < lobby.getMaxPlayers()) {
                throw new ResponseStatusException(CONFLICT, "Readiness is available only when lobby is full");
            }
        }

        if (participant.isReady() != ready) {
            participant.setReady(ready);
            participantRepository.save(participant);
            lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        }

        if (ready) {
            maybeStartGameWhenAllPlayersReady(lobby);
        }

        return lobbySummary(lobby, guestSession.getId());
    }

    public Map<String, Object> setLobbyPassword(HttpServletRequest request, String code, String rawPassword) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));

        if (!guestSession.getId().equals(lobby.getOwnerGuestSessionId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the lobby owner can change privacy settings");
        }
        if (lobby.getStatus() != LobbyStatus.OPEN) {
            throw new ResponseStatusException(CONFLICT, "Lobby is not open");
        }

        String trimmedPassword = rawPassword == null ? null : rawPassword.trim();
        String pinCode = normalizePin(trimmedPassword);
        if (trimmedPassword != null && !trimmedPassword.isBlank() && pinCode == null) {
            throw new ResponseStatusException(BAD_REQUEST, "PIN must be exactly 4 digits");
        }

        String passwordHash = pinCode == null ? null : passwordEncoder.encode(pinCode);
        lobby.setPasswordHash(passwordHash);
        lobby.setPinCode(pinCode);
        lobbyRepository.save(lobby);

        lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        return lobbySummary(lobby, guestSession.getId());
    }

    public LeaveResult leaveLobby(HttpServletRequest request, String code) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));
        String leavingName = resolveParticipantDisplayName(lobby.getId(), guestSession.getId(), normalizeDisplayName(guestSession.getDisplayName(), guestSession.getId()));

        if (lobby.getStatus() == LobbyStatus.IN_GAME) {
            throw new ResponseStatusException(CONFLICT, "Cannot leave while game is in progress");
        }

        long deleted = participantRepository.deleteByLobbyIdAndGuestSessionId(lobby.getId(), guestSession.getId());
        if (deleted > 0) {
            lobbySystemMessageService.publish(lobby.getCode(), leavingName + " left the lobby.");
        }

        long remaining = participantRepository.countByLobbyId(lobby.getId());
        if (remaining == 0) {
            lobby.setEmptySince(Instant.now());
            enforceGuestOwnerCapacity(lobby, 0);
            lobbyRepository.save(lobby);
            lobbyEventPublisher.lobbyUpdated(lobby.getCode());
            return LeaveResult.deleted();
        }

        boolean lobbyChanged = false;
        if (guestSession.getId().equals(lobby.getOwnerGuestSessionId())) {
            LobbyParticipant newOwner = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId()).stream()
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(CONFLICT, "Cannot transfer lobby ownership"));

            lobby.setOwnerGuestSessionId(newOwner.getGuestSessionId());
            boolean ownerAuthenticated = guestSessionRepository.findById(newOwner.getGuestSessionId())
                    .map(s -> s.getUserId() != null)
                    .orElse(false);
            lobby.setOwnerAuthenticated(ownerAuthenticated);
            lobbySystemMessageService.publish(lobby.getCode(), "Lobby owner changed to " + newOwner.getDisplayName() + ".");
            lobbyChanged = true;
        }
        if (enforceGuestOwnerCapacity(lobby, remaining)) {
            lobbyChanged = true;
        }
        if (lobbyChanged) {
            lobbyRepository.save(lobby);
        }

        lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        return LeaveResult.updated(lobbySummary(lobby, guestSession.getId()));
    }

    public Map<String, Object> kickParticipant(HttpServletRequest request, String code, Long participantId) {
        return removeParticipantByOwnerAction(request, code, participantId, false);
    }

    public Map<String, Object> banParticipant(HttpServletRequest request, String code, Long participantId) {
        return removeParticipantByOwnerAction(request, code, participantId, true);
    }

    private Map<String, Object> removeParticipantByOwnerAction(
            HttpServletRequest request,
            String code,
            Long participantId,
            boolean ban
    ) {
        GuestSession ownerSession = guestSessionService.requireValidSession(request);
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));

        if (!ownerSession.getId().equals(lobby.getOwnerGuestSessionId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the lobby owner can manage players");
        }
        if (lobby.getStatus() != LobbyStatus.OPEN) {
            throw new ResponseStatusException(CONFLICT, "Lobby is not open");
        }

        LobbyParticipant target = participantRepository.findByIdAndLobbyId(participantId, lobby.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Player not found"));
        String targetGuestSessionId = target.getGuestSessionId();
        String targetDisplayName = target.getDisplayName();
        if (targetGuestSessionId == null || targetGuestSessionId.isBlank()) {
            throw new ResponseStatusException(CONFLICT, "Player not found");
        }
        if (targetGuestSessionId.equals(lobby.getOwnerGuestSessionId())) {
            throw new ResponseStatusException(CONFLICT, "You cannot remove the lobby owner");
        }
        String ownerDisplayName = resolveParticipantDisplayName(lobby.getId(), ownerSession.getId(), "Owner");

        if (ban) {
            banLobbyParticipant(lobby.getId(), targetGuestSessionId);
        }

        participantRepository.delete(target);
        if (ban) {
            lobbySystemMessageService.publish(lobby.getCode(), targetDisplayName + " was banned by " + ownerDisplayName + ".");
        } else {
            lobbySystemMessageService.publish(lobby.getCode(), targetDisplayName + " was kicked by " + ownerDisplayName + ".");
        }
        lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        if (ban) {
            lobbyEventPublisher.participantBanned(lobby.getCode(), targetGuestSessionId);
        } else {
            lobbyEventPublisher.participantKicked(lobby.getCode(), targetGuestSessionId);
        }

        return lobbySummary(lobby, ownerSession.getId());
    }

    public Map<String, Object> closeLobby(HttpServletRequest request, String code) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));

        if (!guestSession.getId().equals(lobby.getOwnerGuestSessionId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the lobby owner can close the lobby");
        }

        if (lobby.getStatus() == LobbyStatus.IN_GAME) {
            throw new ResponseStatusException(CONFLICT, "Game is in progress");
        }

        if (lobby.getStatus() != LobbyStatus.CLOSED) {
            lobby.setStatus(LobbyStatus.CLOSED);
            lobbyRepository.save(lobby);
            lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        }

        return lobbySummary(lobby, guestSession.getId());
    }

    public void removeParticipantFromOpenLobbies(String guestSessionId) {
        if (guestSessionId == null || guestSessionId.isBlank()) return;

        List<LobbyParticipant> participations = participantRepository.findAllByGuestSessionIdIn(List.of(guestSessionId));
        if (participations.isEmpty()) return;

        for (LobbyParticipant participant : participations) {
            Lobby lobby = participant.getLobby();
            if (lobby == null || lobby.getCode() == null) continue;
            handleGuestDisconnected(guestSessionId, lobby.getCode());
        }
    }

    public void handleGuestDisconnected(String guestSessionId, String code) {
        if (guestSessionId == null || guestSessionId.isBlank()) return;

        Lobby lobby = lobbyRepository.findByCode(code).orElse(null);
        if (lobby == null) return;
        if (lobby.getStatus() != LobbyStatus.OPEN) return;
        String leavingName = resolveParticipantDisplayName(lobby.getId(), guestSessionId, null);

        long deleted = participantRepository.deleteByLobbyIdAndGuestSessionId(lobby.getId(), guestSessionId);
        if (deleted == 0) return;
        if (leavingName != null && !leavingName.isBlank()) {
            lobbySystemMessageService.publish(lobby.getCode(), leavingName + " left the lobby.");
        }

        long remaining = participantRepository.countByLobbyId(lobby.getId());
        if (remaining == 0) {
            lobby.setEmptySince(Instant.now());
            enforceGuestOwnerCapacity(lobby, 0);
            lobbyRepository.save(lobby);
            lobbyEventPublisher.lobbyUpdated(lobby.getCode());
            return;
        }

        boolean lobbyChanged = false;
        if (guestSessionId.equals(lobby.getOwnerGuestSessionId())) {
            LobbyParticipant newOwner = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId()).stream()
                    .findFirst()
                    .orElse(null);
            if (newOwner != null) {
                lobby.setOwnerGuestSessionId(newOwner.getGuestSessionId());
                boolean ownerAuthenticated = guestSessionRepository.findById(newOwner.getGuestSessionId())
                        .map(s -> s.getUserId() != null)
                        .orElse(false);
                lobby.setOwnerAuthenticated(ownerAuthenticated);
                lobbySystemMessageService.publish(lobby.getCode(), "Lobby owner changed to " + newOwner.getDisplayName() + ".");
                lobbyChanged = true;
            }
        }
        if (enforceGuestOwnerCapacity(lobby, remaining)) {
            lobbyChanged = true;
        }
        if (lobbyChanged) {
            lobbyRepository.save(lobby);
        }

        lobbyEventPublisher.lobbyUpdated(lobby.getCode());
    }

    private boolean isBannedFromLobby(String lobbyId, GuestSession guestSession) {
        if (lobbyId == null || lobbyId.isBlank() || guestSession == null) return false;

        if (lobbyBanRepository.existsByLobbyIdAndGuestSessionId(lobbyId, guestSession.getId())) {
            return true;
        }

        Long userId = guestSession.getUserId();
        return userId != null && lobbyBanRepository.existsByLobbyIdAndUserId(lobbyId, userId);
    }

    private void banLobbyParticipant(String lobbyId, String guestSessionId) {
        if (lobbyId == null || lobbyId.isBlank() || guestSessionId == null || guestSessionId.isBlank()) {
            return;
        }

        boolean sessionAlreadyBanned = lobbyBanRepository.existsByLobbyIdAndGuestSessionId(lobbyId, guestSessionId);
        Long userId = guestSessionRepository.findById(guestSessionId)
                .map(GuestSession::getUserId)
                .orElse(null);
        boolean userAlreadyBanned = userId != null && lobbyBanRepository.existsByLobbyIdAndUserId(lobbyId, userId);

        if (!sessionAlreadyBanned) {
            Long rowUserId = userAlreadyBanned ? null : userId;
            lobbyBanRepository.save(LobbyBan.create(lobbyId, guestSessionId, rowUserId, Instant.now()));
            return;
        }

        if (!userAlreadyBanned && userId != null) {
            lobbyBanRepository.save(LobbyBan.create(lobbyId, null, userId, Instant.now()));
        }
    }

    private Map<String, Object> lobbySummary(Lobby lobby, String viewerGuestSessionId) {
        return lobbySummaryMapper.summary(lobby, viewerGuestSessionId);
    }

    private String resolveParticipantDisplayName(String lobbyId, String guestSessionId, String fallback) {
        if (lobbyId == null || lobbyId.isBlank() || guestSessionId == null || guestSessionId.isBlank()) {
            return fallback;
        }
        return participantRepository.findByLobbyIdAndGuestSessionId(lobbyId, guestSessionId)
                .map(LobbyParticipant::getDisplayName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(fallback);
    }

    private Optional<Lobby> findActiveLobbyForGuestSession(String guestSessionId) {
        if (guestSessionId == null || guestSessionId.isBlank()) return Optional.empty();
        return participantRepository
                .findActiveParticipationsByGuestSessionId(guestSessionId, ACTIVE_PARTICIPATION_STATUSES)
                .stream()
                .map(LobbyParticipant::getLobby)
                .filter(l -> l != null)
                .findFirst();
    }

    private void maybeStartGameWhenAllPlayersReady(Lobby lobby) {
        if (lobby == null) return;
        if (lobby.getStatus() != LobbyStatus.OPEN) return;
        if (lobby.getSelectedQuizId() == null) return;

        gameService.tryStartGameFromReady(lobby.getCode());
    }

    private static String normalizePin(String rawPassword) {
        if (rawPassword == null) return null;
        String s = rawPassword.trim();
        if (!s.matches("^\\d{4}$")) return null;
        return s;
    }

    private static boolean enforceGuestOwnerCapacity(Lobby lobby, long participantsCount) {
        if (lobby.isOwnerAuthenticated()) return false;

        int currentMaxPlayers = lobby.getMaxPlayers();
        if (currentMaxPlayers <= GUEST_LOBBY_MAX_PLAYERS) return false;

        int cappedMaxPlayers = participantsCount > GUEST_LOBBY_MAX_PLAYERS
                ? (int) participantsCount
                : GUEST_LOBBY_MAX_PLAYERS;

        if (currentMaxPlayers == cappedMaxPlayers) return false;
        lobby.setMaxPlayers(cappedMaxPlayers);
        return true;
    }

    private String generateUniqueCode() {
        for (int i = 0; i < 20; i++) {
            String code = randomCode();
            if (!lobbyRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new ResponseStatusException(CONFLICT, "Failed to generate unique lobby code");
    }

    private static int resolveMaxPlayers(Integer requestedMaxPlayers, boolean authenticatedUser) {
        if (requestedMaxPlayers == null) return GUEST_LOBBY_MAX_PLAYERS;

        if (!authenticatedUser) {
            if (requestedMaxPlayers == GUEST_LOBBY_MAX_PLAYERS) return GUEST_LOBBY_MAX_PLAYERS;
            if (requestedMaxPlayers > GUEST_LOBBY_MAX_PLAYERS) {
                throw new ResponseStatusException(FORBIDDEN, "Login required for lobbies larger than 2 players");
            }
            throw new ResponseStatusException(BAD_REQUEST, "maxPlayers must be at least 2");
        }

        if (requestedMaxPlayers < AUTH_LOBBY_MIN_PLAYERS || requestedMaxPlayers > AUTH_LOBBY_MAX_PLAYERS) {
            throw new ResponseStatusException(BAD_REQUEST, "maxPlayers must be between 2 and 5");
        }
        return requestedMaxPlayers;
    }

    private String randomCode() {
        char[] buf = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            buf[i] = CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)];
        }
        return new String(buf);
    }

    private static String normalizeDisplayName(String displayName, String guestSessionId) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        String suffix = guestSessionId.length() >= 4 ? guestSessionId.substring(0, 4) : guestSessionId;
        return "Guest_" + suffix;
    }

    private String uniqueDisplayNameForLobby(String lobbyId, String preferredDisplayName) {
        String base = preferredDisplayName == null ? "" : preferredDisplayName.trim();
        if (base.isBlank()) {
            base = "Guest";
        }

        List<String> existing = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobbyId)
                .stream()
                .map(LobbyParticipant::getDisplayName)
                .toList();

        if (!existing.contains(base)) return base;
        for (int i = 2; i <= 99; i++) {
            String candidate = base + "-" + i;
            if (!existing.contains(candidate)) return candidate;
        }
        throw new ResponseStatusException(CONFLICT, "Nickname conflict");
    }

    public sealed interface LeaveResult permits LeaveResult.Deleted, LeaveResult.Updated {
        static LeaveResult deleted() {
            return new Deleted();
        }

        static LeaveResult updated(Map<String, Object> lobby) {
            return new Updated(lobby);
        }

        final class Deleted implements LeaveResult {
        }

        record Updated(Map<String, Object> lobby) implements LeaveResult {
        }
    }
}
