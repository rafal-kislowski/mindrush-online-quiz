import { Injectable } from '@angular/core';
import {
  BehaviorSubject,
  catchError,
  concatMap,
  Observable,
  of,
  shareReplay,
  Subscription,
  tap,
  timer,
} from 'rxjs';
import { GuestSessionApi } from '../api/guest-session.api';
import { GuestSessionInfoDto } from '../models/guest.models';

@Injectable({ providedIn: 'root' })
export class SessionService {
  private static readonly HEARTBEAT_MS = 10_000;

  private readonly sessionSubject = new BehaviorSubject<GuestSessionInfoDto | null>(null);
  readonly session$ = this.sessionSubject.asObservable();

  constructor(private readonly guestSessionApi: GuestSessionApi) {}

  ensure(): Observable<GuestSessionInfoDto | null> {
    if (!this.ensureOnce$) {
      this.ensureOnce$ = this.guestSessionApi
        .ensure()
        .pipe(
          concatMap(() => this.guestSessionApi.get()),
          tap(s => {
            this.sessionSubject.next(s);
            if (s) this.startHeartbeat();
          }),
          catchError(() => of(null)),
          shareReplay({ bufferSize: 1, refCount: false })
        );
    }
    return this.ensureOnce$;
  }

  private ensureOnce$?: Observable<GuestSessionInfoDto | null>;

  private heartbeatSubscription: Subscription | null = null;

  private startHeartbeat(): void {
    if (this.heartbeatSubscription) return;
    this.heartbeatSubscription = timer(SessionService.HEARTBEAT_MS, SessionService.HEARTBEAT_MS)
      .pipe(concatMap(() => this.guestSessionApi.heartbeat().pipe(catchError(() => of(void 0)))))
      .subscribe();
  }
}
