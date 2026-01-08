import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { QuizListItemDto } from '../models/quiz.models';

@Injectable({ providedIn: 'root' })
export class QuizApi {
  constructor(private readonly http: HttpClient) {}

  list(): Observable<QuizListItemDto[]> {
    return this.http.get<QuizListItemDto[]>('/api/quizzes', { withCredentials: true });
  }
}

