import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, interval, startWith, switchMap } from 'rxjs';
import { GameApi } from '../../core/api/game.api';
import { LobbyApi } from '../../core/api/lobby.api';
import { QuizApi } from '../../core/api/quiz.api';
import { LobbyDto } from '../../core/models/lobby.models';
import { QuizListItemDto } from '../../core/models/quiz.models';
import { SessionService } from '../../core/session/session.service';
import { GameEventsService } from '../../core/ws/game-events.service';
import { LobbyEventsService } from '../../core/ws/lobby-events.service';

@Component({
  selector: 'app-lobby',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './lobby.component.html',
  styleUrl: './lobby.component.scss'
})
export class LobbyComponent implements OnInit, OnDestroy {
  code = '';
  lobby: LobbyDto | null = null;
  quizzes: QuizListItemDto[] = [];
  selectedQuizId: number | null = null;

  password = '';
  error: string | null = null;
  joinState: 'unknown' | 'joined' | 'viewOnly' = 'unknown';

  private readonly subscriptions = new Subscription();
  private pollSubscription: Subscription | null = null;
  private gameEventsSubscription: Subscription | null = null;
  private lobbyEventsSubscription: Subscription | null = null;
  private quizzesLoaded = false;
  private unloadHandler: (() => void) | null = null;
  private autoJoinInFlight = false;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly lobbyApi: LobbyApi,
    private readonly quizApi: QuizApi,
    private readonly gameApi: GameApi,
    private readonly sessionService: SessionService,
    private readonly gameEvents: GameEventsService,
    private readonly lobbyEvents: LobbyEventsService
  ) {}

  ngOnInit(): void {
    this.subscriptions.add(
      this.route.paramMap
        .pipe(
          switchMap(params => {
            this.code = (params.get('code') ?? '').toUpperCase();
            return this.sessionService.ensure().pipe(switchMap(() => this.lobbyApi.get(this.code)));
          })
        )
        .subscribe({
          next: lobby => this.initLobby(lobby),
          error: err => {
            this.error = err?.error?.message ?? 'Lobby not found';
          }
        })
    );
  }

  ngOnDestroy(): void {
    this.unloadHandler && window.removeEventListener('beforeunload', this.unloadHandler);
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
    this.startPolling();
    this.attemptAutoJoinIfPossible(this.lobby);
  }

  private startPolling(): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = interval(1500)
      .pipe(
        startWith(0),
        switchMap(() => this.lobbyApi.get(this.code))
      )
      .subscribe({
        next: lobby => {
          this.onLobbyUpdate(lobby);
          this.attemptAutoJoinIfPossible(lobby);
          if (this.joinState === 'joined' && lobby.status === 'IN_GAME') {
            this.router.navigate(['/lobby', this.code, 'game']);
          }
        },
        error: () => {
          // ignore transient errors while typing/refreshing
        }
      });
    this.subscriptions.add(this.pollSubscription);
  }

  private ensureGameAutoSwitch(): void {
    if (this.gameEventsSubscription) return;
    this.gameEventsSubscription = this.gameEvents.subscribeLobbyGame(this.code).subscribe({
      next: () => {
        if (this.joinState !== 'joined') return;
        this.gameApi.state(this.code).subscribe({
          next: state => {
            if (state.lobbyStatus === 'IN_GAME' && state.stage !== 'NO_GAME') {
              this.router.navigate(['/lobby', this.code, 'game']);
            }
          },
          error: () => {
            // ignore
          }
        });
      },
      error: () => {
        // fallback is polling
      }
    });
    this.subscriptions.add(this.gameEventsSubscription);
  }

  private ensureLeaveOnUnload(): void {
    if (this.unloadHandler) return;
    this.unloadHandler = () => {
      try {
        navigator.sendBeacon(`/api/lobbies/${encodeURIComponent(this.code)}/leave`, '');
      } catch {
        // ignore
      }
    };
    window.addEventListener('beforeunload', this.unloadHandler);
  }

  refresh(): void {
    this.error = null;
    this.lobbyApi.get(this.code).subscribe({
      next: lobby => (this.lobby = lobby),
      error: err => (this.error = err?.error?.message ?? 'Failed to refresh lobby')
    });
  }

  private onLobbyUpdate(lobby: LobbyDto): void {
    this.lobby = lobby;

    if (lobby.isOwner && !this.quizzesLoaded) {
      this.loadQuizzes();
      this.quizzesLoaded = true;
    }

    if (lobby.isParticipant && this.joinState !== 'joined') {
      this.joinState = 'joined';
      this.ensureGameAutoSwitch();
      this.ensureLeaveOnUnload();
      return;
    }

    if (this.joinState === 'viewOnly' && lobby.status === 'OPEN' && lobby.players && lobby.maxPlayers && lobby.players.length < lobby.maxPlayers) {
      this.joinState = 'unknown';
      this.error = null;
    }
  }

  private attemptAutoJoinIfPossible(lobby: LobbyDto | null): void {
    if (!lobby) return;
    if (this.joinState === 'joined') return;
    if (lobby.hasPassword) return;
    if (lobby.status !== 'OPEN') return;
    if (lobby.players && lobby.maxPlayers && lobby.players.length >= lobby.maxPlayers) {
      this.joinState = 'viewOnly';
      return;
    }
    if (this.autoJoinInFlight) return;

    this.autoJoinInFlight = true;
    this.lobbyApi.join(this.code).subscribe({
      next: joined => {
        this.autoJoinInFlight = false;
        this.onLobbyUpdate(joined);
      },
      error: err => {
        this.autoJoinInFlight = false;
        if (err?.status === 409) {
          this.joinState = 'viewOnly';
        }
      }
    });
  }

  private ensureLobbyUpdates(): void {
    if (this.lobbyEventsSubscription) return;
    this.lobbyEventsSubscription = this.lobbyEvents.subscribeLobby(this.code).subscribe({
      next: () => {
        this.lobbyApi.get(this.code).subscribe({
          next: lobby => {
            this.onLobbyUpdate(lobby);
            this.attemptAutoJoinIfPossible(lobby);
          },
          error: () => {
            // ignore
          }
        });
      },
      error: () => {
        // polling is fallback
      }
    });
    this.subscriptions.add(this.lobbyEventsSubscription);
  }

  join(): void {
    this.error = null;
    if (this.lobby?.hasPassword && !this.password.trim()) {
      this.error = 'Password is required';
      return;
    }
    this.lobbyApi.join(this.code, this.password.trim() || undefined).subscribe({
      next: lobby => {
        this.onLobbyUpdate(lobby);
        this.startPolling();
      },
      error: err => {
        if (err?.status === 409) {
          this.joinState = 'viewOnly';
        }
        this.error = err?.error?.message ?? 'Failed to join lobby';
      }
    });
  }

  leave(): void {
    this.error = null;
    this.lobbyApi.leave(this.code).subscribe({
      next: () => this.router.navigate(['/']),
      error: err => (this.error = err?.error?.message ?? 'Failed to leave lobby')
    });
  }

  startGame(): void {
    if (!this.selectedQuizId) return;
    this.error = null;
    this.gameApi.start(this.code, this.selectedQuizId).subscribe({
      next: () => this.router.navigate(['/lobby', this.code, 'game']),
      error: err => (this.error = err?.error?.message ?? 'Failed to start game')
    });
  }

  private loadQuizzes(): void {
    this.quizApi.list().subscribe({
      next: quizzes => {
        this.quizzes = quizzes;
        if (this.selectedQuizId == null) {
          this.selectedQuizId = quizzes[0]?.id ?? null;
        }
      }
    });
  }
}
