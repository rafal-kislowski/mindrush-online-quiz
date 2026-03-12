export type GameStartMode = 'STANDARD' | 'THREE_LIVES' | 'TRAINING';

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
  isAuthenticated?: boolean;
  isPremium?: boolean;
  answered: boolean;
  correct: boolean | null;
  score: number;
  correctAnswers?: number;
  totalAnswerTimeMs?: number;
  totalCorrectAnswerTimeMs?: number;
  xpDelta?: number | null;
  coinsDelta?: number | null;
  rankPointsDelta?: number | null;
  rankPoints?: number | null;
  winner?: boolean | null;
}

export interface GameStateDto {
  lobbyCode: string;
  lobbyStatus: string;
  gameStatus: string;
  mode?: GameStartMode | string | null;
  questionIndex: number;
  totalQuestions: number;
  stage: string;
  serverTime: string | null;
  stageEndsAt: string | null;
  stageTotalMs?: number | null;
  question: GameQuestionDto | null;
  players: GamePlayerDto[];
  gameSessionId: string | null;
  correctOptionId: number | null;
  livesRemaining?: number | null;
  wrongAnswers?: number | null;
  finishReason?: string | null;
}

export interface ActiveGameDto {
  type: 'SOLO' | 'LOBBY' | string;
  gameSessionId: string;
  lobbyCode: string | null;
}
