import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export type GameMode = 'CASUAL' | 'RANKED';
export type QuizStatus = 'DRAFT' | 'ACTIVE' | 'TRASHED';

export interface AdminQuizDto {
  id: number;
  title: string;
  description: string | null;
  categoryName: string | null;
  avatarImageUrl: string | null;
  avatarBgStart: string | null;
  avatarBgEnd: string | null;
  avatarTextColor: string | null;
  gameMode: GameMode;
  includeInRanking: boolean;
  xpEnabled: boolean;
  questionTimeLimitSeconds: number | null;
  status: QuizStatus;
}

export interface AdminQuizListItemDto {
  id: number;
  title: string;
  description: string | null;
  categoryName: string | null;
  avatarImageUrl: string | null;
  avatarBgStart: string | null;
  avatarBgEnd: string | null;
  avatarTextColor: string | null;
  gameMode: GameMode;
  includeInRanking: boolean;
  xpEnabled: boolean;
  questionTimeLimitSeconds: number | null;
  status: QuizStatus;
  questionCount: number;
}

export interface AdminQuizQuestionDto {
  id: number;
  orderIndex: number;
  prompt: string;
  imageUrl: string | null;
}

export interface AdminAnswerOptionDto {
  id: number;
  orderIndex: number;
  text: string;
  imageUrl: string | null;
  correct: boolean;
}

export interface AdminQuestionDto {
  id: number;
  orderIndex: number;
  prompt: string;
  imageUrl: string | null;
  options: AdminAnswerOptionDto[];
}

export interface AdminQuizDetailDto {
  id: number;
  title: string;
  description: string | null;
  categoryName: string | null;
  avatarImageUrl: string | null;
  avatarBgStart: string | null;
  avatarBgEnd: string | null;
  avatarTextColor: string | null;
  gameMode: GameMode;
  includeInRanking: boolean;
  xpEnabled: boolean;
  questionTimeLimitSeconds: number | null;
  status: QuizStatus;
  questions: AdminQuestionDto[];
}

@Injectable({ providedIn: 'root' })
export class AdminQuizApi {
  constructor(private readonly http: HttpClient) {}

  uploadImage(file: File): Observable<{ url: string }> {
    const form = new FormData();
    form.append('file', file);

    return this.http.post<{ url: string }>('/api/admin/media', form, {
      withCredentials: true,
    });
  }

  listQuizzes(): Observable<AdminQuizListItemDto[]> {
    return this.http.get<AdminQuizListItemDto[]>('/api/admin/quizzes', {
      withCredentials: true,
    });
  }

  getQuiz(id: number): Observable<AdminQuizDetailDto> {
    return this.http.get<AdminQuizDetailDto>(
      `/api/admin/quizzes/${encodeURIComponent(String(id))}`,
      { withCredentials: true }
    );
  }

  createQuiz(input: {
    title: string;
    description?: string | null;
    categoryName?: string | null;
    avatarImageUrl?: string | null;
    avatarBgStart?: string | null;
    avatarBgEnd?: string | null;
    avatarTextColor?: string | null;
    gameMode?: GameMode | null;
    includeInRanking?: boolean | null;
    xpEnabled?: boolean | null;
    questionTimeLimitSeconds?: number | null;
  }): Observable<AdminQuizDto> {
    return this.http.post<AdminQuizDto>('/api/admin/quizzes', input, {
      withCredentials: true,
    });
  }

  updateQuiz(
    id: number,
    input: {
      title: string;
      description?: string | null;
      categoryName?: string | null;
      avatarImageUrl?: string | null;
      avatarBgStart?: string | null;
      avatarBgEnd?: string | null;
      avatarTextColor?: string | null;
      gameMode?: GameMode | null;
      includeInRanking?: boolean | null;
      xpEnabled?: boolean | null;
      questionTimeLimitSeconds?: number | null;
    }
  ): Observable<AdminQuizDto> {
    return this.http.put<AdminQuizDto>(
      `/api/admin/quizzes/${encodeURIComponent(String(id))}`,
      input,
      { withCredentials: true }
    );
  }

  addQuestion(
    quizId: number,
    input: {
      prompt: string;
      imageUrl?: string | null;
      options: Array<{ text?: string | null; imageUrl?: string | null; correct: boolean }>;
    }
  ): Observable<AdminQuizQuestionDto> {
    return this.http.post<AdminQuizQuestionDto>(
      `/api/admin/quizzes/${encodeURIComponent(String(quizId))}/questions`,
      input,
      { withCredentials: true }
    );
  }

  updateQuestion(
    quizId: number,
    questionId: number,
    input: {
      prompt: string;
      imageUrl?: string | null;
      options: Array<{ id: number; text?: string | null; imageUrl?: string | null; correct: boolean }>;
    }
  ): Observable<void> {
    return this.http.put<void>(
      `/api/admin/quizzes/${encodeURIComponent(String(quizId))}/questions/${encodeURIComponent(String(questionId))}`,
      input,
      { withCredentials: true }
    );
  }

  deleteQuestion(quizId: number, questionId: number): Observable<void> {
    return this.http.delete<void>(
      `/api/admin/quizzes/${encodeURIComponent(String(quizId))}/questions/${encodeURIComponent(String(questionId))}`,
      { withCredentials: true }
    );
  }

  deleteQuiz(quizId: number): Observable<void> {
    return this.http.delete<void>(
      `/api/admin/quizzes/${encodeURIComponent(String(quizId))}`,
      { withCredentials: true }
    );
  }

  purgeQuiz(quizId: number): Observable<void> {
    return this.http.delete<void>(
      `/api/admin/quizzes/${encodeURIComponent(String(quizId))}/purge`,
      { withCredentials: true }
    );
  }

  setStatus(quizId: number, status: QuizStatus): Observable<AdminQuizDto> {
    return this.http.put<AdminQuizDto>(
      `/api/admin/quizzes/${encodeURIComponent(String(quizId))}/status`,
      { status },
      { withCredentials: true }
    );
  }
}
