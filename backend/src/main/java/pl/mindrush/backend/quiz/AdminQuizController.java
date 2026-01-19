package pl.mindrush.backend.quiz;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    @GetMapping
    public ResponseEntity<List<AdminQuizListItemDto>> list() {
        return ResponseEntity.ok(
                adminService.listQuizzes().stream()
                        .map(q -> new AdminQuizListItemDto(q.id(), q.title(), q.description(), q.categoryName(), q.questionCount()))
                        .toList()
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminQuizDetailDto> get(@PathVariable("id") Long quizId) {
        QuizAdminService.AdminQuizDetail quiz = adminService.getQuiz(quizId);
        return ResponseEntity.ok(toDetailDto(quiz));
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

    @PutMapping("/{id}")
    public ResponseEntity<QuizAdminDto> update(@PathVariable("id") Long quizId, @Valid @RequestBody CreateQuizRequest req) {
        Quiz quiz = adminService.updateQuiz(quizId, req.title(), req.description(), req.categoryName());
        return ResponseEntity.ok(new QuizAdminDto(
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
        List<QuizAdminService.AnswerOptionInput> options = req.options() == null
                ? null
                : req.options().stream()
                .map(o -> new QuizAdminService.AnswerOptionInput(o.text(), o.correct()))
                .toList();
        QuizQuestion q = adminService.addQuestion(quizId, req.prompt(), options);
        return ResponseEntity.status(CREATED).body(new QuizQuestionAdminDto(q.getId(), q.getOrderIndex(), q.getPrompt()));
    }

    @PutMapping("/{id}/questions/{questionId}")
    public ResponseEntity<Void> updateQuestion(
            @PathVariable("id") Long quizId,
            @PathVariable("questionId") Long questionId,
            @Valid @RequestBody UpdateQuestionRequest req
    ) {
        List<QuizAdminService.AnswerOptionUpdateInput> options = req.options() == null
                ? null
                : req.options().stream()
                .map(o -> new QuizAdminService.AnswerOptionUpdateInput(o.id(), o.text(), o.correct()))
                .toList();
        adminService.updateQuestion(quizId, questionId, req.prompt(), options);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/questions/{questionId}")
    public ResponseEntity<Void> deleteQuestion(
            @PathVariable("id") Long quizId,
            @PathVariable("questionId") Long questionId
    ) {
        adminService.deleteQuestion(quizId, questionId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteQuiz(@PathVariable("id") Long quizId) {
        adminService.deleteQuiz(quizId);
        return ResponseEntity.noContent().build();
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

    public record UpdateQuestionRequest(
            @NotBlank @Size(max = 500) String prompt,
            List<AnswerOptionUpdateRequest> options
    ) {}

    public record AnswerOptionUpdateRequest(
            Long id,
            @NotBlank @Size(max = 200) String text,
            boolean correct
    ) {}

    public record QuizAdminDto(Long id, String title, String description, String categoryName) {}

    public record QuizQuestionAdminDto(Long id, int orderIndex, String prompt) {}

    public record AdminQuizListItemDto(
            Long id,
            String title,
            String description,
            String categoryName,
            long questionCount
    ) {}

    public record AdminQuizDetailDto(
            Long id,
            String title,
            String description,
            String categoryName,
            List<AdminQuestionDto> questions
    ) {}

    public record AdminQuestionDto(
            Long id,
            int orderIndex,
            String prompt,
            List<AdminAnswerOptionDto> options
    ) {}

    public record AdminAnswerOptionDto(
            Long id,
            int orderIndex,
            String text,
            boolean correct
    ) {}

    private static AdminQuizDetailDto toDetailDto(QuizAdminService.AdminQuizDetail quiz) {
        return new AdminQuizDetailDto(
                quiz.id(),
                quiz.title(),
                quiz.description(),
                quiz.categoryName(),
                quiz.questions().stream()
                        .map(q -> new AdminQuestionDto(
                                q.id(),
                                q.orderIndex(),
                                q.prompt(),
                                q.options().stream()
                                        .map(o -> new AdminAnswerOptionDto(o.id(), o.orderIndex(), o.text(), o.correct()))
                                        .toList()
                        ))
                        .toList()
        );
    }
}
