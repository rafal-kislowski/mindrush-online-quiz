import { CommonModule } from '@angular/common';
import { Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, catchError, map, of, takeUntil } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error.util';
import { LobbyApi } from '../../core/api/lobby.api';
import { LobbyDto } from '../../core/models/lobby.models';
import {
  LeaderboardApi,
  LeaderboardEntryDto,
  LeaderboardStatsDto,
} from '../../core/api/leaderboard.api';
import { AuthService } from '../../core/auth/auth.service';
import { rankForPoints } from '../../core/progression/progression';
import { ToastService } from '../../core/ui/toast.service';

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
  readonly leaderboardSize = 5;
  readonly leaderboardSkeleton = Array.from({ length: this.leaderboardSize });
  private readonly minLobbyTransitionMs = 1500;

  get authUser$() {
    return this.auth.user$;
  }

  joinCode = '';
  joinCodeFocused = false;
  joinCodeActiveIndex = 0;
  creating = false;
  joiningLobby = false;
  private _error: string | null = null;

  leaderboardLoading = true;
  private _leaderboardError: string | null = null;
  leaderboardRows: LeaderboardRowVm[] = [];
  leaderboardSlots: Array<LeaderboardRowVm | null> = Array.from({ length: this.leaderboardSize }, () => null);
  leaderboardStats: LeaderboardStatsDto | null = null;

  currentUserId: number | null = null;
  private readonly destroy$ = new Subject<void>();
  private transitionDelayTimer: number | null = null;
  @ViewChild('joinCodeInput')
  private joinCodeInput?: ElementRef<HTMLInputElement>;

  constructor(
    private readonly lobbyApi: LobbyApi,
    private readonly leaderboardApi: LeaderboardApi,
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
    this.toast.error(value, { dedupeKey: `home:error:${value}` });
  }

  get leaderboardError(): string | null {
    return this._leaderboardError;
  }

  set leaderboardError(value: string | null) {
    this._leaderboardError = value;
    if (!value) return;
    this.toast.warning(value, { title: 'Leaderboards', dedupeKey: `home:leaderboards:${value}` });
  }

  ngOnInit(): void {
    this.auth.ensureLoaded().subscribe({ error: () => {} });
    this.auth.user$.pipe(takeUntil(this.destroy$)).subscribe((u) => {
      this.currentUserId = u?.id ?? null;
    });

    this.leaderboardApi
      .list(this.leaderboardSize)
      .pipe(
        map((list) =>
          list.map((p) => {
            const rank = rankForPoints(p.rankPoints);
            return {
              ...p,
              rankName: rank.name,
              rankColor: rank.color,
              rankAccent: this.hexToRgba(rank.color, 0.18),
              initials: this.initialsFrom(p.displayName),
            };
          })
        ),
        catchError((err) => {
          this.leaderboardError = apiErrorMessage(err, 'Failed to load leaderboards');
          return of([] as LeaderboardRowVm[]);
        })
      )
      .subscribe((rows) => {
        const topRows = rows.slice(0, this.leaderboardSize);
        this.leaderboardRows = topRows;
        this.leaderboardSlots = [
          ...topRows,
          ...Array.from({ length: Math.max(0, this.leaderboardSize - topRows.length) }, () => null),
        ];
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
    if (this.transitionDelayTimer != null) {
      window.clearTimeout(this.transitionDelayTimer);
      this.transitionDelayTimer = null;
    }
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

  get lobbyNavigationBusy(): boolean {
    return this.creating || this.joiningLobby;
  }

  createLobby(): void {
    if (this.lobbyNavigationBusy) return;
    this.error = null;
    this.lobbyApi.getCurrent().subscribe({
      next: (currentLobby) => {
        const currentCode = (currentLobby?.code ?? '').trim().toUpperCase();
        if (currentCode) {
          this.openOwnedLobbyInstant(currentCode, currentLobby);
          return;
        }
        this.createNewLobbyWithTransition();
      },
      error: () => {
        this.createNewLobbyWithTransition();
      },
    });
  }

  private createNewLobbyWithTransition(): void {
    this.creating = true;
    const startedAt = performance.now();
    this.lobbyApi
      .create({})
      .subscribe({
        next: (res) => this.joinCreatedLobbyThenNavigate(res.code, startedAt),
        error: (err) => {
          this.lobbyApi.getCurrent().subscribe({
            next: (currentLobby) => {
              const currentCode = (currentLobby?.code ?? '').trim().toUpperCase();
              if (currentCode) {
                this.openOwnedLobbyInstant(currentCode, currentLobby);
                return;
              }
              const message = apiErrorMessage(err, 'Failed to create lobby');
              this.runAfterMinimumTransition(startedAt, () => {
                this.error = message;
                this.creating = false;
                this.joiningLobby = false;
              });
            },
            error: () => {
              const message = apiErrorMessage(err, 'Failed to create lobby');
              this.runAfterMinimumTransition(startedAt, () => {
                this.error = message;
                this.creating = false;
                this.joiningLobby = false;
              });
            },
          });
        },
      });
  }

  private openOwnedLobbyInstant(
    code: string,
    prefetchedLobby?: LobbyDto | null
  ): void {
    const targetCode = (code ?? '').trim().toUpperCase();
    if (!targetCode) return;
    this.creating = false;
    this.joiningLobby = false;
    const state: Record<string, unknown> = {
      suppressInitialLobbyOverlay: true,
      lobbyCode: targetCode,
    };
    if (
      prefetchedLobby &&
      (prefetchedLobby.code ?? '').trim().toUpperCase() === targetCode
    ) {
      state['prefetchedLobby'] = prefetchedLobby;
    }

    void this.router
      .navigate(['/lobby', targetCode], { state })
      .then((ok) => {
        if (ok) return;
        this.error = 'Failed to open lobby';
      })
      .catch(() => {
        this.error = 'Failed to open lobby';
      });
  }

  private rejoinOwnedLobbyWithTransition(
    code: string,
    prefetchedLobby?: LobbyDto | null
  ): void {
    const targetCode = (code ?? '').trim().toUpperCase();
    if (!targetCode) return;

    this.creating = true;
    const startedAt = performance.now();

    this.lobbyApi.join(targetCode).subscribe({
      next: (joined) =>
        this.runAfterMinimumTransition(startedAt, () =>
          this.navigateToLobby(targetCode, joined)
        ),
      error: () =>
        this.runAfterMinimumTransition(startedAt, () =>
          this.navigateToLobby(targetCode, prefetchedLobby ?? null)
        ),
    });
  }

  joinLobby(): void {
    if (this.lobbyNavigationBusy) return;
    const code = this.joinCode.trim().toUpperCase();
    if (code.length !== this.joinCodeLength) return;
    this.error = null;
    this.joiningLobby = true;
    const startedAt = performance.now();
    this.lobbyApi.getCurrent().subscribe({
      next: (currentLobby) => {
        const currentCode = (currentLobby?.code ?? '').trim().toUpperCase();
        if (currentCode && currentCode !== code) {
          this.notifyMustLeaveCurrentLobby(currentCode);
          this.runAfterMinimumTransition(startedAt, () =>
            this.navigateToLobby(currentCode, currentLobby)
          );
          return;
        }
        this.continueJoinLobbyByCode(code, startedAt);
      },
      error: () => this.continueJoinLobbyByCode(code, startedAt),
    });
  }

  private continueJoinLobbyByCode(code: string, startedAt: number): void {
    this.lobbyApi.get(code).subscribe({
      next: (lobby) => {
        const requiresPin = !!lobby?.hasPassword && !lobby?.isOwner;
        if (requiresPin) {
          this.runAfterMinimumTransition(startedAt, () =>
            this.navigateToLobby(code)
          );
          return;
        }
        this.joinPublicLobbyThenNavigate(code, startedAt);
      },
      error: (err) => {
        const message =
          err?.status === 404
            ? 'Lobby with this code does not exist'
            : apiErrorMessage(err, 'Failed to open lobby');
        this.runAfterMinimumTransition(startedAt, () => {
          this.joiningLobby = false;
          this.clearJoinCodeInput(true);
          this.error = message;
        });
      },
    });
  }

  private joinCreatedLobbyThenNavigate(code: string, startedAt: number): void {
    const targetCode = (code ?? '').trim().toUpperCase();
    if (!targetCode) {
      this.creating = false;
      this.joiningLobby = false;
      this.error = 'Failed to create lobby';
      return;
    }

    // Keep create flow resilient: if eager join fails, fall back to legacy open.
    this.lobbyApi.join(targetCode).subscribe({
      next: (lobby) =>
        this.runAfterMinimumTransition(startedAt, () =>
          this.navigateToLobby(targetCode, lobby)
        ),
      error: () =>
        this.runAfterMinimumTransition(startedAt, () =>
          this.navigateToLobby(targetCode)
        ),
    });
  }

  private joinPublicLobbyThenNavigate(code: string, startedAt: number): void {
    const targetCode = (code ?? '').trim().toUpperCase();
    if (!targetCode) {
      this.joiningLobby = false;
      return;
    }

    this.lobbyApi.join(targetCode).subscribe({
      next: (lobby) =>
        this.runAfterMinimumTransition(startedAt, () =>
          this.navigateToLobby(targetCode, lobby)
        ),
      error: (err) => {
        if (err?.status === 409) {
          this.lobbyApi.getCurrent().subscribe({
            next: (currentLobby) => {
              const currentCode = (currentLobby?.code ?? '').trim().toUpperCase();
              if (currentCode) {
                if (currentCode !== targetCode) {
                  this.notifyMustLeaveCurrentLobby(currentCode);
                }
                this.runAfterMinimumTransition(startedAt, () =>
                  this.navigateToLobby(currentCode, currentLobby)
                );
                return;
              }
              const message = apiErrorMessage(err, 'Failed to join lobby');
              this.runAfterMinimumTransition(startedAt, () => {
                this.joiningLobby = false;
                this.clearJoinCodeInput(true);
                this.error = message;
              });
            },
            error: () => {
              const message = apiErrorMessage(err, 'Failed to join lobby');
              this.runAfterMinimumTransition(startedAt, () => {
                this.joiningLobby = false;
                this.clearJoinCodeInput(true);
                this.error = message;
              });
            },
          });
          return;
        }
        const message = apiErrorMessage(err, 'Failed to join lobby');
        this.runAfterMinimumTransition(startedAt, () => {
          this.joiningLobby = false;
          this.clearJoinCodeInput(true);
          this.error = message;
        });
      },
    });
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

  private navigateToLobby(code: string, prefetchedLobby?: LobbyDto | null): void {
    const targetCode = (code ?? '').trim().toUpperCase();
    if (!targetCode) {
      this.creating = false;
      this.joiningLobby = false;
      return;
    }

    const state =
      prefetchedLobby && (prefetchedLobby.code ?? '').trim().toUpperCase() === targetCode
        ? {
            suppressInitialLobbyOverlay: true,
            lobbyCode: targetCode,
            prefetchedLobby,
          }
        : undefined;

    this.joiningLobby = true;
    void this.router
      .navigate(['/lobby', targetCode], state ? { state } : undefined)
      .then((ok) => {
        if (ok) return;
        this.onLobbyNavigationFailed();
      })
      .catch(() => this.onLobbyNavigationFailed());
  }

  private onLobbyNavigationFailed(): void {
    this.creating = false;
    this.joiningLobby = false;
    this.error = 'Failed to open lobby';
  }

  private runAfterMinimumTransition(
    startedAt: number,
    action: () => void
  ): void {
    const elapsed = performance.now() - startedAt;
    const remaining = Math.max(0, this.minLobbyTransitionMs - elapsed);
    if (remaining <= 0) {
      action();
      return;
    }
    if (this.transitionDelayTimer != null) {
      window.clearTimeout(this.transitionDelayTimer);
    }
    this.transitionDelayTimer = window.setTimeout(() => {
      this.transitionDelayTimer = null;
      action();
    }, remaining);
  }

  private clearJoinCodeInput(focus: boolean): void {
    this.joinCode = '';
    this.joinCodeActiveIndex = 0;
    if (!focus) return;

    const input = this.joinCodeInput?.nativeElement;
    if (!input) return;
    try {
      input.focus({ preventScroll: true } as any);
    } catch {
      input.focus();
    }
    this.joinCodeFocused = true;
  }

  private notifyMustLeaveCurrentLobby(currentCode: string): void {
    this.toast.warning(
      `You are already in lobby ${currentCode}. Leave your current lobby before joining another one.`,
      {
        title: 'Lobby',
        dedupeKey: `home:lobby-switch-blocked:${currentCode}`,
      }
    );
  }
}
