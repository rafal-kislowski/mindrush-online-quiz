import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, interval, startWith } from 'rxjs';
import { GameApi } from '../../core/api/game.api';
import { LobbyApi } from '../../core/api/lobby.api';
import { GameStateDto } from '../../core/models/game.models';
import { SessionService } from '../../core/session/session.service';
import { GameEventsService } from '../../core/ws/game-events.service';
import { StompClientService } from '../../core/ws/stomp-client.service';

@Component({
  selector: 'app-game',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './game.component.html',
  styleUrl: './game.component.scss',
})
export class GameComponent implements OnInit, OnDestroy {
  private static readonly POLL_FAST_MS = 1500;
  private static readonly GUEST_QUESTION_MS = 10_000;
  private static readonly GUEST_REVEAL_MS = 3_000;
  private static readonly GUEST_PRE_COUNTDOWN_MS = 4_000;
  private static readonly ANSWER_FEEDBACK_MS = 1200;

  code = '';
  state: GameStateDto | null = null;
  error: string | null = null;
  isOwner = false;

  meDisplayName: string | null = null;
  progressPct = 0;
  remainingSeconds: number | null = null;
  timerNoAnim = false;
  lastQuestionProgressPct = 0;
  lastQuestionRemainingSeconds: number | null = null;

  questionResults: Array<'pending' | 'correct' | 'wrong'> = [];
  private selectedOptionIdByQuestionIndex = new Map<number, number>();
  submittingAnswer = false;
  private currentGameSessionId: string | null = null;
  private timerNoAnimRaf: number | null = null;
  private lastStageSeen: string | null = null;
  private lastQuestionIndexSeen: number | null = null;

  private readonly subscriptions = new Subscription();
  private unloadHandler: (() => void) | null = null;
  private rafId: number | null = null;
  private lastSecondsRendered: number | null = null;
  private revealPhase: 'feedback' | 'transition' = 'feedback';
  private revealPhaseTimer: number | null = null;
  private revealKey: string | null = null;
  private pollSubscription: Subscription | null = null;
  private pollMs = GameComponent.POLL_FAST_MS;
  private wsConnected = false;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly gameApi: GameApi,
    private readonly lobbyApi: LobbyApi,
    private readonly gameEvents: GameEventsService,
    private readonly sessionService: SessionService,
    private readonly stompClient: StompClientService
  ) {}

  ngOnInit(): void {
    this.code = (this.route.snapshot.paramMap.get('code') ?? '').toUpperCase();

    this.subscriptions.add(
      this.stompClient.state$.subscribe((state) => {
        this.wsConnected = state === 'connected';
        this.updatePollingMode();
        if (this.wsConnected) this.refreshSilent();
      })
    );

    this.subscriptions.add(
      this.sessionService.ensure().subscribe({
        next: (s) => (this.meDisplayName = s?.displayName ?? null),
        error: () => {
          // ignore
        },
      })
    );

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
          // polling is fallback
          this.updatePollingMode();
        },
      })
    );

    this.startCountdownLoop();
  }

  ngOnDestroy(): void {
    this.unloadHandler &&
      window.removeEventListener('beforeunload', this.unloadHandler);
    if (this.timerNoAnimRaf !== null) cancelAnimationFrame(this.timerNoAnimRaf);
    this.stopCountdownLoop();
    this.stopRevealPhaseTimer();
    this.pollSubscription?.unsubscribe();
    this.subscriptions.unsubscribe();
  }

  refresh(): void {
    this.error = null;
    this.gameApi.state(this.code).subscribe({
      next: (state) => {
        this.state = state;
        this.onStateUpdated(state);
      },
      error: (err) =>
        (this.error = err?.error?.message ?? 'Failed to load game state'),
    });
  }

  private refreshSilent(): void {
    this.gameApi.state(this.code).subscribe({
      next: (state) => {
        this.state = state;
        this.onStateUpdated(state);
      },
      error: () => {
        // ignore transient errors while reconnecting
      },
    });
  }

  private stopPolling(): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = null;
  }

  private startPolling(): void {
    this.stopPolling();
    this.pollSubscription = interval(this.pollMs)
      .pipe(startWith(0))
      .subscribe(() => this.refreshSilent());
    this.subscriptions.add(this.pollSubscription);
  }

  private updatePollingMode(): void {
    if (this.wsConnected) {
      this.stopPolling();
      return;
    }

    this.pollMs = GameComponent.POLL_FAST_MS;
    if (!this.pollSubscription) this.startPolling();
  }

  answer(optionId: number): void {
    if (!this.state?.question) return;
    if (!this.canAnswer) return;
    this.error = null;

    const idx = this.state.questionIndex;
    this.selectedOptionIdByQuestionIndex.set(idx, optionId);
    this.submittingAnswer = true;

    this.gameApi.answer(this.code, this.state.question.id, optionId).subscribe({
      next: (state) => {
        this.submittingAnswer = false;
        this.state = state;
        this.onStateUpdated(state);
      },
      error: (err) => {
        this.submittingAnswer = false;
        this.error = err?.error?.message ?? 'Failed to submit answer';
      },
    });
  }

  end(): void {
    this.error = null;
    this.gameApi.end(this.code).subscribe({
      next: (state) => {
        this.state = state;
        this.onStateUpdated(state);
      },
      error: (err) =>
        (this.error = err?.error?.message ?? 'Failed to end game'),
    });
  }

  backToLobby(): void {
    this.router.navigate(['/lobby', this.code]);
  }

  get isReveal(): boolean {
    return this.state?.stage === 'REVEAL';
  }

  get isQuestion(): boolean {
    return this.state?.stage === 'QUESTION';
  }

  get isFinished(): boolean {
    return this.state?.stage === 'FINISHED';
  }

  get isNoGame(): boolean {
    return this.state?.stage === 'NO_GAME';
  }

  get isPreCountdown(): boolean {
    return this.state?.stage === 'PRE_COUNTDOWN';
  }

  get sortedPlayers() {
    const players = this.state?.players ?? [];
    return [...players].sort((a, b) => b.score - a.score);
  }

  get answeredCount(): number {
    return (this.state?.players ?? []).reduce(
      (acc, p) => acc + (p.answered ? 1 : 0),
      0
    );
  }

  get myPlayer() {
    const name = this.meDisplayName;
    if (!name) return null;
    return (
      (this.state?.players ?? []).find((p) => p.displayName === name) ?? null
    );
  }

  get canAnswer(): boolean {
    if (!this.state?.question) return false;
    if (this.state.stage !== 'QUESTION') return false;
    if (this.submittingAnswer) return false;
    return !(this.myPlayer?.answered === true);
  }

  optionLabel(i: number): string {
    return String.fromCharCode('A'.charCodeAt(0) + i);
  }

  optionState(optionId: number): 'idle' | 'selected' | 'correct' | 'wrong' {
    const state = this.state;
    if (!state) return 'idle';

    const selected =
      this.selectedOptionIdByQuestionIndex.get(state.questionIndex) ?? null;
    const isSelected = selected === optionId;

    if (state.stage !== 'REVEAL') {
      return isSelected ? 'selected' : 'idle';
    }

    if (state.correctOptionId != null && state.correctOptionId === optionId) {
      return 'correct';
    }

    if (
      isSelected &&
      state.correctOptionId != null &&
      optionId !== state.correctOptionId
    ) {
      return 'wrong';
    }

    return 'idle';
  }

  get showRevealTransition(): boolean {
    if (!this.state) return false;
    if (this.state.stage !== 'REVEAL') return false;
    if (this.state.questionIndex >= this.state.totalQuestions) return false;
    return this.revealPhase === 'transition';
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

  private onStateUpdated(state: GameStateDto): void {
    const stageChanged = this.lastStageSeen !== state.stage;
    const questionChanged = this.lastQuestionIndexSeen !== state.questionIndex;
    this.lastStageSeen = state.stage;
    this.lastQuestionIndexSeen = state.questionIndex;

    const newGameSessionId = state.gameSessionId ?? null;
    if (newGameSessionId !== this.currentGameSessionId) {
      this.currentGameSessionId = newGameSessionId;
      this.questionResults = [];
      this.selectedOptionIdByQuestionIndex = new Map<number, number>();
      this.lastSecondsRendered = null;
      this.stopRevealPhaseTimer();
      this.revealPhase = 'feedback';
      this.revealKey = null;
    }

    if (stageChanged || questionChanged) {
      if (
        state.stage === 'QUESTION' ||
        state.stage === 'REVEAL' ||
        state.stage === 'PRE_COUNTDOWN'
      ) {
        this.progressPct = 100;
        this.remainingSeconds = null;
        this.lastSecondsRendered = null;
        this.setTimerNoAnimForOneFrame();
      }
    }

    if (
      state.totalQuestions > 0 &&
      this.questionResults.length !== state.totalQuestions
    ) {
      this.questionResults = Array.from(
        { length: state.totalQuestions },
        () => 'pending'
      );
    }

    if (state.stage === 'REVEAL' && state.totalQuestions > 0) {
      const idx = Math.max(
        0,
        Math.min(state.totalQuestions - 1, state.questionIndex - 1)
      );
      const me = this.myPlayer;
      this.questionResults[idx] = me?.correct === true ? 'correct' : 'wrong';
    }

    if (state.stage === 'REVEAL') {
      const key = `${state.gameSessionId ?? 'none'}:${state.questionIndex}:${
        state.stageEndsAt ?? 'none'
      }`;
      if (key !== this.revealKey) {
        this.revealKey = key;
        this.stopRevealPhaseTimer();
        this.revealPhase = 'feedback';

        if (state.questionIndex < state.totalQuestions) {
          this.revealPhaseTimer = window.setTimeout(() => {
            this.revealPhase = 'transition';
            this.revealPhaseTimer = null;
          }, GameComponent.ANSWER_FEEDBACK_MS);
        }
      }
      return;
    }

    this.revealKey = null;
    this.stopRevealPhaseTimer();
    this.revealPhase = 'feedback';
  }

  private startCountdownLoop(): void {
    if (this.rafId !== null) return;
    const tick = () => {
      this.updateCountdown();
      this.rafId = requestAnimationFrame(tick);
    };
    this.rafId = requestAnimationFrame(tick);
  }

  private stopCountdownLoop(): void {
    if (this.rafId === null) return;
    cancelAnimationFrame(this.rafId);
    this.rafId = null;
  }

  private updateCountdown(): void {
    const endsAt = this.state?.stageEndsAt;
    if (!endsAt) {
      this.progressPct = 0;
      this.remainingSeconds = null;
      this.lastSecondsRendered = null;
      return;
    }

    const endsAtMs = new Date(endsAt).getTime();
    const msLeft = Math.max(0, endsAtMs - Date.now());

    const stage = this.state?.stage;
    const totalMs =
      stage === 'PRE_COUNTDOWN'
        ? GameComponent.GUEST_PRE_COUNTDOWN_MS
        : stage === 'REVEAL'
        ? GameComponent.GUEST_REVEAL_MS
        : GameComponent.GUEST_QUESTION_MS;

    const nextPct = Math.max(0, Math.min(100, (msLeft / totalMs) * 100));
    const shouldNoAnim =
      nextPct > this.progressPct &&
      (stage === 'QUESTION' || stage === 'REVEAL' || stage === 'PRE_COUNTDOWN');
    if (shouldNoAnim) this.setTimerNoAnimForOneFrame();
    this.progressPct = nextPct;

    const seconds = Math.max(0, Math.ceil(msLeft / 1000));
    if (seconds !== this.lastSecondsRendered) {
      this.lastSecondsRendered = seconds;
      this.remainingSeconds = seconds;
    }

    if (stage === 'QUESTION') {
      this.lastQuestionProgressPct = this.progressPct;
      this.lastQuestionRemainingSeconds = this.remainingSeconds;
    }
  }

  private stopRevealPhaseTimer(): void {
    if (this.revealPhaseTimer) {
      window.clearTimeout(this.revealPhaseTimer);
      this.revealPhaseTimer = null;
    }
  }

  private setTimerNoAnimForOneFrame(): void {
    this.timerNoAnim = true;
    if (this.timerNoAnimRaf !== null) {
      cancelAnimationFrame(this.timerNoAnimRaf);
      this.timerNoAnimRaf = null;
    }
    this.timerNoAnimRaf = requestAnimationFrame(() => {
      this.timerNoAnim = false;
      this.timerNoAnimRaf = null;
    });
  }
}
