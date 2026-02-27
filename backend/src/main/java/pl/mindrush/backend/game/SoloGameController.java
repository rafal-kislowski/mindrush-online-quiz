package pl.mindrush.backend.game;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.mindrush.backend.game.dto.AnswerRequest;
import pl.mindrush.backend.game.dto.GameStateDto;
import pl.mindrush.backend.game.dto.StartGameRequest;

@RestController
@RequestMapping("/api/solo-games")
public class SoloGameController {

    private final GameService gameService;

    public SoloGameController(GameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping("/start")
    public ResponseEntity<GameStateDto> start(
            HttpServletRequest request,
            @Valid @RequestBody StartGameRequest body
    ) {
        return ResponseEntity.status(201).body(gameService.startSoloGame(request, body.quizId(), body.mode(), body.ranked()));
    }

    @GetMapping("/{gameSessionId}/state")
    public ResponseEntity<GameStateDto> state(
            HttpServletRequest request,
            @PathVariable("gameSessionId") String gameSessionId
    ) {
        return ResponseEntity.ok(gameService.getSoloState(request, gameSessionId));
    }

    @PostMapping("/{gameSessionId}/answer")
    public ResponseEntity<GameStateDto> answer(
            HttpServletRequest request,
            @PathVariable("gameSessionId") String gameSessionId,
            @Valid @RequestBody AnswerRequest body
    ) {
        return ResponseEntity.ok(gameService.submitSoloAnswer(request, gameSessionId, body.questionId(), body.optionId()));
    }

    @PostMapping("/{gameSessionId}/end")
    public ResponseEntity<GameStateDto> end(
            HttpServletRequest request,
            @PathVariable("gameSessionId") String gameSessionId
    ) {
        return ResponseEntity.ok(gameService.endSoloGame(request, gameSessionId));
    }
}
