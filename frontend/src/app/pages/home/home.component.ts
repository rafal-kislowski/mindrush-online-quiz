import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { LobbyApi } from '../../core/api/lobby.api';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent {
  readonly joinCodeLength = 6;
  readonly joinCodeIndices = Array.from(
    { length: this.joinCodeLength },
    (_, i) => i
  );

  joinCode = '';
  joinCodeChars = Array.from({ length: this.joinCodeLength }, () => '');
  creating = false;
  error: string | null = null;
  createPassword = '';

  constructor(
    private readonly lobbyApi: LobbyApi,
    private readonly router: Router
  ) {}

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
    this.lobbyApi.create(password ? password : undefined).subscribe({
      next: (res) => this.router.navigate(['/lobby', res.code]),
      error: (err) => {
        this.error = err?.error?.message ?? 'Failed to create lobby';
        this.creating = false;
      },
    });
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
