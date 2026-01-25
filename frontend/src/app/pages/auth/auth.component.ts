import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { SessionService } from '../../core/session/session.service';

@Component({
  selector: 'app-auth',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: './auth.component.html',
  styleUrl: './auth.component.scss',
})
export class AuthComponent implements OnInit {
  mode: 'login' | 'register' = 'login';
  error: string | null = null;
  loading = false;

  readonly form = new FormGroup({
    displayName: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(32)] }),
    email: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.email] }),
    password: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.minLength(8), Validators.maxLength(72)] }),
    confirmPassword: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(72)] }),
  });

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly auth: AuthService,
    private readonly sessionService: SessionService
  ) {}

  ngOnInit(): void {
    const path = this.route.snapshot.routeConfig?.path ?? '';
    this.mode = path === 'register' ? 'register' : 'login';
    this.auth.ensureLoaded().subscribe((u) => {
      if (u) this.router.navigate(['/']);
    });
  }

  submit(): void {
    this.error = null;
    if (this.form.invalid) return;

    const email = this.form.controls.email.value.trim();
    const displayName = this.form.controls.displayName.value.trim();
    const password = this.form.controls.password.value;
    const confirm = this.form.controls.confirmPassword.value;

    if (this.mode === 'register') {
      if (displayName.length < 3) {
        this.error = 'Nickname must be at least 3 characters';
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
        this.error = err?.error?.message ?? 'Authentication failed';
      },
    });
  }
}
