import { CommonModule } from '@angular/common';
import { Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { apiErrorMessage, apiValidationErrors } from '../../core/api/api-error.util';
import { AuthService } from '../../core/auth/auth.service';
import { SessionService } from '../../core/session/session.service';
import { ToastService } from '../../core/ui/toast.service';

const NICKNAME_RE = /^[A-Za-z0-9 _-]{3,32}$/;
type AuthControlName = 'displayName' | 'email' | 'password' | 'confirmPassword';
type AuthMode = 'login' | 'register' | 'forgot' | 'reset' | 'verify';
type AuthFlashType = 'success' | 'error';

@Component({
  selector: 'app-auth',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: './auth.component.html',
  styleUrl: './auth.component.scss',
})
export class AuthComponent implements OnInit, OnDestroy {
  private static readonly FLASH_STORAGE_KEY = 'mindrush.auth.flash';
  private static readonly RESEND_UI_COOLDOWN_SECONDS = 120;
  private static readonly FORGOT_UI_COOLDOWN_SECONDS = 120;
  private static readonly RESEND_VERIFY_UNTIL_KEY = 'mindrush.auth.resend_verify_until';
  private static readonly FORGOT_UNTIL_KEY = 'mindrush.auth.forgot_until';
  mode: AuthMode = 'login';
  private _error: string | null = null;
  loading = false;
  submitted = false;
  routeToken: string | null = null;
  showResendVerification = false;
  resendVerificationLoading = false;
  resendVerificationCooldownSeconds = 0;
  forgotPasswordCooldownSeconds = 0;
  private tokenSub?: Subscription;
  private resendCooldownTimer: ReturnType<typeof setInterval> | null = null;
  private forgotCooldownTimer: ReturnType<typeof setInterval> | null = null;
  private loginRedirectUrl: string | null = null;

  @ViewChild('authEmailInput')
  private authEmailInput?: ElementRef<HTMLInputElement>;

  readonly form = new FormGroup({
    displayName: new FormControl('', { nonNullable: true }),
    email: new FormControl('', { nonNullable: true }),
    password: new FormControl('', { nonNullable: true }),
    confirmPassword: new FormControl('', { nonNullable: true }),
  });

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly auth: AuthService,
    private readonly sessionService: SessionService,
    private readonly toast: ToastService
  ) {}

  get error(): string | null {
    return this._error;
  }

  set error(value: string | null) {
    this._error = value;
    if (!value) return;
    this.toast.error(value, { dedupeKey: `auth:error:${value}` });
  }

  get showModeSwitch(): boolean {
    return this.mode === 'login' || this.mode === 'register';
  }

  get canSubmit(): boolean {
    if (this.loading) return false;
    if (this.mode === 'verify') return false;
    if (this.mode === 'reset' && !this.routeToken) return false;
    if (this.mode === 'forgot' && this.forgotPasswordCooldownSeconds > 0) return false;
    return this.form.valid;
  }

  get title(): string {
    if (this.mode === 'register') return 'Create your account';
    if (this.mode === 'forgot') return 'Forgot password';
    if (this.mode === 'reset') return 'Set a new password';
    return 'Welcome back';
  }

  get lead(): string {
    if (this.mode === 'register') return 'Register to unlock lobbies, progression, and all game features.';
    if (this.mode === 'forgot') return 'Enter your email and we will send a secure password reset link.';
    if (this.mode === 'reset') return 'Create a strong new password for your account.';
    return 'Sign in and jump straight into your dashboard and lobby.';
  }

  ngOnInit(): void {
    this.resolveMode();
    this.loginRedirectUrl = this.normalizeRedirectUrl(this.route.snapshot.queryParamMap.get('redirect'));
    if (this.mode === 'verify') {
      this.listenForVerifyTokenAndRedirect();
      return;
    }

    this.configureFormForMode();
    if (this.mode === 'login' || this.mode === 'register') {
      this.consumeFlash();
      this.auth.ensureLoaded().subscribe((u) => {
        if (u) this.navigateAfterLogin();
      });
    }

    if (this.mode === 'reset') {
      this.tokenSub = this.route.queryParamMap.subscribe((params) => {
        const token = (params.get('token') ?? '').trim();
        this.routeToken = token || null;
      });
    }
  }

  ngOnDestroy(): void {
    this.tokenSub?.unsubscribe();
    if (this.resendCooldownTimer) {
      clearInterval(this.resendCooldownTimer);
      this.resendCooldownTimer = null;
    }
    if (this.forgotCooldownTimer) {
      clearInterval(this.forgotCooldownTimer);
      this.forgotCooldownTimer = null;
    }
  }

  submit(): void {
    if (this.mode === 'verify') return;

    this.submitted = true;
    this.error = null;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.error = this.firstValidationError();
      return;
    }

    const email = this.form.controls.email.value.trim();
    const displayName = this.form.controls.displayName.value.trim();
    const password = this.form.controls.password.value;
    const confirm = this.form.controls.confirmPassword.value;

    if (this.mode === 'register') {
      if (!displayName) {
        this.error = 'Nickname is required';
        return;
      }
      if (displayName.length < 3) {
        this.error = 'Nickname must be 3-32 characters';
        return;
      }
      if (!NICKNAME_RE.test(displayName)) {
        this.error = 'Nickname contains invalid characters';
        return;
      }
      if (!confirm || confirm !== password) {
        this.error = 'Passwords do not match';
        return;
      }
    }

    if (this.mode === 'reset') {
      if (!this.routeToken) {
        this.error = 'Reset token is missing or invalid.';
        return;
      }
      if (!confirm || confirm !== password) {
        this.error = 'Passwords do not match';
        return;
      }
    }

    this.loading = true;

    if (this.mode === 'login') {
      this.auth.login(email, password).subscribe({
        next: () => {
          this.sessionService.refresh().subscribe({
            next: () => {
              this.loading = false;
              this.navigateAfterLogin();
            },
            error: () => {
              this.loading = false;
              this.navigateAfterLogin();
            },
          });
        },
        error: (err) => {
          this.loading = false;
          this.applyBackendValidationErrors(err);
          const message = apiErrorMessage(err, 'Authentication failed');
          const requiresVerification = this.isVerificationRequiredMessage(message);
          this.showResendVerification = requiresVerification;
          if (requiresVerification) {
            this.clearOnlyPassword();
          } else {
            this.clearLoginInputs(true);
          }
          this.error = message;
        },
      });
      return;
    }

    if (this.mode === 'register') {
      this.auth.register(email, displayName, password).subscribe({
        next: () => {
          this.loading = false;
          if (this.auth.snapshot) {
            this.router.navigate(['/']);
            return;
          }
          this.pushFlash('success', 'Account created. Please verify your email before signing in.', 'Registration');
          this.router.navigate(['/login']);
        },
        error: (err) => {
          this.loading = false;
          this.applyBackendValidationErrors(err);
          this.error = apiErrorMessage(err, 'Authentication failed');
        },
      });
      return;
    }

    if (this.mode === 'forgot') {
      if (this.forgotPasswordCooldownSeconds > 0) {
        return;
      }
      this.auth.forgotPassword(email).subscribe({
        next: (res) => {
          this.loading = false;
          const message = res?.message || 'If this email is registered, a password reset link has been sent.';
          this.startForgotCooldown(AuthComponent.FORGOT_UI_COOLDOWN_SECONDS);
          this.pushFlash('success', message, 'Password reset');
          this.router.navigate(['/login']);
        },
        error: (err) => {
          this.loading = false;
          this.applyBackendValidationErrors(err);
          this.error = apiErrorMessage(err, 'Could not start password reset');
        },
      });
      return;
    }

    this.auth.resetPassword(this.routeToken!, password, confirm).subscribe({
      next: (res) => {
        this.loading = false;
        this.pushFlash('success', res?.message || 'Password was reset successfully.', 'Password reset');
        this.router.navigate(['/login']);
      },
      error: (err) => {
        this.loading = false;
        this.applyBackendValidationErrors(err);
        this.error = apiErrorMessage(err, 'Could not reset password');
      },
    });
  }

  controlError(name: AuthControlName): string | null {
    if (!this.controlActive(name)) return null;
    const control = this.form.controls[name];
    const show = this.submitted || control.touched || control.dirty;
    if (!show) return null;

    const errors = control.errors;
    if (errors) {
      if (errors['required']) return this.requiredMessageFor(name);
      if (errors['email']) return 'Email format is invalid';
      if (errors['minlength']) {
        if (name === 'password') return 'Password must be at least 8 characters';
        if (name === 'displayName') return 'Nickname must be 3-32 characters';
      }
      if (errors['maxlength']) {
        if (name === 'password' || name === 'confirmPassword') return 'Password must be 8-72 characters';
        if (name === 'displayName') return 'Nickname must be 3-32 characters';
      }
      if (errors['pattern'] && name === 'displayName') return 'Nickname contains invalid characters';
    }

    if (name === 'confirmPassword' && (this.mode === 'register' || this.mode === 'reset')) {
      const confirm = this.form.controls.confirmPassword.value;
      if (!confirm) return 'Confirm password is required';
      if (confirm !== this.form.controls.password.value) return 'Passwords do not match';
    }

    return null;
  }

  resendVerificationEmail(): void {
    if (this.mode !== 'login') return;
    if (this.resendVerificationLoading) return;
    if (this.resendVerificationCooldownSeconds > 0) {
      this.error = `You can resend verification email in ${this.resendVerificationCooldownSeconds}s.`;
      return;
    }
    const email = this.form.controls.email.value.trim();
    if (!email) {
      this.error = 'Email is required';
      return;
    }
    if (this.form.controls.email.invalid) {
      this.error = 'Email format is invalid';
      return;
    }

    this.resendVerificationLoading = true;
    this.auth.resendVerificationEmail(email).subscribe({
      next: (res) => {
        this.resendVerificationLoading = false;
        this.toast.success(
          res?.message || 'If this account exists and still requires verification, an email has been sent.',
          { title: 'Verification' }
        );
        this.startResendCooldown(AuthComponent.RESEND_UI_COOLDOWN_SECONDS);
      },
      error: (err) => {
        this.resendVerificationLoading = false;
        this.error = apiErrorMessage(err, 'Could not resend verification email');
      },
    });
  }

  private resolveMode(): void {
    const path = (this.route.snapshot.routeConfig?.path ?? '').trim();
    if (path === 'register') {
      this.mode = 'register';
      return;
    }
    if (path === 'forgot-password') {
      this.mode = 'forgot';
      return;
    }
    if (path === 'reset-password') {
      this.mode = 'reset';
      return;
    }
    if (path === 'verify-email') {
      this.mode = 'verify';
      return;
    }
    this.mode = 'login';
  }

  private configureFormForMode(): void {
    const register = this.mode === 'register';
    const forgot = this.mode === 'forgot';
    const reset = this.mode === 'reset';
    const login = this.mode === 'login';

    const displayValidators = register
      ? [Validators.required, Validators.minLength(3), Validators.maxLength(32), Validators.pattern(NICKNAME_RE)]
      : [];
    const emailValidators = login || register || forgot
      ? [Validators.required, Validators.email, Validators.maxLength(320)]
      : [];
    const passwordValidators = login || register || reset
      ? [Validators.required, Validators.minLength(8), Validators.maxLength(72)]
      : [];
    const confirmValidators = register || reset
      ? [Validators.required, Validators.maxLength(72)]
      : [];

    this.form.controls.displayName.setValidators(displayValidators);
    this.form.controls.email.setValidators(emailValidators);
    this.form.controls.password.setValidators(passwordValidators);
    this.form.controls.confirmPassword.setValidators(confirmValidators);

    this.form.controls.displayName.updateValueAndValidity({ emitEvent: false });
    this.form.controls.email.updateValueAndValidity({ emitEvent: false });
    this.form.controls.password.updateValueAndValidity({ emitEvent: false });
    this.form.controls.confirmPassword.updateValueAndValidity({ emitEvent: false });

    if (!register) this.form.controls.displayName.setValue('');
    if (!login && !register && !forgot) this.form.controls.email.setValue('');
    if (!login && !register && !reset) this.form.controls.password.setValue('');
    if (!register && !reset) this.form.controls.confirmPassword.setValue('');

    this.submitted = false;
    this.error = null;
    this.showResendVerification = false;
    this.refreshCooldownsFromStorage();
  }

  private listenForVerifyTokenAndRedirect(): void {
    this.tokenSub = this.route.queryParamMap.subscribe((params) => {
      const token = (params.get('token') ?? '').trim();
      this.routeToken = token || null;

      if (!this.routeToken) {
        this.pushFlash('error', 'Verification token is missing or invalid.', 'Verification');
        this.router.navigate(['/login']);
        return;
      }

      this.loading = true;
      this.auth.verifyEmail(this.routeToken).subscribe({
        next: (res) => {
          this.loading = false;
          this.pushFlash('success', res?.message || 'Your account email has been verified.', 'Verification');
          this.router.navigate(['/login']);
        },
        error: (err) => {
          this.loading = false;
          this.pushFlash('error', apiErrorMessage(err, 'Verification token is invalid or expired'), 'Verification');
          this.router.navigate(['/login']);
        },
      });
    });
  }

  private pushFlash(type: AuthFlashType, message: string, title?: string): void {
    if (!message) return;
    const payload = JSON.stringify({ type, message, title: title ?? '' });
    try {
      sessionStorage.setItem(AuthComponent.FLASH_STORAGE_KEY, payload);
    } catch {
      if (type === 'success') this.toast.success(message, title ? { title } : undefined);
      else this.toast.error(message, title ? { title } : undefined);
    }
  }

  private consumeFlash(): void {
    try {
      const raw = sessionStorage.getItem(AuthComponent.FLASH_STORAGE_KEY);
      if (!raw) return;
      sessionStorage.removeItem(AuthComponent.FLASH_STORAGE_KEY);
      const parsed = JSON.parse(raw) as { type?: string; message?: string; title?: string };
      const message = (parsed?.message ?? '').trim();
      if (!message) return;
      const title = (parsed?.title ?? '').trim();
      if (parsed?.type === 'success') {
        this.toast.success(message, title ? { title } : undefined);
      } else {
        this.toast.error(message, title ? { title } : undefined);
      }
    } catch {
      sessionStorage.removeItem(AuthComponent.FLASH_STORAGE_KEY);
    }
  }

  private startResendCooldown(seconds: number): void {
    const safeSeconds = Math.max(1, Math.floor(seconds));
    this.persistCooldownUntil(AuthComponent.RESEND_VERIFY_UNTIL_KEY, safeSeconds);
    this.runResendCooldown(safeSeconds);
  }

  private startForgotCooldown(seconds: number): void {
    const safeSeconds = Math.max(1, Math.floor(seconds));
    this.persistCooldownUntil(AuthComponent.FORGOT_UNTIL_KEY, safeSeconds);
    this.runForgotCooldown(safeSeconds);
  }

  private runResendCooldown(seconds: number): void {
    this.resendVerificationCooldownSeconds = Math.max(0, Math.floor(seconds));
    if (this.resendCooldownTimer) {
      clearInterval(this.resendCooldownTimer);
    }
    this.resendCooldownTimer = setInterval(() => {
      this.resendVerificationCooldownSeconds -= 1;
      if (this.resendVerificationCooldownSeconds <= 0) {
        this.resendVerificationCooldownSeconds = 0;
        if (this.resendCooldownTimer) {
          clearInterval(this.resendCooldownTimer);
          this.resendCooldownTimer = null;
        }
      }
    }, 1000);
  }

  private runForgotCooldown(seconds: number): void {
    this.forgotPasswordCooldownSeconds = seconds;
    if (this.forgotCooldownTimer) {
      clearInterval(this.forgotCooldownTimer);
    }
    this.forgotCooldownTimer = setInterval(() => {
      this.forgotPasswordCooldownSeconds -= 1;
      if (this.forgotPasswordCooldownSeconds <= 0) {
        this.forgotPasswordCooldownSeconds = 0;
        if (this.forgotCooldownTimer) {
          clearInterval(this.forgotCooldownTimer);
          this.forgotCooldownTimer = null;
        }
      }
    }, 1000);
  }

  private refreshCooldownsFromStorage(): void {
    const resendSeconds = this.readCooldownSeconds(AuthComponent.RESEND_VERIFY_UNTIL_KEY);
    const forgotSeconds = this.readCooldownSeconds(AuthComponent.FORGOT_UNTIL_KEY);
    if (resendSeconds > 0) this.runResendCooldown(resendSeconds);
    else this.resendVerificationCooldownSeconds = 0;
    if (forgotSeconds > 0) this.runForgotCooldown(forgotSeconds);
    else this.forgotPasswordCooldownSeconds = 0;
  }

  private persistCooldownUntil(key: string, seconds: number): void {
    const until = Date.now() + seconds * 1000;
    try {
      sessionStorage.setItem(key, String(until));
    } catch {
      // no-op
    }
  }

  private readCooldownSeconds(key: string): number {
    try {
      const raw = sessionStorage.getItem(key);
      if (!raw) return 0;
      const until = Number(raw);
      if (!Number.isFinite(until) || until <= 0) return 0;
      const remainingMs = Math.max(0, until - Date.now());
      return Math.ceil(remainingMs / 1000);
    } catch {
      return 0;
    }
  }

  private isVerificationRequiredMessage(message: string | null | undefined): boolean {
    const text = (message ?? '').toLowerCase();
    return text.includes('verify your email');
  }

  private controlActive(name: AuthControlName): boolean {
    if (name === 'displayName') return this.mode === 'register';
    if (name === 'email') return this.mode === 'login' || this.mode === 'register' || this.mode === 'forgot';
    if (name === 'password') return this.mode === 'login' || this.mode === 'register' || this.mode === 'reset';
    if (name === 'confirmPassword') return this.mode === 'register' || this.mode === 'reset';
    return false;
  }

  private firstValidationError(): string {
    const orderByMode: Record<AuthMode, AuthControlName[]> = {
      login: ['email', 'password', 'displayName', 'confirmPassword'],
      register: ['displayName', 'email', 'password', 'confirmPassword'],
      forgot: ['email', 'password', 'displayName', 'confirmPassword'],
      reset: ['password', 'confirmPassword', 'email', 'displayName'],
      verify: ['email', 'password', 'displayName', 'confirmPassword'],
    };
    for (const name of orderByMode[this.mode]) {
      const message = this.controlError(name);
      if (message) return message;
    }
    return 'Please correct the form and try again.';
  }

  private requiredMessageFor(name: AuthControlName): string {
    if (name === 'email') return 'Email is required';
    if (name === 'password') return 'Password is required';
    if (name === 'displayName') return 'Nickname is required';
    return 'Confirm password is required';
  }

  private applyBackendValidationErrors(err: unknown): void {
    const backendErrors = apiValidationErrors(err);
    if (!backendErrors.length) return;

    const known = new Set<AuthControlName>(['displayName', 'email', 'password', 'confirmPassword']);
    for (const issue of backendErrors) {
      const field = issue.field as AuthControlName;
      if (!known.has(field)) continue;
      if (!this.controlActive(field)) continue;
      const control = this.form.controls[field];
      control.markAsTouched();
      control.markAsDirty();
    }
  }

  private clearLoginInputs(focusEmail: boolean): void {
    this.form.controls.email.setValue('');
    this.form.controls.password.setValue('');

    this.form.controls.email.markAsPristine();
    this.form.controls.email.markAsUntouched();
    this.form.controls.password.markAsPristine();
    this.form.controls.password.markAsUntouched();

    this.submitted = false;

    if (!focusEmail) return;
    const input = this.authEmailInput?.nativeElement;
    if (!input) return;
    try {
      input.focus({ preventScroll: true } as any);
    } catch {
      input.focus();
    }
  }

  private clearOnlyPassword(): void {
    this.form.controls.password.setValue('');
    this.form.controls.password.markAsPristine();
    this.form.controls.password.markAsUntouched();
  }

  private navigateAfterLogin(): void {
    const redirect = this.loginRedirectUrl ?? '/';
    void this.router.navigateByUrl(redirect);
  }

  private normalizeRedirectUrl(raw: string | null | undefined): string | null {
    const value = (raw ?? '').trim();
    if (!value) return null;
    if (!value.startsWith('/')) return null;
    if (value.startsWith('//')) return null;
    return value;
  }
}
