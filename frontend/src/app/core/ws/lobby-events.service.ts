import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { StompClientService } from './stomp-client.service';

export interface LobbyEventDto {
  type: 'LOBBY_UPDATED';
  lobbyCode: string;
  serverTime: string;
}

@Injectable({ providedIn: 'root' })
export class LobbyEventsService {
  constructor(private readonly stompClient: StompClientService) {}

  subscribeLobby(lobbyCode: string): Observable<LobbyEventDto> {
    return new Observable<LobbyEventDto>(subscriber => {
      const destination = `/topic/lobbies/${lobbyCode}/lobby`;
      const sub = this.stompClient.subscribe(destination).subscribe({
        next: message => {
          try {
            subscriber.next(JSON.parse(message.body) as LobbyEventDto);
          } catch (e) {
            subscriber.error(e);
          }
        },
        error: err => subscriber.error(err)
      });
      return () => sub.unsubscribe();
    });
  }
}

