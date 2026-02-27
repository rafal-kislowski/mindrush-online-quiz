import { AsyncPipe, NgFor, NgIf } from '@angular/common';
import { Component, ElementRef, OnDestroy, OnInit, ViewChild, inject } from '@angular/core';
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

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, AsyncPipe, NgIf, NgFor, RouterLink, RouterLinkActive, ToastViewportComponent, PlayerAvatarComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent implements OnInit, OnDestroy {
  private readonly particlesService = inject(ParticlesService);
  private readonly sessionService = inject(SessionService);
  private readonly authService = inject(AuthService);
  private readonly gameApi = inject(GameApi);
  private readonly lobbyApi = inject(LobbyApi);
  private readonly lobbyEvents = inject(LobbyEventsService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly subscriptions = new Subscription();
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
  private currentLobbyEventsSub: Subscription | null = null;
  private currentLobbyEventsCode: string | null = null;
  private scrollResetRafId: number | null = null;

  @ViewChild('contentHost')
  private contentHostRef?: ElementRef<HTMLElement>;

  readonly menuItems: Array<{
    label: string;
    route: string;
    icon: 'home' | 'shop' | 'news' | 'settings';
  }> = [
    { label: 'Dashboard', route: '/', icon: 'home' },
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
    this.contentWide = path.startsWith('/create-quiz');
    this.contentFull =
      path === '/' ||
      path.startsWith('/play-solo') ||
      path.startsWith('/solo-game') ||
      path.startsWith('/leaderboards') ||
      path.startsWith('/lobbies') ||
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
      this.router.navigate(['/']);
    });
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
    return new Intl.NumberFormat('en-US').format(v);
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
}
