import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, interval, startWith } from 'rxjs';
import { GameApi } from '../../core/api/game.api';
import { LobbyApi } from '../../core/api/lobby.api';
import { GameStateDto } from '../../core/models/game.models';
import { GameEventsService } from '../../core/ws/game-events.service';

@Component({
  selector: 'app-game',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './game.component.html',
  styleUrl: './game.component.scss',
})
export class GameComponent implements OnInit, OnDestroy {
  code = '';
  state: GameStateDto | null = null;
  remainingSeconds: number | null = null;
  error: string | null = null;
  isOwner = false;

  private readonly subscriptions = new Subscription();
  private unloadHandler: (() => void) | null = null;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly gameApi: GameApi,
    private readonly lobbyApi: LobbyApi,
    private readonly gameEvents: GameEventsService
  ) {}

  ngOnInit(): void {
    this.code = (this.route.snapshot.paramMap.get('code') ?? '').toUpperCase();
    this.lobbyApi.get(this.code).subscribe({
      next: (lobby) => (this.isOwner = lobby.isOwner === true),
      error: () => {
        // ignore
      },
    });
    this.ensureLeaveOnUnload();
    this.refresh();

    this.subscriptions.add(
      this.gameEvents.subscribeLobbyGame(this.code).subscribe({
        next: () => this.refresh(),
        error: () => {
          // REST fallback still works if WS can't connect
        },
      })
    );

    this.subscriptions.add(
      interval(100)
        .pipe(startWith(0))
        .subscribe(() => this.updateCountdown())
    );
  }

  ngOnDestroy(): void {
    this.unloadHandler &&
      window.removeEventListener('beforeunload', this.unloadHandler);
    this.subscriptions.unsubscribe();
  }

  refresh(): void {
    this.error = null;
    this.gameApi.state(this.code).subscribe({
      next: (state) => (this.state = state),
      error: (err) =>
        (this.error = err?.error?.message ?? 'Failed to load game state'),
    });
  }

  answer(optionId: number): void {
    if (!this.state?.question) return;
    this.error = null;
    this.gameApi.answer(this.code, this.state.question.id, optionId).subscribe({
      next: (state) => (this.state = state),
      error: (err) =>
        (this.error = err?.error?.message ?? 'Failed to submit answer'),
    });
  }

  end(): void {
    this.error = null;
    this.gameApi.end(this.code).subscribe({
      next: (state) => (this.state = state),
      error: (err) =>
        (this.error = err?.error?.message ?? 'Failed to end game'),
    });
  }

  backToLobby(): void {
    this.router.navigate(['/lobby', this.code]);
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

  private updateCountdown(): void {
    const endsAt = this.state?.stageEndsAt;
    if (!endsAt) {
      this.remainingSeconds = null;
      return;
    }
    const ms = new Date(endsAt).getTime() - Date.now();
    this.remainingSeconds = Math.max(0, Math.ceil(ms / 1000));
  }
}
