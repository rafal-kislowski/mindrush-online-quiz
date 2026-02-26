export interface QuizListItemDto {
  id: number;
  title: string;
  description: string | null;
  categoryName: string | null;
  avatarImageUrl?: string | null;
  avatarBgStart?: string | null;
  avatarBgEnd?: string | null;
  avatarTextColor?: string | null;
  gameMode?: 'CASUAL' | 'RANKED';
  includeInRanking?: boolean;
  xpEnabled?: boolean;
  questionTimeLimitSeconds?: number | null;
  questionCount: number;
}

export interface QuizAnswerOptionDto {
  id: number;
  text: string | null;
  imageUrl?: string | null;
}

export interface QuizQuestionDto {
  id: number;
  prompt: string;
  imageUrl?: string | null;
  options: QuizAnswerOptionDto[];
}
