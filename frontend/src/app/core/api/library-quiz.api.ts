import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export type QuizStatus = 'DRAFT' | 'ACTIVE' | 'TRASHED';
export type QuizSource = 'OFFICIAL' | 'CUSTOM';
export type QuizModerationStatus = 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED';

export interface ModerationQuestionIssueDto {
  questionId: number;
  message: string;
}

export interface LibraryQuizListItemDto {
  id: number;
  title: string;
  description: string | null;
  categoryName: string | null;
  avatarImageUrl: string | null;
  avatarBgStart: string | null;
  avatarBgEnd: string | null;
  avatarTextColor: string | null;
  questionTimeLimitSeconds: number | null;
  questionsPerGame: number | null;
  status: QuizStatus;
  source: QuizSource;
  moderationStatus: QuizModerationStatus;
  moderationReason: string | null;
  moderationUpdatedAt: string | null;
  moderationQuestionIssueCount: number;
  favorite: boolean;
  questionCount: number;
}

export interface LibraryAnswerOptionDto {
  id: number;
  orderIndex: number;
  text: string;
  imageUrl: string | null;
  correct: boolean;
}

export interface LibraryQuestionDto {
  id: number;
  orderIndex: number;
  prompt: string;
  imageUrl: string | null;
  options: LibraryAnswerOptionDto[];
}

export interface LibraryQuizDetailDto {
  id: number;
  title: string;
  description: string | null;
  categoryName: string | null;
  avatarImageUrl: string | null;
  avatarBgStart: string | null;
  avatarBgEnd: string | null;
  avatarTextColor: string | null;
  questionTimeLimitSeconds: number | null;
  questionsPerGame: number | null;
  status: QuizStatus;
  source: QuizSource;
  moderationStatus: QuizModerationStatus;
  moderationReason: string | null;
  moderationUpdatedAt: string | null;
  moderationQuestionIssues: ModerationQuestionIssueDto[];
  favorite: boolean;
  questions: LibraryQuestionDto[];
}

export interface FavoriteToggleResultDto {
  favorite: boolean;
}

export interface LibraryPolicyDto {
  maxOwnedQuizzes: number;
  maxPublishedQuizzes: number;
  maxPendingSubmissions: number;
  minQuestionsToSubmit: number;
  maxQuestionsPerQuiz: number;
  maxQuestionImagesPerQuiz: number;
  minQuestionTimeLimitSeconds: number;
  maxQuestionTimeLimitSeconds: number;
  maxQuestionsPerGame: number;
  maxUploadBytes: number;
  allowedUploadMimeTypes: string[];
  ownedCount: number;
  publishedCount: number;
  pendingCount: number;
}

export interface QuizQuestionAdminDto {
  id: number;
  orderIndex: number;
  prompt: string;
  imageUrl: string | null;
}

@Injectable({ providedIn: 'root' })
export class LibraryQuizApi {
  constructor(private readonly http: HttpClient) {}

  uploadImage(file: File): Observable<{ url: string }> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<{ url: string }>('/api/library/media', form, {
      withCredentials: true,
    });
  }

  listMy(): Observable<LibraryQuizListItemDto[]> {
    return this.http.get<LibraryQuizListItemDto[]>('/api/library/quizzes/mine', {
      withCredentials: true,
    });
  }

  getPolicy(): Observable<LibraryPolicyDto> {
    return this.http.get<LibraryPolicyDto>('/api/library/quizzes/policy', {
      withCredentials: true,
    });
  }

  listPublic(): Observable<LibraryQuizListItemDto[]> {
    return this.http.get<LibraryQuizListItemDto[]>('/api/library/quizzes/public', {
      withCredentials: true,
    });
  }

  listFavorites(): Observable<LibraryQuizListItemDto[]> {
    return this.http.get<LibraryQuizListItemDto[]>('/api/library/quizzes/favorites', {
      withCredentials: true,
    });
  }

  getMyQuiz(id: number): Observable<LibraryQuizDetailDto> {
    return this.http.get<LibraryQuizDetailDto>(`/api/library/quizzes/mine/${encodeURIComponent(String(id))}`, {
      withCredentials: true,
    });
  }

  createQuiz(input: {
    title: string;
    description?: string | null;
    categoryName?: string | null;
    avatarImageUrl?: string | null;
    avatarBgStart?: string | null;
    avatarBgEnd?: string | null;
    avatarTextColor?: string | null;
    questionTimeLimitSeconds?: number | null;
    questionsPerGame?: number | null;
  }): Observable<LibraryQuizListItemDto> {
    return this.http.post<LibraryQuizListItemDto>('/api/library/quizzes', input, {
      withCredentials: true,
    });
  }

  updateQuiz(id: number, input: {
    title: string;
    description?: string | null;
    categoryName?: string | null;
    avatarImageUrl?: string | null;
    avatarBgStart?: string | null;
    avatarBgEnd?: string | null;
    avatarTextColor?: string | null;
    questionTimeLimitSeconds?: number | null;
    questionsPerGame?: number | null;
  }): Observable<LibraryQuizListItemDto> {
    return this.http.put<LibraryQuizListItemDto>(`/api/library/quizzes/${encodeURIComponent(String(id))}`, input, {
      withCredentials: true,
    });
  }

  addQuestion(quizId: number, input: {
    prompt: string;
    imageUrl?: string | null;
    options: Array<{ text?: string | null; imageUrl?: string | null; correct: boolean }>;
  }): Observable<QuizQuestionAdminDto> {
    return this.http.post<QuizQuestionAdminDto>(
      `/api/library/quizzes/${encodeURIComponent(String(quizId))}/questions`,
      input,
      { withCredentials: true }
    );
  }

  updateQuestion(quizId: number, questionId: number, input: {
    prompt: string;
    imageUrl?: string | null;
    options: Array<{ id: number; text?: string | null; imageUrl?: string | null; correct: boolean }>;
  }): Observable<void> {
    return this.http.put<void>(
      `/api/library/quizzes/${encodeURIComponent(String(quizId))}/questions/${encodeURIComponent(String(questionId))}`,
      input,
      { withCredentials: true }
    );
  }

  deleteQuestion(quizId: number, questionId: number): Observable<void> {
    return this.http.delete<void>(
      `/api/library/quizzes/${encodeURIComponent(String(quizId))}/questions/${encodeURIComponent(String(questionId))}`,
      { withCredentials: true }
    );
  }

  trashQuiz(quizId: number): Observable<LibraryQuizListItemDto> {
    return this.http.delete<LibraryQuizListItemDto>(`/api/library/quizzes/${encodeURIComponent(String(quizId))}`, {
      withCredentials: true,
    });
  }

  setStatus(quizId: number, status: QuizStatus): Observable<LibraryQuizListItemDto> {
    return this.http.put<LibraryQuizListItemDto>(
      `/api/library/quizzes/${encodeURIComponent(String(quizId))}/status`,
      { status },
      { withCredentials: true }
    );
  }

  purgeQuiz(quizId: number): Observable<void> {
    return this.http.delete<void>(
      `/api/library/quizzes/${encodeURIComponent(String(quizId))}/purge`,
      { withCredentials: true }
    );
  }

  submit(quizId: number): Observable<LibraryQuizListItemDto> {
    return this.http.post<LibraryQuizListItemDto>(
      `/api/library/quizzes/${encodeURIComponent(String(quizId))}/submit`,
      {},
      { withCredentials: true }
    );
  }

  toggleFavorite(quizId: number): Observable<FavoriteToggleResultDto> {
    return this.http.post<FavoriteToggleResultDto>(
      `/api/library/quizzes/${encodeURIComponent(String(quizId))}/favorite-toggle`,
      {},
      { withCredentials: true }
    );
  }
}
