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
@RequestMapping("/api/lobbies/{code}/game")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping("/start")
    public ResponseEntity<GameStateDto> start(
            HttpServletRequest request,
            @PathVariable("code") String code,
            @Valid @RequestBody StartGameRequest body
    ) {
        return ResponseEntity.status(201).body(gameService.startGame(request, code, body.quizId()));
    }

    @GetMapping("/state")
    public ResponseEntity<GameStateDto> state(HttpServletRequest request, @PathVariable("code") String code) {
        return ResponseEntity.ok(gameService.getState(request, code));
    }

    @PostMapping("/answer")
    public ResponseEntity<GameStateDto> answer(
            HttpServletRequest request,
            @PathVariable("code") String code,
            @Valid @RequestBody AnswerRequest body
    ) {
        return ResponseEntity.ok(gameService.submitAnswer(request, code, body.questionId(), body.optionId()));
    }

    @PostMapping("/end")
    public ResponseEntity<GameStateDto> end(HttpServletRequest request, @PathVariable("code") String code) {
        return ResponseEntity.ok(gameService.endGame(request, code));
    }
}
