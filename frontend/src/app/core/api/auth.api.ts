import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthUserDto } from '../models/auth.models';

export interface AuthActionResponseDto {
  message: string;
}

@Injectable({ providedIn: 'root' })
export class AuthApi {
  constructor(private readonly http: HttpClient) {}

  register(email: string, displayName: string, password: string): Observable<AuthUserDto> {
    return this.http.post<AuthUserDto>(
      '/api/auth/register',
      { email, displayName, password },
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

  resendVerificationEmail(email: string): Observable<AuthActionResponseDto> {
    return this.http.post<AuthActionResponseDto>(
      '/api/auth/verification/resend',
      { email },
      { withCredentials: true }
    );
  }

  verifyEmail(token: string): Observable<AuthActionResponseDto> {
    return this.http.post<AuthActionResponseDto>(
      '/api/auth/verify-email',
      { token },
      { withCredentials: true }
    );
  }

  forgotPassword(email: string): Observable<AuthActionResponseDto> {
    return this.http.post<AuthActionResponseDto>(
      '/api/auth/password/forgot',
      { email },
      { withCredentials: true }
    );
  }

  resetPassword(token: string, password: string, confirmPassword: string): Observable<AuthActionResponseDto> {
    return this.http.post<AuthActionResponseDto>(
      '/api/auth/password/reset',
      { token, password, confirmPassword },
      { withCredentials: true }
    );
  }

  updateDisplayName(displayName: string): Observable<AuthUserDto> {
    return this.http.post<AuthUserDto>(
      '/api/auth/profile/display-name',
      { displayName },
      { withCredentials: true }
    );
  }

  changePassword(currentPassword: string, newPassword: string, confirmPassword: string): Observable<AuthUserDto> {
    return this.http.post<AuthUserDto>(
      '/api/auth/password/change',
      { currentPassword, newPassword, confirmPassword },
      { withCredentials: true }
    );
  }

  revokeAllSessions(): Observable<AuthActionResponseDto> {
    return this.http.post<AuthActionResponseDto>(
      '/api/auth/sessions/revoke-all',
      {},
      { withCredentials: true }
    );
  }
}
