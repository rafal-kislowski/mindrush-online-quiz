import { AsyncPipe, NgFor, NgIf } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import {
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';
import { filter } from 'rxjs';
import { map } from 'rxjs';
import { AuthService } from './core/auth/auth.service';
import { SessionService } from './core/session/session.service';

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

  sidebarOpen = false;
  contentWide = false;

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
        this.contentWide = this.router.url.startsWith('/create-quiz');
      });

    this.contentWide = this.router.url.startsWith('/create-quiz');
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
}
