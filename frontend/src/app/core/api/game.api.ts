import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { GameStateDto } from '../models/game.models';

@Injectable({ providedIn: 'root' })
export class GameApi {
  constructor(private readonly http: HttpClient) {}

  start(lobbyCode: string, quizId: number): Observable<GameStateDto> {
    return this.http.post<GameStateDto>(`/api/lobbies/${encodeURIComponent(lobbyCode)}/game/start`, { quizId }, { withCredentials: true });
  }

  state(lobbyCode: string): Observable<GameStateDto> {
    return this.http.get<GameStateDto>(`/api/lobbies/${encodeURIComponent(lobbyCode)}/game/state`, { withCredentials: true });
  }

  answer(lobbyCode: string, questionId: number, optionId: number): Observable<GameStateDto> {
    return this.http.post<GameStateDto>(
      `/api/lobbies/${encodeURIComponent(lobbyCode)}/game/answer`,
      { questionId, optionId },
      { withCredentials: true }
    );
  }

  end(lobbyCode: string): Observable<GameStateDto> {
    return this.http.post<GameStateDto>(`/api/lobbies/${encodeURIComponent(lobbyCode)}/game/end`, {}, { withCredentials: true });
  }
}
