package pl.mindrush.backend.quiz;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.quiz.dto.QuizAnswerOptionDto;
import pl.mindrush.backend.quiz.dto.QuizDetailDto;
import pl.mindrush.backend.quiz.dto.QuizListItemDto;
import pl.mindrush.backend.quiz.dto.QuizQuestionDto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional(readOnly = true)
public class QuizService {

    private final QuizRepository quizRepository;
    private final QuizQuestionRepository questionRepository;
    private final QuizAnswerOptionRepository optionRepository;

    public QuizService(
            QuizRepository quizRepository,
            QuizQuestionRepository questionRepository,
            QuizAnswerOptionRepository optionRepository
    ) {
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
    }

    public List<QuizListItemDto> listQuizzes() {
        return quizRepository.findAllWithCategoryByStatus(QuizStatus.ACTIVE).stream()
                .map(q -> new QuizListItemDto(
                        q.getId(),
                        q.getTitle(),
                        q.getDescription(),
                        q.getCategory() == null ? null : q.getCategory().getName(),
                        q.getAvatarImageUrl(),
                        q.getAvatarBgStart(),
                        q.getAvatarBgEnd(),
                        q.getAvatarTextColor(),
                        q.getGameMode(),
                        q.isIncludeInRanking(),
                        q.isXpEnabled(),
                        q.getQuestionTimeLimitSeconds(),
                        questionRepository.countByQuizId(q.getId())
                ))
                .toList();
    }

    public QuizDetailDto getQuiz(Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));

        if (quiz.getStatus() != QuizStatus.ACTIVE) {
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
                questionRepository.countByQuizId(quizId)
        );
    }

    public List<QuizQuestionDto> getQuizQuestions(Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));
        if (quiz.getStatus() != QuizStatus.ACTIVE) {
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
