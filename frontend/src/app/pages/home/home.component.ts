import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { LobbyApi } from '../../core/api/lobby.api';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss'
})
export class HomeComponent {
  joinCode = '';
  creating = false;
  error: string | null = null;
  createPassword = '';

  constructor(private readonly lobbyApi: LobbyApi, private readonly router: Router) {}

  createLobby(): void {
    this.error = null;
    this.creating = true;
    const password = this.createPassword.trim();
    this.lobbyApi.create(password ? password : undefined).subscribe({
      next: res => this.router.navigate(['/lobby', res.code]),
      error: err => {
        this.error = err?.error?.message ?? 'Failed to create lobby';
        this.creating = false;
      }
    });
  }

  joinLobby(): void {
    const code = this.joinCode.trim().toUpperCase();
    if (!code) return;
    this.router.navigate(['/lobby', code]);
  }
}
