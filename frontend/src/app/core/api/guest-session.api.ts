import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { GuestSessionInfoDto } from '../models/guest.models';

@Injectable({ providedIn: 'root' })
export class GuestSessionApi {
  constructor(private readonly http: HttpClient) {}

  ensure(): Observable<unknown> {
    return this.http.post('/api/guest/session', {}, { withCredentials: true });
  }

  get(): Observable<GuestSessionInfoDto> {
    return this.http.get<GuestSessionInfoDto>('/api/guest/session', { withCredentials: true });
  }

  heartbeat(): Observable<void> {
    return this.http.post<void>('/api/guest/session/heartbeat', {}, { withCredentials: true });
  }
}
