import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error.util';
import { AuthService } from '../../core/auth/auth.service';
import { AuthUserDto } from '../../core/models/auth.models';
import { SoundEffectsService } from '../../core/ui/sound-effects.service';
import { ToastService } from '../../core/ui/toast.service';

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.scss',
})
export class SettingsComponent implements OnInit, OnDestroy {
  private static readonly LEGACY_PREF_HIDE_PARTICLES_KEY = 'mindrush.settings.hide_particles';
  private static readonly LEGACY_PREF_REDUCE_MOTION_KEY = 'mindrush.settings.reduce_motion';
  private static readonly VERIFY_RESEND_SECONDS = 60;

  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly soundEffects = inject(SoundEffectsService);
  private readonly toast = inject(ToastService);
  private readonly subscriptions = new Subscription();

  loadingUser = true;
  currentUser: AuthUserDto | null = null;

  savingPassword = false;
  resendingVerification = false;
  revokingSessions = false;
  resendCooldownSeconds = 0;
  private resendCooldownTimer: ReturnType<typeof setInterval> | null = null;
  soundMuted = false;

  readonly passwordForm = new FormGroup({
    currentPassword: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(8), Validators.maxLength(72)],
    }),
    newPassword: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(8), Validators.maxLength(72)],
    }),
    confirmPassword: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(8), Validators.maxLength(72)],
    }),
  });

  ngOnInit(): void {
    this.clearLegacyInterfacePreferences();
    this.syncSoundPreferencesFromService();

    this.subscriptions.add(
      this.auth.user$.subscribe((user) => {
        this.currentUser = user;
      })
    );

    this.subscriptions.add(
      this.auth.ensureLoaded().subscribe({
        next: (user) => {
          this.loadingUser = false;
          if (!user) {
            void this.router.navigate(['/login']);
            return;
          }
        },
        error: () => {
          this.loadingUser = false;
          void this.router.navigate(['/login']);
        },
      })
    );
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    if (this.resendCooldownTimer) {
      clearInterval(this.resendCooldownTimer);
      this.resendCooldownTimer = null;
    }
  }

  get currentPasswordControl(): FormControl<string> {
    return this.passwordForm.controls.currentPassword;
  }

  get newPasswordControl(): FormControl<string> {
    return this.passwordForm.controls.newPassword;
  }

  get confirmPasswordControl(): FormControl<string> {
    return this.passwordForm.controls.confirmPassword;
  }

  get canSavePassword(): boolean {
    if (this.savingPassword) return false;
    if (this.passwordForm.invalid) return false;
    if (!this.passwordsMatch) return false;
    return this.currentPasswordControl.value !== this.newPasswordControl.value;
  }

  get passwordsMatch(): boolean {
    return this.newPasswordControl.value === this.confirmPasswordControl.value;
  }

  get currentPasswordError(): string | null {
    const errors = this.currentPasswordControl.errors;
    if (!errors || (!this.currentPasswordControl.touched && !this.currentPasswordControl.dirty)) return null;
    if (errors['required']) return 'Current password is required';
    return 'Password must be 8-72 characters';
  }

  get newPasswordError(): string | null {
    const errors = this.newPasswordControl.errors;
    if (!errors || (!this.newPasswordControl.touched && !this.newPasswordControl.dirty)) return null;
    if (errors['required']) return 'New password is required';
    return 'Password must be 8-72 characters';
  }

  get confirmPasswordError(): string | null {
    const touched = this.confirmPasswordControl.touched || this.confirmPasswordControl.dirty;
    if (!touched) return null;
    const errors = this.confirmPasswordControl.errors;
    if (errors) {
      if (errors['required']) return 'Confirm password is required';
      return 'Password must be 8-72 characters';
    }
    if (!this.passwordsMatch) return 'Passwords do not match';
    return null;
  }

  savePassword(): void {
    if (!this.canSavePassword) {
      this.passwordForm.markAllAsTouched();
      if (this.currentPasswordControl.value === this.newPasswordControl.value) {
        this.toast.warning('New password must be different from current password.', { title: 'Security' });
      }
      return;
    }

    this.savingPassword = true;
    this.auth.changePassword(
      this.currentPasswordControl.value,
      this.newPasswordControl.value,
      this.confirmPasswordControl.value
    ).subscribe({
      next: () => {
        this.savingPassword = false;
        this.passwordForm.reset({
          currentPassword: '',
          newPassword: '',
          confirmPassword: '',
        });
        this.passwordForm.markAsPristine();
        this.toast.success('Password changed successfully.', { title: 'Security' });
      },
      error: (err) => {
        this.savingPassword = false;
        this.toast.error(apiErrorMessage(err, 'Could not change password'), { title: 'Security' });
      },
    });
  }

  resendVerificationEmail(): void {
    if (!this.currentUser || this.currentUser.emailVerified) return;
    if (this.resendingVerification || this.resendCooldownSeconds > 0) return;

    this.resendingVerification = true;
    this.auth.resendVerificationEmail(this.currentUser.email).subscribe({
      next: (res) => {
        this.resendingVerification = false;
        this.toast.success(
          res?.message || 'Verification email has been sent.',
          { title: 'Verification' }
        );
        this.startResendCooldown(SettingsComponent.VERIFY_RESEND_SECONDS);
      },
      error: (err) => {
        this.resendingVerification = false;
        this.toast.error(apiErrorMessage(err, 'Could not resend verification email'), { title: 'Verification' });
      },
    });
  }

  revokeAllSessions(): void {
    if (this.revokingSessions) return;
    const confirmed = window.confirm('Sign out from all devices now? You will need to log in again.');
    if (!confirmed) return;

    this.revokingSessions = true;
    this.auth.revokeAllSessions().subscribe({
      next: (res) => {
        this.revokingSessions = false;
        this.toast.success(res?.message || 'Signed out from all devices.', { title: 'Security' });
        void this.router.navigate(['/login']);
      },
      error: (err) => {
        this.revokingSessions = false;
        this.toast.error(apiErrorMessage(err, 'Could not revoke active sessions'), { title: 'Security' });
      },
    });
  }

  goBack(): void {
    void this.router.navigate(['/']);
  }

  onSoundMutedChange(muted: boolean): void {
    this.soundMuted = muted;
    this.soundEffects.setEnabled(!muted);
  }

  formatDateTimeLabel(value: string | null | undefined): string {
    const raw = String(value ?? '').trim();
    if (!raw) return 'No data';
    const millis = Date.parse(raw);
    if (!Number.isFinite(millis)) return 'No data';
    const date = new Date(millis);
    const day = String(date.getDate()).padStart(2, '0');
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const year = date.getFullYear();
    const hour = String(date.getHours()).padStart(2, '0');
    const minute = String(date.getMinutes()).padStart(2, '0');
    return `${day}.${month}.${year}, ${hour}:${minute}`;
  }

  private startResendCooldown(seconds: number): void {
    this.resendCooldownSeconds = Math.max(0, Math.floor(seconds));
    if (this.resendCooldownTimer) {
      clearInterval(this.resendCooldownTimer);
    }
    this.resendCooldownTimer = setInterval(() => {
      this.resendCooldownSeconds -= 1;
      if (this.resendCooldownSeconds <= 0) {
        this.resendCooldownSeconds = 0;
        if (this.resendCooldownTimer) {
          clearInterval(this.resendCooldownTimer);
          this.resendCooldownTimer = null;
        }
      }
    }, 1000);
  }

  private clearLegacyInterfacePreferences(): void {
    document.body.classList.remove('mr-hide-particles', 'mr-reduce-motion');
    try {
      localStorage.removeItem(SettingsComponent.LEGACY_PREF_HIDE_PARTICLES_KEY);
      localStorage.removeItem(SettingsComponent.LEGACY_PREF_REDUCE_MOTION_KEY);
    } catch {
      // ignore storage errors
    }
  }

  private syncSoundPreferencesFromService(): void {
    this.soundMuted = !this.soundEffects.isEnabled();
  }

}
