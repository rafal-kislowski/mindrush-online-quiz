import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthUserDto } from '../models/auth.models';

@Injectable({ providedIn: 'root' })
export class AuthApi {
  constructor(private readonly http: HttpClient) {}

  register(email: string, password: string): Observable<AuthUserDto> {
    return this.http.post<AuthUserDto>(
      '/api/auth/register',
      { email, password },
      { withCredentials: true }
    );
  }

  login(email: string, password: string): Observable<AuthUserDto> {
    return this.http.post<AuthUserDto>(
      '/api/auth/login',
      { email, password },
      { withCredentials: true }
    );
  }

  refresh(): Observable<AuthUserDto> {
    return this.http.post<AuthUserDto>(
      '/api/auth/refresh',
      {},
      { withCredentials: true }
    );
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', {}, { withCredentials: true });
  }

  me(): Observable<AuthUserDto> {
    return this.http.get<AuthUserDto>('/api/auth/me', { withCredentials: true });
  }
}

