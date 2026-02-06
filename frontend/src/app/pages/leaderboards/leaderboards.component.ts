import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import {
  LeaderboardApi,
  LeaderboardEntryDto,
  LeaderboardMeDto,
  LeaderboardStatsDto,
} from '../../core/api/leaderboard.api';
import { AuthService } from '../../core/auth/auth.service';
import { rankForPoints } from '../../core/progression/progression';

type LeaderboardRowVm = LeaderboardEntryDto & {
  rankName: string;
  rankColor: string;
  initials: string;
};

type LeaderboardMeVm = LeaderboardMeDto & {
  rankName: string;
  rankColor: string;
  initials: string;
};

@Component({
  selector: 'app-leaderboards',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './leaderboards.component.html',
  styleUrl: './leaderboards.component.scss',
})
export class LeaderboardsComponent implements OnInit {
  loading = true;
  rows: LeaderboardRowVm[] = [];
  error: string | null = null;

  stats: LeaderboardStatsDto | null = null;
  page = 1;
  pageSize: 10 | 25 | 50 | 100 = 10;

  me: LeaderboardMeVm | null = null;

  constructor(
    private readonly api: LeaderboardApi,
    private readonly auth: AuthService,
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    this.api.stats().subscribe({
      next: (s) => (this.stats = s),
      error: () => {},
    });

    this.loadPage();

    this.auth.ensureLoaded().subscribe({
      next: (u) => {
        if (!u) {
          this.me = null;
          return;
        }
        this.api.me().subscribe({
          next: (me) => {
            const rank = rankForPoints(me.rankPoints);
            this.me = {
              ...me,
              rankName: rank.name,
              rankColor: rank.color,
              initials: this.initialsFrom(me.displayName),
            };
          },
          error: () => (this.me = null),
        });
      },
      error: () => (this.me = null),
    });
  }

  goHome(): void {
    this.router.navigate(['/']);
  }

  get totalPlayers(): number {
    return this.stats?.players ?? 0;
  }

  get totalPages(): number {
    const total = this.totalPlayers;
    const size = this.pageSize;
    return Math.max(1, Math.ceil(total / size));
  }

  get showMeRow(): boolean {
    if (!this.me) return false;
    return !this.rows.some((r) => r.userId === this.me!.userId);
  }

  rowPosition(i: number): number {
    const base = (Math.max(1, this.page) - 1) * this.pageSize;
    return base + i + 1;
  }

  canPrev(): boolean {
    return this.page > 1;
  }

  canNext(): boolean {
    return this.page < this.totalPages;
  }

  prev(): void {
    if (!this.canPrev()) return;
    this.page -= 1;
    this.loadPage();
  }

  next(): void {
    if (!this.canNext()) return;
    this.page += 1;
    this.loadPage();
  }

  setPageSize(size: 10 | 25 | 50 | 100): void {
    if (this.pageSize === size) return;
    this.pageSize = size;
    this.page = 1;
    this.loadPage();
  }

  onPageSizeChange(raw: string): void {
    const n = Number(raw);
    if (n === 10 || n === 25 || n === 50 || n === 100) {
      this.setPageSize(n);
    }
  }

  private loadPage(): void {
    this.loading = true;
    this.error = null;
    const safePage = Math.max(1, this.page);
    const safeSize = this.pageSize;

    this.api
      .listPage(safePage, safeSize)
      .pipe(
        map((list) =>
          list.map((p) => {
            const rank = rankForPoints(p.rankPoints);
            return {
              ...p,
              rankName: rank.name,
              rankColor: rank.color,
              initials: this.initialsFrom(p.displayName),
            };
          })
        ),
        catchError((err) => {
          this.error = err?.error?.message ?? 'Failed to load leaderboards';
          return of([] as LeaderboardRowVm[]);
        })
      )
      .subscribe((rows) => {
        this.rows = rows;
        this.loading = false;
      });
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

  avatarGradient(userId: number): string {
    const n = Math.abs(Math.floor(userId || 0));
    const hue1 = n % 360;
    const hue2 = (hue1 + 35) % 360;
    return `linear-gradient(135deg, hsla(${hue1}, 90%, 60%, 0.95), hsla(${hue2}, 90%, 55%, 0.85))`;
  }

  trophyClassByPosition(pos: number): string | null {
    if (pos === 1) return 'trophy trophy--gold';
    if (pos === 2) return 'trophy trophy--silver';
    if (pos === 3) return 'trophy trophy--bronze';
    return null;
  }
}
