import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable, catchError, finalize, of, shareReplay, tap } from 'rxjs';
import { AuthApi } from '../api/auth.api';
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
      tap((u) => {
        this.loaded = true;
        this.userSubject.next(u);
      }),
      catchError(() => {
        this.loaded = true;
        this.userSubject.next(null);
        return of(null);
      }),
      finalize(() => (this.loading = null)),
      shareReplay({ bufferSize: 1, refCount: false })
    );
    return this.loading;
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
      tap((u) => {
        this.loaded = true;
        this.userSubject.next(u);
        this.sessionService.refresh().subscribe({ error: () => {} });
      })
    );
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
