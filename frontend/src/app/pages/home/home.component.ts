import { CommonModule } from '@angular/common';
import { Component, ElementRef, HostListener, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { LobbyApi } from '../../core/api/lobby.api';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent implements OnInit {
  readonly joinCodeLength = 6;
  readonly joinCodeIndices = Array.from(
    { length: this.joinCodeLength },
    (_, i) => i
  );

  get authUser$() {
    return this.auth.user$;
  }

  joinCode = '';
  joinCodeChars = Array.from({ length: this.joinCodeLength }, () => '');
  creating = false;
  error: string | null = null;
  createPassword = '';
  createMaxPlayers: 2 | 3 | 4 | 5 = 2;
  maxPlayersMenuOpen = false;
  maxPlayersMenuDirection: 'down' | 'up' = 'down';

  constructor(
    private readonly lobbyApi: LobbyApi,
    private readonly auth: AuthService,
    private readonly router: Router,
    private readonly el: ElementRef<HTMLElement>
  ) {}

  ngOnInit(): void {
    this.auth.ensureLoaded().subscribe({ error: () => {} });
  }

  readonly steps: Array<{ n: number; label: string }> = [
    { n: 1, label: 'Enter as guest' },
    { n: 2, label: 'Create or join lobby' },
    { n: 3, label: 'Play in real time' },
  ];

  readonly leaderboard: Array<{
    name: string;
    points: number;
    badge: 'gold' | 'silver' | 'bronze' | 'none';
  }> = [
    { name: 'Example user 1', points: 100, badge: 'gold' },
    { name: 'Example user 2', points: 70, badge: 'silver' },
    { name: 'Example user 3', points: 50, badge: 'bronze' },
    { name: 'Example user 4', points: 30, badge: 'none' },
    { name: 'Example user 5', points: 20, badge: 'none' },
    { name: 'Example user 6', points: 10, badge: 'none' },
  ];

  createLobby(): void {
    this.error = null;
    this.creating = true;
    const password = this.createPassword.trim();
    const isLoggedIn = !!this.auth.snapshot;
    const maxPlayers = isLoggedIn ? this.createMaxPlayers : undefined;

    this.lobbyApi
      .create({
        password: password ? password : undefined,
        maxPlayers,
      })
      .subscribe({
        next: (res) => this.router.navigate(['/lobby', res.code]),
        error: (err) => {
          this.error = err?.error?.message ?? 'Failed to create lobby';
          this.creating = false;
        },
      });
  }

  toggleMaxPlayersMenu(ev?: MouseEvent): void {
    const next = !this.maxPlayersMenuOpen;
    this.maxPlayersMenuOpen = next;

    if (!next) return;

    const target = ev?.currentTarget;
    if (!(target instanceof HTMLElement)) return;

    const rect = target.getBoundingClientRect();
    const menuWantedHeight = 220;
    const spaceBelow = window.innerHeight - rect.bottom;
    const spaceAbove = rect.top;
    this.maxPlayersMenuDirection =
      spaceBelow < menuWantedHeight && spaceAbove > spaceBelow ? 'up' : 'down';
  }

  setCreateMaxPlayers(n: 2 | 3 | 4 | 5): void {
    this.createMaxPlayers = n;
    this.maxPlayersMenuOpen = false;
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(ev: MouseEvent): void {
    if (!this.maxPlayersMenuOpen) return;
    const target = ev.target as Node | null;
    if (!target) return;
    if (!this.el.nativeElement.contains(target)) {
      this.maxPlayersMenuOpen = false;
    }
  }

  @HostListener('document:keydown', ['$event'])
  onDocumentKeydown(ev: KeyboardEvent): void {
    if (!this.maxPlayersMenuOpen) return;
    if (ev.key === 'Escape') {
      this.maxPlayersMenuOpen = false;
    }
  }

  joinLobby(): void {
    const code = this.joinCode.trim().toUpperCase();
    if (!code) return;
    this.router.navigate(['/lobby', code]);
  }

  isJoinCodeComplete(): boolean {
    return this.joinCode.trim().length === 6;
  }

  onJoinCodeChange(value: string): void {
    this.joinCode = value
      .toUpperCase()
      .replace(/[^A-Z0-9]/g, '')
      .slice(0, 6);
    this.joinCodeChars = Array.from(
      { length: this.joinCodeLength },
      (_, i) => this.joinCode[i] ?? ''
    );
  }
}
