package pl.mindrush.backend.quiz;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.quiz.dto.QuizAnswerOptionDto;
import pl.mindrush.backend.quiz.dto.QuizDetailDto;
import pl.mindrush.backend.quiz.dto.QuizListItemDto;
import pl.mindrush.backend.quiz.dto.QuizQuestionDto;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional(readOnly = true)
public class QuizService {

    private final QuizRepository quizRepository;
    private final QuizQuestionRepository questionRepository;
    private final QuizAnswerOptionRepository optionRepository;
    private final QuizFavoriteRepository favoriteRepository;

    public QuizService(
            QuizRepository quizRepository,
            QuizQuestionRepository questionRepository,
            QuizAnswerOptionRepository optionRepository,
            QuizFavoriteRepository favoriteRepository
    ) {
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.favoriteRepository = favoriteRepository;
    }

    public List<QuizListItemDto> listQuizzes(Long viewerUserId) {
        Map<Long, Quiz> visibleById = new LinkedHashMap<>();

        for (Quiz quiz : quizRepository.findAllWithCategory()) {
            if (!isVisibleForViewer(quiz, viewerUserId)) continue;
            visibleById.put(quiz.getId(), quiz);
        }

        Set<Long> favoriteIds = favoriteQuizIds(viewerUserId);

        return visibleById.values().stream()
                .sorted(Comparator.comparing(Quiz::getId).reversed())
                .map(q -> new QuizListItemDto(
                        q.getId(),
                        q.getTitle(),
                        q.getDescription(),
                        q.getCategory() == null ? null : q.getCategory().getName(),
                        q.getSource().name().toLowerCase(),
                        favoriteIds.contains(q.getId()),
                        isInLibraryForViewer(q, viewerUserId),
                        QuizVisibilityRules.isOwnedBy(q, viewerUserId),
                        QuizVisibilityRules.isPubliclyVisible(q),
                        q.getAvatarImageUrl(),
                        q.getAvatarBgStart(),
                        q.getAvatarBgEnd(),
                        q.getAvatarTextColor(),
                        q.getGameMode(),
                        q.isIncludeInRanking(),
                        q.isXpEnabled(),
                        q.getQuestionTimeLimitSeconds(),
                        q.getQuestionsPerGame(),
                        questionRepository.countByQuizId(q.getId())
                ))
                .toList();
    }

    private boolean isVisibleForViewer(Quiz quiz, Long viewerUserId) {
        return QuizVisibilityRules.isPubliclyVisible(quiz)
                || QuizVisibilityRules.canOwnerUsePrivately(quiz, viewerUserId);
    }

    private Set<Long> favoriteQuizIds(Long viewerUserId) {
        if (viewerUserId == null) return Set.of();
        Set<Long> ids = new HashSet<>();
        for (QuizFavorite favorite : favoriteRepository.findAllByUserId(viewerUserId)) {
            if (favorite.getQuizId() != null) {
                ids.add(favorite.getQuizId());
            }
        }
        return ids;
    }

    private boolean isInLibraryForViewer(Quiz quiz, Long viewerUserId) {
        if (viewerUserId == null) return false;
        return QuizVisibilityRules.isOwnedBy(quiz, viewerUserId)
                && QuizVisibilityRules.isPubliclyVisible(quiz);
    }

    public QuizDetailDto getQuiz(Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));

        if (!QuizVisibilityRules.isPubliclyVisible(quiz)) {
            throw new ResponseStatusException(NOT_FOUND, "Quiz not found");
        }

        return new QuizDetailDto(
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
                quiz.getQuestionsPerGame(),
                questionRepository.countByQuizId(quizId)
        );
    }

    public List<QuizQuestionDto> getQuizQuestions(Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));
        if (!QuizVisibilityRules.isPubliclyVisible(quiz)) {
            throw new ResponseStatusException(NOT_FOUND, "Quiz not found");
        }

        List<QuizQuestion> questions = questionRepository.findAllByQuizIdOrderByOrderIndexAsc(quizId);
        List<Long> questionIds = questions.stream().map(QuizQuestion::getId).toList();

        Map<Long, List<QuizAnswerOptionDto>> optionsByQuestionId = new HashMap<>();
        if (!questionIds.isEmpty()) {
            optionRepository.findAllByQuestionIdInOrderByQuestionIdAscOrderIndexAsc(questionIds).forEach(o -> {
                optionsByQuestionId.computeIfAbsent(o.getQuestion().getId(), ignored -> new java.util.ArrayList<>())
                        .add(new QuizAnswerOptionDto(o.getId(), o.getText(), o.getImageUrl()));
            });
        }

        return questions.stream()
                .map(q -> new QuizQuestionDto(
                        q.getId(),
                        q.getPrompt(),
                        q.getImageUrl(),
                        optionsByQuestionId.getOrDefault(q.getId(), List.of())
                ))
                .toList();
    }
}
