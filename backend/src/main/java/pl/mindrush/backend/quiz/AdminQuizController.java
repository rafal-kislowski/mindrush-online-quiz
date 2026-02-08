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
                        .map(q -> new AdminQuizListItemDto(
                                q.id(),
                                q.title(),
                                q.description(),
                                q.categoryName(),
                                q.avatarImageUrl(),
                                q.avatarBgStart(),
                                q.avatarBgEnd(),
                                q.avatarTextColor(),
                                q.gameMode(),
                                q.includeInRanking(),
                                q.xpEnabled(),
                                q.questionTimeLimitSeconds(),
                                q.status(),
                                q.questionCount()
                        ))
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
        Quiz quiz = adminService.createQuiz(
                req.title(),
                req.description(),
                req.categoryName(),
                req.avatarImageUrl(),
                req.avatarBgStart(),
                req.avatarBgEnd(),
                req.avatarTextColor(),
                req.gameMode(),
                req.includeInRanking(),
                req.xpEnabled(),
                req.questionTimeLimitSeconds()
        );
        return ResponseEntity.status(CREATED).body(new QuizAdminDto(
                quiz.getId(),
                quiz.getTitle(),
                quiz.getDescription(),
                quiz.getCategory() == null ? null : quiz.getCategory().getName(),
                quiz.getAvatarImageUrl(),
                quiz.getAvatarBgStart(),
                quiz.getAvatarBgEnd(),
                quiz.getAvatarTextColor(),
                quiz.getGameMode(),
                quiz.isIncludeInRanking(),
                quiz.isXpEnabled(),
                quiz.getQuestionTimeLimitSeconds(),
                quiz.getStatus()
        ));
    }

    @PutMapping("/{id}")
    public ResponseEntity<QuizAdminDto> update(@PathVariable("id") Long quizId, @Valid @RequestBody CreateQuizRequest req) {
        Quiz quiz = adminService.updateQuiz(
                quizId,
                req.title(),
                req.description(),
                req.categoryName(),
                req.avatarImageUrl(),
                req.avatarBgStart(),
                req.avatarBgEnd(),
                req.avatarTextColor(),
                req.gameMode(),
                req.includeInRanking(),
                req.xpEnabled(),
                req.questionTimeLimitSeconds()
        );
        return ResponseEntity.ok(new QuizAdminDto(
                quiz.getId(),
                quiz.getTitle(),
                quiz.getDescription(),
                quiz.getCategory() == null ? null : quiz.getCategory().getName(),
                quiz.getAvatarImageUrl(),
                quiz.getAvatarBgStart(),
                quiz.getAvatarBgEnd(),
                quiz.getAvatarTextColor(),
                quiz.getGameMode(),
                quiz.isIncludeInRanking(),
                quiz.isXpEnabled(),
                quiz.getQuestionTimeLimitSeconds(),
                quiz.getStatus()
        ));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<QuizAdminDto> setStatus(@PathVariable("id") Long quizId, @RequestBody StatusRequest req) {
        Quiz quiz = adminService.setStatus(quizId, req == null ? null : req.status());
        return ResponseEntity.ok(new QuizAdminDto(
                quiz.getId(),
                quiz.getTitle(),
                quiz.getDescription(),
                quiz.getCategory() == null ? null : quiz.getCategory().getName(),
                quiz.getAvatarImageUrl(),
                quiz.getAvatarBgStart(),
                quiz.getAvatarBgEnd(),
                quiz.getAvatarTextColor(),
                quiz.getGameMode(),
                quiz.isIncludeInRanking(),
                quiz.isXpEnabled(),
                quiz.getQuestionTimeLimitSeconds(),
                quiz.getStatus()
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
                .map(o -> new QuizAdminService.AnswerOptionInput(o.text(), o.imageUrl(), o.correct()))
                .toList();
        QuizQuestion q = adminService.addQuestion(quizId, req.prompt(), req.imageUrl(), options);
        return ResponseEntity.status(CREATED).body(new QuizQuestionAdminDto(q.getId(), q.getOrderIndex(), q.getPrompt(), q.getImageUrl()));
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
                .map(o -> new QuizAdminService.AnswerOptionUpdateInput(o.id(), o.text(), o.imageUrl(), o.correct()))
                .toList();
        adminService.updateQuestion(quizId, questionId, req.prompt(), req.imageUrl(), options);
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

    @DeleteMapping("/{id}/purge")
    public ResponseEntity<Void> purgeQuiz(@PathVariable("id") Long quizId) {
        adminService.purgeQuiz(quizId);
        return ResponseEntity.noContent().build();
    }

    public record CreateQuizRequest(
            @NotBlank @Size(max = 120) String title,
            @Size(max = 500) String description,
            @Size(max = 64) String categoryName,
            @Size(max = 500) String avatarImageUrl,
            @Size(max = 32) String avatarBgStart,
            @Size(max = 32) String avatarBgEnd,
            @Size(max = 32) String avatarTextColor,
            GameMode gameMode,
            Boolean includeInRanking,
            Boolean xpEnabled,
            Integer questionTimeLimitSeconds
    ) {}

    public record AddQuestionRequest(
            @NotBlank @Size(max = 500) String prompt,
            @Size(max = 500) String imageUrl,
            List<AnswerOptionRequest> options
    ) {}

    public record AnswerOptionRequest(
            @Size(max = 200) String text,
            @Size(max = 500) String imageUrl,
            boolean correct
    ) {}

    public record UpdateQuestionRequest(
            @NotBlank @Size(max = 500) String prompt,
            @Size(max = 500) String imageUrl,
            List<AnswerOptionUpdateRequest> options
    ) {}

    public record AnswerOptionUpdateRequest(
            Long id,
            @Size(max = 200) String text,
            @Size(max = 500) String imageUrl,
            boolean correct
    ) {}

    public record QuizAdminDto(
            Long id,
            String title,
            String description,
            String categoryName,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor,
            GameMode gameMode,
            boolean includeInRanking,
            boolean xpEnabled,
            Integer questionTimeLimitSeconds,
            QuizStatus status
    ) {}

    public record StatusRequest(QuizStatus status) {}

    public record QuizQuestionAdminDto(Long id, int orderIndex, String prompt, String imageUrl) {}

    public record AdminQuizListItemDto(
            Long id,
            String title,
            String description,
            String categoryName,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor,
            GameMode gameMode,
            boolean includeInRanking,
            boolean xpEnabled,
            Integer questionTimeLimitSeconds,
            QuizStatus status,
            long questionCount
    ) {}

    public record AdminQuizDetailDto(
            Long id,
            String title,
            String description,
            String categoryName,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor,
            GameMode gameMode,
            boolean includeInRanking,
            boolean xpEnabled,
            Integer questionTimeLimitSeconds,
            QuizStatus status,
            List<AdminQuestionDto> questions
    ) {}

    public record AdminQuestionDto(
            Long id,
            int orderIndex,
            String prompt,
            String imageUrl,
            List<AdminAnswerOptionDto> options
    ) {}

    public record AdminAnswerOptionDto(
            Long id,
            int orderIndex,
            String text,
            String imageUrl,
            boolean correct
    ) {}

    private static AdminQuizDetailDto toDetailDto(QuizAdminService.AdminQuizDetail quiz) {
        return new AdminQuizDetailDto(
                quiz.id(),
                quiz.title(),
                quiz.description(),
                quiz.categoryName(),
                quiz.avatarImageUrl(),
                quiz.avatarBgStart(),
                quiz.avatarBgEnd(),
                quiz.avatarTextColor(),
                quiz.gameMode(),
                quiz.includeInRanking(),
                quiz.xpEnabled(),
                quiz.questionTimeLimitSeconds(),
                quiz.status(),
                quiz.questions().stream()
                        .map(q -> new AdminQuestionDto(
                                q.id(),
                                q.orderIndex(),
                                q.prompt(),
                                q.imageUrl(),
                                q.options().stream()
                                        .map(o -> new AdminAnswerOptionDto(o.id(), o.orderIndex(), o.text(), o.imageUrl(), o.correct()))
                                        .toList()
                        ))
                        .toList()
        );
    }
}
