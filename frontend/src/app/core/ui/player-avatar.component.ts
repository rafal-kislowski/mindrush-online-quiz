import { NgIf } from '@angular/common';
import { Component, Input } from '@angular/core';
import { avatarInitials } from './player-avatar.util';

@Component({
  selector: 'app-player-avatar',
  standalone: true,
  imports: [NgIf],
  templateUrl: './player-avatar.component.html',
  styleUrl: './player-avatar.component.scss',
})
export class PlayerAvatarComponent {
  @Input() displayName: string | null | undefined = null;
  @Input() isAuthenticated = false;
  @Input() size = 32;
  @Input() decorative = true;
  @Input() ariaLabel: string | null = null;

  get initials(): string {
    return avatarInitials(this.displayName);
  }

  get resolvedLabel(): string {
    const explicit = String(this.ariaLabel ?? '').trim();
    if (explicit) return explicit;

    const name = String(this.displayName ?? '').trim() || 'User';
    if (this.isAuthenticated) return `${name} avatar`;
    return `${name} guest avatar`;
  }
}
