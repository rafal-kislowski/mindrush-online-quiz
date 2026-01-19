import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface AdminQuizDto {
  id: number;
  title: string;
  description: string | null;
  categoryName: string | null;
}

export interface AdminQuizQuestionDto {
  id: number;
  orderIndex: number;
  prompt: string;
}

@Injectable({ providedIn: 'root' })
export class AdminQuizApi {
  constructor(private readonly http: HttpClient) {}

  createQuiz(input: {
    title: string;
    description?: string | null;
    categoryName?: string | null;
  }): Observable<AdminQuizDto> {
    return this.http.post<AdminQuizDto>('/api/admin/quizzes', input, {
      withCredentials: true,
    });
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
}

