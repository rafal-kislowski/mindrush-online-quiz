import { CommonModule } from '@angular/common';
import { Component, ElementRef, HostListener, OnDestroy, OnInit } from '@angular/core';
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
  readonly joinCodeIndices = Array.from(
    { length: this.joinCodeLength },
    (_, i) => i
  );

  get authUser$() {
    return this.auth.user$;
  }

  joinCode = '';
  joinCodeChars = Array.from({ length: this.joinCodeLength }, () => '');
  creating = false;
  error: string | null = null;
  createPassword = '';
  createMaxPlayers: 2 | 3 | 4 | 5 = 2;
  maxPlayersMenuOpen = false;
  maxPlayersMenuDirection: 'down' | 'up' = 'down';

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
    private readonly router: Router,
    private readonly el: ElementRef<HTMLElement>
  ) {}

  ngOnInit(): void {
    this.auth.ensureLoaded().subscribe({ error: () => {} });
    this.auth.user$.pipe(takeUntil(this.destroy$)).subscribe((u) => {
      this.currentUserId = u?.id ?? null;
    });

    this.leaderboardApi
      .list(6)
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
          this.leaderboardError =
            err?.error?.message ?? 'Failed to load leaderboards';
          return of([] as LeaderboardRowVm[]);
        })
      )
      .subscribe((rows) => {
        this.leaderboardRows = rows;
        this.leaderboardLoading = false;
      });

    this.leaderboardApi.stats().subscribe({
      next: (s) => (this.leaderboardStats = s),
      error: () => {},
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  readonly steps: Array<{ n: number; label: string }> = [
    { n: 1, label: 'Enter as guest' },
    { n: 2, label: 'Create or join lobby' },
    { n: 3, label: 'Play in real time' },
  ];

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

  trophyClass(i: number): string | null {
    if (i === 0) return 'trophy trophy--gold';
    if (i === 1) return 'trophy trophy--silver';
    if (i === 2) return 'trophy trophy--bronze';
    return null;
  }

  createLobby(): void {
    this.error = null;
    this.creating = true;
    const password = this.createPassword.trim();
    const isLoggedIn = !!this.auth.snapshot;
    const maxPlayers = isLoggedIn ? this.createMaxPlayers : undefined;

    this.lobbyApi
      .create({
        password: password ? password : undefined,
        maxPlayers,
      })
      .subscribe({
        next: (res) => this.router.navigate(['/lobby', res.code]),
        error: (err) => {
          this.error = err?.error?.message ?? 'Failed to create lobby';
          this.creating = false;
        },
      });
  }

  toggleMaxPlayersMenu(ev?: MouseEvent): void {
    const next = !this.maxPlayersMenuOpen;
    this.maxPlayersMenuOpen = next;

    if (!next) return;

    const target = ev?.currentTarget;
    if (!(target instanceof HTMLElement)) return;

    const rect = target.getBoundingClientRect();
    const menuWantedHeight = 220;
    const spaceBelow = window.innerHeight - rect.bottom;
    const spaceAbove = rect.top;
    this.maxPlayersMenuDirection =
      spaceBelow < menuWantedHeight && spaceAbove > spaceBelow ? 'up' : 'down';
  }

  setCreateMaxPlayers(n: 2 | 3 | 4 | 5): void {
    this.createMaxPlayers = n;
    this.maxPlayersMenuOpen = false;
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(ev: MouseEvent): void {
    if (!this.maxPlayersMenuOpen) return;
    const target = ev.target as Node | null;
    if (!target) return;
    if (!this.el.nativeElement.contains(target)) {
      this.maxPlayersMenuOpen = false;
    }
  }

  @HostListener('document:keydown', ['$event'])
  onDocumentKeydown(ev: KeyboardEvent): void {
    if (!this.maxPlayersMenuOpen) return;
    if (ev.key === 'Escape') {
      this.maxPlayersMenuOpen = false;
    }
  }

  joinLobby(): void {
    const code = this.joinCode.trim().toUpperCase();
    if (!code) return;
    this.router.navigate(['/lobby', code]);
  }

  isJoinCodeComplete(): boolean {
    return this.joinCode.trim().length === 6;
  }

  onJoinCodeChange(value: string): void {
    this.joinCode = value
      .toUpperCase()
      .replace(/[^A-Z0-9]/g, '')
      .slice(0, 6);
    this.joinCodeChars = Array.from(
      { length: this.joinCodeLength },
      (_, i) => this.joinCode[i] ?? ''
    );
  }
}
