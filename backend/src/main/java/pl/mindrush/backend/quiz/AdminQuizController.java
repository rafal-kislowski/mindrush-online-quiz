package pl.mindrush.backend.quiz;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.http.HttpStatus.CREATED;

@RestController
@RequestMapping("/api/admin/quizzes")
public class AdminQuizController {

    private final QuizAdminService adminService;

    public AdminQuizController(QuizAdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping
    public ResponseEntity<QuizAdminDto> create(@Valid @RequestBody CreateQuizRequest req) {
        Quiz quiz = adminService.createQuiz(req.title(), req.description(), req.categoryName());
        return ResponseEntity.status(CREATED).body(new QuizAdminDto(
                quiz.getId(),
                quiz.getTitle(),
                quiz.getDescription(),
                quiz.getCategory() == null ? null : quiz.getCategory().getName()
        ));
    }

    @PostMapping("/{id}/questions")
    public ResponseEntity<QuizQuestionAdminDto> addQuestion(
            @PathVariable("id") Long quizId,
            @Valid @RequestBody AddQuestionRequest req
    ) {
        List<QuizAdminService.AnswerOptionInput> options = req.options().stream()
                .map(o -> new QuizAdminService.AnswerOptionInput(o.text(), o.correct()))
                .toList();
        QuizQuestion q = adminService.addQuestion(quizId, req.prompt(), options);
        return ResponseEntity.status(CREATED).body(new QuizQuestionAdminDto(q.getId(), q.getOrderIndex(), q.getPrompt()));
    }

    public record CreateQuizRequest(
            @NotBlank @Size(max = 120) String title,
            @Size(max = 500) String description,
            @Size(max = 64) String categoryName
    ) {}

    public record AddQuestionRequest(
            @NotBlank @Size(max = 500) String prompt,
            List<AnswerOptionRequest> options
    ) {}

    public record AnswerOptionRequest(
            @NotBlank @Size(max = 200) String text,
            boolean correct
    ) {}

    public record QuizAdminDto(Long id, String title, String description, String categoryName) {}

    public record QuizQuestionAdminDto(Long id, int orderIndex, String prompt) {}
}

