import { AsyncPipe, NgClass, NgFor, NgIf, NgStyle } from '@angular/common';
import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild, inject } from '@angular/core';
import {
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';
import { Subscription, catchError, combineLatest, distinctUntilChanged, filter, finalize, forkJoin, interval, map, of, startWith, switchMap } from 'rxjs';
import { AppInfoApi } from './core/api/app-info.api';
import { AuthService } from './core/auth/auth.service';
import {
  activeBonusIconPath,
  activeBonusRemainingLabel,
  buildActiveBonuses,
} from './core/bonus/active-bonuses';
import { GameApi } from './core/api/game.api';
import { LobbyApi } from './core/api/lobby.api';
import { NotificationApi, NotificationStreamEventDto, UserNotificationDto } from './core/api/notification.api';
import { AppInfoDto } from './core/models/app-info.models';
import { ActiveGameDto } from './core/models/game.models';
import { LobbyDto } from './core/models/lobby.models';
import { SessionService } from './core/session/session.service';
import { ShopCartService } from './core/shop/shop-cart.service';
import { computeLevelProgress, levelTheme, rankForPoints } from './core/progression/progression';
import { ParticlesService } from './core/ui/particles.service';
import { PlayerAvatarComponent } from './core/ui/player-avatar.component';
import { PremiumBadgeComponent } from './core/ui/premium-badge.component';
import { SoundEffectsService } from './core/ui/sound-effects.service';
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
  id: number;
  category: AppNotificationCategory;
  severity: AppNotificationSeverity;
  title: string;
  subtitle: string | null;
  text: string | null;
  meta: string | null;
  createdAt: string | null;
  readAt: string | null;
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
  imports: [RouterOutlet, AsyncPipe, NgIf, NgFor, NgClass, NgStyle, RouterLink, RouterLinkActive, ToastViewportComponent, PlayerAvatarComponent, PremiumBadgeComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent implements OnInit, OnDestroy {
  private readonly demoNoticeStorageKey = 'mindrush.demo-notice.dismissed';
  private readonly particlesService = inject(ParticlesService);
  private readonly appInfoApi = inject(AppInfoApi);
  private readonly sessionService = inject(SessionService);
  private readonly authService = inject(AuthService);
  private readonly gameApi = inject(GameApi);
  private readonly notificationApi = inject(NotificationApi);
  private readonly lobbyApi = inject(LobbyApi);
  private readonly lobbyEvents = inject(LobbyEventsService);
  private readonly soundEffects = inject(SoundEffectsService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly shopCart = inject(ShopCartService);
  private readonly subscriptions = new Subscription();
  private readonly intFormatter = new Intl.NumberFormat('en-US');
  private readonly demoResetFormatter = new Intl.DateTimeFormat('en-US', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
  readonly session$ = this.sessionService.session$;
  readonly authUser$ = this.authService.user$;
  readonly isAdmin$ = this.authService.user$.pipe(map(u => !!u?.roles?.includes('ADMIN')));
  readonly cartItemCount$ = this.shopCart.itemCount$;
  private readonly bonusNow$ = interval(30_000).pipe(startWith(0), map(() => Date.now()));

  readonly profileVm$ = combineLatest([this.authService.user$, this.sessionService.session$, this.bonusNow$]).pipe(
    map(([user, session, nowMs]) => {
      const isAuthenticated = !!user;
      const displayName = user?.displayName ?? session?.displayName ?? 'Guest';
      const roles = user?.roles ?? [];
      const isPremium = roles.includes('PREMIUM');

      const rankPoints = user?.rankPoints ?? session?.rankPoints ?? 0;
      const xp = user?.xp ?? session?.xp ?? 0;
      const coins = user?.coins ?? session?.coins ?? 0;
      const activeBonuses = buildActiveBonuses(user, nowMs)
        .map((bonus) => ({
          key: bonus.key,
          label: bonus.label,
          iconPath: activeBonusIconPath(bonus.key),
          remainingLabel: activeBonusRemainingLabel(bonus.expiresAtMs, nowMs),
          expiresLabel: this.bonusExpiryLabel(bonus.expiresAtMs),
        }))
        .sort((a, b) => (a.key === 'premium' ? -1 : b.key === 'premium' ? 1 : 0));

      const lvl = computeLevelProgress(xp);
      const theme = levelTheme(lvl.level);
      const rank = rankForPoints(rankPoints);

      return {
        isAuthenticated,
        displayName,
        roles,
        isPremium,
        rankPoints,
        rankName: rank.name,
        rankColor: rank.color,
        rankColor2: tintHex(rank.color, 0.28),
        rankSoft: withAlpha(rank.color, 0.16, '170,179,194'),
        rankGlow: withAlpha(rank.color, 0.34, '170,179,194'),
        isHighRank: rankPoints >= 1200,
        xp,
        coins,
        activeBonuses,
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
  sidebarTransitionsReady = false;
  contentWide = false;
  contentFull = false;
  appInfo: AppInfoDto | null = null;
  currentLobby: LobbyDto | null = null;
  currentGame: ActiveGameDto | null = null;
  demoNoticeDismissed = false;
  notifications: AppNotification[] = [];
  notificationFilter: 'all' | 'unread' = 'all';
  notificationsOpen = false;
  profileMenuOpen = false;
  private unreadNotificationCountValue = 0;
  private readonly dismissedNotificationIds = new Set<number>();
  private readonly dismissInFlightNotificationIds = new Set<number>();
  private readonly locallyReadNotificationIds = new Set<number>();
  private readonly readInFlightNotificationIds = new Set<number>();
  private readonly knownNotificationIds = new Set<number>();
  private notificationsHydrated = false;
  private notificationStream: EventSource | null = null;
  private notificationStreamReconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private notificationStreamReconnectDelayMs = 0;
  private notificationRefreshInFlight = false;
  private currentLobbyEventsSub: Subscription | null = null;
  private currentLobbyEventsCode: string | null = null;
  private scrollResetRafId: number | null = null;

  @ViewChild('contentHost')
  private contentHostRef?: ElementRef<HTMLElement>;

  @ViewChild('notificationsMenu')
  private notificationsMenuRef?: ElementRef<HTMLElement>;

  @ViewChild('profileMenu')
  private profileMenuRef?: ElementRef<HTMLElement>;

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
    this.loadAppInfo();
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
          this.profileMenuOpen = false;
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
    this.startNotificationFallbackRefresh();

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
    this.stopNotificationStream();
    this.subscriptions.unsubscribe();
  }

  private updateContentFlags(url: string): void {
    const path = (url ?? '').split('?')[0]?.split('#')[0] ?? '';
    this.contentWide = path.startsWith('/create-quiz') || path.startsWith('/admin/products') || path.startsWith('/admin/quiz-submissions');
    this.contentFull =
      path === '/' ||
      path.startsWith('/shop') ||
      path.startsWith('/play-solo') ||
      path.startsWith('/solo-game') ||
      path.startsWith('/profile') ||
      path.startsWith('/settings') ||
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
    this.sidebarOpen = !!input?.checked;
  }

  onDesktopBurgerKeyToggle(event: Event): void {
    event.preventDefault();
    this.sidebarOpen = !this.sidebarOpen;
  }

  logout(): void {
    this.profileMenuOpen = false;
    this.authService.logout().subscribe(() => {
      this.currentLobby = null;
      this.currentGame = null;
      this.shopCart.clear();
      this.resetNotificationsState();
      this.stopNotificationStream();
      this.router.navigate(['/']);
    });
  }

  openCart(event?: Event): void {
    event?.stopPropagation();
    void this.router.navigate(['/shop', 'cart']);
  }

  get notificationCount(): number {
    return this.unreadNotificationCount;
  }

  get unreadNotificationCount(): number {
    return Math.max(0, this.unreadNotificationCountValue);
  }

  get visibleNotifications(): AppNotification[] {
    const base = this.notificationFilter !== 'unread'
      ? this.notifications
      : this.notifications.filter((item) => this.isNotificationUnread(item));
    return base.filter((item) => !this.dismissedNotificationIds.has(item.id));
  }

  get notificationTitleCount(): number {
    return this.visibleNotifications.length;
  }

  toggleNotifications(event?: Event): void {
    event?.stopPropagation();
    const willOpen = !this.notificationsOpen;
    this.notificationsOpen = willOpen;
    if (willOpen) {
      this.profileMenuOpen = false;
    }
  }

  toggleProfileMenu(event?: Event): void {
    event?.stopPropagation();
    const willOpen = !this.profileMenuOpen;
    this.profileMenuOpen = willOpen;
    if (willOpen) {
      this.notificationsOpen = false;
    }
  }

  openProfile(event?: Event): void {
    event?.stopPropagation();
    this.profileMenuOpen = false;
    void this.router.navigate(['/profile']);
  }

  openSettings(event?: Event): void {
    event?.stopPropagation();
    this.profileMenuOpen = false;
    void this.router.navigate(['/settings']);
  }

  logoutFromProfileMenu(event?: Event): void {
    event?.stopPropagation();
    this.profileMenuOpen = false;
    this.logout();
  }

  setNotificationFilter(filter: 'all' | 'unread', event?: Event): void {
    event?.stopPropagation();
    this.notificationFilter = filter;
  }

  isNotificationUnread(item: Pick<AppNotification, 'id' | 'readAt'>): boolean {
    return !item.readAt && !this.locallyReadNotificationIds.has(item.id);
  }

  openNotification(item: AppNotification): void {
    this.markNotificationRead(item.id);
    this.notificationsOpen = false;
    if (!item.routeCommands?.length) return;
    void this.router.navigate(item.routeCommands, {
      queryParams: item.routeQueryParams ?? undefined,
    });
  }

  removeNotification(item: AppNotification, event?: Event): void {
    event?.stopPropagation();
    if (this.dismissInFlightNotificationIds.has(item.id)) return;
    const confirmed = window.confirm('Delete this notification permanently?');
    if (!confirmed) return;
    this.dismissNotification(item.id);
  }

  clearAllNotifications(event?: Event): void {
    event?.stopPropagation();
    const ids = this.notifications
      .map((item) => item.id)
      .filter((id) => !this.dismissInFlightNotificationIds.has(id));
    if (!ids.length) return;

    const confirmed = window.confirm('Delete all notifications permanently?');
    if (!confirmed) return;

    const idsSet = new Set(ids);
    const removedUnread = this.notifications.reduce((count, item) => {
      if (idsSet.has(item.id) && this.isNotificationUnread(item)) {
        return count + 1;
      }
      return count;
    }, 0);

    for (const id of ids) {
      this.dismissedNotificationIds.add(id);
      this.dismissInFlightNotificationIds.add(id);
    }

    this.notifications = this.notifications.filter((item) => !idsSet.has(item.id));
    this.unreadNotificationCountValue = Math.max(0, this.unreadNotificationCountValue - removedUnread);

    forkJoin(
      ids.map((id) =>
        this.notificationApi.dismiss(id).pipe(
          catchError(() => of(void 0)),
          finalize(() => {
            this.dismissInFlightNotificationIds.delete(id);
          })
        )
      )
    ).subscribe(() => {
      this.refreshNotifications();
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

  private bonusExpiryLabel(expiresAtMs: number | null): string {
    if (expiresAtMs == null || !Number.isFinite(expiresAtMs)) return 'No expiry';
    return this.demoResetFormatter.format(new Date(expiresAtMs));
  }

  get showDemoBanner(): boolean {
    return !!this.appInfo?.demo;
  }

  get showDemoNoticeModal(): boolean {
    return this.showDemoBanner && !this.demoNoticeDismissed;
  }

  get demoBannerLabel(): string {
    return String(this.appInfo?.bannerLabel ?? '').trim() || 'Public demo';
  }

  get demoBannerMessage(): string {
    return String(this.appInfo?.bannerMessage ?? '').trim()
      || 'Demo data resets daily. Selected leaderboard entries and lobby activity are simulated.';
  }

  demoResetLabel(value: string | null | undefined): string | null {
    const raw = String(value ?? '').trim();
    if (!raw) return null;
    const timestamp = Date.parse(raw);
    if (!Number.isFinite(timestamp)) return null;
    return this.demoResetFormatter.format(new Date(timestamp));
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
          map((user) => user?.id ?? null),
          distinctUntilChanged()
        )
        .subscribe((userId) => {
          this.stopNotificationStream();
          if (!userId) {
            this.resetNotificationsState();
            return;
          }
          this.refreshNotifications();
          this.openNotificationStream();
        })
    );
  }

  private startNotificationFallbackRefresh(): void {
    this.subscriptions.add(
      interval(60000).subscribe(() => {
        if (!this.authService.snapshot) return;
        this.refreshNotifications();
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

  private loadAppInfo(): void {
    this.appInfoApi.get()
      .pipe(catchError(() => of(null)))
      .subscribe((info) => {
        this.appInfo = info;
        this.syncDemoNoticeState();
      });
  }

  dismissDemoNotice(): void {
    if (!this.showDemoBanner) return;
    this.demoNoticeDismissed = true;

    try {
      window.localStorage.setItem(this.demoNoticeStorageKey, this.demoNoticeSignature());
    } catch {
    }
  }

  private syncDemoNoticeState(): void {
    if (!this.showDemoBanner) {
      this.demoNoticeDismissed = false;
      return;
    }

    const expectedSignature = this.demoNoticeSignature();
    let storedSignature = '';
    try {
      storedSignature = window.localStorage.getItem(this.demoNoticeStorageKey) ?? '';
    } catch {
      storedSignature = '';
    }
    this.demoNoticeDismissed = storedSignature === expectedSignature;
  }

  private demoNoticeSignature(): string {
    return [
      'v1',
      this.demoBannerLabel,
      this.demoBannerMessage,
    ].join('|');
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

  private refreshNotifications(): void {
    if (!this.authService.snapshot || this.notificationRefreshInFlight) return;
    this.notificationRefreshInFlight = true;
    this.notificationApi.list(50).pipe(
      catchError(() => of({ items: [], unreadCount: 0 })),
      finalize(() => {
        this.notificationRefreshInFlight = false;
      })
    ).subscribe((response) => {
      const mapped = (response.items ?? []).map((item) => this.mapNotification(item));
      const incomingUnreadNewItems = mapped.filter(
        (item) => !item.readAt && !this.knownNotificationIds.has(item.id)
      );
      const shouldPlayIncomingNotificationSound =
        this.notificationsHydrated && incomingUnreadNewItems.length > 0;

      for (const item of mapped) {
        this.knownNotificationIds.add(item.id);
      }

      const optimisticReadNowIso = new Date().toISOString();
      const normalized = mapped.map((item) => {
        if (!item.readAt && this.locallyReadNotificationIds.has(item.id)) {
          return {
            ...item,
            readAt: optimisticReadNowIso,
          };
        }
        return item;
      });
      const serverIds = new Set<number>(mapped.map((item) => item.id));
      for (const id of this.dismissedNotificationIds) {
        if (!serverIds.has(id) && !this.dismissInFlightNotificationIds.has(id)) {
          this.dismissedNotificationIds.delete(id);
        }
      }
      for (const id of this.locallyReadNotificationIds) {
        if (!serverIds.has(id)) {
          this.locallyReadNotificationIds.delete(id);
        }
      }
      for (const item of mapped) {
        if (item.readAt) {
          this.locallyReadNotificationIds.delete(item.id);
        }
      }

      const visibleItems = normalized.filter((item) => !this.dismissedNotificationIds.has(item.id));
      const hiddenUnread = normalized.reduce((count, item) => {
        if (this.dismissedNotificationIds.has(item.id) && this.isNotificationUnread(item)) {
          return count + 1;
        }
        return count;
      }, 0);
      const optimisticStillUnread = mapped.reduce((count, item) => {
        if (this.locallyReadNotificationIds.has(item.id) && !item.readAt) {
          return count + 1;
        }
        return count;
      }, 0);
      this.notifications = this.sortNotifications(visibleItems);
      this.unreadNotificationCountValue = Math.max(
        0,
        Number(response.unreadCount ?? 0) - hiddenUnread - optimisticStillUnread
      );
      if (!this.notifications.length) {
        this.notificationsOpen = false;
      }

      if (shouldPlayIncomingNotificationSound) {
        void this.soundEffects.playEffect('notification');
      }
      this.notificationsHydrated = true;
    });
  }

  private mapNotification(item: UserNotificationDto): AppNotification {
    const category = this.normalizeCategory(item.category);
    const severity = this.normalizeSeverity(item.severity);
    const routePath = String(item.routePath ?? '').trim();
    const title = String(item.title ?? '').trim();
    const subtitle = String(item.subtitle ?? '').trim();
    const normalizedRoute = routePath.toLowerCase();
    const normalizedTitle = title.toLowerCase();
    const normalizedSubtitle = subtitle.toLowerCase();
    const hasPremiumMeaning =
      normalizedRoute === '/shop/premium' ||
      normalizedTitle.includes('premium activated') ||
      normalizedTitle.includes('premium expired') ||
      normalizedSubtitle.includes('mindrush premium');
    const rawAvatarImageUrl = String(item.avatarImageUrl ?? '').trim();
    const avatarImageUrl = rawAvatarImageUrl || (hasPremiumMeaning ? '/shop/Premium_account_icon.png' : null);

    return {
      id: Number(item.id),
      category,
      severity,
      title: title || 'Notification',
      subtitle: item.subtitle ?? null,
      text: item.text ?? null,
      meta: item.meta ?? null,
      createdAt: item.createdAt ?? null,
      readAt: item.readAt ?? null,
      decision: item.decision ?? null,
      avatarImageUrl,
      avatarBgStart: item.avatarBgStart ?? null,
      avatarBgEnd: item.avatarBgEnd ?? null,
      avatarTextColor: item.avatarTextColor ?? null,
      routeCommands: this.routeCommandsFor(routePath),
      routeQueryParams: this.normalizeRouteQuery(item.routeQueryParams),
    };
  }

  private normalizeCategory(value: string | null | undefined): AppNotificationCategory {
    if (value === 'moderation') return 'moderation';
    if (value === 'reward') return 'reward';
    if (value === 'news') return 'news';
    return 'system';
  }

  private normalizeSeverity(value: string | null | undefined): AppNotificationSeverity {
    if (value === 'success') return 'success';
    if (value === 'warning') return 'warning';
    if (value === 'danger') return 'danger';
    return 'neutral';
  }

  private routeCommandsFor(path: string | null | undefined): any[] | null {
    const normalized = String(path ?? '').trim();
    if (!normalized) return null;
    if (normalized.startsWith('/')) return [normalized];
    return ['/', normalized];
  }

  private normalizeRouteQuery(
    input: Record<string, string | number> | null | undefined
  ): Record<string, string | number> | null {
    if (!input) return null;
    const entries = Object.entries(input).filter(([key, value]) => {
      if (!key) return false;
      return typeof value === 'string' || typeof value === 'number';
    });
    if (!entries.length) return null;
    return Object.fromEntries(entries);
  }

  private openNotificationStream(): void {
    if (this.notificationStream || !this.authService.snapshot) return;
    try {
      const stream = this.notificationApi.openStream();
      stream.addEventListener('connected', (event) => this.handleNotificationStreamEvent(event));
      stream.addEventListener('refresh', (event) => this.handleNotificationStreamEvent(event));
      stream.onerror = () => {
        this.closeNotificationStream();
        this.scheduleNotificationStreamReconnect();
      };
      this.notificationStream = stream;
    } catch {
      this.scheduleNotificationStreamReconnect();
    }
  }

  private handleNotificationStreamEvent(event: Event): void {
    const message = event as MessageEvent<string>;
    const parsed = this.parseNotificationStreamEvent(message.data);
    if (!parsed) return;
    this.notificationStreamReconnectDelayMs = 0;
    this.unreadNotificationCountValue = Math.max(0, Number(parsed.unreadCount ?? 0));
    this.refreshNotifications();
  }

  private parseNotificationStreamEvent(raw: string | null | undefined): NotificationStreamEventDto | null {
    const data = String(raw ?? '').trim();
    if (!data) return null;
    try {
      const parsed = JSON.parse(data) as Partial<NotificationStreamEventDto>;
      const type = parsed.type === 'connected' || parsed.type === 'refresh' ? parsed.type : null;
      if (!type) return null;
      return {
        type,
        unreadCount: Math.max(0, Number(parsed.unreadCount ?? 0)),
        ts: String(parsed.ts ?? ''),
      };
    } catch {
      return null;
    }
  }

  private scheduleNotificationStreamReconnect(): void {
    if (!this.authService.snapshot || this.notificationStreamReconnectTimer) return;
    this.notificationStreamReconnectDelayMs = this.notificationStreamReconnectDelayMs <= 0
      ? 2000
      : Math.min(this.notificationStreamReconnectDelayMs * 2, 15000);
    this.notificationStreamReconnectTimer = setTimeout(() => {
      this.notificationStreamReconnectTimer = null;
      this.openNotificationStream();
    }, this.notificationStreamReconnectDelayMs);
  }

  private closeNotificationStream(): void {
    if (!this.notificationStream) return;
    try {
      this.notificationStream.close();
    } catch {
    }
    this.notificationStream = null;
  }

  private stopNotificationStream(): void {
    if (this.notificationStreamReconnectTimer) {
      clearTimeout(this.notificationStreamReconnectTimer);
      this.notificationStreamReconnectTimer = null;
    }
    this.closeNotificationStream();
    this.notificationStreamReconnectDelayMs = 0;
  }

  private resetNotificationsState(): void {
    this.notifications = [];
    this.unreadNotificationCountValue = 0;
    this.dismissedNotificationIds.clear();
    this.dismissInFlightNotificationIds.clear();
    this.locallyReadNotificationIds.clear();
    this.readInFlightNotificationIds.clear();
    this.knownNotificationIds.clear();
    this.notificationsHydrated = false;
    this.notificationsOpen = false;
    this.notificationFilter = 'all';
  }

  private dismissNotification(notificationId: number): void {
    if (this.dismissInFlightNotificationIds.has(notificationId)) return;
    const target = this.notifications.find((item) => item.id === notificationId);
    if (!target) return;
    const unread = this.isNotificationUnread(target);

    this.dismissedNotificationIds.add(notificationId);
    this.dismissInFlightNotificationIds.add(notificationId);
    this.locallyReadNotificationIds.delete(notificationId);
    this.readInFlightNotificationIds.delete(notificationId);
    this.notifications = this.notifications.filter((item) => item.id !== notificationId);
    if (!this.notifications.length) this.notificationsOpen = false;
    if (unread && this.unreadNotificationCountValue > 0) {
      this.unreadNotificationCountValue -= 1;
    }

    this.notificationApi.dismiss(notificationId)
      .pipe(
        catchError(() => {
          this.dismissedNotificationIds.delete(notificationId);
          this.refreshNotifications();
          return of(void 0);
        }),
        finalize(() => {
          this.dismissInFlightNotificationIds.delete(notificationId);
        })
      )
      .subscribe();
  }

  private markNotificationRead(notificationId: number): void {
    if (this.readInFlightNotificationIds.has(notificationId)) return;
    const index = this.notifications.findIndex((item) => item.id === notificationId);
    if (index < 0) return;

    const current = this.notifications[index];
    if (!this.isNotificationUnread(current)) return;

    this.locallyReadNotificationIds.add(notificationId);
    this.readInFlightNotificationIds.add(notificationId);
    const next = [...this.notifications];
    next[index] = {
      ...current,
      readAt: new Date().toISOString(),
    };
    this.notifications = next;
    if (this.unreadNotificationCountValue > 0) {
      this.unreadNotificationCountValue -= 1;
    }

    this.notificationApi.markRead(notificationId)
      .pipe(
        catchError(() => {
          this.locallyReadNotificationIds.delete(notificationId);
          this.refreshNotifications();
          return of(null);
        }),
        finalize(() => {
          this.readInFlightNotificationIds.delete(notificationId);
        })
      )
      .subscribe((updated) => {
        if (!updated) return;
        const updatedIndex = this.notifications.findIndex((item) => item.id === notificationId);
        if (updatedIndex < 0) return;
        const patched = [...this.notifications];
        patched[updatedIndex] = this.mapNotification(updated);
        this.notifications = patched;
        if (patched[updatedIndex]?.readAt) {
          this.locallyReadNotificationIds.delete(notificationId);
        }
      });
  }

  private notificationTimestamp(value: string | null | undefined): number {
    const timestamp = Date.parse(String(value ?? ''));
    if (!Number.isFinite(timestamp)) return 0;
    return timestamp;
  }

  private sortNotifications(items: AppNotification[]): AppNotification[] {
    return [...items].sort((a, b) => this.notificationTimestamp(b.createdAt) - this.notificationTimestamp(a.createdAt));
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.notificationsOpen && !this.profileMenuOpen) return;
    const target = event.target;

    if (this.notificationsOpen) {
      const notificationsHost = this.notificationsMenuRef?.nativeElement;
      if (!notificationsHost) {
        this.notificationsOpen = false;
      } else if (!(target instanceof Node && notificationsHost.contains(target))) {
        this.notificationsOpen = false;
      }
    }

    if (this.profileMenuOpen) {
      const profileHost = this.profileMenuRef?.nativeElement;
      if (!profileHost) {
        this.profileMenuOpen = false;
      } else if (!(target instanceof Node && profileHost.contains(target))) {
        this.profileMenuOpen = false;
      }
    }
  }

  @HostListener('document:keydown.escape')
  onDocumentEscape(): void {
    if (this.showDemoNoticeModal) {
      this.dismissDemoNotice();
      return;
    }
    this.sidebarOpen = false;
    this.notificationsOpen = false;
    this.profileMenuOpen = false;
  }
}
