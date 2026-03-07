package pl.mindrush.backend.quiz;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.JwtCookieAuthenticationFilter;

import java.util.List;

import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/library/quizzes")
public class LibraryQuizController {

    private final QuizLibraryService libraryService;

    public LibraryQuizController(QuizLibraryService libraryService) {
        this.libraryService = libraryService;
    }

    @GetMapping("/mine")
    public ResponseEntity<List<QuizLibraryService.LibraryQuizListItem>> myQuizzes(Authentication authentication) {
        Long userId = requireUserId(authentication);
        return ResponseEntity.ok(libraryService.listOwnedQuizzes(userId));
    }

    @GetMapping("/policy")
    public ResponseEntity<QuizLibraryService.LibraryPolicy> policy(Authentication authentication) {
        Long userId = requireUserId(authentication);
        return ResponseEntity.ok(libraryService.getPolicy(userId));
    }

    @GetMapping("/public")
    public ResponseEntity<List<QuizLibraryService.LibraryQuizListItem>> publicQuizzes(Authentication authentication) {
        Long userId = requireUserId(authentication);
        return ResponseEntity.ok(libraryService.listPublicQuizzes(userId));
    }

    @GetMapping("/favorites")
    public ResponseEntity<List<QuizLibraryService.LibraryQuizListItem>> favoriteQuizzes(Authentication authentication) {
        Long userId = requireUserId(authentication);
        return ResponseEntity.ok(libraryService.listFavoriteQuizzes(userId));
    }

    @GetMapping("/mine/{id}")
    public ResponseEntity<QuizLibraryService.LibraryQuizDetail> myQuizDetail(
            Authentication authentication,
            @PathVariable("id") Long quizId
    ) {
        Long userId = requireUserId(authentication);
        return ResponseEntity.ok(libraryService.getOwnedQuizDetail(userId, quizId));
    }

    @PostMapping
    public ResponseEntity<QuizLibraryService.LibraryQuizListItem> create(
            Authentication authentication,
            @Valid @RequestBody CreateQuizRequest request
    ) {
        Long userId = requireUserId(authentication);
        Quiz created = libraryService.createOwnedQuiz(
                userId,
                request.title(),
                request.description(),
                request.categoryName(),
                request.avatarImageUrl(),
                request.avatarBgStart(),
                request.avatarBgEnd(),
                request.avatarTextColor(),
                request.questionTimeLimitSeconds(),
                request.questionsPerGame()
        );
        QuizLibraryService.LibraryQuizListItem payload = libraryService.listOwnedQuizzes(userId).stream()
                .filter(q -> q.id().equals(created.getId()))
                .findFirst()
                .orElseThrow();
        return ResponseEntity.status(CREATED).body(payload);
    }

    @PutMapping("/{id}")
    public ResponseEntity<QuizLibraryService.LibraryQuizListItem> update(
            Authentication authentication,
            @PathVariable("id") Long quizId,
            @Valid @RequestBody CreateQuizRequest request
    ) {
        Long userId = requireUserId(authentication);
        Quiz updated = libraryService.updateOwnedQuiz(
                userId,
                quizId,
                request.title(),
                request.description(),
                request.categoryName(),
                request.avatarImageUrl(),
                request.avatarBgStart(),
                request.avatarBgEnd(),
                request.avatarTextColor(),
                request.questionTimeLimitSeconds(),
                request.questionsPerGame()
        );
        QuizLibraryService.LibraryQuizListItem payload = libraryService.listOwnedQuizzes(userId).stream()
                .filter(q -> q.id().equals(updated.getId()))
                .findFirst()
                .orElseThrow();
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/{id}/questions")
    public ResponseEntity<QuizQuestionAdminDto> addQuestion(
            Authentication authentication,
            @PathVariable("id") Long quizId,
            @Valid @RequestBody AddQuestionRequest request
    ) {
        Long userId = requireUserId(authentication);
        List<QuizLibraryService.AnswerOptionInput> options = request.options() == null
                ? null
                : request.options().stream()
                .map(o -> new QuizLibraryService.AnswerOptionInput(o.text(), o.imageUrl(), o.correct()))
                .toList();

        QuizQuestion question = libraryService.addOwnedQuestion(
                userId,
                quizId,
                request.prompt(),
                request.imageUrl(),
                options
        );
        return ResponseEntity.status(CREATED).body(
                new QuizQuestionAdminDto(question.getId(), question.getOrderIndex(), question.getPrompt(), question.getImageUrl())
        );
    }

    @PutMapping("/{id}/questions/{questionId}")
    public ResponseEntity<Void> updateQuestion(
            Authentication authentication,
            @PathVariable("id") Long quizId,
            @PathVariable("questionId") Long questionId,
            @Valid @RequestBody UpdateQuestionRequest request
    ) {
        Long userId = requireUserId(authentication);
        List<QuizLibraryService.AnswerOptionUpdateInput> options = request.options() == null
                ? null
                : request.options().stream()
                .map(o -> new QuizLibraryService.AnswerOptionUpdateInput(o.id(), o.text(), o.imageUrl(), o.correct()))
                .toList();
        libraryService.updateOwnedQuestion(userId, quizId, questionId, request.prompt(), request.imageUrl(), options);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/questions/{questionId}")
    public ResponseEntity<Void> deleteQuestion(
            Authentication authentication,
            @PathVariable("id") Long quizId,
            @PathVariable("questionId") Long questionId
    ) {
        Long userId = requireUserId(authentication);
        libraryService.deleteOwnedQuestion(userId, quizId, questionId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<QuizLibraryService.LibraryQuizListItem> trashQuiz(
            Authentication authentication,
            @PathVariable("id") Long quizId
    ) {
        Long userId = requireUserId(authentication);
        Quiz updated = libraryService.trashOwnedQuiz(userId, quizId);
        QuizLibraryService.LibraryQuizListItem payload = new QuizLibraryService.LibraryQuizListItem(
                updated.getId(),
                updated.getTitle(),
                updated.getDescription(),
                updated.getCategory() == null ? null : updated.getCategory().getName(),
                updated.getAvatarImageUrl(),
                updated.getAvatarBgStart(),
                updated.getAvatarBgEnd(),
                updated.getAvatarTextColor(),
                updated.getQuestionTimeLimitSeconds(),
                updated.getQuestionsPerGame(),
                updated.getStatus(),
                updated.getSource(),
                updated.getModerationStatus(),
                updated.getModerationReason(),
                updated.getModerationUpdatedAt(),
                0,
                false,
                0
        );
        return ResponseEntity.ok(payload);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<QuizLibraryService.LibraryQuizListItem> setStatus(
            Authentication authentication,
            @PathVariable("id") Long quizId,
            @RequestBody StatusRequest request
    ) {
        Long userId = requireUserId(authentication);
        Quiz updated = libraryService.setOwnedQuizStatus(userId, quizId, request == null ? null : request.status());
        QuizLibraryService.LibraryQuizListItem payload = libraryService.listOwnedQuizzes(userId).stream()
                .filter(q -> q.id().equals(updated.getId()))
                .findFirst()
                .orElseThrow();
        return ResponseEntity.ok(payload);
    }

    @DeleteMapping("/{id}/purge")
    public ResponseEntity<Void> purgeQuiz(
            Authentication authentication,
            @PathVariable("id") Long quizId
    ) {
        Long userId = requireUserId(authentication);
        libraryService.purgeOwnedQuiz(userId, quizId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<QuizLibraryService.LibraryQuizListItem> submitForModeration(
            Authentication authentication,
            @PathVariable("id") Long quizId
    ) {
        Long userId = requireUserId(authentication);
        Quiz updated = libraryService.submitOwnedQuizForModeration(userId, quizId);
        QuizLibraryService.LibraryQuizListItem payload = libraryService.listOwnedQuizzes(userId).stream()
                .filter(q -> q.id().equals(updated.getId()))
                .findFirst()
                .orElseThrow();
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/{id}/favorite-toggle")
    public ResponseEntity<QuizLibraryService.FavoriteToggleResult> toggleFavorite(
            Authentication authentication,
            @PathVariable("id") Long quizId
    ) {
        Long userId = requireUserId(authentication);
        return ResponseEntity.ok(libraryService.toggleFavorite(userId, quizId));
    }

    private static Long requireUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Authentication is required");
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof JwtCookieAuthenticationFilter.AuthenticatedUser user) || user.id() == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Authentication is required");
        }
        return user.id();
    }

    public record CreateQuizRequest(
            @NotBlank @Size(max = 120) String title,
            @Size(max = 500) String description,
            @Size(max = 64) String categoryName,
            @Size(max = 500) String avatarImageUrl,
            @Size(max = 32) String avatarBgStart,
            @Size(max = 32) String avatarBgEnd,
            @Size(max = 32) String avatarTextColor,
            Integer questionTimeLimitSeconds,
            Integer questionsPerGame
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

    public record QuizQuestionAdminDto(Long id, int orderIndex, String prompt, String imageUrl) {}

    public record StatusRequest(QuizStatus status) {}
}
