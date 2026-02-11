import { Injectable } from '@angular/core';
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
  constructor(private readonly stompClient: StompClientService) {}

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

  send(lobbyCode: string, text: string): void {
    this.stompClient.publish(`/app/lobbies/${lobbyCode}/chat`, { text });
  }
}

