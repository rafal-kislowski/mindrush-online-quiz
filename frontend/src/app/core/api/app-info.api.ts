import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { AppInfoDto } from '../models/app-info.models';

@Injectable({ providedIn: 'root' })
export class AppInfoApi {
  constructor(private readonly http: HttpClient) {}

  get(): Observable<AppInfoDto> {
    return this.http.get<AppInfoDto>('/api/app/info', { withCredentials: true });
  }
}
