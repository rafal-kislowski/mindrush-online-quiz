export interface GameOptionDto {
  id: number;
  text: string | null;
  imageUrl?: string | null;
}

export interface GameQuestionDto {
  id: number;
  prompt: string;
  imageUrl?: string | null;
  options: GameOptionDto[];
}

export interface GamePlayerDto {
  displayName: string;
  answered: boolean;
  correct: boolean | null;
  score: number;
  correctAnswers?: number;
  totalAnswerTimeMs?: number;
  totalCorrectAnswerTimeMs?: number;
  xpDelta?: number | null;
  rankPointsDelta?: number | null;
  winner?: boolean | null;
}

export interface GameStateDto {
  lobbyCode: string;
  lobbyStatus: string;
  gameStatus: string;
  questionIndex: number;
  totalQuestions: number;
  stage: string;
  stageEndsAt: string | null;
  question: GameQuestionDto | null;
  players: GamePlayerDto[];
  gameSessionId: string | null;
  correctOptionId: number | null;
}
