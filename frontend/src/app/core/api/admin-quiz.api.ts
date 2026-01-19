import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface AdminQuizDto {
  id: number;
  title: string;
  description: string | null;
  categoryName: string | null;
}

export interface AdminQuizListItemDto {
  id: number;
  title: string;
  description: string | null;
  categoryName: string | null;
  questionCount: number;
}

export interface AdminQuizQuestionDto {
  id: number;
  orderIndex: number;
  prompt: string;
}

export interface AdminAnswerOptionDto {
  id: number;
  orderIndex: number;
  text: string;
  correct: boolean;
}

export interface AdminQuestionDto {
  id: number;
  orderIndex: number;
  prompt: string;
  options: AdminAnswerOptionDto[];
}

export interface AdminQuizDetailDto {
  id: number;
  title: string;
  description: string | null;
  categoryName: string | null;
  questions: AdminQuestionDto[];
}

@Injectable({ providedIn: 'root' })
export class AdminQuizApi {
  constructor(private readonly http: HttpClient) {}

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
  }): Observable<AdminQuizDto> {
    return this.http.post<AdminQuizDto>('/api/admin/quizzes', input, {
      withCredentials: true,
    });
  }

  updateQuiz(
    id: number,
    input: { title: string; description?: string | null; categoryName?: string | null }
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
      options: Array<{ text: string; correct: boolean }>;
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
      options: Array<{ id: number; text: string; correct: boolean }>;
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
}
