import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { StompClientService } from './stomp-client.service';

export interface LobbyChatMessageDto {
  lobbyCode: string;
  displayName: string;
  text: string;
  serverTime: string;
}

@Injectable({ providedIn: 'root' })
export class LobbyChatService {
  constructor(
    private readonly stompClient: StompClientService,
    private readonly http: HttpClient
  ) {}

  history(lobbyCode: string): Observable<LobbyChatMessageDto[]> {
    const code = (lobbyCode ?? '').trim().toUpperCase();
    return this.http.get<LobbyChatMessageDto[]>(
      `/api/lobbies/${encodeURIComponent(code)}/chat`,
      { withCredentials: true }
    );
  }

  subscribe(lobbyCode: string): Observable<LobbyChatMessageDto> {
    return new Observable<LobbyChatMessageDto>((subscriber) => {
      const destination = `/topic/lobbies/${lobbyCode}/chat`;
      const sub = this.stompClient.subscribe(destination).subscribe({
        next: (message) => {
          try {
            subscriber.next(JSON.parse(message.body) as LobbyChatMessageDto);
          } catch (e) {
            subscriber.error(e);
          }
        },
        error: (err) => subscriber.error(err),
      });
      return () => sub.unsubscribe();
    });
  }

  send(lobbyCode: string, text: string): Observable<LobbyChatMessageDto> {
    const code = (lobbyCode ?? '').trim().toUpperCase();
    return this.http.post<LobbyChatMessageDto>(
      `/api/lobbies/${encodeURIComponent(code)}/chat`,
      { text },
      { withCredentials: true }
    );
  }
}
