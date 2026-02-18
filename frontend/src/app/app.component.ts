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

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, AsyncPipe, NgIf, NgFor, RouterLink, RouterLinkActive],
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
      path === '/' || path.startsWith('/leaderboards') || path.startsWith('/lobby');
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
