import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { LobbyDto } from '../models/lobby.models';

@Injectable({ providedIn: 'root' })
export class LobbyApi {
  constructor(private readonly http: HttpClient) {}

  create(password?: string): Observable<{ code: string }> {
    const body = password ? { password } : {};
    return this.http.post<{ code: string }>('/api/lobbies', body, { withCredentials: true });
  }

  get(code: string): Observable<LobbyDto> {
    return this.http.get<LobbyDto>(`/api/lobbies/${encodeURIComponent(code)}`, { withCredentials: true });
  }

  join(code: string, password?: string): Observable<LobbyDto> {
    const body = password ? { password } : {};
    return this.http.post<LobbyDto>(`/api/lobbies/${encodeURIComponent(code)}/join`, body, { withCredentials: true });
  }

  leave(code: string): Observable<unknown> {
    return this.http.post(`/api/lobbies/${encodeURIComponent(code)}/leave`, {}, { withCredentials: true });
  }
}

