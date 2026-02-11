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
