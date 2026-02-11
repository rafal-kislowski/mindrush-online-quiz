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
import { GameApi } from '../../core/api/game.api';
import { LobbyApi } from '../../core/api/lobby.api';
import { QuizApi } from '../../core/api/quiz.api';
import { LobbyDto } from '../../core/models/lobby.models';
import { QuizListItemDto } from '../../core/models/quiz.models';
import { AuthService } from '../../core/auth/auth.service';
import { SessionService } from '../../core/session/session.service';
import { GameEventsService } from '../../core/ws/game-events.service';
import { LobbyChatMessageDto, LobbyChatService } from '../../core/ws/lobby-chat.service';
import { LobbyEventsService } from '../../core/ws/lobby-events.service';
import { StompClientService } from '../../core/ws/stomp-client.service';

@Component({
  selector: 'app-lobby',
  standalone: true,
  imports: [CommonModule, FormsModule],
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

  code = '';
  lobby: LobbyDto | null = null;
  quizzes: QuizListItemDto[] = [];
  selectedQuizId: number | null = null;
  selectedQuizSaving = false;

  quizSearch = '';
  categoryOptions: ReadonlyArray<{ name: string | null; label: string; count: number }> = [];

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
  private gameEventsSubscription: Subscription | null = null;
  private lobbyEventsSubscription: Subscription | null = null;
  private chatEventsSubscription: Subscription | null = null;
  private quizzesLoaded = false;
  private unloadHandler: (() => void) | null = null;
  private autoJoinInFlight = false;
  private scrollLockY: number | null = null;
  private marqueeRafId: number | null = null;
  private readonly onWindowResize = () => this.scheduleMarqueeMeasure();

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
  meDisplayName: string | null = null;

  codeCopied = false;
  private codeCopiedTimeout: number | null = null;

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
    private readonly el: ElementRef<HTMLElement>,
    @Inject(DOCUMENT) private readonly document: Document
  ) {}

  ngOnInit(): void {
    this.auth.ensureLoaded().subscribe({ error: () => {} });
    this.subscriptions.add(
      this.session$.subscribe((s) => {
        this.meDisplayName = s?.displayName ?? null;
      })
    );

    this.subscriptions.add(
      this.stompClient.state$.subscribe((state) => {
        if (state === 'connected' && this.code) {
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
            const nextCode = (params.get('code') ?? '').toUpperCase();
            if (nextCode !== this.code) {
              this.code = nextCode;
              this.resetTransientState();
            }
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

  ngAfterViewInit(): void {
    window.addEventListener('resize', this.onWindowResize, { passive: true });
    this.scheduleMarqueeMeasure();

    if (this.quizTitleEls) {
      this.subscriptions.add(
        this.quizTitleEls.changes.subscribe(() => this.scheduleMarqueeMeasure())
      );
    }
  }

  ngOnDestroy(): void {
    this.updateBodyScrollLock(false);
    if (this.codeCopiedTimeout != null) {
      window.clearTimeout(this.codeCopiedTimeout);
      this.codeCopiedTimeout = null;
    }
    this.unloadHandler &&
      window.removeEventListener('beforeunload', this.unloadHandler);
    window.removeEventListener('resize', this.onWindowResize);
    if (this.marqueeRafId != null) {
      cancelAnimationFrame(this.marqueeRafId);
      this.marqueeRafId = null;
    }
    this.chatEventsSubscription?.unsubscribe();
    this.subscriptions.unsubscribe();
  }

  private initLobby(lobby: LobbyDto): void {
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
          // ignore; user can refresh manually if WS is down
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
      next: (lobby) => this.onLobbyUpdate(lobby),
      error: (err) =>
        (this.error = err?.error?.message ?? 'Failed to refresh lobby'),
    });
  }

  private onLobbyUpdate(lobby: LobbyDto): void {
    const prevSelectedQuizId = this.selectedQuizId ?? null;
    this.lobby = lobby;

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

    this.syncMatchTypeFromSelectedQuiz();
    if (prevSelectedQuizId !== (this.selectedQuizId ?? null)) {
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

    if (lobby.isParticipant && this.joinState !== 'joined') {
      this.joinState = 'joined';
      this.updateBodyScrollLock(false);
      this.ensureGameAutoSwitch();
      this.ensureLeaveOnUnload();
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
  }

  private updateBodyScrollLock(locked: boolean): void {
    const body = this.document?.body;
    const root = this.document?.documentElement;
    if (!body || !root) return;

    const alreadyLocked = body.classList.contains('mr-no-scroll');

    if (locked && !alreadyLocked) {
      const win = this.document.defaultView;
      const y = win?.scrollY ?? 0;
      this.scrollLockY = y;

      body.style.position = 'fixed';
      body.style.top = `-${y}px`;
      body.style.left = '0';
      body.style.right = '0';
      body.style.width = '100%';
    }

    if (!locked && alreadyLocked) {
      const win = this.document.defaultView;
      const y = this.scrollLockY ?? 0;

      body.style.position = '';
      body.style.top = '';
      body.style.left = '';
      body.style.right = '';
      body.style.width = '';
      this.scrollLockY = null;

      // Restore the previous scroll position (no visual jump on unlock).
      try {
        win?.scrollTo?.({ top: y, left: 0, behavior: 'auto' });
      } catch {
        win?.scrollTo?.(0, y);
      }
    }

    body.classList.toggle('mr-no-scroll', locked);
    root.classList.toggle('mr-no-scroll', locked);
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
          // ignore; user can refresh manually if WS is down
        },
      });
    this.subscriptions.add(this.lobbyEventsSubscription);
  }

  private ensureChatUpdates(): void {
    if (this.chatEventsSubscription) return;
    this.chatEventsSubscription = this.chat.subscribe(this.code).subscribe({
      next: (msg) => {
        this.chatMessages.push(msg);
        if (this.chatMessages.length > 200) {
          this.chatMessages.splice(0, this.chatMessages.length - 200);
        }
        this.scrollChatToBottomIfNearBottom();
      },
      error: () => {
        // ignore; chat is optional
      },
    });
  }

  join(): void {
    this.error = null;
    const lobby = this.lobby;
    const needsPin = !!lobby?.hasPassword && !lobby.isOwner;
    const pin = needsPin ? this.joinPinDigits.join('') : '';
    if (needsPin && !/^\d{4}$/.test(pin)) {
      this.error = 'PIN is required';
      return;
    }

    this.lobbyApi.join(this.code, needsPin ? pin : undefined).subscribe({
      next: (lobby) => {
        this.joinPinDigits = ['', '', '', ''];
        this.joinPinActiveIndex = 0;
        this.joinPinAutoFocusDone = false;
        this.onLobbyUpdate(lobby);
      },
      error: (err) => {
        if (err?.status === 409) {
          this.joinState = 'viewOnly';
        }
        this.error = err?.error?.message ?? 'Failed to join lobby';
      },
    });
  }

  cancelJoin(): void {
    this.router.navigate(['/']);
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
        this.recomputeCategoryOptions();
        this.syncMatchTypeFromSelectedQuiz();
      },
    });
  }

  openQuizSelection(): void {
    if (!this.lobby?.isOwner) return;
    this.cancelPrivacyPinEdit();
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

  playerInitials(displayName: string): string {
    const cleaned = (displayName ?? '').trim();
    if (!cleaned) return '?';
    const parts = cleaned.split(/\s+/g).filter(Boolean);
    const first = parts[0]?.[0] ?? cleaned[0] ?? '?';
    const second = parts.length > 1 ? parts[1]?.[0] : cleaned[1];
    const txt = (first + (second ?? '')).toUpperCase();
    return txt.slice(0, 2);
  }

  playerAvatarStyle(displayName: string): { [key: string]: string } {
    const seed = this.hashString(displayName ?? '');
    const hue = seed % 360;
    const bg = `hsl(${hue} 68% 52%)`;
    return {
      background: bg,
    };
  }

  private hashString(value: string): number {
    let h = 2166136261;
    for (let i = 0; i < value.length; i++) {
      h ^= value.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    return Math.abs(h);
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
    }
  }

  selectSort(sort: 'az' | 'za'): void {
    this.pickerSort = sort;
    this.sortMenuOpen = false;
  }

  setMatchType(next: 'CASUAL' | 'RANKED'): void {
    if (this.matchType === next) return;
    this.matchType = next;

    if (next === 'RANKED') {
      this.pickerScope = 'official';
    }

    const selected = this.selectedQuiz;
    if (!this.lobby?.isOwner) return;
    if (!selected) return;
    if (this.quizMatchesMatchType(selected, next)) return;
    this.clearSelectedQuiz();
  }

  toggleCategoryMenu(): void {
    const next = !this.categoryMenuOpen;
    this.categoryMenuOpen = next;
    if (next) this.categoryMenuSearch = '';
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
      if (typeof raw !== 'string' || !raw.trim()) return true;
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
    this.lobbyApi.setSelectedQuiz(this.code, quizId).subscribe({
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
        this.error = err?.error?.message ?? 'Failed to update selected quiz';
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
    this.lobbyApi.setSelectedQuiz(this.code, null).subscribe({
      next: (updated) => {
        this.selectedQuizSaving = false;
        this.onLobbyUpdate(updated);
      },
      error: (err) => {
        this.selectedQuizSaving = false;
        this.selectedQuizId = previous ?? null;
        this.error = err?.error?.message ?? 'Failed to clear selected quiz';
      },
    });
  }

  sendChat(): void {
    if (!this.code) return;
    if (this.joinState !== 'joined') return;
    const raw = (this.chatText ?? '').trim();
    if (!raw) return;
    const text = raw.length > this.maxChatMessageLength ? raw.slice(0, this.maxChatMessageLength) : raw;
    this.chat.send(this.code, text);
    this.chatText = '';
    queueMicrotask(() => this.scrollChatToBottom());
  }

  trackChatMessage(_index: number, msg: LobbyChatMessageDto): string {
    return `${msg.serverTime}:${msg.displayName}:${msg.text}`;
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
    const isRanked = q.gameMode === 'RANKED' || q.includeInRanking === true;
    return type === 'RANKED' ? isRanked : !isRanked;
  }

  private syncMatchTypeFromSelectedQuiz(): void {
    const q = this.selectedQuiz;
    if (!q) return;
    const next: 'CASUAL' | 'RANKED' = this.quizMatchesMatchType(q, 'RANKED') ? 'RANKED' : 'CASUAL';
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
    if (this.joinState === 'viewOnly') return;
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
    this.view = 'lobby';
    this.error = null;
    this.password = '';
    this.joinState = 'unknown';
    this.joinPinDigits = ['', '', '', ''];
    this.joinPinActiveIndex = 0;
    this.joinPinAutoFocusDone = false;
    this.maxPlayersMenuOpen = false;
    this.quizMenuOpen = false;
    this.categoryMenuOpen = false;
    this.categoryMenuSearch = '';
    this.chatText = '';
    this.chatMessages = [];
    this.chatEventsSubscription?.unsubscribe();
    this.chatEventsSubscription = null;
    this.quizzesLoaded = false;
    this.quizzes = [];
    this.categorySelected = [];
    this.pickerScope = 'official';
    this.pickerSort = 'az';
    this.quizSearch = '';
    this.categoryOptions = [];
    this.selectedQuizId = null;
    this.selectedQuizSaving = false;
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
        this.error = err?.error?.message ?? 'Failed to update lobby privacy';
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
      !this.sortMenuOpen
    )
      return;
    if (!target) return;
    if (target.closest('.mr-select-wrap')) return;
    this.maxPlayersMenuOpen = false;
    this.quizMenuOpen = false;
    this.categoryMenuOpen = false;
    this.sortMenuOpen = false;
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
        this.sortMenuOpen;
      this.maxPlayersMenuOpen = false;
      this.quizMenuOpen = false;
      this.categoryMenuOpen = false;
      this.sortMenuOpen = false;
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
