package pl.mindrush.backend.lobby;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/lobbies")
public class LobbyController {

    private final LobbyService lobbyService;

    public LobbyController(LobbyService lobbyService) {
        this.lobbyService = lobbyService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            HttpServletRequest request,
            Authentication authentication,
            @Valid @RequestBody(required = false) CreateLobbyRequest body
    ) {
        String password = body == null ? null : body.password();
        Integer maxPlayers = body == null ? null : body.maxPlayers();
        return ResponseEntity.status(201).body(lobbyService.createLobby(request, password, maxPlayers, isAuthenticated(authentication)));
    }

    @GetMapping("/{code}")
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request, @PathVariable String code) {
        return ResponseEntity.ok(lobbyService.getLobby(request, code));
    }

    @PostMapping("/{code}/join")
    public ResponseEntity<Map<String, Object>> join(
            HttpServletRequest request,
            @PathVariable String code,
            @Valid @RequestBody(required = false) JoinLobbyRequest body
    ) {
        String password = body == null ? null : body.password();
        return ResponseEntity.ok(lobbyService.joinLobby(request, code, password));
    }

    @PostMapping("/{code}/password")
    public ResponseEntity<Map<String, Object>> setPassword(
            HttpServletRequest request,
            @PathVariable String code,
            @Valid @RequestBody(required = false) SetPasswordRequest body
    ) {
        String password = body == null ? null : body.password();
        return ResponseEntity.ok(lobbyService.setLobbyPassword(request, code, password));
    }

    @PostMapping("/{code}/max-players")
    public ResponseEntity<Map<String, Object>> setMaxPlayers(
            HttpServletRequest request,
            Authentication authentication,
            @PathVariable String code,
            @Valid @RequestBody SetMaxPlayersRequest body
    ) {
        Integer maxPlayers = body.maxPlayers();
        return ResponseEntity.ok(lobbyService.setLobbyMaxPlayers(request, code, maxPlayers, isAuthenticated(authentication)));
    }

    @PostMapping("/{code}/selected-quiz")
    public ResponseEntity<Map<String, Object>> setSelectedQuiz(
            HttpServletRequest request,
            @PathVariable String code,
            @Valid @RequestBody(required = false) SetSelectedQuizRequest body
    ) {
        Long quizId = body == null ? null : body.quizId();
        return ResponseEntity.ok(lobbyService.setSelectedQuiz(request, code, quizId));
    }

    @PostMapping("/{code}/leave")
    public ResponseEntity<?> leave(HttpServletRequest request, @PathVariable String code) {
        LobbyService.LeaveResult result = lobbyService.leaveLobby(request, code);
        if (result instanceof LobbyService.LeaveResult.Deleted) {
            return ResponseEntity.noContent().build();
        }
        LobbyService.LeaveResult.Updated updated = (LobbyService.LeaveResult.Updated) result;
        return ResponseEntity.ok(updated.lobby());
    }

    @PostMapping("/{code}/close")
    public ResponseEntity<Map<String, Object>> close(HttpServletRequest request, @PathVariable String code) {
        return ResponseEntity.ok(lobbyService.closeLobby(request, code));
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    public record CreateLobbyRequest(
            @Pattern(regexp = "^\\d{4}$", message = "PIN must be exactly 4 digits")
            String password,
            @Min(value = 2, message = "maxPlayers must be between 2 and 5")
            @Max(value = 5, message = "maxPlayers must be between 2 and 5")
            Integer maxPlayers
    ) {
    }

    public record JoinLobbyRequest(
            @Pattern(regexp = "^\\d{4}$", message = "PIN must be exactly 4 digits")
            String password
    ) {
    }

    public record SetPasswordRequest(
            @Pattern(regexp = "^\\d{4}$", message = "PIN must be exactly 4 digits")
            String password
    ) {
    }

    public record SetMaxPlayersRequest(
            @NotNull(message = "maxPlayers is required")
            @Min(value = 2, message = "maxPlayers must be between 2 and 5")
            @Max(value = 5, message = "maxPlayers must be between 2 and 5")
            Integer maxPlayers
    ) {
    }

    public record SetSelectedQuizRequest(
            @Positive(message = "quizId must be a positive number")
            Long quizId
    ) {
    }
}

