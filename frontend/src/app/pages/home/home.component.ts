import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, catchError, map, of, takeUntil } from 'rxjs';
import { LobbyApi } from '../../core/api/lobby.api';
import {
  LeaderboardApi,
  LeaderboardEntryDto,
  LeaderboardStatsDto,
} from '../../core/api/leaderboard.api';
import { AuthService } from '../../core/auth/auth.service';
import { rankForPoints } from '../../core/progression/progression';

type LeaderboardRowVm = LeaderboardEntryDto & {
  rankName: string;
  rankColor: string;
  rankAccent: string;
  initials: string;
};

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent implements OnInit, OnDestroy {
  readonly joinCodeLength = 6;
  readonly joinCodeSlots = Array.from({ length: this.joinCodeLength });
  readonly leaderboardSkeleton = Array.from({ length: 5 });

  get authUser$() {
    return this.auth.user$;
  }

  joinCode = '';
  joinCodeFocused = false;
  joinCodeActiveIndex = 0;
  creating = false;
  error: string | null = null;

  leaderboardLoading = true;
  leaderboardError: string | null = null;
  leaderboardRows: LeaderboardRowVm[] = [];
  leaderboardStats: LeaderboardStatsDto | null = null;

  currentUserId: number | null = null;
  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly lobbyApi: LobbyApi,
    private readonly leaderboardApi: LeaderboardApi,
    private readonly auth: AuthService,
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    this.auth.ensureLoaded().subscribe({ error: () => {} });
    this.auth.user$.pipe(takeUntil(this.destroy$)).subscribe((u) => {
      this.currentUserId = u?.id ?? null;
    });

    this.leaderboardApi
      .list(5)
      .pipe(
        map((list) =>
          list.map((p) => {
            const rank = rankForPoints(p.rankPoints);
            return {
              ...p,
              rankName: rank.name,
              rankColor: rank.color,
              rankAccent: this.hexToRgba(rank.color, 0.26),
              initials: this.initialsFrom(p.displayName),
            };
          })
        ),
        catchError((err) => {
          this.leaderboardError =
            err?.error?.message ?? 'Failed to load leaderboards';
          return of([] as LeaderboardRowVm[]);
        })
      )
      .subscribe((rows) => {
        this.leaderboardRows = rows;
        this.leaderboardLoading = false;
      });

    this.leaderboardApi
      .stats()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (s) => (this.leaderboardStats = s),
        error: () => {},
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  initialsFrom(name: string): string {
    const trimmed = (name ?? '').trim();
    if (!trimmed) return '?';
    const parts = trimmed.split(/\s+/).slice(0, 2);
    const letters = parts
      .map((p) => p.replace(/[^A-Za-z0-9]/g, '').slice(0, 1))
      .join('')
      .toUpperCase();
    return letters || trimmed.slice(0, 1).toUpperCase();
  }

  private hexToRgba(hex: string, a: number): string {
    const alpha = Math.max(0, Math.min(1, a));
    const h = String(hex ?? '').replace('#', '').trim();
    if (h.length !== 6) return `rgba(56,189,248,${alpha})`;
    const n = Number.parseInt(h, 16);
    if (!Number.isFinite(n)) return `rgba(56,189,248,${alpha})`;
    const r = (n >> 16) & 255;
    const g = (n >> 8) & 255;
    const b = n & 255;
    return `rgba(${r},${g},${b},${alpha})`;
  }

  avatarGradient(userId: number): string {
    const n = Math.abs(Math.floor(userId || 0));
    const hue1 = n % 360;
    const hue2 = (hue1 + 35) % 360;
    return `linear-gradient(135deg, hsla(${hue1}, 90%, 60%, 0.95), hsla(${hue2}, 90%, 55%, 0.85))`;
  }


  createLobby(): void {
    this.error = null;
    this.creating = true;
    this.lobbyApi
      .create({})
      .subscribe({
        next: (res) => this.router.navigate(['/lobby', res.code]),
        error: (err) => {
          this.error = err?.error?.message ?? 'Failed to create lobby';
          this.creating = false;
        },
      });
  }

  joinLobby(): void {
    const code = this.joinCode.trim().toUpperCase();
    if (!code) return;
    this.router.navigate(['/lobby', code]);
  }

  isJoinCodeComplete(): boolean {
    return this.joinCode.trim().length === this.joinCodeLength;
  }

  onJoinCodeFocus(): void {
    this.joinCodeFocused = true;
    this.joinCodeActiveIndex = Math.min(
      this.joinCode.length,
      this.joinCodeLength - 1
    );
  }

  onJoinCodeBlur(): void {
    this.joinCodeFocused = false;
  }

  onJoinCodeChange(value: string): void {
    this.joinCode = value
      .toUpperCase()
      .replace(/[^A-Z0-9]/g, '')
      .slice(0, this.joinCodeLength);
    this.joinCodeActiveIndex = Math.min(
      this.joinCode.length,
      this.joinCodeLength - 1
    );
  }
}
