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
  private readonly router = inject(Router);
  readonly session$ = this.sessionService.session$;

  sidebarOpen = false;

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

    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe(() => {
        this.sidebarOpen = false;
      });
  }

  toggleSidebar(): void {
    this.sidebarOpen = !this.sidebarOpen;
  }

  closeSidebar(): void {
    this.sidebarOpen = false;
  }
}
