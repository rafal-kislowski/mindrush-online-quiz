package pl.mindrush.backend.quiz;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.mindrush.backend.JwtCookieAuthenticationFilter;
import pl.mindrush.backend.quiz.dto.QuizDetailDto;
import pl.mindrush.backend.quiz.dto.QuizListItemDto;
import pl.mindrush.backend.quiz.dto.QuizQuestionDto;

import java.util.List;

@RestController
@RequestMapping("/api/quizzes")
public class QuizController {

    private final QuizService quizService;

    public QuizController(QuizService quizService) {
        this.quizService = quizService;
    }

    @GetMapping
    public ResponseEntity<List<QuizListItemDto>> listQuizzes(Authentication authentication) {
        Long viewerUserId = null;
        if (authentication != null && authentication.getPrincipal() instanceof JwtCookieAuthenticationFilter.AuthenticatedUser user) {
            viewerUserId = user.id();
        }
        return ResponseEntity.ok(quizService.listQuizzes(viewerUserId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<QuizDetailDto> getQuiz(@PathVariable("id") Long quizId) {
        return ResponseEntity.ok(quizService.getQuiz(quizId));
    }

    @GetMapping("/{id}/questions")
    public ResponseEntity<List<QuizQuestionDto>> getQuizQuestions(@PathVariable("id") Long quizId) {
        return ResponseEntity.ok(quizService.getQuizQuestions(quizId));
    }
}
