import { CommonModule } from '@angular/common';
import { Component, HostListener, OnInit } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error.util';
import {
  LeaderboardApi,
  LeaderboardEntryDto,
  LeaderboardMeDto,
  LeaderboardStatsDto,
} from '../../core/api/leaderboard.api';
import { AuthService } from '../../core/auth/auth.service';
import { rankForPoints } from '../../core/progression/progression';
import { PlayerAvatarComponent } from '../../core/ui/player-avatar.component';
import { ToastService } from '../../core/ui/toast.service';

type LeaderboardRowVm = LeaderboardEntryDto & {
  rankName: string;
  rankColor: string;
};

type LeaderboardMeVm = LeaderboardMeDto & {
  rankName: string;
  rankColor: string;
};

@Component({
  selector: 'app-leaderboards',
  standalone: true,
  imports: [CommonModule, RouterLink, PlayerAvatarComponent],
  templateUrl: './leaderboards.component.html',
  styleUrl: './leaderboards.component.scss',
})
export class LeaderboardsComponent implements OnInit {
  loading = true;
  rows: LeaderboardRowVm[] = [];
  private _error: string | null = null;

  stats: LeaderboardStatsDto | null = null;
  page = 1;
  pageSize: 10 | 25 | 50 | 100 = 10;
  pageSizeMenuOpen = false;
  readonly pageSizeOptions: ReadonlyArray<10 | 25 | 50 | 100> = [10, 25, 50, 100];

  me: LeaderboardMeVm | null = null;

  constructor(
    private readonly api: LeaderboardApi,
    private readonly auth: AuthService,
    private readonly router: Router,
    private readonly toast: ToastService
  ) {}

  get error(): string | null {
    return this._error;
  }

  set error(value: string | null) {
    this._error = value;
    if (!value) return;
    this.toast.error(value, { title: 'Leaderboards', dedupeKey: `leaderboards:error:${value}` });
  }

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
    this.pageSizeMenuOpen = false;
    this.loadPage();
  }

  next(): void {
    if (!this.canNext()) return;
    this.page += 1;
    this.pageSizeMenuOpen = false;
    this.loadPage();
  }

  setPageSize(size: 10 | 25 | 50 | 100): void {
    if (this.pageSize === size) {
      this.pageSizeMenuOpen = false;
      return;
    }
    this.pageSize = size;
    this.page = 1;
    this.pageSizeMenuOpen = false;
    this.loadPage();
  }

  onPageSizeChange(raw: string): void {
    const n = Number(raw);
    if (n === 10 || n === 25 || n === 50 || n === 100) {
      this.setPageSize(n);
      return;
    }
    this.pageSizeMenuOpen = false;
  }

  togglePageSizeMenu(ev?: Event): void {
    ev?.stopPropagation();
    this.pageSizeMenuOpen = !this.pageSizeMenuOpen;
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(ev: MouseEvent): void {
    const target = ev.target as HTMLElement | null;
    if (target?.closest('.mr-select-wrap')) return;
    this.pageSizeMenuOpen = false;
  }

  @HostListener('document:keydown.escape')
  onDocumentEscape(): void {
    this.pageSizeMenuOpen = false;
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
            };
          })
        ),
        catchError((err) => {
          this.error = apiErrorMessage(err, 'Failed to load leaderboards');
          return of([] as LeaderboardRowVm[]);
        })
      )
      .subscribe((rows) => {
        this.rows = rows;
        this.loading = false;
      });
  }

  trophyClassByPosition(pos: number): string | null {
    if (pos === 1) return 'trophy trophy--gold';
    if (pos === 2) return 'trophy trophy--silver';
    if (pos === 3) return 'trophy trophy--bronze';
    return null;
  }
}
