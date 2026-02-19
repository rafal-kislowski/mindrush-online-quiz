import { CommonModule } from '@angular/common';
import { Component, ElementRef, OnInit, ViewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { apiErrorMessage, apiValidationErrors } from '../../core/api/api-error.util';
import { AuthService } from '../../core/auth/auth.service';
import { SessionService } from '../../core/session/session.service';
import { ToastService } from '../../core/ui/toast.service';

const NICKNAME_RE = /^[A-Za-z0-9 _-]{3,32}$/;
type AuthControlName = 'displayName' | 'email' | 'password' | 'confirmPassword';

@Component({
  selector: 'app-auth',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: './auth.component.html',
  styleUrl: './auth.component.scss',
})
export class AuthComponent implements OnInit {
  mode: 'login' | 'register' = 'login';
  private _error: string | null = null;
  loading = false;
  submitted = false;
  @ViewChild('authEmailInput')
  private authEmailInput?: ElementRef<HTMLInputElement>;

  readonly form = new FormGroup({
    displayName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.minLength(3), Validators.maxLength(32), Validators.pattern(NICKNAME_RE)],
    }),
    email: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.email] }),
    password: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.minLength(8), Validators.maxLength(72)] }),
    confirmPassword: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(72)] }),
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

  ngOnInit(): void {
    const path = this.route.snapshot.routeConfig?.path ?? '';
    this.mode = path === 'register' ? 'register' : 'login';
    this.auth.ensureLoaded().subscribe((u) => {
      if (u) this.router.navigate(['/']);
    });
  }

  submit(): void {
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
      if (!confirm) {
        this.error = 'Confirm password is required';
        return;
      }
      if (!confirm || confirm !== password) {
        this.error = 'Passwords do not match';
        return;
      }
    }

    this.loading = true;
    const call =
      this.mode === 'register'
        ? this.auth.register(email, displayName, password)
        : this.auth.login(email, password);

    call.subscribe({
      next: () => {
        this.sessionService.refresh().subscribe({
          next: () => {
            this.loading = false;
            this.router.navigate(['/']);
          },
          error: () => {
            this.loading = false;
            this.router.navigate(['/']);
          },
        });
      },
      error: (err) => {
        this.loading = false;
        this.applyBackendValidationErrors(err);
        if (this.mode === 'login') {
          this.clearLoginInputs(true);
        }
        this.error = apiErrorMessage(err, 'Authentication failed');
      },
    });
  }

  controlError(name: AuthControlName): string | null {
    const control = this.form.controls[name];
    const show = this.submitted || control.touched || control.dirty;
    if (!show) return null;

    if (name === 'displayName' && this.mode !== 'register') return null;
    if (name === 'confirmPassword' && this.mode !== 'register') return null;

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

    if (name === 'confirmPassword') {
      const confirm = this.form.controls.confirmPassword.value;
      if (!confirm) return 'Confirm password is required';
      if (confirm !== this.form.controls.password.value) return 'Passwords do not match';
    }

    return null;
  }

  private firstValidationError(): string {
    const order: AuthControlName[] = ['displayName', 'email', 'password', 'confirmPassword'];
    for (const name of order) {
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
}
