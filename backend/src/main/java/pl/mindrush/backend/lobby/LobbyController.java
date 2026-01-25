package pl.mindrush.backend.lobby;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

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
            @RequestBody(required = false) Map<String, Object> body
    ) {
        String password = body == null ? null : asNullableString(body.get("password"));
        Integer maxPlayers = body == null ? null : asNullableInteger(body.get("maxPlayers"));
        return ResponseEntity.status(201).body(lobbyService.createLobby(request, password, maxPlayers, isAuthenticated(authentication)));
    }

    @GetMapping("/{code}")
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request, @PathVariable String code) {
        return ResponseEntity.ok(lobbyService.getLobby(request, code));
    }

    @PostMapping("/{code}/join")
    public ResponseEntity<Map<String, Object>> join(HttpServletRequest request, @PathVariable String code, @RequestBody(required = false) Map<String, Object> body) {
        String password = body == null ? null : asNullableString(body.get("password"));
        return ResponseEntity.ok(lobbyService.joinLobby(request, code, password));
    }

    @PostMapping("/{code}/password")
    public ResponseEntity<Map<String, Object>> setPassword(HttpServletRequest request, @PathVariable String code, @RequestBody(required = false) Map<String, Object> body) {
        String password = body == null ? null : asNullableString(body.get("password"));
        return ResponseEntity.ok(lobbyService.setLobbyPassword(request, code, password));
    }

    @PostMapping("/{code}/max-players")
    public ResponseEntity<Map<String, Object>> setMaxPlayers(
            HttpServletRequest request,
            Authentication authentication,
            @PathVariable String code,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        Integer maxPlayers = body == null ? null : asNullableInteger(body.get("maxPlayers"));
        if (maxPlayers == null) {
            throw new ResponseStatusException(BAD_REQUEST, "maxPlayers is required");
        }
        return ResponseEntity.ok(lobbyService.setLobbyMaxPlayers(request, code, maxPlayers, isAuthenticated(authentication)));
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

    private static String asNullableString(Object value) {
        if (value == null) return null;
        String s = value.toString().trim();
        return s.isBlank() ? null : s;
    }

    private static Integer asNullableInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        String s = value.toString().trim();
        if (s.isBlank()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid maxPlayers value");
        }
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}

