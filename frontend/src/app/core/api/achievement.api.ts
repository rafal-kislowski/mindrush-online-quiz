import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface AchievementItemDto {
  key: string;
  title: string;
  description: string;
  icon: string;
  category: 'gameplay' | 'competitive' | 'progression' | 'creator' | string;
  tier: 'rookie' | 'bronze' | 'silver' | 'gold' | 'platinum' | 'diamond' | 'master' | 'legend' | 'mythic' | string;
  tierColor: string;
  target: number;
  progress: number;
  unlocked: boolean;
  unlockedAt: string | null;
}

export interface AchievementListResponseDto {
  title: string;
  description: string;
  totalCount: number;
  unlockedCount: number;
  completionPct: number;
  items: AchievementItemDto[];
}

@Injectable({ providedIn: 'root' })
export class AchievementApi {
  constructor(private readonly http: HttpClient) {}

  mine(): Observable<AchievementListResponseDto> {
    return this.http.get<AchievementListResponseDto>('/api/achievements/me', {
      withCredentials: true,
    });
  }
}
