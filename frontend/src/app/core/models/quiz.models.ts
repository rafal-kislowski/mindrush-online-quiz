export interface QuizListItemDto {
  id: number;
  title: string;
  description: string | null;
  categoryName: string | null;
  questionCount: number;
}

