import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { ActiveLobbyDto, LobbyDto } from '../models/lobby.models';

@Injectable({ providedIn: 'root' })
export class LobbyApi {
  constructor(private readonly http: HttpClient) {}

  create(params: { password?: string; maxPlayers?: number } = {}): Observable<{ code: string }> {
    const body: Record<string, unknown> = {};
    if (params.password) body['password'] = params.password;
    if (params.maxPlayers != null) body['maxPlayers'] = params.maxPlayers;
    return this.http.post<{ code: string }>('/api/lobbies', body, { withCredentials: true });
  }

  get(code: string): Observable<LobbyDto> {
    return this.http.get<LobbyDto>(`/api/lobbies/${encodeURIComponent(code)}`, { withCredentials: true });
  }

  listActive(): Observable<ActiveLobbyDto[]> {
    return this.http.get<ActiveLobbyDto[]>('/api/lobbies/active', { withCredentials: true });
  }

  getOwnedOpen(): Observable<LobbyDto | null> {
    return this.http
      .get<LobbyDto | null>('/api/lobbies/owned', {
        withCredentials: true,
        observe: 'response',
      })
      .pipe(map((response) => response.body ?? null));
  }

  getCurrent(): Observable<LobbyDto | null> {
    return this.http
      .get<LobbyDto | null>('/api/lobbies/current', {
        withCredentials: true,
        observe: 'response',
      })
      .pipe(map((response) => response.body ?? null));
  }

  join(code: string, password?: string): Observable<LobbyDto> {
    const body = password ? { password } : {};
    return this.http.post<LobbyDto>(`/api/lobbies/${encodeURIComponent(code)}/join`, body, { withCredentials: true });
  }

  leave(code: string): Observable<unknown> {
    return this.http.post(`/api/lobbies/${encodeURIComponent(code)}/leave`, {}, { withCredentials: true });
  }

  setPassword(code: string, password?: string): Observable<LobbyDto> {
    const body = password ? { password } : {};
    return this.http.post<LobbyDto>(`/api/lobbies/${encodeURIComponent(code)}/password`, body, { withCredentials: true });
  }

  setMaxPlayers(code: string, maxPlayers: number): Observable<LobbyDto> {
    return this.http.post<LobbyDto>(
      `/api/lobbies/${encodeURIComponent(code)}/max-players`,
      { maxPlayers },
      { withCredentials: true }
    );
  }

  setSelectedQuiz(code: string, quizId: number | null): Observable<LobbyDto> {
    return this.http.post<LobbyDto>(
      `/api/lobbies/${encodeURIComponent(code)}/selected-quiz`,
      { quizId },
      { withCredentials: true }
    );
  }

  setReady(code: string, ready: boolean): Observable<LobbyDto> {
    return this.http.post<LobbyDto>(
      `/api/lobbies/${encodeURIComponent(code)}/ready`,
      { ready },
      { withCredentials: true }
    );
  }

  kickPlayer(code: string, participantId: number): Observable<LobbyDto> {
    return this.http.post<LobbyDto>(
      `/api/lobbies/${encodeURIComponent(code)}/players/${participantId}/kick`,
      {},
      { withCredentials: true }
    );
  }

  banPlayer(code: string, participantId: number): Observable<LobbyDto> {
    return this.http.post<LobbyDto>(
      `/api/lobbies/${encodeURIComponent(code)}/players/${participantId}/ban`,
      {},
      { withCredentials: true }
    );
  }
}
