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
  questionsPerGame: number | null;
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
  questionsPerGame: number | null;
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

export type AdminQuestionGenerationDifficulty = 'EASY' | 'MEDIUM' | 'HARD' | 'MIXED';
export type AdminQuestionGenerationLanguage = 'PL' | 'EN';

export interface AdminGenerateQuestionsResponseDto {
  requestedCount: number;
  generatedCount: number;
  totalQuestionCount: number;
}

export interface AdminOpenTdbCategoryDto {
  id: number;
  name: string;
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
  questionsPerGame: number | null;
  status: QuizStatus;
  questions: AdminQuestionDto[];
}

export type QuizModerationStatus = 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED';
export type QuizSource = 'OFFICIAL' | 'CUSTOM';

export interface AdminQuizSubmissionListItemDto {
  id: number;
  title: string;
  categoryName: string | null;
  ownerDisplayName: string | null;
  ownerIsPremium: boolean;
  avatarImageUrl: string | null;
  avatarBgStart: string | null;
  avatarBgEnd: string | null;
  avatarTextColor: string | null;
  questionCount: number;
  status: QuizStatus;
  moderationStatus: QuizModerationStatus;
  submissionVersion: number;
  moderationUpdatedAt?: string | null;
}

export interface AdminQuizSubmissionDetailDto {
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
  ownerUserId: number | null;
  ownerDisplayName: string | null;
  ownerEmail: string | null;
  ownerBanned: boolean;
  ownerRoles: string[];
  questions: AdminQuestionDto[];
  submissionVersion: number;
}

export interface AdminSubmissionOwnerModerationDto {
  userId: number | null;
  displayName: string | null;
  email: string | null;
  banned: boolean;
  roles: string[];
}

export interface ModerationResultDto {
  quizId: number;
  moderationStatus: QuizModerationStatus;
  status: QuizStatus;
  moderationReason: string | null;
  quizVersion: number;
}

export interface AdminQuestionIssueInputDto {
  questionId: number;
  message: string;
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
    questionsPerGame?: number | null;
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
      questionsPerGame?: number | null;
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

  generateQuestions(
    quizId: number,
    input: {
      topic: string;
      categoryHint?: string | null;
      instructions?: string | null;
      questionCount: number;
      difficulty: AdminQuestionGenerationDifficulty;
      language: AdminQuestionGenerationLanguage;
    }
  ): Observable<AdminGenerateQuestionsResponseDto> {
    return this.http.post<AdminGenerateQuestionsResponseDto>(
      `/api/admin/quizzes/${encodeURIComponent(String(quizId))}/questions/generate`,
      input,
      { withCredentials: true }
    );
  }

  generateQuestionsFromFiles(
    quizId: number,
    input: {
      topic: string;
      categoryHint?: string | null;
      instructions?: string | null;
      questionCount: number;
      difficulty: AdminQuestionGenerationDifficulty;
      language: AdminQuestionGenerationLanguage;
    },
    files: File[]
  ): Observable<AdminGenerateQuestionsResponseDto> {
    const form = new FormData();
    form.append('request', new Blob([JSON.stringify(input)], { type: 'application/json' }));
    for (const file of files) {
      form.append('files', file);
    }

    return this.http.post<AdminGenerateQuestionsResponseDto>(
      `/api/admin/quizzes/${encodeURIComponent(String(quizId))}/questions/generate/ai/from-files`,
      form,
      { withCredentials: true }
    );
  }

  generateQuestionsFromOpenTdb(
    quizId: number,
    input: {
      questionCount: number;
      categoryId?: number | null;
      difficulty: AdminQuestionGenerationDifficulty;
      language: AdminQuestionGenerationLanguage;
    }
  ): Observable<AdminGenerateQuestionsResponseDto> {
    return this.http.post<AdminGenerateQuestionsResponseDto>(
      `/api/admin/quizzes/${encodeURIComponent(String(quizId))}/questions/generate/opentdb`,
      input,
      { withCredentials: true }
    );
  }

  listOpenTdbCategories(): Observable<AdminOpenTdbCategoryDto[]> {
    return this.http.get<AdminOpenTdbCategoryDto[]>('/api/admin/quizzes/questions/generate/opentdb/categories', {
      withCredentials: true,
    });
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

  listPendingSubmissions(): Observable<AdminQuizSubmissionListItemDto[]> {
    return this.http.get<AdminQuizSubmissionListItemDto[]>('/api/admin/quiz-submissions', {
      withCredentials: true,
    });
  }

  getPendingSubmission(quizId: number): Observable<AdminQuizSubmissionDetailDto> {
    return this.http.get<AdminQuizSubmissionDetailDto>(
      `/api/admin/quiz-submissions/${encodeURIComponent(String(quizId))}`,
      { withCredentials: true }
    );
  }

  approveSubmission(quizId: number, expectedSubmissionVersion: number): Observable<ModerationResultDto> {
    return this.http.post<ModerationResultDto>(
      `/api/admin/quiz-submissions/${encodeURIComponent(String(quizId))}/approve`,
      { expectedSubmissionVersion },
      { withCredentials: true }
    );
  }

  undoApproveSubmission(quizId: number, expectedSubmissionVersion: number): Observable<ModerationResultDto> {
    return this.http.post<ModerationResultDto>(
      `/api/admin/quiz-submissions/${encodeURIComponent(String(quizId))}/undo-approve`,
      { expectedSubmissionVersion },
      { withCredentials: true }
    );
  }

  rejectSubmission(
    quizId: number,
    expectedSubmissionVersion: number,
    reason: string,
    questionIssues: AdminQuestionIssueInputDto[] = []
  ): Observable<ModerationResultDto> {
    return this.http.post<ModerationResultDto>(
      `/api/admin/quiz-submissions/${encodeURIComponent(String(quizId))}/reject`,
      { expectedSubmissionVersion, reason, questionIssues },
      { withCredentials: true }
    );
  }

  removeSubmissionQuestionImage(quizId: number, questionId: number): Observable<AdminQuizSubmissionDetailDto> {
    return this.http.delete<AdminQuizSubmissionDetailDto>(
      `/api/admin/quiz-submissions/${encodeURIComponent(String(quizId))}/questions/${encodeURIComponent(String(questionId))}/image`,
      { withCredentials: true }
    );
  }

  removeSubmissionAvatarImage(quizId: number): Observable<AdminQuizSubmissionDetailDto> {
    return this.http.delete<AdminQuizSubmissionDetailDto>(
      `/api/admin/quiz-submissions/${encodeURIComponent(String(quizId))}/avatar`,
      { withCredentials: true }
    );
  }

  removeSubmissionOptionImage(
    quizId: number,
    questionId: number,
    optionId: number
  ): Observable<AdminQuizSubmissionDetailDto> {
    return this.http.delete<AdminQuizSubmissionDetailDto>(
      `/api/admin/quiz-submissions/${encodeURIComponent(String(quizId))}/questions/${encodeURIComponent(String(questionId))}/options/${encodeURIComponent(String(optionId))}/image`,
      { withCredentials: true }
    );
  }

  banSubmissionOwner(quizId: number): Observable<AdminSubmissionOwnerModerationDto> {
    return this.http.post<AdminSubmissionOwnerModerationDto>(
      `/api/admin/quiz-submissions/${encodeURIComponent(String(quizId))}/owner/ban`,
      {},
      { withCredentials: true }
    );
  }

  unbanSubmissionOwner(quizId: number): Observable<AdminSubmissionOwnerModerationDto> {
    return this.http.post<AdminSubmissionOwnerModerationDto>(
      `/api/admin/quiz-submissions/${encodeURIComponent(String(quizId))}/owner/unban`,
      {},
      { withCredentials: true }
    );
  }
}
