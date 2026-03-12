import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface LeaderboardEntryDto {
  userId: number;
  displayName: string;
  rankPoints: number;
  isPremium: boolean;
}

export interface LeaderboardStatsDto {
  players: number;
  matches: number;
  answers: number;
}

export interface LeaderboardMeDto {
  userId: number;
  displayName: string;
  rankPoints: number;
  position: number;
  isPremium: boolean;
}

@Injectable({ providedIn: 'root' })
export class LeaderboardApi {
  constructor(private readonly http: HttpClient) {}

  list(limit = 50): Observable<LeaderboardEntryDto[]> {
    return this.http.get<LeaderboardEntryDto[]>(
      `/api/leaderboard?limit=${encodeURIComponent(String(limit))}`,
      { withCredentials: true }
    );
  }

  listPage(page: number, size: number): Observable<LeaderboardEntryDto[]> {
    return this.http.get<LeaderboardEntryDto[]>(
      `/api/leaderboard?page=${encodeURIComponent(String(page))}&size=${encodeURIComponent(String(size))}`,
      { withCredentials: true }
    );
  }

  stats(): Observable<LeaderboardStatsDto> {
    return this.http.get<LeaderboardStatsDto>('/api/leaderboard/stats', {
      withCredentials: true,
    });
  }

  me(): Observable<LeaderboardMeDto> {
    return this.http.get<LeaderboardMeDto>('/api/leaderboard/me', {
      withCredentials: true,
    });
  }
}
