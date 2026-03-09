import { Injectable } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { BehaviorSubject, Observable, catchError, finalize, of, shareReplay, switchMap, tap } from 'rxjs';
import { AuthActionResponseDto, AuthApi } from '../api/auth.api';
import { AuthUserDto } from '../models/auth.models';
import { SessionService } from '../session/session.service';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly userSubject = new BehaviorSubject<AuthUserDto | null>(null);
  readonly user$ = this.userSubject.asObservable();

  private loaded = false;
  private loading: Observable<AuthUserDto | null> | null = null;
  private refreshInFlight: Observable<AuthUserDto> | null = null;

  constructor(
    private readonly api: AuthApi,
    private readonly sessionService: SessionService
  ) {}

  get snapshot(): AuthUserDto | null {
    return this.userSubject.value;
  }

  ensureLoaded(): Observable<AuthUserDto | null> {
    if (this.loaded) return of(this.userSubject.value);
    if (this.loading) return this.loading;

    this.loading = this.api.me().pipe(
      catchError((err: unknown) => {
        if (err instanceof HttpErrorResponse && err.status === 401) {
          return this.refreshOnce().pipe(catchError(() => of(null)));
        }
        return of(null);
      }),
      tap((u) => {
        this.loaded = true;
        this.userSubject.next(u);
        if (u) this.sessionService.refresh().subscribe({ error: () => {} });
      }),
      finalize(() => (this.loading = null)),
      shareReplay({ bufferSize: 1, refCount: false })
    );
    return this.loading;
  }

  dropLocalAuth(): void {
    this.loaded = true;
    this.userSubject.next(null);
  }

  login(email: string, password: string): Observable<AuthUserDto> {
    return this.api.login(email, password).pipe(
      tap((u) => {
        this.loaded = true;
        this.userSubject.next(u);
        this.sessionService.refresh().subscribe({ error: () => {} });
      })
    );
  }

  register(email: string, displayName: string, password: string): Observable<AuthUserDto> {
    return this.api.register(email, displayName, password).pipe(
      switchMap((registered) =>
        this.api.me().pipe(
          tap((currentUser) => {
            this.loaded = true;
            this.userSubject.next(currentUser);
            this.sessionService.refresh().subscribe({ error: () => {} });
          }),
          catchError(() => {
            this.loaded = true;
            this.userSubject.next(null);
            return of(registered);
          })
        )
      )
    );
  }

  resendVerificationEmail(email: string): Observable<AuthActionResponseDto> {
    return this.api.resendVerificationEmail(email);
  }

  forgotPassword(email: string): Observable<AuthActionResponseDto> {
    return this.api.forgotPassword(email);
  }

  verifyEmail(token: string): Observable<AuthActionResponseDto> {
    return this.api.verifyEmail(token);
  }

  resetPassword(token: string, password: string, confirmPassword: string): Observable<AuthActionResponseDto> {
    return this.api.resetPassword(token, password, confirmPassword);
  }

  refreshOnce(): Observable<AuthUserDto> {
    if (this.refreshInFlight) return this.refreshInFlight;
    this.refreshInFlight = this.api.refresh().pipe(
      tap((u) => {
        this.loaded = true;
        this.userSubject.next(u);
        this.sessionService.refresh().subscribe({ error: () => {} });
      }),
      finalize(() => (this.refreshInFlight = null)),
      shareReplay({ bufferSize: 1, refCount: false })
    );
    return this.refreshInFlight;
  }

  reloadMe(): Observable<AuthUserDto | null> {
    return this.api.me().pipe(
      catchError((err: unknown) => {
        if (err instanceof HttpErrorResponse && err.status === 401) {
          return this.refreshOnce().pipe(catchError(() => of(null)));
        }
        return of(null);
      }),
      tap((u) => {
        this.loaded = true;
        this.userSubject.next(u);
      })
    );
  }

  logout(): Observable<void> {
    return this.api.logout().pipe(
      tap(() => {
        this.loaded = true;
        this.userSubject.next(null);
        this.sessionService.clearAndRefresh().subscribe({ error: () => {} });
      }),
      catchError(() => {
        this.loaded = true;
        this.userSubject.next(null);
        this.sessionService.clearAndRefresh().subscribe({ error: () => {} });
        return of(void 0);
      })
    );
  }

  isAdmin(u: AuthUserDto | null = this.snapshot): boolean {
    return !!u?.roles?.includes('ADMIN');
  }
}
