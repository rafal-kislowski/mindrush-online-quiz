package pl.mindrush.backend.lobby;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/lobbies")
public class LobbyController {

    private final LobbyService lobbyService;

    public LobbyController(LobbyService lobbyService) {
        this.lobbyService = lobbyService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
        String password = body == null ? null : asNullableString(body.get("password"));
        return ResponseEntity.status(201).body(lobbyService.createLobby(request, password));
    }

    @GetMapping("/{code}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String code) {
        return ResponseEntity.ok(lobbyService.getLobby(code));
    }

    @PostMapping("/{code}/join")
    public ResponseEntity<Map<String, Object>> join(HttpServletRequest request, @PathVariable String code, @RequestBody(required = false) Map<String, Object> body) {
        String password = body == null ? null : asNullableString(body.get("password"));
        return ResponseEntity.ok(lobbyService.joinLobby(request, code, password));
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
}

