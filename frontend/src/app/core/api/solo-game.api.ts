import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { GameStartMode, GameStateDto } from '../models/game.models';

@Injectable({ providedIn: 'root' })
export class SoloGameApi {
  constructor(private readonly http: HttpClient) {}

  start(quizId: number, mode: GameStartMode = 'STANDARD'): Observable<GameStateDto> {
    return this.http.post<GameStateDto>(
      '/api/solo-games/start',
      { quizId, mode },
      { withCredentials: true }
    );
  }

  state(gameSessionId: string): Observable<GameStateDto> {
    return this.http.get<GameStateDto>(
      `/api/solo-games/${encodeURIComponent(gameSessionId)}/state`,
      { withCredentials: true }
    );
  }

  answer(gameSessionId: string, questionId: number, optionId: number): Observable<GameStateDto> {
    return this.http.post<GameStateDto>(
      `/api/solo-games/${encodeURIComponent(gameSessionId)}/answer`,
      { questionId, optionId },
      { withCredentials: true }
    );
  }

  end(gameSessionId: string): Observable<GameStateDto> {
    return this.http.post<GameStateDto>(
      `/api/solo-games/${encodeURIComponent(gameSessionId)}/end`,
      {},
      { withCredentials: true }
    );
  }
}
