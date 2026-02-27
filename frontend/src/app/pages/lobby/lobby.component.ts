import { CommonModule, DOCUMENT } from '@angular/common';
import {
  AfterViewInit,
  Component,
  ElementRef,
  HostListener,
  OnDestroy,
  OnInit,
  QueryList,
  ViewChild,
  ViewChildren,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Inject } from '@angular/core';
import { Subscription, switchMap } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error.util';
import { GameApi } from '../../core/api/game.api';
import { LobbyApi } from '../../core/api/lobby.api';
import { QuizApi } from '../../core/api/quiz.api';
import { LobbyDto, LobbyPlayerDto } from '../../core/models/lobby.models';
import { QuizListItemDto } from '../../core/models/quiz.models';
import { rankForPoints } from '../../core/progression/progression';
import { AuthService } from '../../core/auth/auth.service';
import { SessionService } from '../../core/session/session.service';
import { PlayerAvatarComponent } from '../../core/ui/player-avatar.component';
import { ToastService } from '../../core/ui/toast.service';
import { GameEventsService } from '../../core/ws/game-events.service';
import { LobbyChatMessageDto, LobbyChatService } from '../../core/ws/lobby-chat.service';
import { LobbyEventsService } from '../../core/ws/lobby-events.service';
import { StompClientService } from '../../core/ws/stomp-client.service';

@Component({
  selector: 'app-lobby',
  standalone: true,
  imports: [CommonModule, FormsModule, PlayerAvatarComponent],
  templateUrl: './lobby.component.html',
  styleUrl: './lobby.component.scss',
})
export class LobbyComponent implements OnInit, AfterViewInit, OnDestroy {
  // Real-time updates are handled via WebSocket (/ws + STOMP topics).

  view: 'lobby' | 'picker' = 'lobby';

  matchType: 'CASUAL' | 'RANKED' = 'CASUAL';

  pickerScope: 'official' | 'custom' | 'library' = 'official';
  pickerSort: 'az' | 'za' = 'az';
  sortMenuOpen = false;
  categorySelected: string[] = [];
  categoryMenuOpen = false;
  categoryMenuSearch = '';

  get authUser$() {
    return this.auth.user$;
  }

  get isLoggedIn(): boolean {
    return !!this.auth.snapshot;
  }
  readonly maxPlayersOptions = [2, 3, 4, 5] as const;
  readonly maxChatMessageLength = 300;
  readonly maxChatMessages = 2000;

  code = '';
  lobby: LobbyDto | null = null;
  quizzes: QuizListItemDto[] = [];
  selectedQuizId: number | null = null;
  selectedQuizSaving = false;

  quizSearch = '';
  categoryOptions: ReadonlyArray<{ name: string | null; label: string; count: number }> = [];

  private _error: string | null = null;
  joinState: 'unknown' | 'joined' | 'viewOnly' = 'unknown';
  joinRequestInFlight = false;
  get session$() {
    return this.sessionService.session$;
  }

  privacyMode: 'public' | 'private' = 'public';
  privacySaving = false;
  private privacyDirty = false;

  maxPlayersDraft: 2 | 3 | 4 | 5 = 2;
  maxPlayersSaving = false;
  private maxPlayersDirty = false;
  readySaving = false;
  maxPlayersMenuOpen = false;
  quizMenuOpen = false;
  playerMenuOpenForParticipantId: number | null = null;
  private playerActionInFlight: { participantId: number; type: 'kick' | 'ban' } | null = null;

  private readonly subscriptions = new Subscription();
  private gameEventsSubscription: Subscription | null = null;
  private lobbyEventsSubscription: Subscription | null = null;
  private chatEventsSubscription: Subscription | null = null;
  private hasLiveLobbySnapshot = false;
  private lobbySnapshotFetchInFlight = false;
  private quizzesLoaded = false;
  private autoJoinInFlight = false;
  private autoJoinFailed = false;
  private autoJoinBlocked = false;
  private joinConflictResolving = false;
  private intentionalLeaveInProgress = false;
  private lastForegroundSyncAt = 0;
  private scrollLockY: number | null = null;
  private marqueeRafId: number | null = null;
  private readonly onWindowResize = () => this.scheduleMarqueeMeasure();
  private readonly onWindowFocus = () => this.syncLobbyOnForeground();
  private readonly onVisibilityChange = () => {
    if (this.document?.visibilityState !== 'visible') return;
    this.syncLobbyOnForeground();
  };

  @ViewChild('chatLog')
  private chatLog?: ElementRef<HTMLElement>;

  @ViewChild('quizPicker')
  private quizPicker?: ElementRef<HTMLElement>;

  @ViewChildren('quizTitle')
  private quizTitleEls?: QueryList<ElementRef<HTMLElement>>;

  joinPinDigits: string[] = ['', '', '', ''];
  joinPinActiveIndex = 0;
  private joinPinAutoFocusDone = false;

  @ViewChild('joinPinHidden')
  private joinPinHidden?: ElementRef<HTMLInputElement>;

  privacyPinEditing = false;
  privacyPinDigits: string[] = ['', '', '', ''];
  private privacyPinApplied: string | null = null;
  private privacyPinHiddenPrev = '';
  private privacyPinLastInsertAt = 0;
  private privacyPinLastInsertDigit = '';

  @ViewChild('privacyPinHidden')
  private privacyPinHidden?: ElementRef<HTMLInputElement>;

  waitingSlots: number[] = [];

  chatText = '';
  chatMessages: LobbyChatMessageDto[] = [];
  private chatMessageKeys = new Set<string>();
  meDisplayName: string | null = null;
  private meSessionRankPoints: number | null = null;

  codeCopied = false;
  private codeCopiedTimeout: number | null = null;
  hasViewSwitched = false;
  private readonly minJoinTransitionMs = 1500;
  private readonly joinDelayTimers = new Set<number>();
  private destroyed = false;
  private redirectingToDashboard = false;
  private lobbyDiffInitialized = false;
  private lobbyLoadStartedAt = performance.now();
  private suppressInitialLoadingOverlay = false;
  private initialLobbyReady = false;
  private quizzesReady = false;

  get joinBusy(): boolean {
    return (
      this.joinRequestInFlight ||
      this.autoJoinInFlight ||
      this.joinConflictResolving
    );
  }

  get roomTransitionBusy(): boolean {
    return this.joinBusy;
  }

  get readyPlayersCount(): number {
    return this.lobby?.players?.filter((p) => !!p.ready).length ?? 0;
  }

  get lobbyPlayersCount(): number {
    return this.lobby?.players?.length ?? 0;
  }

  get meReady(): boolean {
    return !!this.myLobbyPlayer?.ready;
  }

  get hasSelectedQuiz(): boolean {
    return (this.selectedQuizId ?? this.lobby?.selectedQuizId ?? null) != null;
  }

  get hasRequiredPlayers(): boolean {
    const playersCount = this.lobby?.players?.length ?? 0;
    const maxPlayers = this.lobby?.maxPlayers ?? 0;
    return maxPlayers > 0 && playersCount >= maxPlayers;
  }

  get canToggleReady(): boolean {
    return (
      !!this.lobby?.isParticipant &&
      this.joinState === 'joined' &&
      this.lobby?.status === 'OPEN' &&
      this.hasSelectedQuiz &&
      this.hasRequiredPlayers &&
      !this.readySaving
    );
  }

  get readyActionLabel(): string {
    if (!this.hasSelectedQuiz) return 'Select quiz first';
    if (!this.hasRequiredPlayers) return 'Waiting for players';
    return this.meReady ? 'Cancel readiness' : "I'm ready";
  }

  private get myLobbyPlayer() {
    const players = this.lobby?.players ?? [];
    const explicit = players.find((p) => this.isLobbyPlayerMe(p, players));
    if (explicit) return explicit;
    return null;
  }

  get showJoinCard(): boolean {
    const lobby = this.lobby;
    if (!lobby) return false;
    if (this.redirectingToDashboard) return false;
    if (this.intentionalLeaveInProgress) return false;
    if (this.roomTransitionBusy) return false;
    if (this.joinState === 'joined') return false;
    if (this.joinState === 'viewOnly') return true;
    if (lobby.hasPassword && !lobby.isOwner) return true;
    if (this.canAutoJoinLobby(lobby) && !this.autoJoinFailed) return false;
    return true;
  }

  get roomTransitionTitle(): string {
    return 'Please wait';
  }

  get roomTransitionText(): string {
    return 'Establishing secure connection.';
  }

  get showInitialLoadingOverlay(): boolean {
    return !this.suppressInitialLoadingOverlay;
  }

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly lobbyApi: LobbyApi,
    private readonly quizApi: QuizApi,
    private readonly gameApi: GameApi,
    private readonly auth: AuthService,
    private readonly sessionService: SessionService,
    private readonly gameEvents: GameEventsService,
    private readonly chat: LobbyChatService,
    private readonly lobbyEvents: LobbyEventsService,
    private readonly stompClient: StompClientService,
    private readonly toast: ToastService,
    private readonly el: ElementRef<HTMLElement>,
    @Inject(DOCUMENT) private readonly document: Document
  ) {}

  get error(): string | null {
    return this._error;
  }

  set error(value: string | null) {
    this._error = value;
    if (!value) return;
    this.toast.error(value, { title: 'Lobby', dedupeKey: `lobby:error:${value}` });
  }

  ngOnInit(): void {
    this.auth.ensureLoaded().subscribe({ error: () => {} });
    this.subscriptions.add(
      this.session$.subscribe((s) => {
        this.meDisplayName = s?.displayName ?? null;
        this.meSessionRankPoints = this.toNonNegativeInt(s?.rankPoints);
      })
    );

    this.subscriptions.add(
      this.stompClient.state$.subscribe((state) => {
        if (state === 'disconnected') {
          this.hasLiveLobbySnapshot = false;
        }
        if (state === 'connected' && this.code) {
          this.fetchLobbySnapshotFromServer();
        }
      })
    );

    this.subscriptions.add(
      this.route.paramMap
        .pipe(
          switchMap((params) => {
            const nextCode = (params.get('code') ?? '').toUpperCase();
            if (nextCode !== this.code) {
              this.code = nextCode;
              this.resetTransientState();
            }
            this.updateInitialLoadingOverlayPreference(nextCode);
            const prefetchedLobby = this.consumePrefetchedLobby(nextCode);
            if (prefetchedLobby) {
              this.initLobby(prefetchedLobby);
            }
            this.lobbyLoadStartedAt = performance.now();
            return this.sessionService
              .ensure()
              .pipe(switchMap(() => this.lobbyApi.get(this.code)));
          })
        )
        .subscribe({
          next: (lobby) => this.initLobby(lobby),
          error: () =>
            this.redirectToDashboardForUnavailableLobby(
              'Lobby does not exist or is no longer active.',
              this.lobbyLoadStartedAt
            ),
        })
    );
  }

  ngAfterViewInit(): void {
    window.addEventListener('resize', this.onWindowResize, { passive: true });
    window.addEventListener('focus', this.onWindowFocus, { passive: true });
    this.document.addEventListener('visibilitychange', this.onVisibilityChange);
    this.scheduleMarqueeMeasure();

    if (this.quizTitleEls) {
      this.subscriptions.add(
        this.quizTitleEls.changes.subscribe(() => this.scheduleMarqueeMeasure())
      );
    }
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.updateBodyScrollLock(false);
    for (const timerId of this.joinDelayTimers) {
      window.clearTimeout(timerId);
    }
    this.joinDelayTimers.clear();
    if (this.codeCopiedTimeout != null) {
      window.clearTimeout(this.codeCopiedTimeout);
      this.codeCopiedTimeout = null;
    }
    window.removeEventListener('resize', this.onWindowResize);
    window.removeEventListener('focus', this.onWindowFocus);
    this.document.removeEventListener('visibilitychange', this.onVisibilityChange);
    if (this.marqueeRafId != null) {
      cancelAnimationFrame(this.marqueeRafId);
      this.marqueeRafId = null;
    }
    this.chatEventsSubscription?.unsubscribe();
    this.subscriptions.unsubscribe();
  }

  private initLobby(lobby: LobbyDto): void {
    this.suppressInitialLoadingOverlay = false;
    if (this.isLobbyUnavailable(lobby)) {
      this.redirectToDashboardForUnavailableLobby(
        'Lobby does not exist or is no longer active.',
        this.lobbyLoadStartedAt
      );
      return;
    }

    this.initialLobbyReady = true;
    this.onLobbyUpdate(lobby);
    this.ensureChatUpdates();

    if (this.lobby?.status === 'IN_GAME' && this.joinState === 'joined') {
      this.router.navigate(['/lobby', this.code, 'game']);
      return;
    }

    this.ensureLobbyUpdates();
    this.attemptAutoJoinIfPossible(this.lobby);
  }

  private ensureGameAutoSwitch(): void {
    if (this.gameEventsSubscription) return;
    this.gameEventsSubscription = this.gameEvents
      .subscribeLobbyGame(this.code)
      .subscribe({
        next: (event) => {
          if (this.joinState !== 'joined') return;
          if (
            event.lobbyStatus === 'IN_GAME' &&
            event.stage != null &&
            event.stage !== 'NO_GAME'
          ) {
            this.router.navigate(['/lobby', this.code, 'game']);
            return;
          }
          if (event.lobbyStatus != null && event.lobbyStatus !== 'IN_GAME') {
            return;
          }

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
          // ignore; user can refresh manually if WS is down
        },
      });
    this.subscriptions.add(this.gameEventsSubscription);
  }

  refresh(): void {
    this.error = null;
    this.lobbyApi.get(this.code).subscribe({
      next: (lobby) => this.onLobbyUpdate(lobby),
      error: (err) =>
        (this.error = apiErrorMessage(err, 'Failed to refresh lobby')),
    });
  }

  private onLobbyUpdate(lobby: LobbyDto): void {
    if (this.isLobbyUnavailable(lobby)) {
      this.redirectToDashboardForUnavailableLobby(
        'Lobby does not exist or is no longer active.',
        this.initialLobbyReady ? undefined : this.lobbyLoadStartedAt
      );
      return;
    }

    const previousLobby = this.lobby;
    lobby = this.normalizeLobbySnapshot(lobby, previousLobby);
    const prevSelectedQuizId = this.selectedQuizId ?? null;
    const prevMatchType = this.matchType;
    this.lobby = lobby;
    if (this.playerMenuOpenForParticipantId != null) {
      const exists = (lobby.players ?? []).some(
        (p) => p.participantId === this.playerMenuOpenForParticipantId
      );
      if (!exists) {
        this.playerMenuOpenForParticipantId = null;
      }
    }

    // If we are not the owner, never keep/reveal PIN digits locally.
    if (!lobby.isOwner) {
      this.privacyPinApplied = null;
      this.privacyPinEditing = false;
      this.privacyPinDigits = ['', '', '', ''];
      this.privacyPinHiddenPrev = '';
    }

    const maxPlayers = lobby.maxPlayers ?? 0;
    const players = lobby.players?.length ?? 0;
    const missing = Math.max(0, maxPlayers - players);
    this.waitingSlots = Array.from({ length: missing }, (_, i) => i);

    if (!this.selectedQuizSaving && lobby.selectedQuizId !== undefined) {
      this.selectedQuizId = lobby.selectedQuizId ?? null;
    }

    this.syncMatchTypeFromLobby(lobby);
    if (prevSelectedQuizId !== (this.selectedQuizId ?? null) || prevMatchType !== this.matchType) {
      this.scheduleMarqueeMeasure();
    }

    if (this.view === 'picker' && !lobby.isOwner) {
      this.view = 'lobby';
      this.categoryMenuOpen = false;
    }

    this.maybeAutoFocusJoinPin();

    if (!this.privacyDirty) {
      this.privacyMode = lobby.hasPassword ? 'private' : 'public';
    }

    if (!lobby.hasPassword && !this.privacyDirty) {
      this.privacyPinApplied = null;
      if (!this.privacyPinEditing) this.privacyPinDigits = ['', '', '', ''];
    }

    // If backend provides the current PIN for the owner, sync it so a new owner can see it.
    if (
      lobby.isOwner &&
      typeof lobby.pin === 'string' &&
      /^\d{4}$/.test(lobby.pin)
    ) {
      this.privacyPinApplied = lobby.pin;
      if (!this.privacyPinEditing) {
        this.privacyPinDigits = lobby.pin.split('');
        this.privacyPinHiddenPrev = lobby.pin;
      }
    }

    if (!this.maxPlayersDirty && lobby.maxPlayers != null) {
      const mp = lobby.maxPlayers;
      if (mp === 2 || mp === 3 || mp === 4 || mp === 5) {
        this.maxPlayersDraft = mp;
      }
    }

    if (!this.quizzesLoaded) {
      this.loadQuizzes();
      this.quizzesLoaded = true;
    }

    if (
      !lobby.isParticipant &&
      this.joinState === 'joined' &&
      !this.intentionalLeaveInProgress
    ) {
      this.joinState = 'unknown';
      this.joinRequestInFlight = false;
      this.autoJoinInFlight = false;
      this.autoJoinFailed = false;
      this.toast.warning('Lobby presence lost. Trying to rejoin...', {
        title: 'Lobby',
        dedupeKey: 'lobby:presence-lost',
      });
    }

    if (lobby.isParticipant && this.joinState !== 'joined') {
      this.joinState = 'joined';
      this.autoJoinFailed = false;
      this.updateBodyScrollLock(false);
      this.ensureGameAutoSwitch();
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

    // In join view we keep the page fixed (no document scroll).
    this.updateBodyScrollLock(this.joinState !== 'joined');
    this.emitJoinSystemMessagesFromLobbyDiff(previousLobby, lobby);
  }

  private updateBodyScrollLock(locked: boolean): void {
    const body = this.document?.body;
    const root = this.document?.documentElement;
    if (!body || !root) return;

    const win = this.document.defaultView;
    const alreadyLocked = body.classList.contains('mr-no-scroll');

    if (locked) {
      if (!alreadyLocked) {
        const y = win?.scrollY ?? 0;
        this.scrollLockY = y;

        body.style.position = 'fixed';
        body.style.top = `-${y}px`;
        body.style.left = '0';
        body.style.right = '0';
        body.style.width = '100%';
      }

      body.classList.add('mr-no-scroll');
      root.classList.add('mr-no-scroll');
      return;
    }

    // Always clear scroll-lock styles (even if the class was removed elsewhere).
    let y = this.scrollLockY;
    if (y == null) {
      const top = body.style.top ?? '';
      const m = top.match(/^-(\d+)(?:\.\d+)?px$/);
      y = m ? Number(m[1]) : 0;
    }

    body.style.position = '';
    body.style.top = '';
    body.style.left = '';
    body.style.right = '';
    body.style.width = '';
    this.scrollLockY = null;

    body.classList.remove('mr-no-scroll');
    root.classList.remove('mr-no-scroll');

    // Restore the previous scroll position (no visual jump on unlock).
    if (y && y > 0) {
      try {
        win?.scrollTo?.({ top: y, left: 0, behavior: 'auto' });
      } catch {
        win?.scrollTo?.(0, y);
      }
    }
  }

  private canAutoJoinLobby(lobby: LobbyDto | null): boolean {
    if (!lobby) return false;
    if (lobby.hasPassword && !lobby.isOwner) return false;
    if (lobby.status !== 'OPEN') return false;
    if (
      lobby.players &&
      lobby.maxPlayers &&
      lobby.players.length >= lobby.maxPlayers
    ) {
      return false;
    }
    return true;
  }

  private attemptAutoJoinIfPossible(lobby: LobbyDto | null): void {
    if (!lobby) return;
    if (this.autoJoinBlocked) return;
    if (this.joinState === 'joined') return;
    if (!this.canAutoJoinLobby(lobby)) {
      if (this.isLobbyJoinBlockedForVisitor(lobby)) {
        this.redirectToDashboardForBlockedJoin(lobby);
      }
      return;
    }
    if (this.joinBusy) return;

    this.autoJoinFailed = false;
    this.autoJoinInFlight = true;
    const startedAt = performance.now();
    this.error = null;
    this.lobbyApi.join(this.code).subscribe({
      next: (joined) => {
        this.runAfterMinimumJoinTransition(startedAt, () => {
          this.autoJoinInFlight = false;
          this.autoJoinFailed = false;
          this.onLobbyUpdate(joined);
        });
      },
      error: (err) => {
        const message = apiErrorMessage(err, 'Failed to join lobby');
        this.runAfterMinimumJoinTransition(startedAt, () => {
          this.autoJoinInFlight = false;
          if (err?.status === 409) {
            this.joinConflictResolving = true;
            this.handleJoinError(err, message, true, () => {
              this.joinConflictResolving = false;
            });
            return;
          }
          this.autoJoinFailed = true;
          this.handleJoinError(err, message);
        });
      },
    });
  }

  private ensureLobbyUpdates(): void {
    if (this.lobbyEventsSubscription) return;
    this.lobbyEventsSubscription = this.lobbyEvents
      .subscribeLobby(this.code)
      .subscribe({
        next: (event) => {
          if (event.type === 'LOBBY_KICKED') {
            this.redirectToDashboardForForcedRemoval('kick');
            return;
          }
          if (event.type === 'LOBBY_BANNED') {
            this.redirectToDashboardForForcedRemoval('ban');
            return;
          }
          if (event.state) {
            this.hasLiveLobbySnapshot = true;
            this.onLobbyUpdate(event.state);
            this.attemptAutoJoinIfPossible(event.state);
            return;
          }
          this.fetchLobbySnapshotFromServer();
        },
        error: () => {
          // ignore; user can refresh manually if WS is down
        },
      });
    this.subscriptions.add(this.lobbyEventsSubscription);
  }

  private fetchLobbySnapshotFromServer(): void {
    if (!this.code) return;
    if (this.lobbySnapshotFetchInFlight) return;
    this.lobbySnapshotFetchInFlight = true;
    this.lobbyApi.get(this.code).subscribe({
      next: (lobby) => {
        this.lobbySnapshotFetchInFlight = false;
        this.onLobbyUpdate(lobby);
        this.attemptAutoJoinIfPossible(lobby);
      },
      error: (err) => {
        this.lobbySnapshotFetchInFlight = false;
        if (err?.status === 404 || err?.status === 410) {
          this.redirectToDashboardForUnavailableLobby(
            'Lobby does not exist or is no longer active.',
            this.initialLobbyReady ? undefined : this.lobbyLoadStartedAt
          );
        }
      },
    });
  }

  private isLobbyUnavailable(lobby: LobbyDto | null): boolean {
    const status = String(lobby?.status ?? '').toUpperCase();
    if (!status) return false;
    return status !== 'OPEN' && status !== 'IN_GAME';
  }

  private isLobbyJoinBlockedForVisitor(lobby: LobbyDto | null): boolean {
    if (!lobby) return false;
    const status = String(lobby.status ?? '').toUpperCase();
    if (status && status !== 'OPEN') return true;
    return (
      !!lobby.players &&
      !!lobby.maxPlayers &&
      lobby.players.length >= lobby.maxPlayers
    );
  }

  private redirectToDashboardForUnavailableLobby(
    message: string,
    startedAt?: number
  ): void {
    if (this.redirectingToDashboard || this.destroyed) return;
    this.redirectingToDashboard = true;
    this.joinRequestInFlight = false;
    this.autoJoinInFlight = false;
    this.runAfterMinimumJoinTransition(startedAt ?? performance.now(), () => {
      this.toast.warning(message, {
        title: 'Lobby',
        dedupeKey: 'lobby:unavailable',
      });
      void this.router.navigate(['/']);
    });
  }

  private redirectToDashboardForBlockedJoin(lobby: LobbyDto | null): void {
    if (this.redirectingToDashboard || this.destroyed) return;
    this.redirectingToDashboard = true;
    this.autoJoinBlocked = true;
    this.joinState = 'viewOnly';
    this.joinRequestInFlight = false;
    this.autoJoinInFlight = false;

    const status = String(lobby?.status ?? '').toUpperCase();
    const isFull =
      !!lobby?.players &&
      !!lobby?.maxPlayers &&
      lobby.players.length >= lobby.maxPlayers;
    const message = isFull
      ? 'Lobby is full. Redirected to dashboard.'
      : status === 'IN_GAME'
        ? 'Lobby game is already in progress. Redirected to dashboard.'
        : 'Lobby is not joinable. Redirected to dashboard.';

    this.toast.warning(message, {
      title: 'Lobby',
      dedupeKey: `lobby:blocked-join:${this.code}:${status}:${isFull ? 'full' : 'status'}`,
    });
    void this.router.navigate(['/']);
  }

  private redirectToDashboardForForcedRemoval(reason: 'kick' | 'ban'): void {
    if (this.redirectingToDashboard || this.destroyed) return;
    this.redirectingToDashboard = true;
    this.autoJoinBlocked = true;
    this.joinRequestInFlight = false;
    this.autoJoinInFlight = false;
    this.joinState = 'viewOnly';

    const code = (this.code ?? '').trim().toUpperCase();
    const actionLabel = reason === 'ban' ? 'banned' : 'kicked';
    const message = code
      ? `You were ${actionLabel} from lobby ${code}.`
      : `You were ${actionLabel} from the lobby.`;
    const dedupeKey = reason === 'ban' ? `lobby:forced-removal:ban:${code}` : `lobby:forced-removal:kick:${code}`;

    this.toast.warning(message, {
      title: 'Lobby',
      dedupeKey,
    });

    this.maxPlayersMenuOpen = false;
    this.quizMenuOpen = false;
    this.categoryMenuOpen = false;
    this.sortMenuOpen = false;
    this.playerMenuOpenForParticipantId = null;

    void this.router.navigate(['/']);
  }

  private ensureChatUpdates(): void {
    if (this.chatEventsSubscription) return;

    const lobbyCode = this.code;

    this.chatEventsSubscription = this.chat.subscribe(lobbyCode).subscribe({
      next: (msg) => {
        if (this.code !== lobbyCode) return;
        this.appendChatMessage(msg);
        this.scrollChatToBottomIfNearBottom();
      },
      error: () => {
        // ignore; chat is optional
      },
    });

    this.chat.history(lobbyCode).subscribe({
      next: (messages) => {
        if (this.code !== lobbyCode) return;
        for (const msg of messages ?? []) {
          this.appendChatMessage(msg);
        }
        queueMicrotask(() => this.scrollChatToBottomIfNearBottom());
      },
      error: () => {
        // ignore; chat is optional
      },
    });
  }

  join(): void {
    if (this.joinBusy) return;
    this.error = null;
    const lobby = this.lobby;
    const needsPin = !!lobby?.hasPassword && !lobby.isOwner;
    const pin = needsPin ? this.joinPinDigits.join('') : '';
    if (needsPin && !/^\d{4}$/.test(pin)) {
      this.error = 'PIN is required';
      return;
    }

    this.joinRequestInFlight = true;
    const startedAt = performance.now();
    this.lobbyApi.join(this.code, needsPin ? pin : undefined).subscribe({
      next: (lobby) => {
        this.runAfterMinimumJoinTransition(startedAt, () => {
          this.joinRequestInFlight = false;
          this.clearJoinPinInput(false);
          this.joinPinAutoFocusDone = false;
          this.onLobbyUpdate(lobby);
        });
      },
      error: (err) => {
        const message = apiErrorMessage(err, 'Failed to join lobby');
        this.runAfterMinimumJoinTransition(startedAt, () => {
          this.joinRequestInFlight = false;
          if (needsPin) {
            this.clearJoinPinInput(true);
          }
          if (err?.status === 409) {
            this.joinConflictResolving = true;
            this.handleJoinError(err, message, false, () => {
              this.joinConflictResolving = false;
            });
            return;
          }
          this.handleJoinError(err, message, false);
        });
      },
    });
  }

  cancelJoin(): void {
    this.router.navigate(['/']);
  }

  leaveOnRouteChange(nextUrl?: string): void {
    if (this.joinState !== 'joined') return;
    if (!this.code) return;

    const targetPath = this.pathFromUrl(nextUrl).toLowerCase();
    const lobbyGamePath = `/lobby/${this.code}/game`.toLowerCase();
    if (targetPath === lobbyGamePath) return;

    this.autoJoinBlocked = true;
    this.joinState = 'viewOnly';
    this.joinRequestInFlight = false;
    this.autoJoinInFlight = false;

    let beaconSent = false;
    try {
      beaconSent = navigator.sendBeacon(
        `/api/lobbies/${encodeURIComponent(this.code)}/leave`,
        ''
      );
    } catch {
      beaconSent = false;
    }

    if (!beaconSent) {
      this.lobbyApi.leave(this.code).subscribe({
        next: () => {},
        error: () => {},
      });
    }
  }

  leave(): void {
    this.error = null;
    this.intentionalLeaveInProgress = true;
    this.autoJoinBlocked = true;
    this.lobbyApi.leave(this.code).subscribe({
      next: () => {
        this.router.navigate(['/']);
      },
      error: (err) => {
        this.intentionalLeaveInProgress = false;
        this.autoJoinBlocked = false;
        this.joinState = 'joined';
        this.error = apiErrorMessage(err, 'Failed to leave lobby');
      },
    });
  }

  toggleReady(): void {
    if (!this.canToggleReady) return;
    const nextReady = !this.meReady;
    this.error = null;
    this.readySaving = true;
    this.lobbyApi.setReady(this.code, nextReady).subscribe({
      next: (lobby) => {
        this.readySaving = false;
        this.onLobbyUpdate(lobby);
      },
      error: (err) => {
        this.readySaving = false;
        this.error = apiErrorMessage(err, 'Failed to update readiness');
      },
    });
  }

  private loadQuizzes(): void {
    this.quizApi.list().subscribe({
      next: (quizzes) => {
        this.quizzes = quizzes;
        this.recomputeCategoryOptions();
        this.syncMatchTypeFromLobby(this.lobby);
        this.quizzesReady = true;
      },
      error: () => {
        this.quizzes = [];
        this.categoryOptions = [];
        this.quizzesReady = true;
      },
    });
  }

  openQuizSelection(): void {
    if (!this.lobby?.isOwner) return;
    this.cancelPrivacyPinEdit();
    this.hasViewSwitched = true;
    this.view = 'picker';
    this.categoryMenuOpen = false;
    this.categoryMenuSearch = '';
    this.sortMenuOpen = false;

    // Keep the page pinned to top when switching views.
    this.scrollPageToTop();
    window.setTimeout(() => {
      this.scrollPageToTop();
      this.scheduleMarqueeMeasure();
    }, 0);
  }

  closeQuizSelection(): void {
    this.hasViewSwitched = true;
    this.view = 'lobby';
    this.categoryMenuOpen = false;
    this.categoryMenuSearch = '';
    this.sortMenuOpen = false;
    this.scrollPageToTop();
    window.setTimeout(() => {
      this.scrollPageToTop();
      this.scheduleMarqueeMeasure();
    }, 0);
  }

  private scrollPageToTop(): void {
    const el = document.scrollingElement ?? document.documentElement;
    try {
      el.scrollTo({ top: 0, behavior: 'auto' });
    } catch {
      el.scrollTop = 0;
    }
  }

  copyLobbyCode(code: string, ev?: Event): void {
    ev?.stopPropagation?.();
    const txt = (code ?? '').trim();
    if (!txt) return;

    const done = () => {
      this.codeCopied = true;
      this.toast.success('Lobby code copied', { title: 'Lobby', dedupeKey: 'lobby:copy-code' });
      if (this.codeCopiedTimeout != null) {
        window.clearTimeout(this.codeCopiedTimeout);
      }
      this.codeCopiedTimeout = window.setTimeout(() => {
        this.codeCopiedTimeout = null;
        this.codeCopied = false;
      }, 1200);
    };

    const fail = () => {
      this.error = 'Failed to copy code';
    };

    try {
      const navAny = navigator as any;
      if (navAny?.clipboard?.writeText) {
        navAny.clipboard
          .writeText(txt)
          .then(done)
          .catch(() => {
            if (this.copyWithExecCommand(txt)) done();
            else fail();
          });
        return;
      }
    } catch {
      // ignore
    }

    if (this.copyWithExecCommand(txt)) done();
    else fail();
  }

  private copyWithExecCommand(value: string): boolean {
    try {
      const ta = document.createElement('textarea');
      ta.value = value;
      ta.setAttribute('readonly', 'true');
      ta.style.position = 'fixed';
      ta.style.left = '-9999px';
      ta.style.top = '0';
      document.body.appendChild(ta);
      ta.focus();
      ta.select();
      const ok = document.execCommand('copy');
      document.body.removeChild(ta);
      return ok;
    } catch {
      return false;
    }
  }

  onCodeTileKeydown(ev: KeyboardEvent, code: string): void {
    if (ev.key === 'Enter' || ev.key === ' ') {
      ev.preventDefault();
      this.copyLobbyCode(code);
    }
  }

  onMaxPlayersTileClick(ev: MouseEvent): void {
    ev.stopPropagation();
    this.toggleMaxPlayersMenu();
  }

  onMaxPlayersTileKeydown(ev: KeyboardEvent): void {
    if (ev.key === 'Enter' || ev.key === ' ') {
      ev.preventDefault();
      this.toggleMaxPlayersMenu();
    }
  }

  private scheduleMarqueeMeasure(): void {
    if (this.marqueeRafId != null) {
      cancelAnimationFrame(this.marqueeRafId);
    }
    this.marqueeRafId = requestAnimationFrame(() => {
      this.marqueeRafId = null;
      this.updateTitleOverflow();
    });
  }

  private updateTitleOverflow(): void {
    const items = this.quizTitleEls?.toArray() ?? [];
    for (const item of items) {
      const host = item.nativeElement;
      const primary = host.querySelector<HTMLElement>(
        '.quiz-card__titleOnce:not([aria-hidden="true"])'
      );
      if (!primary) continue;

      const hasOverflow = primary.scrollWidth > host.clientWidth + 1;
      host.classList.toggle('is-overflow', hasOverflow);
    }
  }

  isAuthenticatedLobbyPlayer(player: LobbyPlayerDto): boolean {
    if (this.isLoggedIn && this.isLobbyPlayerMe(player)) return true;
    if (player?.isAuthenticated != null) return player.isAuthenticated;
    return false;
  }

  playerRankName(player: LobbyPlayerDto): string {
    return rankForPoints(this.playerRankPoints(player)).name;
  }

  playerRankColor(player: LobbyPlayerDto): string {
    return rankForPoints(this.playerRankPoints(player)).color;
  }

  canManagePlayer(player: LobbyPlayerDto): boolean {
    if (!this.lobby?.isOwner) return false;
    if (!player) return false;
    if (player.isOwner) return false;
    const participantId = this.resolvePlayerParticipantId(player);
    return participantId != null;
  }

  isPlayerMenuOpen(player: LobbyPlayerDto): boolean {
    const participantId = this.resolvePlayerParticipantId(player);
    if (participantId == null) return false;
    return this.playerMenuOpenForParticipantId === participantId;
  }

  togglePlayerMenu(ev: Event, player: LobbyPlayerDto): void {
    ev.preventDefault();
    ev.stopPropagation();
    const participantId = this.resolvePlayerParticipantId(player);
    if (participantId == null) return;
    this.playerMenuOpenForParticipantId =
      this.playerMenuOpenForParticipantId === participantId ? null : participantId;
  }

  onPlayerMenuKeydown(ev: KeyboardEvent, player: LobbyPlayerDto): void {
    void player;
    if (ev.key !== 'Escape') return;
    ev.preventDefault();
    ev.stopPropagation();
    this.playerMenuOpenForParticipantId = null;
  }

  kickPlayer(ev: Event, player: LobbyPlayerDto): void {
    ev.preventDefault();
    ev.stopPropagation();
    this.managePlayer('kick', player);
  }

  banPlayer(ev: Event, player: LobbyPlayerDto): void {
    ev.preventDefault();
    ev.stopPropagation();
    this.managePlayer('ban', player);
  }

  isPlayerActionInFlight(player: LobbyPlayerDto, action: 'kick' | 'ban'): boolean {
    const participantId = this.resolvePlayerParticipantId(player);
    if (participantId == null) return false;
    return this.playerActionInFlight?.participantId === participantId
      && this.playerActionInFlight?.type === action;
  }

  private managePlayer(action: 'kick' | 'ban', player: LobbyPlayerDto): void {
    const participantId = this.resolvePlayerParticipantId(player);
    if (participantId == null) return;
    if (!this.canManagePlayer(player)) return;
    if (this.playerActionInFlight) return;

    this.error = null;
    this.playerActionInFlight = { participantId, type: action };
    const request$ = action === 'ban'
      ? this.lobbyApi.banPlayer(this.code, participantId)
      : this.lobbyApi.kickPlayer(this.code, participantId);
    request$.subscribe({
      next: (updated) => {
        this.playerActionInFlight = null;
        this.playerMenuOpenForParticipantId = null;
        this.onLobbyUpdate(updated);
      },
      error: (err) => {
        this.playerActionInFlight = null;
        this.error = apiErrorMessage(
          err,
          action === 'ban' ? 'Failed to ban player' : 'Failed to kick player'
        );
      },
    });
  }

  private resolvePlayerParticipantId(player: LobbyPlayerDto): number | null {
    const raw = player?.participantId;
    if (typeof raw !== 'number' || !Number.isFinite(raw)) return null;
    const value = Math.floor(raw);
    return value > 0 ? value : null;
  }

  private playerRankPoints(player: LobbyPlayerDto): number {
    const points = this.toNonNegativeInt(player?.rankPoints);
    if (points != null && points > 0) return points;

    if (this.isLobbyPlayerMe(player)) {
      const fallback = this.resolveLoggedInRankPoints();
      if (fallback != null && fallback > 0) return fallback;
    }

    return points ?? 0;
  }

  setPickerScope(scope: 'official' | 'custom' | 'library'): void {
    if (this.matchType === 'RANKED' && scope !== 'official') return;
    this.pickerScope = scope;
  }

  setPickerSort(sort: 'az' | 'za'): void {
    this.pickerSort = sort;
  }

  get pickerSortLabel(): string {
    return this.pickerSort === 'za' ? 'Name Z–A' : 'Name A–Z';
  }

  toggleSortMenu(): void {
    this.sortMenuOpen = !this.sortMenuOpen;
    if (this.sortMenuOpen) {
      this.categoryMenuOpen = false;
      this.maxPlayersMenuOpen = false;
      this.quizMenuOpen = false;
      this.playerMenuOpenForParticipantId = null;
    }
  }

  selectSort(sort: 'az' | 'za'): void {
    this.pickerSort = sort;
    this.sortMenuOpen = false;
  }

  setMatchType(next: 'CASUAL' | 'RANKED'): void {
    if (this.matchType === next) return;
    const lobby = this.lobby;
    if (!lobby?.isOwner) {
      this.matchType = next;
      if (next === 'RANKED') this.pickerScope = 'official';
      return;
    }
    if (this.selectedQuizSaving) return;

    const prevType = this.matchType;
    const prevQuizId = this.selectedQuizId ?? null;
    const rankingEnabled = next === 'RANKED';
    const selectedQuiz = this.selectedQuiz;
    const nextQuizId =
      rankingEnabled && selectedQuiz && !this.quizSupportsRanking(selectedQuiz)
        ? null
        : prevQuizId;

    this.error = null;
    this.matchType = next;
    this.selectedQuizId = nextQuizId;
    if (rankingEnabled) this.pickerScope = 'official';

    this.selectedQuizSaving = true;
    this.lobbyApi
      .setSelectedQuiz(this.code, nextQuizId, rankingEnabled)
      .subscribe({
        next: (updated) => {
          this.selectedQuizSaving = false;
          this.onLobbyUpdate(updated);
        },
        error: (err) => {
          this.selectedQuizSaving = false;
          this.matchType = prevType;
          this.selectedQuizId = prevQuizId;
          this.error = apiErrorMessage(err, 'Failed to update match type');
        },
      });
  }

  toggleCategoryMenu(): void {
    const next = !this.categoryMenuOpen;
    this.categoryMenuOpen = next;
    if (next) this.categoryMenuSearch = '';
    if (next) this.playerMenuOpenForParticipantId = null;
  }

  clearCategories(): void {
    this.categorySelected = [];
  }

  isCategorySelected(name: string): boolean {
    return this.categorySelected.includes(name);
  }

  toggleCategory(name: string): void {
    if (this.isCategorySelected(name)) {
      this.categorySelected = this.categorySelected.filter((c) => c !== name);
      return;
    }
    this.categorySelected = [...this.categorySelected, name].sort((a, b) => a.localeCompare(b, 'pl'));
  }

  get categorySelectedLabel(): string {
    const count = this.categorySelected.length;
    if (!count) return 'All categories';
    if (count === 1) return this.categorySelected[0] ?? 'All categories';
    return `Categories (${count})`;
  }

  get categoryMenuItems(): string[] {
    const items = (this.categoryOptions ?? [])
      .map((c) => (c.name ?? '').trim())
      .filter(Boolean);
    return Array.from(new Set(items)).sort((a, b) => a.localeCompare(b, 'pl'));
  }

  get categoryMenuItemsFiltered(): string[] {
    const needle = (this.categoryMenuSearch ?? '').trim().toLowerCase();
    if (!needle) return this.categoryMenuItems;
    return this.categoryMenuItems.filter((c) => c.toLowerCase().includes(needle));
  }

  get filteredQuizzes(): QuizListItemDto[] {
    const needle = (this.quizSearch ?? '').trim().toLowerCase();
    const cats = this.categorySelected.map((c) => c.trim().toLowerCase()).filter(Boolean);
    const scope = this.matchType === 'RANKED' ? 'official' : this.pickerScope;
    const type = this.matchType;

    const matchesScope = (q: QuizListItemDto): boolean => {
      const raw = (q as any)?.source ?? (q as any)?.scope ?? (q as any)?.type ?? null;
      // Missing source metadata means system quiz -> treat as "official".
      if (typeof raw !== 'string' || !raw.trim()) return scope === 'official';
      const v = raw.trim().toLowerCase();
      if (v === 'official') return scope === 'official';
      if (v === 'custom') return scope === 'custom';
      if (v === 'library') return scope === 'library';
      if (v === 'user') return scope !== 'official';
      return true;
    };

    const filtered = (this.quizzes ?? []).filter((q) => {
      if (!this.quizMatchesMatchType(q, type)) return false;
      if (!matchesScope(q)) return false;

      if (cats.length) {
        const c = (q.categoryName ?? '').trim().toLowerCase();
        if (!cats.includes(c)) return false;
      }

      if (!needle) return true;
      const title = (q.title ?? '').toLowerCase();
      const desc = (q.description ?? '').toLowerCase();
      const cat = (q.categoryName ?? '').toLowerCase();
      return title.includes(needle) || desc.includes(needle) || cat.includes(needle);
    });

    const sorted = filtered.slice().sort((a, b) => {
      const at = (a.title ?? '').toLowerCase();
      const bt = (b.title ?? '').toLowerCase();
      return this.pickerSort === 'za' ? bt.localeCompare(at) : at.localeCompare(bt);
    });

    return sorted;
  }

  get selectedQuiz(): QuizListItemDto | null {
    const id = this.selectedQuizId ?? null;
    if (id == null) return null;
    return this.quizzes.find((q) => q.id === id) ?? null;
  }

  selectQuiz(quizId: number): void {
    if (!this.lobby?.isOwner) return;
    if (this.selectedQuizSaving) return;
    this.error = null;

    const previous = this.selectedQuizId;
    this.selectedQuizSaving = true;
    this.selectedQuizId = quizId;
    this.lobbyApi
      .setSelectedQuiz(this.code, quizId, this.matchType === 'RANKED')
      .subscribe({
      next: (updated) => {
        this.selectedQuizSaving = false;
        this.onLobbyUpdate(updated);
        if (this.view === 'picker') {
          this.closeQuizSelection();
        }
      },
      error: (err) => {
        this.selectedQuizSaving = false;
        this.selectedQuizId = previous ?? null;
        this.error = apiErrorMessage(err, 'Failed to update selected quiz');
      },
      });
  }

  clearSelectedQuiz(): void {
    if (!this.lobby?.isOwner) return;
    if (this.selectedQuizSaving) return;
    this.error = null;

    const previous = this.selectedQuizId;
    this.selectedQuizSaving = true;
    this.selectedQuizId = null;
    this.lobbyApi
      .setSelectedQuiz(this.code, null, this.matchType === 'RANKED')
      .subscribe({
      next: (updated) => {
        this.selectedQuizSaving = false;
        this.onLobbyUpdate(updated);
      },
      error: (err) => {
        this.selectedQuizSaving = false;
        this.selectedQuizId = previous ?? null;
        this.error = apiErrorMessage(err, 'Failed to clear selected quiz');
      },
      });
  }

  sendChat(): void {
    if (!this.code) return;
    if (this.joinState !== 'joined') return;
    const raw = (this.chatText ?? '').trim();
    if (!raw) return;
    const text = raw.length > this.maxChatMessageLength ? raw.slice(0, this.maxChatMessageLength) : raw;
    this.chat.send(this.code, text).subscribe({
      next: (msg) => {
        this.appendChatMessage(msg);
        this.chatText = '';
        queueMicrotask(() => this.scrollChatToBottom());
      },
      error: (err) => {
        this.error = apiErrorMessage(err, 'Failed to send chat message');
      },
    });
  }

  isSystemChatMessage(msg: LobbyChatMessageDto | null | undefined): boolean {
    const kind = String(msg?.kind ?? 'USER').trim().toUpperCase();
    return kind === 'SYSTEM';
  }

  trackChatMessage(_index: number, msg: LobbyChatMessageDto): string {
    const kind = String(msg.kind ?? 'USER').trim().toUpperCase();
    return `${msg.serverTime}:${kind}:${msg.displayName}:${msg.text}`;
  }

  quizAvatarStyle(q: QuizListItemDto | null): { [key: string]: string } {
    if (!q) {
      return {
        background: 'rgba(255, 255, 255, 0.06)',
        color: 'rgba(255, 255, 255, 0.92)',
      };
    }
    const imageUrl = this.normalizeNullableUrl(q.avatarImageUrl ?? null);
    const bgStart = this.normalizeNullableColor(q.avatarBgStart ?? null) ?? '#30D0FF';
    const bgEnd = this.normalizeNullableColor(q.avatarBgEnd ?? null);
    const textColor = this.normalizeNullableColor(q.avatarTextColor ?? null) ?? '#0A0E1C';

    if (imageUrl) {
      return {
        'background-image': `url(${imageUrl})`,
        'background-size': 'cover',
        'background-position': 'center',
        color: textColor,
      };
    }

    if (bgEnd) {
      return {
        'background-image': `linear-gradient(180deg, ${bgStart}, ${bgEnd})`,
        color: textColor,
      };
    }

    return {
      background: bgStart,
      color: textColor,
    };
  }

  quizInitials(title: string): string {
    const t = (title ?? '').trim();
    if (!t) return '?';
    const parts = t.split(/\s+/).filter(Boolean);
    const first = parts[0]?.[0] ?? '?';
    const second = parts.length > 1 ? (parts[1]?.[0] ?? '') : (parts[0]?.[1] ?? '');
    return (first + second).toUpperCase();
  }

  private normalizeNullableUrl(url: string | null): string | null {
    const t = (url ?? '').trim();
    return t ? t : null;
  }

  private normalizeNullableColor(color: string | null): string | null {
    const t = (color ?? '').trim();
    return t ? t : null;
  }

  private quizMatchesMatchType(q: QuizListItemDto, type: 'CASUAL' | 'RANKED'): boolean {
    if (type === 'CASUAL') return true;
    return this.quizSupportsRanking(q);
  }

  private quizSupportsRanking(q: QuizListItemDto | null | undefined): boolean {
    return q?.includeInRanking === true;
  }

  private syncMatchTypeFromLobby(lobby: LobbyDto | null): void {
    const explicitRanking = lobby?.rankingEnabled;
    let next: 'CASUAL' | 'RANKED';
    if (explicitRanking != null) {
      next = explicitRanking ? 'RANKED' : 'CASUAL';
    } else {
      const q = this.selectedQuiz;
      next = this.quizSupportsRanking(q) ? 'RANKED' : 'CASUAL';
    }

    if (this.matchType === next) return;
    this.matchType = next;
    if (next === 'RANKED') this.pickerScope = 'official';
  }

  private recomputeCategoryOptions(): void {
    const counts = new Map<string, number>();
    for (const q of this.quizzes ?? []) {
      const name = (q.categoryName ?? '').trim();
      if (!name) continue;
      counts.set(name, (counts.get(name) ?? 0) + 1);
    }

    const entries = Array.from(counts.entries())
      .sort((a, b) => a[0].localeCompare(b[0], 'pl'))
      .map(([name, count]) => ({ name, label: name, count }));

    this.categoryOptions = [{ name: null, label: 'All', count: this.quizzes.length }, ...entries];

    if (this.categorySelected.length) {
      const allowed = new Set(counts.keys());
      const next = this.categorySelected.filter((c) => allowed.has(c));
      if (next.length !== this.categorySelected.length) {
        this.categorySelected = next;
      }
    }
  }

  get isJoinPinComplete(): boolean {
    return this.joinPinDigits.length === 4 && this.joinPinDigits.every((d) => /^\d$/.test(d));
  }

  onJoinPinAreaClick(): void {
    if (this.joinState === 'viewOnly' || this.joinBusy) return;
    this.focusJoinPin();
  }

  private focusJoinPin(): void {
    const el = this.joinPinHidden?.nativeElement;
    if (!el) return;
    try {
      el.focus({ preventScroll: true } as any);
    } catch {
      el.focus();
    }
    this.joinPinActiveIndex = Math.min(this.joinPinDigits.filter(Boolean).length, 3);
  }

  private clearJoinPinInput(focus: boolean): void {
    this.joinPinDigits = ['', '', '', ''];
    this.joinPinActiveIndex = 0;

    const input = this.joinPinHidden?.nativeElement;
    if (input) {
      input.value = '';
    }

    if (!focus) return;
    this.focusJoinPin();
  }

  onJoinPinHiddenInput(ev: Event): void {
    const input = ev.target as HTMLInputElement | null;
    if (!input) return;
    const normalized = (input.value ?? '').replace(/\D/g, '').slice(0, 4);
    if (input.value !== normalized) input.value = normalized;

    const digits = normalized.split('');
    this.joinPinDigits = [0, 1, 2, 3].map((i) => digits[i] ?? '');
    this.joinPinActiveIndex = Math.min(digits.length, 3);

    if (digits.length === 4) {
      try {
        input.blur();
      } catch {
        // ignore
      }
    }
  }

  onJoinPinHiddenKeydown(ev: KeyboardEvent): void {
    if (ev.key === 'Escape') {
      ev.preventDefault();
      this.joinPinDigits = ['', '', '', ''];
      this.joinPinActiveIndex = 0;
      try {
        this.joinPinHidden?.nativeElement?.blur();
      } catch {
        // ignore
      }
      return;
    }
  }

  private maybeAutoFocusJoinPin(): void {
    if (this.joinPinAutoFocusDone) return;
    const lobby = this.lobby;
    if (!lobby) return;
    if (!lobby.hasPassword || lobby.isOwner) return;
    if (this.joinState === 'joined' || this.joinState === 'viewOnly') return;

    this.joinPinAutoFocusDone = true;
    window.setTimeout(() => {
      this.focusJoinPin();
    }, 0);
  }

  private resetTransientState(): void {
    for (const timerId of this.joinDelayTimers) {
      window.clearTimeout(timerId);
    }
    this.joinDelayTimers.clear();
    this.lobby = null;
    this.redirectingToDashboard = false;
    this.lobbyLoadStartedAt = performance.now();
    this.suppressInitialLoadingOverlay = false;
    this.view = 'lobby';
    this.error = null;
    this.joinState = 'unknown';
    this.joinRequestInFlight = false;
    this.autoJoinInFlight = false;
    this.autoJoinFailed = false;
    this.autoJoinBlocked = false;
    this.joinConflictResolving = false;
    this.intentionalLeaveInProgress = false;
    this.joinPinDigits = ['', '', '', ''];
    this.joinPinActiveIndex = 0;
    this.joinPinAutoFocusDone = false;
    this.maxPlayersMenuOpen = false;
    this.quizMenuOpen = false;
    this.categoryMenuOpen = false;
    this.sortMenuOpen = false;
    this.playerMenuOpenForParticipantId = null;
    this.playerActionInFlight = null;
    this.categoryMenuSearch = '';
    this.chatText = '';
    this.chatMessages = [];
    this.chatMessageKeys.clear();
    this.hasLiveLobbySnapshot = false;
    this.chatEventsSubscription?.unsubscribe();
    this.chatEventsSubscription = null;
    this.lobbyDiffInitialized = false;
    this.quizzesLoaded = false;
    this.quizzesReady = false;
    this.initialLobbyReady = false;
    this.quizzes = [];
    this.categorySelected = [];
    this.pickerScope = 'official';
    this.pickerSort = 'az';
    this.quizSearch = '';
    this.categoryOptions = [];
    this.selectedQuizId = null;
    this.selectedQuizSaving = false;
    this.readySaving = false;
  }

  private updateInitialLoadingOverlayPreference(nextCode: string): void {
    const state = (this.router.getCurrentNavigation()?.extras?.state ??
      history.state ??
      null) as Record<string, unknown> | null;
    const suppress = state?.['suppressInitialLobbyOverlay'] === true;
    const stateCode = String(state?.['lobbyCode'] ?? '')
      .trim()
      .toUpperCase();
    this.suppressInitialLoadingOverlay =
      suppress && !!nextCode && stateCode === nextCode;
  }

  private consumePrefetchedLobby(nextCode: string): LobbyDto | null {
    const state = (this.router.getCurrentNavigation()?.extras?.state ??
      history.state ??
      null) as Record<string, unknown> | null;
    const raw = state?.['prefetchedLobby'];
    if (!raw || typeof raw !== 'object') return null;

    const prefetched = raw as LobbyDto;
    const code = String(prefetched?.code ?? '')
      .trim()
      .toUpperCase();
    if (!code || code !== nextCode) return null;
    return prefetched;
  }

  private pathFromUrl(url: string | undefined): string {
    const raw = String(url ?? '').trim();
    if (!raw) return '';
    const noHash = raw.split('#')[0] ?? '';
    return noHash.split('?')[0] ?? '';
  }

  private handleJoinError(
    err: unknown,
    message: string,
    fromAutoJoin = false,
    onSettled?: () => void
  ): void {
    if ((err as { status?: number } | null)?.status !== 409) {
      this.error = message;
      onSettled?.();
      return;
    }

    this.lobbyApi.getCurrent().subscribe({
      next: (currentLobby) => {
        const currentCode = (currentLobby?.code ?? '').trim().toUpperCase();
        if (currentCode && currentCode !== this.code) {
          this.autoJoinBlocked = true;
          this.notifyMustLeaveCurrentLobby(currentCode);
          this.openLobbyInstant(currentCode, currentLobby, onSettled);
          return;
        }

        if (currentCode && currentCode === this.code && currentLobby) {
          this.onLobbyUpdate(currentLobby);
          onSettled?.();
          return;
        }

        this.applyJoinConflictFallback(message, fromAutoJoin);
        onSettled?.();
      },
      error: () => {
        this.applyJoinConflictFallback(message, fromAutoJoin);
        onSettled?.();
      },
    });
  }

  private applyJoinConflictFallback(
    message: string,
    fromAutoJoin: boolean
  ): void {
    if (this.shouldShowViewOnlyState(message)) {
      this.redirectToDashboardForBlockedJoin(this.lobby);
      return;
    }

    this.autoJoinBlocked = true;
    this.joinState = 'unknown';
    if (fromAutoJoin) {
      this.autoJoinFailed = true;
    }
    this.error = message;
  }

  private shouldShowViewOnlyState(message: string): boolean {
    const lobby = this.lobby;
    if (lobby?.status && lobby.status !== 'OPEN') return true;
    if (
      lobby?.players &&
      lobby?.maxPlayers &&
      lobby.players.length >= lobby.maxPlayers
    ) {
      return true;
    }

    const normalized = (message ?? '').trim().toLowerCase();
    if (!normalized) return false;
    return (
      normalized.includes('full') ||
      normalized.includes('closed') ||
      normalized.includes('not open') ||
      normalized.includes('not joinable')
    );
  }

  private openLobbyInstant(
    code: string,
    prefetchedLobby?: LobbyDto | null,
    onFailed?: () => void
  ): void {
    const targetCode = (code ?? '').trim().toUpperCase();
    if (!targetCode) return;

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
        onFailed?.();
      })
      .catch(() => onFailed?.());
  }

  private notifyMustLeaveCurrentLobby(currentCode: string): void {
    this.toast.warning(
      `You are already in lobby ${currentCode}. Leave your current lobby before joining another one.`,
      {
        title: 'Lobby',
        dedupeKey: `lobby:lobby-switch-blocked:${currentCode}`,
      }
    );
  }

  private runAfterMinimumJoinTransition(
    startedAt: number,
    action: () => void
  ): void {
    const elapsed = performance.now() - startedAt;
    const remaining = Math.max(0, this.minJoinTransitionMs - elapsed);
    if (remaining <= 0) {
      action();
      return;
    }

    const timerId = window.setTimeout(() => {
      this.joinDelayTimers.delete(timerId);
      if (this.destroyed) return;
      action();
    }, remaining);
    this.joinDelayTimers.add(timerId);
  }

  private syncLobbyOnForeground(): void {
    if (!this.code || this.destroyed) return;
    const now = performance.now();
    if (now - this.lastForegroundSyncAt < 3000) return;
    this.lastForegroundSyncAt = now;

    this.sessionService.refresh().subscribe({
      next: () => this.fetchLobbySnapshotFromServer(),
      error: () => this.fetchLobbySnapshotFromServer(),
    });
  }

  private scrollChatToBottomIfNearBottom(): void {
    const el = this.chatLog?.nativeElement;
    if (!el) return;
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
    if (distance > 120) return;
    requestAnimationFrame(() => this.scrollChatToBottom());
  }

  private scrollChatToBottom(): void {
    const el = this.chatLog?.nativeElement;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }

  private appendChatMessage(msg: LobbyChatMessageDto): void {
    if (this.isSystemChatMessage(msg) && this.hasRecentSystemMessageText(msg.text, 5000)) {
      return;
    }

    const key = this.chatMessageKey(msg);
    if (this.chatMessageKeys.has(key)) return;

    this.chatMessages.push(msg);
    this.chatMessageKeys.add(key);

    if (this.chatMessages.length <= this.maxChatMessages) return;

    const overflow = this.chatMessages.length - this.maxChatMessages;
    const removed = this.chatMessages.splice(0, overflow);
    for (const item of removed) {
      this.chatMessageKeys.delete(this.chatMessageKey(item));
    }
  }

  private chatMessageKey(msg: LobbyChatMessageDto): string {
    const kind = String(msg.kind ?? 'USER').trim().toUpperCase();
    return `${msg.serverTime}:${kind}:${msg.displayName}:${msg.text}`;
  }

  private emitJoinSystemMessagesFromLobbyDiff(previous: LobbyDto | null, current: LobbyDto | null): void {
    if (!current) return;
    if (!this.lobbyDiffInitialized) {
      this.lobbyDiffInitialized = true;
      return;
    }

    const prevPlayers = previous?.players ?? [];
    const nextPlayers = current.players ?? [];

    const prevKeys = new Set(prevPlayers.map((p) => this.playerIdentityKey(p)));

    for (const player of nextPlayers) {
      const key = this.playerIdentityKey(player);
      const displayName = String(player.displayName ?? '').trim();
      if (!displayName) continue;

      if (!key) continue;

      // New participant appeared in the lobby.
      if (!prevKeys.has(key)) {
        if (this.isLobbyPlayerMe(player, nextPlayers)) continue;
        const text = `${displayName} joined the lobby.`;
        if (this.hasRecentSystemMessageText(text)) continue;
        this.appendChatMessage({
          lobbyCode: (this.code ?? '').trim().toUpperCase(),
          displayName: 'System',
          text,
          serverTime: new Date().toISOString(),
          kind: 'SYSTEM',
        });
        this.scrollChatToBottomIfNearBottom();
        continue;
      }
    }
  }

  private hasRecentSystemMessageText(text: string, withinMs: number = 8000): boolean {
    const needle = String(text ?? '').trim();
    if (!needle) return false;
    const now = Date.now();

    for (let i = this.chatMessages.length - 1; i >= 0; i--) {
      const msg = this.chatMessages[i];
      if (!msg) continue;
      if (!this.isSystemChatMessage(msg)) continue;
      if (String(msg.text ?? '').trim() !== needle) continue;
      const ts = new Date(String(msg.serverTime ?? '')).getTime();
      if (!Number.isFinite(ts)) return true;
      if (now - ts <= withinMs) return true;
    }
    return false;
  }

  private playerIdentityKey(player: LobbyPlayerDto | null | undefined): string {
    if (!player) return '';
    const participantId = player.participantId;
    if (typeof participantId === 'number' && Number.isFinite(participantId) && participantId > 0) {
      return `id:${Math.floor(participantId)}`;
    }
    const joinedAt = String(player.joinedAt ?? '').trim();
    const displayName = String(player.displayName ?? '').trim();
    return `${displayName}|${joinedAt}`;
  }

  isLobbyPlayerMe(
    player: LobbyPlayerDto | null | undefined,
    players: readonly LobbyPlayerDto[] = this.lobby?.players ?? []
  ): boolean {
    if (!player) return false;
    if (player.isYou === true) return true;

    const myName = this.normalizedDisplayName(this.meDisplayName);
    if (!myName) return false;

    const playerName = this.normalizedDisplayName(player.displayName);
    if (!playerName || playerName !== myName) return false;

    const sameNameCount = players.reduce((count, p) => {
      return this.normalizedDisplayName(p.displayName) === myName ? count + 1 : count;
    }, 0);
    return sameNameCount === 1;
  }

  private normalizeLobbySnapshot(current: LobbyDto, previous: LobbyDto | null): LobbyDto {
    const nextPlayers = current.players ?? [];
    if (!nextPlayers.length) return current;

    const prevPlayers = previous?.players ?? [];
    const prevByIdentity = new Map<string, LobbyPlayerDto>();
    for (const prev of prevPlayers) {
      const key = this.playerIdentityKey(prev);
      if (!key) continue;
      prevByIdentity.set(key, prev);
    }

    const normalizedPlayers = nextPlayers.map((player) => {
      const key = this.playerIdentityKey(player);
      const prev = key ? prevByIdentity.get(key) : undefined;
      return this.normalizeLobbyPlayer(player, prev, nextPlayers);
    });
    return { ...current, players: normalizedPlayers };
  }

  private normalizeLobbyPlayer(
    player: LobbyPlayerDto,
    previous: LobbyPlayerDto | undefined,
    allPlayers: readonly LobbyPlayerDto[]
  ): LobbyPlayerDto {
    const out: LobbyPlayerDto = { ...player };

    if (out.isYou == null && previous?.isYou != null) {
      out.isYou = previous.isYou;
    }
    if (out.isAuthenticated == null && previous?.isAuthenticated != null) {
      out.isAuthenticated = previous.isAuthenticated;
    }

    const currentRankPoints = this.toNonNegativeInt(out.rankPoints);
    const previousRankPoints = this.toNonNegativeInt(previous?.rankPoints);
    if (currentRankPoints == null && previousRankPoints != null) {
      out.rankPoints = previousRankPoints;
    }

    if (this.isLoggedIn && this.isLobbyPlayerMe(out, allPlayers)) {
      out.isYou = true;
      out.isAuthenticated = true;
      const fallbackRank = this.resolveLoggedInRankPoints();
      const resolvedRank = this.toNonNegativeInt(out.rankPoints);
      if (fallbackRank != null && (resolvedRank == null || (resolvedRank === 0 && fallbackRank > 0))) {
        out.rankPoints = fallbackRank;
      }
    }

    return out;
  }

  private resolveLoggedInRankPoints(): number | null {
    if (!this.isLoggedIn) return null;

    const authRankPoints = this.toNonNegativeInt(this.auth.snapshot?.rankPoints);
    const sessionRankPoints = this.meSessionRankPoints;

    if (authRankPoints == null) return sessionRankPoints;
    if (sessionRankPoints == null) return authRankPoints;

    if (authRankPoints === 0 && sessionRankPoints > 0) {
      return sessionRankPoints;
    }
    return authRankPoints;
  }

  private normalizedDisplayName(value: string | null | undefined): string {
    return String(value ?? '').trim().toLowerCase();
  }

  private toNonNegativeInt(value: unknown): number | null {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) return null;
    return Math.max(0, Math.floor(numeric));
  }

  setPrivacyMode(mode: 'public' | 'private'): void {
    if (this.privacySaving) return;
    this.privacyDirty = true;
    this.privacyMode = mode;

    if (mode === 'public') {
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
          this.error = apiErrorMessage(err, 'Failed to update lobby privacy');
          this.privacyMode = this.lobby?.hasPassword ? 'private' : 'public';
        },
      });
      return;
    }
  }

  onPrivacyApplyClick(): void {
    if (!this.lobby?.isOwner) return;
    if (this.privacySaving) return;

    if (!this.privacyPinEditing) {
      this.startPrivacyPinEdit();
      return;
    }

    this.applyPrivacyPin();
  }

  onPrivacyClearClick(): void {
    if (!this.lobby?.isOwner) return;
    if (this.privacySaving) return;
    this.error = null;
    this.privacyPinApplied = null;
    this.cancelPrivacyPinEdit();
    this.setPrivacyMode('public');
  }

  private startPrivacyPinEdit(): void {
    if (!this.lobby?.isOwner) return;
    if (this.privacySaving) return;
    this.error = null;
    this.privacyPinEditing = true;
    if (this.privacyPinApplied && /^\d{4}$/.test(this.privacyPinApplied)) {
      this.privacyPinDigits = this.privacyPinApplied.split('');
    } else {
      this.privacyPinDigits = ['', '', '', ''];
    }
    window.setTimeout(() => this.focusPrivacyPinHidden(), 0);
  }

  private cancelPrivacyPinEdit(): void {
    this.privacyPinEditing = false;

    const serverPin =
      this.lobby?.isOwner && typeof this.lobby?.pin === 'string'
        ? this.lobby.pin
        : null;
    const appliedPin =
      this.lobby?.isOwner && this.privacyPinApplied && /^\d{4}$/.test(this.privacyPinApplied)
        ? this.privacyPinApplied
        : null;

    const pin = (serverPin && /^\d{4}$/.test(serverPin) ? serverPin : appliedPin) ?? null;
    this.privacyPinDigits = pin ? pin.split('') : ['', '', '', ''];
    this.privacyPinHiddenPrev = this.privacyPinDigits.join('');
  }

  onPrivacyPinAreaClick(): void {
    if (!this.lobby?.isOwner) return;
    if (!this.privacyPinEditing) return;
    if (this.privacySaving) return;
    this.focusPrivacyPinHidden();
  }

  onPrivacyPinHiddenKeydown(ev: KeyboardEvent): void {
    if (!this.privacyPinEditing) return;

    if (ev.key === 'Enter') {
      if (this.isPrivacyPinComplete) this.applyPrivacyPin();
      ev.preventDefault();
      return;
    }

    if (ev.key === 'Escape') {
      this.cancelPrivacyPinEdit();
      ev.preventDefault();
      return;
    }

    // Allow navigation / deletion keys
    const allowed = new Set([
      'Backspace',
      'Delete',
      'ArrowLeft',
      'ArrowRight',
      'Home',
      'End',
      'Tab',
    ]);
    if (allowed.has(ev.key)) return;

    // Block non-digit single-character keys
    if (ev.key.length === 1 && !/^\d$/.test(ev.key)) {
      ev.preventDefault();
    }
  }

  onPrivacyPinHiddenInput(ev: Event): void {
    if (!this.privacyPinEditing) return;
    const el = ev.target as HTMLInputElement | null;
    if (!el) return;

    const inputEv = ev as InputEvent;
    const inputType = inputEv?.inputType ?? '';
    const dataDigit = (inputEv?.data ?? '').replace(/\D/g, '').slice(-1);

    const rawDigits = (el.value ?? '').replace(/\D/g, '').slice(0, 4);
    let digits = rawDigits;

    // Some mobile keyboards can duplicate the entered digit in the same input event (e.g. "11").
    // If this looks like a normal single-digit insert, clamp it to +1 char.
    const pasteLike =
      inputType.includes('paste') ||
      inputType.includes('insertFromPaste') ||
      inputType.includes('insertFromDrop') ||
      inputType.includes('insertFromYank') ||
      inputType.includes('insertFrom') ||
      inputType === 'insertReplacementText';

    const looksLikeInsert = !inputType || inputType.startsWith('insert') || inputType === 'insertText';

    if (
      looksLikeInsert &&
      !pasteLike &&
      digits.length > this.privacyPinHiddenPrev.length + 1
    ) {
      const last = dataDigit || digits.slice(-1);
      if (last) digits = (this.privacyPinHiddenPrev + last).slice(0, 4);
    }

    // If a keyboard fires a duplicate insert event for the same digit, drop the 2nd one.
    if (looksLikeInsert && !pasteLike) {
      const prevLen = this.privacyPinHiddenPrev.length;
      const nextLen = digits.length;
      const inserted = dataDigit || (nextLen > prevLen ? digits.slice(-1) : '');
      const now = typeof performance !== 'undefined' ? performance.now() : Date.now();
      if (
        inserted &&
        nextLen === prevLen + 1 &&
        inserted === this.privacyPinLastInsertDigit &&
        now - this.privacyPinLastInsertAt < 45
      ) {
        digits = this.privacyPinHiddenPrev;
      } else if (inserted && nextLen === prevLen + 1) {
        this.privacyPinLastInsertDigit = inserted;
        this.privacyPinLastInsertAt = now;
      }
    }

    if (el.value !== digits) el.value = digits;

    this.privacyPinDigits = [
      digits[0] ?? '',
      digits[1] ?? '',
      digits[2] ?? '',
      digits[3] ?? '',
    ];

    this.privacyPinHiddenPrev = digits;

    // Keep caret at the end.
    window.setTimeout(() => {
      try {
        el.setSelectionRange(digits.length, digits.length);
      } catch {
        // ignore
      }
    }, 0);
  }

  private focusPrivacyPinHidden(): void {
    const el = this.privacyPinHidden?.nativeElement;
    if (!el) return;
    const v = this.privacyPinDigits.join('');
    el.value = v;
    this.privacyPinHiddenPrev = v;
    this.privacyPinLastInsertAt = 0;
    this.privacyPinLastInsertDigit = '';
    el.focus();
    try {
      el.setSelectionRange(el.value.length, el.value.length);
    } catch {
      // ignore
    }
  }

  private applyPrivacyPin(): void {
    if (!this.lobby?.isOwner) return;
    if (this.privacySaving) return;

    const pin = this.privacyPinDigits.join('');
    if (!/^\d{4}$/.test(pin)) {
      this.error = 'PIN must be 4 digits';
      return;
    }

    this.error = null;
    this.privacySaving = true;
    this.privacyDirty = true;
    this.privacyMode = 'private';

    this.lobbyApi.setPassword(this.code, pin).subscribe({
      next: (updated) => {
        this.privacySaving = false;
        this.privacyDirty = false;
        this.privacyPinApplied = pin;
        this.cancelPrivacyPinEdit();
        this.onLobbyUpdate(updated);
      },
      error: (err) => {
        this.privacySaving = false;
        this.privacyDirty = false;
        this.error = apiErrorMessage(err, 'Failed to update lobby privacy');
        this.privacyMode = this.lobby?.hasPassword ? 'private' : 'public';
      },
    });
  }

  get isPrivacyPinComplete(): boolean {
    return this.privacyPinDigits.every((d) => /^\d$/.test(d));
  }

  get privacyPinActiveIndex(): number {
    if (!this.privacyPinEditing) return -1;
    const idx = this.privacyPinDigits.findIndex((d) => !/^\d$/.test(d));
    return idx === -1 ? 3 : idx;
  }

  onMaxPlayersChange(value: number): void {
    this.maxPlayersDirty = true;
    this.maxPlayersDraft = value as 2 | 3 | 4 | 5;
  }

  toggleMaxPlayersMenu(): void {
    if (this.maxPlayersSaving) return;
    if (!this.lobby?.isOwner) return;
    if (!this.isLoggedIn) return;
    this.maxPlayersMenuOpen = !this.maxPlayersMenuOpen;
    if (this.maxPlayersMenuOpen) {
      this.quizMenuOpen = false;
      this.playerMenuOpenForParticipantId = null;
    }
  }

  setMaxPlayersFromMenu(value: 2 | 3 | 4 | 5): void {
    if (this.lobby?.players && value < this.lobby.players.length) return;
    this.onMaxPlayersChange(value);
    this.maxPlayersMenuOpen = false;
    this.saveMaxPlayers();
  }

  toggleQuizMenu(): void {
    this.quizMenuOpen = !this.quizMenuOpen;
    if (this.quizMenuOpen) {
      this.maxPlayersMenuOpen = false;
      this.playerMenuOpenForParticipantId = null;
    }
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
    if (this.privacyPinEditing) {
      const target = ev.target as HTMLElement | null;
      if (target && target.closest('.stat-tile--privacyWide')) {
        // inside
      } else {
        this.cancelPrivacyPinEdit();
      }
    }

    const target = ev.target as HTMLElement | null;

    if (
      !this.maxPlayersMenuOpen &&
      !this.quizMenuOpen &&
      !this.categoryMenuOpen &&
      !this.sortMenuOpen &&
      this.playerMenuOpenForParticipantId == null
    )
      return;
    if (!target) return;
    if (target.closest('.mr-select-wrap') || target.closest('.player-actions')) return;
    this.maxPlayersMenuOpen = false;
    this.quizMenuOpen = false;
    this.categoryMenuOpen = false;
    this.sortMenuOpen = false;
    this.playerMenuOpenForParticipantId = null;
  }

  @HostListener('document:keydown', ['$event'])
  onDocumentKeydown(ev: KeyboardEvent): void {
    if (ev.key === 'Escape') {
      if (this.privacyPinEditing) {
        this.cancelPrivacyPinEdit();
        return;
      }
      const hadMenusOpen =
        this.maxPlayersMenuOpen ||
        this.quizMenuOpen ||
        this.categoryMenuOpen ||
        this.sortMenuOpen ||
        this.playerMenuOpenForParticipantId != null;
      this.maxPlayersMenuOpen = false;
      this.quizMenuOpen = false;
      this.categoryMenuOpen = false;
      this.sortMenuOpen = false;
      this.playerMenuOpenForParticipantId = null;
      if (!hadMenusOpen && this.view === 'picker') this.closeQuizSelection();
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

    const submitUpdate = () => {
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
          this.error = apiErrorMessage(err, 'Failed to update max players');
        },
      });
    };

    this.maxPlayersSaving = true;
    if (desired > 2) {
      this.auth.reloadMe().subscribe({
        next: (user) => {
          if (!user && !this.auth.snapshot) {
            this.maxPlayersSaving = false;
            this.error = 'Login required for lobbies larger than 2 players';
            return;
          }
          submitUpdate();
        },
        error: () => {
          this.maxPlayersSaving = false;
          this.error = 'Login required for lobbies larger than 2 players';
        },
      });
      return;
    }

    submitUpdate();
  }
}
