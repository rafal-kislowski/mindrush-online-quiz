import { AsyncPipe, NgClass, NgFor, NgIf, NgStyle } from '@angular/common';
import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild, inject } from '@angular/core';
import {
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';
import { Subscription, catchError, combineLatest, filter, interval, map, of, startWith, switchMap } from 'rxjs';
import { AuthService } from './core/auth/auth.service';
import { GameApi } from './core/api/game.api';
import { LibraryQuizApi, LibraryQuizListItemDto } from './core/api/library-quiz.api';
import { LobbyApi } from './core/api/lobby.api';
import { ActiveGameDto } from './core/models/game.models';
import { LobbyDto } from './core/models/lobby.models';
import { SessionService } from './core/session/session.service';
import { computeLevelProgress, levelTheme, rankForPoints } from './core/progression/progression';
import { ParticlesService } from './core/ui/particles.service';
import { PlayerAvatarComponent } from './core/ui/player-avatar.component';
import { ToastService } from './core/ui/toast.service';
import { ToastViewportComponent } from './core/ui/toast-viewport.component';
import { LobbyEventDto, LobbyEventsService } from './core/ws/lobby-events.service';

function clamp01(n: number): number {
  return Math.max(0, Math.min(1, n));
}

function hexToRgb(hex: string): { r: number; g: number; b: number } | null {
  const h = (hex || '').replace('#', '').trim();
  if (h.length !== 6) return null;
  const n = Number.parseInt(h, 16);
  if (!Number.isFinite(n)) return null;
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
}

function withAlpha(hex: string, alpha: number, fallback = '255,255,255'): string {
  const a = clamp01(alpha);
  const rgb = hexToRgb(hex);
  if (!rgb) return `rgba(${fallback},${a})`;
  return `rgba(${rgb.r},${rgb.g},${rgb.b},${a})`;
}

function clamp255(n: number): number {
  return Math.max(0, Math.min(255, Math.round(n)));
}

function toHex(v: number): string {
  return clamp255(v).toString(16).padStart(2, '0');
}

function tintHex(hex: string, amount: number): string {
  const rgb = hexToRgb(hex);
  const a = clamp01(amount);
  if (!rgb) return '#c7d0df';
  const r = rgb.r + (255 - rgb.r) * a;
  const g = rgb.g + (255 - rgb.g) * a;
  const b = rgb.b + (255 - rgb.b) * a;
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`;
}

type AppNotificationCategory = 'moderation' | 'reward' | 'news' | 'system';
type AppNotificationSeverity = 'neutral' | 'success' | 'warning' | 'danger';

interface AppNotification {
  id: string;
  category: AppNotificationCategory;
  severity: AppNotificationSeverity;
  title: string;
  subtitle: string | null;
  text: string;
  meta: string | null;
  createdAt: string | null;
  actionLabel: string | null;
  decision: 'approved' | 'rejected' | null;
  avatarImageUrl: string | null;
  avatarBgStart: string | null;
  avatarBgEnd: string | null;
  avatarTextColor: string | null;
  routeCommands: any[] | null;
  routeQueryParams: Record<string, string | number> | null;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, AsyncPipe, NgIf, NgFor, NgClass, NgStyle, RouterLink, RouterLinkActive, ToastViewportComponent, PlayerAvatarComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent implements OnInit, OnDestroy {
  private readonly particlesService = inject(ParticlesService);
  private readonly sessionService = inject(SessionService);
  private readonly authService = inject(AuthService);
  private readonly gameApi = inject(GameApi);
  private readonly libraryQuizApi = inject(LibraryQuizApi);
  private readonly lobbyApi = inject(LobbyApi);
  private readonly lobbyEvents = inject(LobbyEventsService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly subscriptions = new Subscription();
  private readonly intFormatter = new Intl.NumberFormat('en-US');
  readonly session$ = this.sessionService.session$;
  readonly authUser$ = this.authService.user$;
  readonly isAdmin$ = this.authService.user$.pipe(map(u => !!u?.roles?.includes('ADMIN')));

  readonly profileVm$ = combineLatest([this.authService.user$, this.sessionService.session$]).pipe(
    map(([user, session]) => {
      const isAuthenticated = !!user;
      const displayName = user?.displayName ?? session?.displayName ?? 'Guest';
      const roles = user?.roles ?? [];
      const isAdmin = roles.includes('ADMIN');

      const rankPoints = user?.rankPoints ?? session?.rankPoints ?? 0;
      const xp = user?.xp ?? session?.xp ?? 0;
      const coins = user?.coins ?? session?.coins ?? 0;

      const lvl = computeLevelProgress(xp);
      const theme = levelTheme(lvl.level);
      const rank = rankForPoints(rankPoints);

      return {
        isAuthenticated,
        isAdmin,
        displayName,
        roles,
        rankPoints,
        rankName: rank.name,
        rankColor: rank.color,
        rankColor2: tintHex(rank.color, 0.28),
        rankSoft: withAlpha(rank.color, 0.16, '170,179,194'),
        rankGlow: withAlpha(rank.color, 0.34, '170,179,194'),
        isHighRank: rankPoints >= 1200,
        xp,
        coins,
        level: lvl.level,
        levelTextColor: theme.text,
        ringStrong: theme.ringStrong,
        ringDim: theme.ringDim,
        progress: lvl.progress,
        xpInLevel: lvl.xpInLevel,
        levelXpNeeded: Math.max(0, lvl.levelXpEnd - lvl.levelXpStart),
        xpToNext: lvl.xpToNext,
        maxLevel: lvl.maxLevel,
      };
    })
  );

  sidebarOpen = false;
  sidebarCollapsed = true;
  sidebarTransitionsReady = false;
  contentWide = false;
  contentFull = false;
  currentLobby: LobbyDto | null = null;
  currentGame: ActiveGameDto | null = null;
  notifications: AppNotification[] = [];
  notificationFilter: 'all' | 'unread' = 'all';
  notificationsOpen = false;
  private readonly readNotificationIds = new Set<string>();
  private currentLobbyEventsSub: Subscription | null = null;
  private currentLobbyEventsCode: string | null = null;
  private scrollResetRafId: number | null = null;

  @ViewChild('contentHost')
  private contentHostRef?: ElementRef<HTMLElement>;

  @ViewChild('notificationsMenu')
  private notificationsMenuRef?: ElementRef<HTMLElement>;

  readonly menuItems: Array<{
    label: string;
    route: string;
    icon: 'home' | 'library' | 'shop' | 'news' | 'settings';
  }> = [
    { label: 'Dashboard', route: '/', icon: 'home' },
    { label: 'Library', route: '/library', icon: 'library' },
    { label: 'Shop', route: '/shop', icon: 'shop' },
    { label: 'News', route: '/news', icon: 'news' },
    { label: 'Settings', route: '/settings', icon: 'settings' },
  ];

  ngOnInit(): void {
    this.particlesService.initParticles();
    if ('scrollRestoration' in history) {
      history.scrollRestoration = 'manual';
    }

    this.sessionService.ensure().subscribe();
    this.authService.ensureLoaded().subscribe((u) => {
      if (u) {
        this.sessionService.refresh().subscribe({ error: () => {} });
      }
    });

    this.subscriptions.add(
      this.router.events
        .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
        .subscribe(() => {
          this.sidebarOpen = false;
          this.notificationsOpen = false;
          this.updateContentFlags(this.router.url);
          this.resetViewScrollToTop();
          this.refreshCurrentLobby();
          this.refreshCurrentGame();
        })
    );

    this.updateContentFlags(this.router.url);
    this.resetViewScrollToTop();
    this.startCurrentLobbyTracking();
    this.startCurrentGameTracking();
    this.startNotificationTracking();

    // Prevent sidebar collapse animation flash on initial page load.
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        this.sidebarTransitionsReady = true;
      });
    });
  }

  ngOnDestroy(): void {
    if (this.scrollResetRafId != null) {
      cancelAnimationFrame(this.scrollResetRafId);
      this.scrollResetRafId = null;
    }
    this.currentLobbyEventsSub?.unsubscribe();
    this.currentLobbyEventsSub = null;
    this.currentLobbyEventsCode = null;
    this.subscriptions.unsubscribe();
  }

  private updateContentFlags(url: string): void {
    const path = (url ?? '').split('?')[0]?.split('#')[0] ?? '';
    this.contentWide = path.startsWith('/create-quiz') || path.startsWith('/admin/quiz-submissions');
    this.contentFull =
      path === '/' ||
      path.startsWith('/play-solo') ||
      path.startsWith('/solo-game') ||
      path.startsWith('/leaderboards') ||
      path.startsWith('/lobbies') ||
      path.startsWith('/library') ||
      path.startsWith('/lobby') ||
      path.startsWith('/login') ||
      path.startsWith('/register');
  }

  toggleSidebar(): void {
    this.sidebarOpen = !this.sidebarOpen;
  }

  closeSidebar(): void {
    this.sidebarOpen = false;
  }

  onDesktopBurgerChange(event: Event): void {
    const input = event.target as HTMLInputElement | null;
    this.sidebarCollapsed = !input?.checked;
  }

  onDesktopBurgerKeyToggle(event: Event): void {
    event.preventDefault();
    this.sidebarCollapsed = !this.sidebarCollapsed;
  }

  logout(): void {
    this.authService.logout().subscribe(() => {
      this.currentLobby = null;
      this.currentGame = null;
      this.notificationsOpen = false;
      this.notifications = [];
      this.notificationFilter = 'all';
      this.readNotificationIds.clear();
      this.router.navigate(['/']);
    });
  }

  get notificationCount(): number {
    return this.unreadNotificationCount;
  }

  get unreadNotificationCount(): number {
    let count = 0;
    for (const item of this.notifications) {
      if (this.isNotificationUnread(item)) count += 1;
    }
    return count;
  }

  get visibleNotifications(): AppNotification[] {
    if (this.notificationFilter !== 'unread') return this.notifications;
    return this.notifications.filter((item) => this.isNotificationUnread(item));
  }

  toggleNotifications(event?: Event): void {
    event?.stopPropagation();
    this.notificationsOpen = !this.notificationsOpen;
  }

  setNotificationFilter(filter: 'all' | 'unread', event?: Event): void {
    event?.stopPropagation();
    this.notificationFilter = filter;
  }

  isNotificationUnread(item: Pick<AppNotification, 'id'>): boolean {
    return !this.readNotificationIds.has(item.id);
  }

  openNotification(item: AppNotification): void {
    this.readNotificationIds.add(item.id);
    this.notificationsOpen = false;
    if (!item.routeCommands?.length) return;
    void this.router.navigate(item.routeCommands, {
      queryParams: item.routeQueryParams ?? undefined,
    });
  }

  notificationCategoryLabel(category: AppNotificationCategory): string {
    if (category === 'moderation') return 'Moderation';
    if (category === 'reward') return 'Rewards';
    if (category === 'news') return 'News';
    return 'System';
  }

  notificationIcon(item: Pick<AppNotification, 'category' | 'severity'>): string {
    if (item.category === 'moderation') return 'fa-solid fa-layer-group';
    if (item.category === 'reward') return 'fa-solid fa-trophy';
    if (item.category === 'news') return 'fa-regular fa-newspaper';
    if (item.severity === 'success') return 'fa-regular fa-circle-check';
    if (item.severity === 'warning') return 'fa-solid fa-circle-exclamation';
    if (item.severity === 'danger') return 'fa-solid fa-triangle-exclamation';
    return 'fa-regular fa-bell';
  }

  notificationInitials(title: string | null | undefined): string {
    const raw = String(title ?? '').trim();
    if (!raw) return 'NT';
    const words = raw.split(/\s+/).filter(Boolean);
    const first = words[0]?.[0] ?? '';
    const second = words[1]?.[0] ?? words[0]?.[1] ?? '';
    return `${first}${second}`.toUpperCase() || 'NT';
  }

  notificationQuizAvatarStyle(item: Pick<AppNotification, 'avatarImageUrl' | 'avatarBgStart' | 'avatarBgEnd' | 'avatarTextColor'>): Record<string, string> {
    const image = (item.avatarImageUrl ?? '').trim();
    const start = (item.avatarBgStart ?? '').trim() || '#30D0FF';
    const end = (item.avatarBgEnd ?? '').trim();
    const text = (item.avatarTextColor ?? '').trim() || '#0A0E1C';
    if (image) {
      return {
        'background-image': `url(${image})`,
        'background-size': 'cover',
        'background-position': 'center',
        color: text,
      };
    }
    if (end) {
      return {
        'background-image': `linear-gradient(180deg, ${start}, ${end})`,
        color: text,
      };
    }
    return {
      background: start,
      color: text,
    };
  }

  notificationTimeLabel(value: string | null | undefined): string {
    const raw = String(value ?? '').trim();
    if (!raw) return 'just now';
    const timestamp = Date.parse(raw);
    if (!Number.isFinite(timestamp)) return 'just now';
    const diffMs = Math.max(0, Date.now() - timestamp);
    const diffMinutes = Math.floor(diffMs / 60000);
    if (diffMinutes < 1) return 'just now';
    if (diffMinutes < 60) return `${diffMinutes}m ago`;
    const diffHours = Math.floor(diffMinutes / 60);
    if (diffHours < 24) return `${diffHours}h ago`;
    const diffDays = Math.floor(diffHours / 24);
    return `${diffDays}d ago`;
  }

  openCurrentLobby(): void {
    const code = (this.currentLobby?.code ?? '').trim().toUpperCase();
    if (!code) return;
    void this.router.navigate(['/lobby', code], {
      state: {
        suppressInitialLobbyOverlay: true,
        lobbyCode: code,
        prefetchedLobby: this.currentLobby,
      },
    });
  }

  openCurrentGame(): void {
    const current = this.currentGame;
    if (!current) return;

    const type = String(current.type ?? '').trim().toUpperCase();
    if (type === 'LOBBY') {
      const code = String(current.lobbyCode ?? '').trim().toUpperCase();
      if (!code) return;
      void this.router.navigate(['/lobby', code, 'game']);
      return;
    }

    const gameSessionId = String(current.gameSessionId ?? '').trim();
    if (!gameSessionId) return;
    void this.router.navigate(['/solo-game', gameSessionId]);
  }

  get currentLobbyPlayerCount(): number {
    return this.currentLobby?.players?.length ?? 0;
  }

  get currentLobbyCapacity(): number {
    return this.currentLobby?.maxPlayers ?? 0;
  }

  formatInt(n: number | null | undefined): string {
    const v = Math.max(0, Math.floor(n ?? 0));
    return this.intFormatter.format(v);
  }

  private startCurrentLobbyTracking(): void {
    this.subscriptions.add(
      interval(5000)
        .pipe(
          startWith(0),
          switchMap(() =>
            this.lobbyApi.getCurrent().pipe(catchError(() => of(null)))
          )
        )
        .subscribe((lobby) => {
          this.currentLobby = lobby;
          this.syncCurrentLobbyEventsSubscription(lobby?.code ?? null);
        })
    );
  }

  private startCurrentGameTracking(): void {
    this.subscriptions.add(
      interval(5000)
        .pipe(
          startWith(0),
          switchMap(() =>
            this.gameApi.current().pipe(catchError(() => of(null)))
          )
        )
        .subscribe((game) => {
          this.currentGame = game;
        })
    );
  }

  private startNotificationTracking(): void {
    this.subscriptions.add(
      this.authService.user$
        .pipe(
          switchMap((user) => {
            if (!user) return of<LibraryQuizListItemDto[]>([]);
            return interval(15000).pipe(
              startWith(0),
              switchMap(() => this.libraryQuizApi.listMy().pipe(catchError(() => of<LibraryQuizListItemDto[]>([]))))
            );
          })
        )
        .subscribe((items) => {
          const moderationNotifications = this.toModerationNotifications(items ?? []);
          const rewardNotifications = this.toRewardNotifications();
          const newsNotifications = this.toNewsNotifications();
          const systemNotifications = this.toSystemNotifications();
          this.notifications = this.sortNotifications([
            ...moderationNotifications,
            ...rewardNotifications,
            ...newsNotifications,
            ...systemNotifications,
          ]);
          this.pruneReadNotificationIds(this.notifications);
          if (!this.notifications.length) {
            this.notificationsOpen = false;
          }
        })
    );
  }

  private refreshCurrentLobby(): void {
    this.lobbyApi
      .getCurrent()
      .pipe(catchError(() => of(null)))
      .subscribe((lobby) => {
        this.currentLobby = lobby;
        this.syncCurrentLobbyEventsSubscription(lobby?.code ?? null);
      });
  }

  private refreshCurrentGame(): void {
    this.gameApi
      .current()
      .pipe(catchError(() => of(null)))
      .subscribe((game) => {
        this.currentGame = game;
      });
  }

  private resetViewScrollToTop(): void {
    const doReset = () => {
      const contentEl = this.contentHostRef?.nativeElement;
      window.scrollTo({ top: 0, left: 0, behavior: 'auto' });
      document.documentElement.scrollTop = 0;
      document.body.scrollTop = 0;
      if (contentEl) {
        contentEl.scrollTo({ top: 0, left: 0, behavior: 'auto' });
        contentEl.scrollTop = 0;
      }
    };

    doReset();

    if (this.scrollResetRafId != null) {
      cancelAnimationFrame(this.scrollResetRafId);
    }
    this.scrollResetRafId = requestAnimationFrame(() => {
      this.scrollResetRafId = null;
      doReset();
    });
  }

  private syncCurrentLobbyEventsSubscription(code: string | null | undefined): void {
    const normalized = String(code ?? '').trim().toUpperCase();
    if (!normalized) {
      this.currentLobbyEventsSub?.unsubscribe();
      this.currentLobbyEventsSub = null;
      this.currentLobbyEventsCode = null;
      return;
    }

    if (this.currentLobbyEventsCode === normalized && this.currentLobbyEventsSub) return;

    this.currentLobbyEventsSub?.unsubscribe();
    this.currentLobbyEventsCode = normalized;
    this.currentLobbyEventsSub = this.lobbyEvents.subscribeLobbyUserQueue(normalized).subscribe({
      next: (event) => this.handleCurrentLobbyEvent(event),
      error: () => {
        // ignore; periodic current-lobby refresh will resubscribe when needed
      },
    });
  }

  private handleCurrentLobbyEvent(event: LobbyEventDto): void {
    if (event.type !== 'LOBBY_KICKED' && event.type !== 'LOBBY_BANNED') return;

    const code = String(event.lobbyCode ?? '').trim().toUpperCase();
    const path = this.currentPath(this.router.url);
    if (this.isLobbyShellPath(path, code)) {
      // Lobby component handles its own forced-removal flow.
      return;
    }

    this.currentLobby = null;
    this.syncCurrentLobbyEventsSubscription(null);

    const reason: 'kick' | 'ban' = event.type === 'LOBBY_BANNED' ? 'ban' : 'kick';
    const actionLabel = reason === 'ban' ? 'banned' : 'kicked';
    const message = code
      ? `You were ${actionLabel} from lobby ${code}.`
      : `You were ${actionLabel} from the lobby.`;
    const dedupeKey =
      reason === 'ban'
        ? `lobby:forced-removal:ban:${code}`
        : `lobby:forced-removal:kick:${code}`;

    this.toast.warning(message, {
      title: 'Lobby',
      dedupeKey,
    });
  }

  private currentPath(url: string | undefined): string {
    const raw = String(url ?? '').trim();
    if (!raw) return '';
    const noHash = raw.split('#')[0] ?? '';
    return noHash.split('?')[0] ?? '';
  }

  private isLobbyShellPath(path: string, code: string): boolean {
    // Exact lobby screen path: /lobby/{CODE}
    const match = path.match(/^\/lobby\/([^/]+)\/?$/);
    if (!match) return false;
    const routeCode = String(match[1] ?? '').trim().toUpperCase();
    if (!routeCode) return false;
    return !code || routeCode === code;
  }

  private toModerationNotifications(items: LibraryQuizListItemDto[]): AppNotification[] {
    return (items ?? [])
      .filter((item) => item.moderationStatus === 'APPROVED' || item.moderationStatus === 'REJECTED')
      .map((item) => ({
        id: `moderation:${item.id}:${item.moderationUpdatedAt ?? ''}`,
        category: 'moderation' as const,
        severity: item.moderationStatus === 'APPROVED' ? ('success' as const) : ('danger' as const),
        title: 'Quiz verification',
        subtitle: item.title,
        text: this.buildModerationText(item),
        meta: this.buildModerationMeta(item),
        createdAt: item.moderationUpdatedAt,
        actionLabel: null,
        decision: item.moderationStatus === 'APPROVED' ? ('approved' as const) : ('rejected' as const),
        avatarImageUrl: item.avatarImageUrl ?? null,
        avatarBgStart: item.avatarBgStart ?? null,
        avatarBgEnd: item.avatarBgEnd ?? null,
        avatarTextColor: item.avatarTextColor ?? null,
        routeCommands: ['/library'],
        routeQueryParams: {
          openQuiz: item.id,
          moderationTab: (item.moderationQuestionIssueCount ?? 0) > 0 ? 'questions' : 'details',
          ...(item.moderationStatus === 'REJECTED' ? { reopenModeration: '1' } : {}),
        },
      }))
      .sort((a, b) => this.notificationTimestamp(b.createdAt) - this.notificationTimestamp(a.createdAt));
  }

  private toRewardNotifications(): AppNotification[] {
    return [];
  }

  private toNewsNotifications(): AppNotification[] {
    return [];
  }

  private toSystemNotifications(): AppNotification[] {
    return [];
  }

  private buildModerationMeta(
    item: Pick<LibraryQuizListItemDto, 'moderationStatus' | 'moderationQuestionIssueCount'>
  ): string {
    if (item.moderationStatus === 'APPROVED') return 'Approved';
    const issueCount = Math.max(0, item.moderationQuestionIssueCount ?? 0);
    if (issueCount <= 0) return 'Rejected';
    return `Rejected - ${issueCount} question issue${issueCount === 1 ? '' : 's'}`;
  }

  private buildModerationText(
    item: Pick<LibraryQuizListItemDto, 'moderationStatus' | 'moderationReason'>
  ): string {
    if (item.moderationStatus === 'APPROVED') return '';
    return '';
  }

  private notificationTimestamp(value: string | null | undefined): number {
    const timestamp = Date.parse(String(value ?? ''));
    if (!Number.isFinite(timestamp)) return 0;
    return timestamp;
  }

  private sortNotifications(items: AppNotification[]): AppNotification[] {
    return [...items].sort((a, b) => this.notificationTimestamp(b.createdAt) - this.notificationTimestamp(a.createdAt));
  }

  private pruneReadNotificationIds(items: AppNotification[]): void {
    const active = new Set(items.map((item) => item.id));
    const toDelete: string[] = [];
    this.readNotificationIds.forEach((id) => {
      if (!active.has(id)) toDelete.push(id);
    });
    for (const id of toDelete) {
      this.readNotificationIds.delete(id);
    }
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.notificationsOpen) return;
    const host = this.notificationsMenuRef?.nativeElement;
    if (!host) {
      this.notificationsOpen = false;
      return;
    }
    const target = event.target;
    if (target instanceof Node && host.contains(target)) return;
    this.notificationsOpen = false;
  }

  @HostListener('document:keydown.escape')
  onDocumentEscape(): void {
    this.notificationsOpen = false;
  }
}
