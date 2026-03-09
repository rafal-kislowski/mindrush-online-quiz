package pl.mindrush.backend.quiz;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/quiz-submissions")
public class AdminQuizSubmissionController {

    private final QuizLibraryService libraryService;

    public AdminQuizSubmissionController(QuizLibraryService libraryService) {
        this.libraryService = libraryService;
    }

    @GetMapping
    public ResponseEntity<List<QuizLibraryService.AdminSubmissionListItem>> listPending() {
        return ResponseEntity.ok(libraryService.listPendingSubmissions());
    }

    @GetMapping("/{id}")
    public ResponseEntity<QuizLibraryService.AdminSubmissionDetail> detail(@PathVariable("id") Long quizId) {
        return ResponseEntity.ok(libraryService.getSubmissionDetail(quizId));
    }

    @DeleteMapping("/{id}/questions/{questionId}/image")
    public ResponseEntity<QuizLibraryService.AdminSubmissionDetail> removeQuestionImage(
            @PathVariable("id") Long quizId,
            @PathVariable("questionId") Long questionId
    ) {
        return ResponseEntity.ok(libraryService.removeSubmissionQuestionImage(quizId, questionId));
    }

    @DeleteMapping("/{id}/avatar")
    public ResponseEntity<QuizLibraryService.AdminSubmissionDetail> removeAvatarImage(
            @PathVariable("id") Long quizId
    ) {
        return ResponseEntity.ok(libraryService.removeSubmissionAvatarImage(quizId));
    }

    @DeleteMapping("/{id}/questions/{questionId}/options/{optionId}/image")
    public ResponseEntity<QuizLibraryService.AdminSubmissionDetail> removeOptionImage(
            @PathVariable("id") Long quizId,
            @PathVariable("questionId") Long questionId,
            @PathVariable("optionId") Long optionId
    ) {
        return ResponseEntity.ok(libraryService.removeSubmissionOptionImage(quizId, questionId, optionId));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ModerationResultDto> approve(
            @PathVariable("id") Long quizId,
            @Valid @RequestBody ApproveRequest request
    ) {
        Quiz quiz = libraryService.approveSubmission(quizId, request.expectedSubmissionVersion());
        return ResponseEntity.ok(
                new ModerationResultDto(
                        quiz.getId(),
                        quiz.getModerationStatus(),
                        quiz.getStatus(),
                        null,
                        quiz.getVersion()
                )
        );
    }

    @PostMapping("/{id}/undo-approve")
    public ResponseEntity<ModerationResultDto> undoApprove(
            @PathVariable("id") Long quizId,
            @Valid @RequestBody ApproveRequest request
    ) {
        Quiz quiz = libraryService.undoApprovedSubmission(quizId, request.expectedSubmissionVersion());
        return ResponseEntity.ok(
                new ModerationResultDto(
                        quiz.getId(),
                        quiz.getModerationStatus(),
                        quiz.getStatus(),
                        null,
                        quiz.getVersion()
                )
        );
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ModerationResultDto> reject(
            @PathVariable("id") Long quizId,
            @Valid @RequestBody RejectRequest request
    ) {
        List<QuizLibraryService.QuestionIssueInput> questionIssues = request.questionIssues() == null
                ? List.of()
                : request.questionIssues().stream()
                .map(issue -> new QuizLibraryService.QuestionIssueInput(issue.questionId(), issue.message()))
                .toList();
        Quiz quiz = libraryService.rejectSubmission(
                quizId,
                request.expectedSubmissionVersion(),
                request.reason(),
                questionIssues
        );
        return ResponseEntity.ok(
                new ModerationResultDto(
                        quiz.getId(),
                        quiz.getModerationStatus(),
                        quiz.getStatus(),
                        quiz.getModerationReason(),
                        quiz.getVersion()
                )
        );
    }

    @PostMapping("/{id}/owner/ban")
    public ResponseEntity<OwnerModerationDto> banOwner(@PathVariable("id") Long quizId) {
        QuizLibraryService.OwnerModerationResult result = libraryService.banSubmissionOwner(quizId);
        return ResponseEntity.ok(new OwnerModerationDto(
                result.userId(),
                result.displayName(),
                result.email(),
                result.banned(),
                result.roles()
        ));
    }

    @PostMapping("/{id}/owner/unban")
    public ResponseEntity<OwnerModerationDto> unbanOwner(@PathVariable("id") Long quizId) {
        QuizLibraryService.OwnerModerationResult result = libraryService.unbanSubmissionOwner(quizId);
        return ResponseEntity.ok(new OwnerModerationDto(
                result.userId(),
                result.displayName(),
                result.email(),
                result.banned(),
                result.roles()
        ));
    }

    public record ApproveRequest(@NotNull Long expectedSubmissionVersion) {}

    public record RejectRequest(
            @NotNull Long expectedSubmissionVersion,
            @NotBlank @Size(max = 500) String reason,
            List<@Valid QuestionIssueRequest> questionIssues
    ) {}

    public record QuestionIssueRequest(
            @NotNull Long questionId,
            @NotBlank @Size(max = 500) String message
    ) {}

    public record ModerationResultDto(
            Long quizId,
            QuizModerationStatus moderationStatus,
            QuizStatus status,
            String moderationReason,
            Long quizVersion
    ) {}

    public record OwnerModerationDto(
            Long userId,
            String displayName,
            String email,
            boolean banned,
            List<String> roles
    ) {}
}
