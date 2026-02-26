import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface CasualThreeLivesBestDto {
  points: number;
  answered: number;
  durationMs: number;
  updatedAt: string;
}

@Injectable({ providedIn: 'root' })
export class CasualApi {
  constructor(private readonly http: HttpClient) {}

  threeLivesBest(): Observable<CasualThreeLivesBestDto | null> {
    return this.http.get<CasualThreeLivesBestDto | null>(
      '/api/casual/three-lives/best',
      { withCredentials: true }
    );
  }
}

