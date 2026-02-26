package pl.mindrush.backend.game;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.mindrush.backend.game.dto.ActiveGameDto;

@RestController
@RequestMapping("/api/games")
public class ActiveGameController {

    private final GameService gameService;

    public ActiveGameController(GameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping("/current")
    public ResponseEntity<ActiveGameDto> current(HttpServletRequest request) {
        return gameService.findCurrentActiveGame(request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
