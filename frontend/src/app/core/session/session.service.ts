import { Injectable } from '@angular/core';
import { BehaviorSubject, catchError, concatMap, Observable, of, shareReplay, tap } from 'rxjs';
import { GuestSessionApi } from '../api/guest-session.api';
import { GuestSessionInfoDto } from '../models/guest.models';

@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly sessionSubject = new BehaviorSubject<GuestSessionInfoDto | null>(null);
  readonly session$ = this.sessionSubject.asObservable();

  constructor(private readonly guestSessionApi: GuestSessionApi) {}

  ensure(): Observable<GuestSessionInfoDto | null> {
    if (!this.ensureOnce$) {
      this.ensureOnce$ = this.guestSessionApi
        .ensure()
        .pipe(
          concatMap(() => this.guestSessionApi.get()),
          tap(s => this.sessionSubject.next(s)),
          catchError(() => of(null)),
          shareReplay({ bufferSize: 1, refCount: false })
        );
    }
    return this.ensureOnce$;
  }

  private ensureOnce$?: Observable<GuestSessionInfoDto | null>;
}
