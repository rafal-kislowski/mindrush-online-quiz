import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, Subscription } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error.util';
import { GameApi } from '../../core/api/game.api';
import { LobbyApi } from '../../core/api/lobby.api';
import { SoloGameApi } from '../../core/api/solo-game.api';
import { AuthService } from '../../core/auth/auth.service';
import {
  GameOptionDto,
  GamePlayerDto,
  GameQuestionDto,
  GameStateDto,
} from '../../core/models/game.models';
import { rankForPoints } from '../../core/progression/progression';
import { SessionService } from '../../core/session/session.service';
import { ConfettiService } from '../../core/ui/confetti.service';
import { PlayerAvatarComponent } from '../../core/ui/player-avatar.component';
import { ToastService } from '../../core/ui/toast.service';
import { GameEventsService } from '../../core/ws/game-events.service';
import { StompClientService } from '../../core/ws/stomp-client.service';

@Component({
  selector: 'app-game',
  standalone: true,
  imports: [CommonModule, PlayerAvatarComponent],
  templateUrl: './game.component.html',
  styleUrl: './game.component.scss',
})
export class GameComponent implements OnInit, OnDestroy {
  private static readonly GUEST_QUESTION_MS = 15_000;
  private static readonly GUEST_REVEAL_MS = 3_000;
  private static readonly GUEST_PRE_COUNTDOWN_MS = 4_000;
  private static readonly ANSWER_FEEDBACK_MS = 1200;
  private static readonly SOLO_POLL_MS = 350;
  private static readonly SOLO_BOUNDARY_REFRESH_THROTTLE_MS = 240;

  code = '';
  soloSessionId = '';
  isSoloFlow = false;
  state: GameStateDto | null = null;
  private _error: string | null = null;
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
  endingGame = false;
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
  private previousThreeLivesRemaining: number | null = null;
  private heartLossTimer: number | null = null;
  private wsConnected = false;
  private serverClockOffsetMs = 0;
  private soloPollIntervalId: number | null = null;
  private lastSoloBoundaryRefreshAtMs = 0;
  private silentRefreshInFlight = false;
  private stateRecoveryInFlight = false;
  private celebratedGameSessionId: string | null = null;
  heartLostSlot: number | null = null;
  readonly heartSlots: ReadonlyArray<number> = [1, 2, 3];

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly gameApi: GameApi,
    private readonly soloGameApi: SoloGameApi,
    private readonly lobbyApi: LobbyApi,
    private readonly gameEvents: GameEventsService,
    private readonly sessionService: SessionService,
    private readonly stompClient: StompClientService,
    private readonly authService: AuthService,
    private readonly confettiService: ConfettiService,
    private readonly toast: ToastService
  ) {}

  get error(): string | null {
    return this._error;
  }

  set error(value: string | null) {
    this._error = value;
    if (!value) return;
    this.toast.error(value, { dedupeKey: `game:error:${value}` });
  }

  ngOnInit(): void {
    const routeCode = (this.route.snapshot.paramMap.get('code') ?? '').toUpperCase();
    const routeSoloSessionId = (this.route.snapshot.paramMap.get('sessionId') ?? '').trim();
    this.isSoloFlow = !!routeSoloSessionId;
    this.soloSessionId = routeSoloSessionId;
    this.code = this.isSoloFlow ? 'SOLO' : routeCode;

    if (!this.isSoloFlow) {
      this.subscriptions.add(
        this.stompClient.state$.subscribe((state) => {
          this.wsConnected = state === 'connected';
          if (this.wsConnected) this.refreshSilent();
        })
      );
    }

    this.subscriptions.add(
      this.sessionService.ensure().subscribe({
        next: (s) => (this.meDisplayName = s?.displayName ?? null),
        error: () => {
          // ignore
        },
      })
    );

    if (this.isSoloFlow) {
      this.isOwner = true;
      this.startSoloPolling();
    } else {
      this.lobbyApi.get(this.code).subscribe({
        next: (lobby) => (this.isOwner = lobby.isOwner === true),
        error: () => {
          // ignore
        },
      });
      this.ensureLeaveOnUnload();
    }

    this.refresh();

    if (!this.isSoloFlow) {
      this.subscriptions.add(
        this.gameEvents.subscribeLobbyGame(this.code).subscribe({
          next: () => this.refresh(),
          error: () => {
            // ignore; user can refresh manually if WS is down
          },
        })
      );
    }

    this.startCountdownLoop();
  }

  ngOnDestroy(): void {
    this.unloadHandler &&
      window.removeEventListener('beforeunload', this.unloadHandler);
    this.stopSoloPolling();
    if (this.timerNoAnimRaf !== null) cancelAnimationFrame(this.timerNoAnimRaf);
    this.stopCountdownLoop();
    this.stopRevealPhaseTimer();
    this.clearHeartLossTimer();
    this.subscriptions.unsubscribe();
  }

  refresh(): void {
    this.error = null;
    const requestStartedAtMs = Date.now();
    this.stateRequest$().subscribe({
      next: (state) => {
        this.syncServerClock(state, requestStartedAtMs, Date.now());
        this.state = state;
        this.onStateUpdated(state);
      },
      error: (err) => this.handleStateLoadError(err, false),
    });
  }

  private refreshSilent(): void {
    if (this.silentRefreshInFlight) return;
    this.silentRefreshInFlight = true;
    const requestStartedAtMs = Date.now();
    this.stateRequest$().subscribe({
      next: (state) => {
        this.silentRefreshInFlight = false;
        this.syncServerClock(state, requestStartedAtMs, Date.now());
        this.state = state;
        this.onStateUpdated(state);
      },
      error: (err) => {
        this.silentRefreshInFlight = false;
        this.handleStateLoadError(err, true);
      },
    });
  }

  answer(optionId: number): void {
    if (!this.state?.question) return;
    if (!this.canAnswer) return;
    this.error = null;

    const idx = this.state.questionIndex;
    this.selectedOptionIdByQuestionIndex.set(idx, optionId);
    this.submittingAnswer = true;

    const request$ =
      this.isSoloFlow && this.soloSessionId
        ? this.soloGameApi.answer(this.soloSessionId, this.state.question.id, optionId)
        : this.gameApi.answer(this.code, this.state.question.id, optionId);

    request$.subscribe({
      next: (state) => {
        this.submittingAnswer = false;
        this.state = state;
        this.onStateUpdated(state);
      },
      error: (err) => {
        this.submittingAnswer = false;
        this.error = apiErrorMessage(err, 'Failed to submit answer');
      },
    });
  }

  end(): void {
    if (this.endingGame) return;
    this.error = null;
    this.endingGame = true;
    const request$ =
      this.isSoloFlow && this.soloSessionId
        ? this.soloGameApi.end(this.soloSessionId)
        : this.gameApi.end(this.code);
    request$.subscribe({
      next: (state) => {
        this.endingGame = false;
        this.state = state;
        this.onStateUpdated(state);
      },
      error: (err) => {
        this.endingGame = false;
        this.error = apiErrorMessage(err, 'Failed to end game');
      },
    });
  }

  leaveTraining(): void {
    if (!this.canLeaveTraining) return;
    if (this.endingGame) return;
    this.error = null;
    this.endingGame = true;

    const request$ =
      this.isSoloFlow && this.soloSessionId
        ? this.soloGameApi.end(this.soloSessionId)
        : this.gameApi.end(this.code);

    request$.subscribe({
      next: () => {
        this.endingGame = false;
        this.backToLobby();
      },
      error: (err) => {
        this.endingGame = false;
        this.error = apiErrorMessage(err, 'Failed to leave training');
      },
    });
  }

  backToLobby(): void {
    if (this.isSoloFlow) {
      this.router.navigate(['/play-solo']);
      return;
    }
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

  get isThreeLivesMode(): boolean {
    return this.state?.mode === 'THREE_LIVES';
  }

  get isTrainingMode(): boolean {
    return this.state?.mode === 'TRAINING';
  }

  get canLeaveTraining(): boolean {
    if (!this.isTrainingMode) return false;
    if (this.isFinished || this.isNoGame) return false;
    return !this.endingGame;
  }

  get finishedTitle(): string {
    if (this.state?.finishReason === 'EXPIRED' && this.isTrainingMode) {
      return 'Training expired';
    }
    return 'Game finished';
  }

  get finishedSubtitle(): string {
    if (this.state?.finishReason === 'EXPIRED' && this.isTrainingMode) {
      return 'Session expired because of inactivity.';
    }

    const state = this.state;
    const outcome = this.resolveMyFinishedOutcome(state);
    if (outcome === 'WIN') {
      return 'You won. Congratulations!';
    }
    if (outcome === 'DRAW') {
      const place = this.resolveMyFinishedPlacement(state?.players ?? []);
      if (place === 1) return "It's a draw for 1st place.";
      return "It's a draw.";
    }
    if (outcome === 'LOSE') {
      const place = this.resolveMyFinishedPlacement(state?.players ?? []);
      if (place != null) return `Unfortunately, you lost. You placed #${place}.`;
      return 'Unfortunately, you lost.';
    }
    return 'Final scores';
  }

  get showQuestionTimer(): boolean {
    return !this.isTrainingMode;
  }

  get showSoloHearts(): boolean {
    return this.isSoloFlow && this.isThreeLivesMode;
  }

  get visibleLives(): number {
    if (!this.showSoloHearts) return 0;
    if (!this.isThreeLivesMode) return 3;
    return this.normalizeLives(this.state?.livesRemaining);
  }

  modeLabel(mode: string | null | undefined): string {
    if (mode === 'THREE_LIVES') return '3 Lives Challenge';
    if (mode === 'TRAINING') return 'Training';
    return 'Standard Solo';
  }

  get isPreCountdown(): boolean {
    return this.state?.stage === 'PRE_COUNTDOWN';
  }

  get sortedPlayers() {
    return this.sortPlayersForResults(this.state?.players ?? []);
  }

  formatMs(ms: number | null | undefined): string {
    if (ms == null || !Number.isFinite(ms)) return '-';
    const totalSeconds = ms / 1000;
    if (totalSeconds < 60) return `${totalSeconds.toFixed(2)}s`;
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds - minutes * 60;
    const secStr = seconds.toFixed(2).padStart(5, '0');
    return `${minutes}:${secStr}`;
  }

  formatDelta(value: number | null | undefined, unit: string): string {
    const n = Number(value);
    if (!Number.isFinite(n)) return '-';
    const rounded = Math.trunc(n);
    const sign = rounded >= 0 ? '+' : '';
    return `${sign}${rounded} ${unit}`;
  }

  formatSigned(value: number | null | undefined): string {
    const n = Number(value);
    if (!Number.isFinite(n)) return '0';
    const rounded = Math.trunc(n);
    const sign = rounded > 0 ? '+' : '';
    return `${sign}${rounded}`;
  }

  coinsDeltaValue(player: GamePlayerDto): number | null {
    const direct = Number(player.coinsDelta);
    if (Number.isFinite(direct)) return Math.trunc(direct);

    // Fallback: for STANDARD mode mirror backend formula
    // so summary still shows coins if payload omitted this field.
    const mode = (this.state?.mode ?? '').toUpperCase();
    if (mode !== 'STANDARD') return null;

    const score = Number(player.score);
    if (!Number.isFinite(score)) return null;
    const winnerBonus = player.winner === true ? 20 : 5;
    return Math.max(0, Math.trunc(score / 20) + winnerBonus);
  }

  rankPointsInline(player: GamePlayerDto): string {
    const value = this.rankPointsDeltaValue(player);
    const sign = value > 0 ? '+' : '';
    return `(${sign}${value} RP)`;
  }

  get showRankingPointsInSummary(): boolean {
    if (this.isTrainingMode) return false;
    const players = this.state?.players ?? [];
    return players.some((p) => p.rankPointsDelta != null && Number.isFinite(Number(p.rankPointsDelta)));
  }

  rankPointsInlineClass(player: GamePlayerDto): 'is-positive' | 'is-negative' | 'is-zero' {
    const value = this.rankPointsDeltaValue(player);
    if (value > 0) return 'is-positive';
    if (value < 0) return 'is-negative';
    return 'is-zero';
  }

  correctAnswersValue(player: GamePlayerDto): number {
    const n = Number(player.correctAnswers);
    if (!Number.isFinite(n)) return 0;
    return Math.max(0, Math.trunc(n));
  }

  playerRankName(player: GamePlayerDto): string {
    return rankForPoints(this.playerRankPoints(player)).name;
  }

  playerRankColor(player: GamePlayerDto): string {
    return rankForPoints(this.playerRankPoints(player)).color;
  }

  trophyClassByPosition(position: number): string | null {
    if (position === 1) return 'mr-game-results-trophy mr-game-results-trophy--gold';
    if (position === 2) return 'mr-game-results-trophy mr-game-results-trophy--silver';
    if (position === 3) return 'mr-game-results-trophy mr-game-results-trophy--bronze';
    return null;
  }

  resultIconForPlayer(
    player: GamePlayerDto,
    position: number
  ): { icon: 'trophy' | 'star'; className: string } | null {
    if (this.isDrawTopPlayer(player)) {
      return {
        icon: 'star',
        className: 'mr-game-results-trophy mr-game-results-trophy--draw',
      };
    }

    const trophyClass = this.trophyClassByPosition(position);
    if (!trophyClass) return null;
    return { icon: 'trophy', className: trophyClass };
  }

  private rankPointsDeltaValue(player: GamePlayerDto): number {
    const n = Number(player.rankPointsDelta);
    if (!Number.isFinite(n)) return 0;
    return Math.trunc(n);
  }

  private playerRankPoints(player: GamePlayerDto): number {
    const n = Number(player.rankPoints);
    if (!Number.isFinite(n)) return 0;
    return Math.max(0, Math.trunc(n));
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

  get hasAnsweredCurrentQuestion(): boolean {
    if (!this.state?.question) return false;
    if (this.state.stage !== 'QUESTION') return false;
    // In solo, backend may move QUESTION -> REVEAL almost immediately.
    // Treat in-flight submit as "answered" to keep UI feedback consistent.
    if (this.submittingAnswer) return true;
    return this.myPlayer?.answered === true;
  }

  get showWaitingForPlayersLabel(): boolean {
    return (
      !this.isSoloFlow &&
      !this.submittingAnswer &&
      this.hasAnsweredCurrentQuestion &&
      !this.allPlayersAnsweredCurrentQuestion
    );
  }

  private get allPlayersAnsweredCurrentQuestion(): boolean {
    if (!this.state || this.state.stage !== 'QUESTION') return false;
    const players = this.state.players ?? [];
    if (!players.length) return false;
    return players.every((p) => p.answered === true);
  }

  get showSoloAnsweredTimerDim(): boolean {
    if (this.isTrainingMode) return false;
    if (!this.isSoloFlow) return false;
    if (this.state?.stage === 'REVEAL') return true;
    return this.hasAnsweredCurrentQuestion;
  }

  isAuthenticatedGamePlayer(player: GamePlayerDto): boolean {
    if (player?.isAuthenticated != null) return player.isAuthenticated;
    if (player?.displayName === this.meDisplayName) return this.authService.snapshot != null;
    return false;
  }

  optionLabel(i: number): string {
    return String.fromCharCode('A'.charCodeAt(0) + i);
  }

  questionProgressLabel(
    questionIndex: number | null | undefined,
    totalQuestions: number | null | undefined
  ): string {
    const current = Math.max(1, Math.floor(Number(questionIndex) || 1));
    const total = Math.floor(Number(totalQuestions) || 0);
    if (total <= 0) return `Question ${current}`;
    return `Question ${current} / ${total}`;
  }

  isTextOnlyOptions(options: ReadonlyArray<GameOptionDto> | null | undefined): boolean {
    const rows = options ?? [];
    if (!rows.length) return false;
    return rows.every((o) => !this.hasImageUrl(o?.imageUrl));
  }

  hasImageOptions(
    options: ReadonlyArray<GameOptionDto> | null | undefined
  ): boolean {
    return (options ?? []).some((o) => this.hasOptionImage(o));
  }

  hasOptionImage(option: GameOptionDto | null | undefined): boolean {
    return this.hasImageUrl(option?.imageUrl);
  }

  hasOptionText(option: GameOptionDto | null | undefined): boolean {
    return (option?.text ?? '').trim().length > 0;
  }

  isImageOnlyOption(option: GameOptionDto | null | undefined): boolean {
    return this.hasOptionImage(option) && !this.hasOptionText(option);
  }

  shouldUseMobileImageAnswerGrid(
    question: GameQuestionDto | null | undefined
  ): boolean {
    if (!question) return false;
    const options = question.options ?? [];
    if (!options.length) return false;
    return options.every((o) => this.hasOptionImage(o));
  }

  trackByOptionId(_: number, option: GameOptionDto): number {
    return option.id;
  }

  trackByHeartSlot(_: number, slot: number): number {
    return slot;
  }

  optionState(optionId: number): 'idle' | 'selected' | 'correct' | 'wrong' {
    const state = this.state;
    if (!state) return 'idle';

    const selected =
      this.selectedOptionIdByQuestionIndex.get(state.questionIndex) ?? null;
    const isSelected = selected === optionId;

    if (state.stage !== 'REVEAL') {
      if (this.isSoloFlow) return 'idle';
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
      const stage = this.state?.stage ?? null;
      if (stage === 'FINISHED' || stage === 'NO_GAME') return;
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

  private handleStateLoadError(err: unknown, silent: boolean): void {
    if (!this.isSoloFlow && this.shouldRecoverFromLobbyStateError(err)) {
      this.recoverFromLobbyStateError(err);
      return;
    }
    if (!silent) {
      this.error = apiErrorMessage(err, 'Failed to load game state');
    }
  }

  private shouldRecoverFromLobbyStateError(err: unknown): boolean {
    if (!(err instanceof HttpErrorResponse)) return false;
    if (err.status !== 403 && err.status !== 404) return false;

    const message = apiErrorMessage(err, '').toLowerCase();
    return (
      message.includes('not a lobby participant') ||
      message.includes('lobby not found')
    );
  }

  private recoverFromLobbyStateError(err: unknown): void {
    if (this.stateRecoveryInFlight) return;
    this.stateRecoveryInFlight = true;

    const rawMessage = apiErrorMessage(err, '').toLowerCase();
    const alertMessage = rawMessage.includes('not a lobby participant')
      ? 'You are not a participant of this lobby game. Redirected to dashboard.'
      : 'This game is no longer available. Redirected to dashboard.';

    this.toast.warning(alertMessage, {
      title: 'Game',
      dedupeKey: `game:invalid-access:${this.code}`,
    });

    void this.router.navigate(['/'], { replaceUrl: true }).finally(() => {
      this.stateRecoveryInFlight = false;
    });
  }

  private onStateUpdated(state: GameStateDto): void {
    const stageChanged = this.lastStageSeen !== state.stage;
    const questionChanged = this.lastQuestionIndexSeen !== state.questionIndex;
    this.lastStageSeen = state.stage;
    this.lastQuestionIndexSeen = state.questionIndex;

    if (stageChanged && state.stage === 'FINISHED') {
      this.sessionService.refresh().subscribe({ error: () => {} });
      this.authService.reloadMe().subscribe({ error: () => {} });
      this.playWinnerConfettiIfEligible(state);
    }

    const newGameSessionId = state.gameSessionId ?? null;
    if (newGameSessionId !== this.currentGameSessionId) {
      this.currentGameSessionId = newGameSessionId;
      this.celebratedGameSessionId = null;
      this.questionResults = [];
      this.selectedOptionIdByQuestionIndex = new Map<number, number>();
      this.lastSecondsRendered = null;
      this.stopRevealPhaseTimer();
      this.revealPhase = 'feedback';
      this.revealKey = null;
      this.previousThreeLivesRemaining = null;
      this.heartLostSlot = null;
      this.clearHeartLossTimer();
    }

    this.syncThreeLivesHearts(state);

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
      // Keep reveal feedback timing stable per question.
      // `stageEndsAt` can jitter between state refreshes and should not reset the phase timer.
      const key = `${state.gameSessionId ?? 'none'}:${state.questionIndex}`;
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
    const serverNowMs = Date.now() + this.serverClockOffsetMs;
    const msLeft = Math.max(0, endsAtMs - serverNowMs);

    const stage = this.state?.stage;
    const totalMs =
      this.state?.stageTotalMs ??
      (stage === 'PRE_COUNTDOWN'
        ? GameComponent.GUEST_PRE_COUNTDOWN_MS
        : stage === 'REVEAL'
          ? GameComponent.GUEST_REVEAL_MS
          : GameComponent.GUEST_QUESTION_MS);

    const safeTotal = Math.max(1, totalMs);
    const seconds = Math.max(0, Math.ceil(msLeft / 1000));
    const nextPct = Math.max(0, Math.min(100, (msLeft / safeTotal) * 100));

    if (
      this.isSoloFlow &&
      stage &&
      stage !== 'FINISHED' &&
      msLeft === 0
    ) {
      const now = Date.now();
      if (
        now - this.lastSoloBoundaryRefreshAtMs >=
        GameComponent.SOLO_BOUNDARY_REFRESH_THROTTLE_MS
      ) {
        this.lastSoloBoundaryRefreshAtMs = now;
        this.refreshSilent();
      }
    }

    const shouldNoAnim =
      nextPct > this.progressPct &&
      (stage === 'QUESTION' || stage === 'REVEAL' || stage === 'PRE_COUNTDOWN');
    if (shouldNoAnim) this.setTimerNoAnimForOneFrame();
    this.progressPct = nextPct;

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

  private syncThreeLivesHearts(state: GameStateDto): void {
    if (!this.isSoloFlow) {
      this.previousThreeLivesRemaining = null;
      this.heartLostSlot = null;
      this.clearHeartLossTimer();
      return;
    }
    if (state.mode !== 'THREE_LIVES') {
      this.previousThreeLivesRemaining = null;
      this.heartLostSlot = null;
      this.clearHeartLossTimer();
      return;
    }

    const lives = this.normalizeLives(state.livesRemaining);
    const prev = this.previousThreeLivesRemaining;
    this.previousThreeLivesRemaining = lives;

    if (prev == null || lives >= prev) return;

    const lostSlot = Math.max(1, Math.min(3, prev));
    this.heartLostSlot = lostSlot;
    this.clearHeartLossTimer();
    this.heartLossTimer = window.setTimeout(() => {
      this.heartLostSlot = null;
      this.heartLossTimer = null;
    }, 760);
  }

  private clearHeartLossTimer(): void {
    if (this.heartLossTimer == null) return;
    window.clearTimeout(this.heartLossTimer);
    this.heartLossTimer = null;
  }

  private normalizeLives(value: number | null | undefined): number {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) return 3;
    return Math.max(0, Math.min(3, Math.floor(parsed)));
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

  private syncServerClock(
    state: GameStateDto,
    requestStartedAtMs: number,
    responseReceivedAtMs: number
  ): void {
    const rawServerTime = state?.serverTime;
    if (!rawServerTime) return;

    const serverNowMs = new Date(rawServerTime).getTime();
    if (!Number.isFinite(serverNowMs)) return;

    const startMs = Math.min(requestStartedAtMs, responseReceivedAtMs);
    const endMs = Math.max(requestStartedAtMs, responseReceivedAtMs);
    const midpointMs = startMs + (endMs - startMs) / 2;

    this.serverClockOffsetMs = serverNowMs - midpointMs;
  }

  private stateRequest$(): Observable<GameStateDto> {
    if (this.isSoloFlow && this.soloSessionId) {
      return this.soloGameApi.state(this.soloSessionId);
    }
    return this.gameApi.state(this.code);
  }

  private startSoloPolling(): void {
    if (!this.isSoloFlow || this.soloPollIntervalId != null) return;
    this.soloPollIntervalId = window.setInterval(() => {
      if (this.state?.stage === 'FINISHED') return;
      this.refreshSilent();
    }, GameComponent.SOLO_POLL_MS);
  }

  private stopSoloPolling(): void {
    if (this.soloPollIntervalId == null) return;
    window.clearInterval(this.soloPollIntervalId);
    this.soloPollIntervalId = null;
  }

  private hasImageUrl(value: string | null | undefined): boolean {
    return (value ?? '').trim().length > 0;
  }

  private playWinnerConfettiIfEligible(state: GameStateDto): void {
    if (!this.shouldCelebrateCurrentPlayerWin(state)) return;

    const sessionKey =
      (state.gameSessionId ?? '').trim() ||
      `${this.code}:${state.totalQuestions}:${state.questionIndex}`;
    if (this.celebratedGameSessionId === sessionKey) return;
    this.celebratedGameSessionId = sessionKey;

    void this.confettiService.makeItRain(2000);
  }

  private shouldCelebrateCurrentPlayerWin(state: GameStateDto): boolean {
    return this.resolveMyFinishedOutcome(state) === 'WIN';
  }

  private sortPlayersForResults(players: readonly GamePlayerDto[]): GamePlayerDto[] {
    return [...players].sort((a, b) => {
      const bScore = this.safeInt(b.score);
      const aScore = this.safeInt(a.score);
      if (bScore !== aScore) return bScore - aScore;

      const bCorrect = this.safeInt(b.correctAnswers);
      const aCorrect = this.safeInt(a.correctAnswers);
      if (bCorrect !== aCorrect) return bCorrect - aCorrect;

      const aTime = this.safeTime(a.totalCorrectAnswerTimeMs);
      const bTime = this.safeTime(b.totalCorrectAnswerTimeMs);
      return aTime - bTime;
    });
  }

  private countPlayersWithSameResult(players: readonly GamePlayerDto[], ref: GamePlayerDto): number {
    const signature = this.playerResultSignature(ref);
    return players.reduce((count, player) => {
      return this.playerResultSignature(player) === signature ? count + 1 : count;
    }, 0);
  }

  private resolveMyFinishedOutcome(
    state: GameStateDto | null | undefined
  ): 'WIN' | 'LOSE' | 'DRAW' | null {
    if (!state || state.stage !== 'FINISHED') return null;
    const mode = String(state.mode ?? '').trim().toUpperCase();
    if (mode === 'TRAINING') return null;

    const players = state.players ?? [];
    if (players.length < 2) return null;

    const me = this.resolveCurrentPlayerFromResults(players);
    if (!me) return null;

    const winners = players.filter((p) => p?.winner === true);
    if (winners.length > 1) return 'DRAW';
    if (winners.length === 1) {
      const winner = winners[0];
      return this.playerResultSignature(winner) === this.playerResultSignature(me)
        ? 'WIN'
        : 'LOSE';
    }

    const sorted = this.sortPlayersForResults(players);
    const top = sorted[0];
    if (!top) return null;

    const meIsTop = this.playerResultSignature(me) === this.playerResultSignature(top);
    if (!meIsTop) return 'LOSE';
    return this.countPlayersWithSameResult(sorted, top) > 1 ? 'DRAW' : 'WIN';
  }

  private resolveCurrentPlayerFromResults(players: readonly GamePlayerDto[]): GamePlayerDto | null {
    const meName = this.normalizedName(this.resolveMeDisplayName());
    if (!meName) return null;
    return players.find((p) => this.normalizedName(p.displayName) === meName) ?? null;
  }

  private isDrawTopPlayer(player: GamePlayerDto): boolean {
    const players = this.state?.players ?? [];
    if (players.length < 2) return false;

    // For result icon UX treat equal top score as a draw group,
    // even if sort tiebreakers (correct/time) still determine row order.
    let topScore = Number.NEGATIVE_INFINITY;
    for (const p of players) {
      const score = this.safeInt(p.score);
      if (score > topScore) topScore = score;
    }
    if (!Number.isFinite(topScore)) return false;

    const topScorePlayers = players.filter((p) => this.safeInt(p.score) === topScore);
    if (topScorePlayers.length < 2) return false;

    return this.safeInt(player.score) === topScore;
  }

  private resolveMyFinishedPlacement(players: readonly GamePlayerDto[]): number | null {
    const me = this.resolveCurrentPlayerFromResults(players);
    if (!me) return null;

    const meSignature = this.playerResultSignature(me);
    const sorted = this.sortPlayersForResults(players);
    const idx = sorted.findIndex((p) => this.playerResultSignature(p) === meSignature);
    if (idx < 0) return null;
    return idx + 1;
  }

  private playerResultSignature(player: GamePlayerDto): string {
    const score = this.safeInt(player.score);
    const correct = this.safeInt(player.correctAnswers);
    const time = this.safeTime(player.totalCorrectAnswerTimeMs);
    return `${score}|${correct}|${time}`;
  }

  private safeInt(value: unknown): number {
    const n = Number(value);
    if (!Number.isFinite(n)) return 0;
    return Math.trunc(n);
  }

  private safeTime(value: unknown): number {
    const n = Number(value);
    if (!Number.isFinite(n)) return Number.MAX_SAFE_INTEGER;
    return Math.max(0, Math.trunc(n));
  }

  private resolveMeDisplayName(): string | null {
    const fromSession = String(this.meDisplayName ?? '').trim();
    if (fromSession) return fromSession;
    const fromAuth = String(this.authService.snapshot?.displayName ?? '').trim();
    return fromAuth || null;
  }

  private normalizedName(value: string | null | undefined): string {
    return String(value ?? '').trim().toLowerCase();
  }
}
