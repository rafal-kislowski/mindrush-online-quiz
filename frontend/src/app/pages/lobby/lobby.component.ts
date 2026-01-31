import { CommonModule } from '@angular/common';
import { Component, ElementRef, HostListener, OnDestroy, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, interval, startWith, switchMap } from 'rxjs';
import { GameApi } from '../../core/api/game.api';
import { LobbyApi } from '../../core/api/lobby.api';
import { QuizApi } from '../../core/api/quiz.api';
import { LobbyDto } from '../../core/models/lobby.models';
import { QuizListItemDto } from '../../core/models/quiz.models';
import { AuthService } from '../../core/auth/auth.service';
import { SessionService } from '../../core/session/session.service';
import { GameEventsService } from '../../core/ws/game-events.service';
import { LobbyEventsService } from '../../core/ws/lobby-events.service';
import { StompClientService } from '../../core/ws/stomp-client.service';

@Component({
  selector: 'app-lobby',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './lobby.component.html',
  styleUrl: './lobby.component.scss',
})
export class LobbyComponent implements OnInit, OnDestroy {
  private static readonly POLL_FAST_MS = 1500;

  get authUser$() {
    return this.auth.user$;
  }
  readonly maxPlayersOptions = [2, 3, 4, 5] as const;

  code = '';
  lobby: LobbyDto | null = null;
  quizzes: QuizListItemDto[] = [];
  selectedQuizId: number | null = null;

  password = '';
  error: string | null = null;
  joinState: 'unknown' | 'joined' | 'viewOnly' = 'unknown';
  get session$() {
    return this.sessionService.session$;
  }

  privacyMode: 'public' | 'private' = 'public';
  privacyPassword = '';
  privacyShowPassword = false;
  privacySaving = false;
  private privacyDirty = false;

  maxPlayersDraft: 2 | 3 | 4 | 5 = 2;
  maxPlayersSaving = false;
  private maxPlayersDirty = false;
  maxPlayersMenuOpen = false;
  quizMenuOpen = false;

  private readonly subscriptions = new Subscription();
  private pollSubscription: Subscription | null = null;
  private gameEventsSubscription: Subscription | null = null;
  private lobbyEventsSubscription: Subscription | null = null;
  private quizzesLoaded = false;
  private unloadHandler: (() => void) | null = null;
  private autoJoinInFlight = false;
  private pollMs = LobbyComponent.POLL_FAST_MS;
  private wsConnected = false;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly lobbyApi: LobbyApi,
    private readonly quizApi: QuizApi,
    private readonly gameApi: GameApi,
    private readonly auth: AuthService,
    private readonly sessionService: SessionService,
    private readonly gameEvents: GameEventsService,
    private readonly lobbyEvents: LobbyEventsService,
    private readonly stompClient: StompClientService,
    private readonly el: ElementRef<HTMLElement>
  ) {}

  ngOnInit(): void {
    this.auth.ensureLoaded().subscribe({ error: () => {} });

    this.subscriptions.add(
      this.stompClient.state$.subscribe((state) => {
        this.wsConnected = state === 'connected';
        this.updatePollingMode();
        if (this.wsConnected && this.code) {
          this.lobbyApi.get(this.code).subscribe({
            next: (lobby) => this.onLobbyUpdate(lobby),
            error: () => {
              // ignore
            },
          });
        }
      })
    );

    this.subscriptions.add(
      this.route.paramMap
        .pipe(
          switchMap((params) => {
            this.code = (params.get('code') ?? '').toUpperCase();
            return this.sessionService
              .ensure()
              .pipe(switchMap(() => this.lobbyApi.get(this.code)));
          })
        )
        .subscribe({
          next: (lobby) => this.initLobby(lobby),
          error: (err) => {
            this.error = err?.error?.message ?? 'Lobby not found';
          },
        })
    );
  }

  ngOnDestroy(): void {
    this.unloadHandler &&
      window.removeEventListener('beforeunload', this.unloadHandler);
    this.subscriptions.unsubscribe();
  }

  private initLobby(lobby: LobbyDto): void {
    this.onLobbyUpdate(lobby);
    if (lobby.isOwner && !this.quizzesLoaded) {
      this.loadQuizzes();
      this.quizzesLoaded = true;
    }

    if (this.lobby?.status === 'IN_GAME' && this.joinState === 'joined') {
      this.router.navigate(['/lobby', this.code, 'game']);
      return;
    }

    this.ensureLobbyUpdates();
    this.updatePollingMode();
    this.attemptAutoJoinIfPossible(this.lobby);
  }

  private stopPolling(): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = null;
  }

  private startPolling(): void {
    if (!this.code) return;
    this.stopPolling();
    this.pollSubscription = interval(this.pollMs)
      .pipe(
        startWith(0),
        switchMap(() => this.lobbyApi.get(this.code))
      )
      .subscribe({
        next: (lobby) => {
          this.onLobbyUpdate(lobby);
          this.attemptAutoJoinIfPossible(lobby);
          if (this.joinState === 'joined' && lobby.status === 'IN_GAME') {
            this.router.navigate(['/lobby', this.code, 'game']);
          }
        },
        error: () => {
          // ignore transient errors while typing/refreshing
        },
      });
    this.subscriptions.add(this.pollSubscription);
  }

  private updatePollingMode(): void {
    if (this.wsConnected) {
      this.stopPolling();
      return;
    }

    this.pollMs = LobbyComponent.POLL_FAST_MS;
    if (!this.pollSubscription) this.startPolling();
  }

  private ensureGameAutoSwitch(): void {
    if (this.gameEventsSubscription) return;
    this.gameEventsSubscription = this.gameEvents
      .subscribeLobbyGame(this.code)
      .subscribe({
        next: () => {
          if (this.joinState !== 'joined') return;
          this.gameApi.state(this.code).subscribe({
            next: (state) => {
              if (
                state.lobbyStatus === 'IN_GAME' &&
                state.stage !== 'NO_GAME'
              ) {
                this.router.navigate(['/lobby', this.code, 'game']);
              }
            },
            error: () => {
              // ignore
            },
          });
        },
        error: () => {
          // polling is fallback
          this.updatePollingMode();
        },
      });
    this.subscriptions.add(this.gameEventsSubscription);
  }

  private ensureLeaveOnUnload(): void {
    if (this.unloadHandler) return;
    this.unloadHandler = () => {
      try {
        navigator.sendBeacon(
          `/api/lobbies/${encodeURIComponent(this.code)}/leave`,
          ''
        );
      } catch {
        // ignore
      }
    };
    window.addEventListener('beforeunload', this.unloadHandler);
  }

  refresh(): void {
    this.error = null;
    this.lobbyApi.get(this.code).subscribe({
      next: (lobby) => (this.lobby = lobby),
      error: (err) =>
        (this.error = err?.error?.message ?? 'Failed to refresh lobby'),
    });
  }

  private onLobbyUpdate(lobby: LobbyDto): void {
    this.lobby = lobby;

    if (!this.privacyDirty) {
      this.privacyMode = lobby.hasPassword ? 'private' : 'public';
    }

    if (!this.maxPlayersDirty && lobby.maxPlayers != null) {
      const mp = lobby.maxPlayers;
      if (mp === 2 || mp === 3 || mp === 4 || mp === 5) {
        this.maxPlayersDraft = mp;
      }
    }

    if (lobby.isOwner && !this.quizzesLoaded) {
      this.loadQuizzes();
      this.quizzesLoaded = true;
    }

    if (lobby.isParticipant && this.joinState !== 'joined') {
      this.joinState = 'joined';
      this.ensureGameAutoSwitch();
      this.ensureLeaveOnUnload();
      this.updatePollingMode();
      return;
    }

    if (
      this.joinState === 'viewOnly' &&
      lobby.status === 'OPEN' &&
      lobby.players &&
      lobby.maxPlayers &&
      lobby.players.length < lobby.maxPlayers
    ) {
      this.joinState = 'unknown';
      this.error = null;
    }

    this.updatePollingMode();
  }

  private attemptAutoJoinIfPossible(lobby: LobbyDto | null): void {
    if (!lobby) return;
    if (this.joinState === 'joined') return;
    if (lobby.hasPassword && !lobby.isOwner) return;
    if (lobby.status !== 'OPEN') return;
    if (
      lobby.players &&
      lobby.maxPlayers &&
      lobby.players.length >= lobby.maxPlayers
    ) {
      this.joinState = 'viewOnly';
      return;
    }
    if (this.autoJoinInFlight) return;

    this.autoJoinInFlight = true;
    this.lobbyApi.join(this.code).subscribe({
      next: (joined) => {
        this.autoJoinInFlight = false;
        this.onLobbyUpdate(joined);
      },
      error: (err) => {
        this.autoJoinInFlight = false;
        if (err?.status === 409) {
          this.joinState = 'viewOnly';
        }
      },
    });
  }

  private ensureLobbyUpdates(): void {
    if (this.lobbyEventsSubscription) return;
    this.lobbyEventsSubscription = this.lobbyEvents
      .subscribeLobby(this.code)
      .subscribe({
        next: () => {
          this.lobbyApi.get(this.code).subscribe({
            next: (lobby) => {
              this.onLobbyUpdate(lobby);
              this.attemptAutoJoinIfPossible(lobby);
            },
            error: () => {
              // ignore
            },
          });
        },
        error: () => {
          // polling is fallback
          this.updatePollingMode();
        },
      });
    this.subscriptions.add(this.lobbyEventsSubscription);
  }

  join(): void {
    this.error = null;
    if (this.lobby?.hasPassword && !this.lobby.isOwner && !this.password.trim()) {
      this.error = 'Password is required';
      return;
    }
    this.lobbyApi.join(this.code, this.password.trim() || undefined).subscribe({
      next: (lobby) => {
        this.onLobbyUpdate(lobby);
        this.updatePollingMode();
      },
      error: (err) => {
        if (err?.status === 409) {
          this.joinState = 'viewOnly';
        }
        this.error = err?.error?.message ?? 'Failed to join lobby';
      },
    });
  }

  leave(): void {
    this.error = null;
    this.lobbyApi.leave(this.code).subscribe({
      next: () => this.router.navigate(['/']),
      error: (err) =>
        (this.error = err?.error?.message ?? 'Failed to leave lobby'),
    });
  }

  startGame(): void {
    if (!this.selectedQuizId) return;
    this.error = null;
    this.gameApi.start(this.code, this.selectedQuizId).subscribe({
      next: () => this.router.navigate(['/lobby', this.code, 'game']),
      error: (err) =>
        (this.error = err?.error?.message ?? 'Failed to start game'),
    });
  }

  private loadQuizzes(): void {
    this.quizApi.list().subscribe({
      next: (quizzes) => {
        this.quizzes = quizzes;
        if (this.selectedQuizId == null) {
          this.selectedQuizId = quizzes[0]?.id ?? null;
        }
      },
    });
  }

  setPrivacyMode(mode: 'public' | 'private'): void {
    if (this.privacySaving) return;
    this.privacyDirty = true;
    this.privacyMode = mode;

    if (mode === 'public') {
      this.privacyPassword = '';
      this.privacyShowPassword = false;
      if (!this.lobby?.hasPassword) {
        this.privacyDirty = false;
        return;
      }

      this.privacySaving = true;
      this.lobbyApi.setPassword(this.code, undefined).subscribe({
        next: (updated) => {
          this.privacySaving = false;
          this.privacyDirty = false;
          this.onLobbyUpdate(updated);
        },
        error: (err) => {
          this.privacySaving = false;
          this.privacyDirty = false;
          this.error = err?.error?.message ?? 'Failed to update lobby privacy';
          this.privacyMode = this.lobby?.hasPassword ? 'private' : 'public';
        },
      });
      return;
    }
  }

  togglePrivacyPassword(): void {
    this.privacyShowPassword = !this.privacyShowPassword;
  }

  setPrivatePassword(): void {
    if (!this.lobby?.isOwner) return;
    if (this.privacySaving) return;
    this.error = null;

    const password = this.privacyPassword.trim();
    if (!password) {
      this.error = 'Password is required to make this lobby private';
      return;
    }

    this.privacySaving = true;
    this.lobbyApi
      .setPassword(this.code, password)
      .subscribe({
        next: (updated) => {
          this.privacySaving = false;
          this.privacyDirty = false;
          this.privacyPassword = '';
          this.privacyShowPassword = false;
          this.onLobbyUpdate(updated);
        },
        error: (err) => {
          this.privacySaving = false;
          this.error = err?.error?.message ?? 'Failed to update lobby privacy';
        },
      });
  }

  onMaxPlayersChange(value: number): void {
    this.maxPlayersDirty = true;
    this.maxPlayersDraft = value as 2 | 3 | 4 | 5;
  }

  toggleMaxPlayersMenu(): void {
    if (this.maxPlayersSaving) return;
    this.maxPlayersMenuOpen = !this.maxPlayersMenuOpen;
    if (this.maxPlayersMenuOpen) this.quizMenuOpen = false;
  }

  setMaxPlayersFromMenu(value: 2 | 3 | 4 | 5): void {
    if (this.lobby?.players && value < this.lobby.players.length) return;
    this.onMaxPlayersChange(value);
    this.maxPlayersMenuOpen = false;
    this.saveMaxPlayers();
  }

  toggleQuizMenu(): void {
    this.quizMenuOpen = !this.quizMenuOpen;
    if (this.quizMenuOpen) this.maxPlayersMenuOpen = false;
  }

  selectQuizFromMenu(id: number): void {
    this.selectedQuizId = id;
    this.quizMenuOpen = false;
  }

  get selectedQuizLabel(): string {
    const selectedId = this.selectedQuizId;
    const found = selectedId == null ? null : this.quizzes.find((q) => q.id === selectedId);
    return found?.title ?? (this.quizzes[0]?.title ?? 'Select quiz');
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(ev: MouseEvent): void {
    if (!this.maxPlayersMenuOpen && !this.quizMenuOpen) return;
    const target = ev.target as HTMLElement | null;
    if (!target) return;
    if (target.closest('.mr-select-wrap')) return;
    this.maxPlayersMenuOpen = false;
    this.quizMenuOpen = false;
  }

  @HostListener('document:keydown', ['$event'])
  onDocumentKeydown(ev: KeyboardEvent): void {
    if (ev.key === 'Escape') {
      this.maxPlayersMenuOpen = false;
      this.quizMenuOpen = false;
    }
  }

  saveMaxPlayers(): void {
    if (!this.lobby?.isOwner) return;
    if (!this.lobby.players) return;
    if (this.maxPlayersSaving) return;

    this.error = null;
    const previous = this.lobby.maxPlayers;
    const currentPlayers = this.lobby.players.length;
    const desired = this.maxPlayersDraft;

    if (desired < currentPlayers) {
      this.error = `Cannot set max players below current players (${currentPlayers})`;
      return;
    }

    const isLoggedIn = !!this.auth.snapshot;
    if (!isLoggedIn && desired > 2) {
      this.error = 'Login required for lobbies larger than 2 players';
      return;
    }

    this.maxPlayersSaving = true;
    this.lobbyApi.setMaxPlayers(this.code, desired).subscribe({
      next: (updated) => {
        this.maxPlayersSaving = false;
        this.maxPlayersDirty = false;
        this.onLobbyUpdate(updated);
      },
      error: (err) => {
        this.maxPlayersSaving = false;
        this.maxPlayersDirty = false;
        if (previous === 2 || previous === 3 || previous === 4 || previous === 5) {
          this.maxPlayersDraft = previous;
        }
        this.error = err?.error?.message ?? 'Failed to update max players';
      },
    });
  }
}
