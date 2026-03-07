package pl.mindrush.backend.quiz;

public final class QuizVisibilityRules {

    private QuizVisibilityRules() {
    }

    public static boolean isPubliclyVisible(Quiz quiz) {
        if (quiz == null) return false;
        if (quiz.getStatus() != QuizStatus.ACTIVE) return false;

        if (quiz.getSource() == QuizSource.OFFICIAL) return true;
        if (quiz.getSource() == QuizSource.CUSTOM) {
            return quiz.getModerationStatus() == QuizModerationStatus.APPROVED;
        }
        return false;
    }

    public static boolean isOwnedBy(Quiz quiz, Long userId) {
        return quiz != null
                && userId != null
                && userId.equals(quiz.getOwnerUserId());
    }

    public static boolean canOwnerUsePrivately(Quiz quiz, Long userId) {
        return isOwnedBy(quiz, userId) && quiz.getStatus() != QuizStatus.TRASHED;
    }

    public static boolean canUserPlay(Quiz quiz, Long userId) {
        return canOwnerUsePrivately(quiz, userId) || isPubliclyVisible(quiz);
    }
}
