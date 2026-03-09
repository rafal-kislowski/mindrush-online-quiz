import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface UserNotificationDto {
  id: number;
  category: 'moderation' | 'reward' | 'news' | 'system';
  severity: 'neutral' | 'success' | 'warning' | 'danger';
  title: string;
  subtitle: string | null;
  text: string | null;
  meta: string | null;
  createdAt: string | null;
  readAt: string | null;
  decision: 'approved' | 'rejected' | null;
  avatarImageUrl: string | null;
  avatarBgStart: string | null;
  avatarBgEnd: string | null;
  avatarTextColor: string | null;
  routePath: string | null;
  routeQueryParams: Record<string, string | number> | null;
}

export interface NotificationListResponseDto {
  items: UserNotificationDto[];
  unreadCount: number;
}

export interface NotificationMarkAllReadDto {
  updated: number;
}

export interface NotificationStreamEventDto {
  type: 'connected' | 'refresh';
  unreadCount: number;
  ts: string;
}

@Injectable({ providedIn: 'root' })
export class NotificationApi {
  constructor(private readonly http: HttpClient) {}

  list(limit = 50): Observable<NotificationListResponseDto> {
    return this.http.get<NotificationListResponseDto>(
      `/api/notifications?limit=${encodeURIComponent(String(limit))}`,
      { withCredentials: true }
    );
  }

  markRead(notificationId: number): Observable<UserNotificationDto> {
    return this.http.post<UserNotificationDto>(
      `/api/notifications/${encodeURIComponent(String(notificationId))}/read`,
      {},
      { withCredentials: true }
    );
  }

  dismiss(notificationId: number): Observable<void> {
    return this.http.post<void>(
      `/api/notifications/${encodeURIComponent(String(notificationId))}/dismiss`,
      {},
      { withCredentials: true }
    );
  }

  markAllRead(): Observable<NotificationMarkAllReadDto> {
    return this.http.post<NotificationMarkAllReadDto>(
      '/api/notifications/read-all',
      {},
      { withCredentials: true }
    );
  }

  openStream(): EventSource {
    return new EventSource('/api/notifications/stream', { withCredentials: true });
  }
}

