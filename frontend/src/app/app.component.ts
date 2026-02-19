import { AsyncPipe, NgFor, NgIf } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import {
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';
import { combineLatest, filter, map } from 'rxjs';
import { AuthService } from './core/auth/auth.service';
import { SessionService } from './core/session/session.service';
import { computeLevelProgress, levelTheme, rankForPoints } from './core/progression/progression';
import { ToastViewportComponent } from './core/ui/toast-viewport.component';

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
  imports: [RouterOutlet, AsyncPipe, NgIf, NgFor, RouterLink, RouterLinkActive, ToastViewportComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent implements OnInit {
  private readonly sessionService = inject(SessionService);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
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
  contentWide = false;
  contentFull = false;

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
    this.sessionService.ensure().subscribe();
    this.authService.ensureLoaded().subscribe((u) => {
      if (u) {
        this.sessionService.refresh().subscribe({ error: () => {} });
      }
    });

    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe(() => {
        this.sidebarOpen = false;
        this.updateContentFlags(this.router.url);
      });

    this.updateContentFlags(this.router.url);
  }

  private updateContentFlags(url: string): void {
    const path = (url ?? '').split('?')[0]?.split('#')[0] ?? '';
    this.contentWide = path.startsWith('/create-quiz');
    this.contentFull =
      path === '/' ||
      path.startsWith('/leaderboards') ||
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

  logout(): void {
    this.authService.logout().subscribe(() => {
      this.router.navigate(['/']);
    });
  }

  formatInt(n: number | null | undefined): string {
    const v = Math.max(0, Math.floor(n ?? 0));
    return new Intl.NumberFormat('en-US').format(v);
  }
}
