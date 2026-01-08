export interface GameOptionDto {
  id: number;
  text: string;
}

export interface GameQuestionDto {
  id: number;
  prompt: string;
  options: GameOptionDto[];
}

export interface GamePlayerDto {
  displayName: string;
  answered: boolean;
  correct: boolean | null;
  score: number;
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
}

