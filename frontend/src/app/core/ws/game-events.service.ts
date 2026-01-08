import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { StompClientService } from './stomp-client.service';

export interface GameEventDto {
  type: 'GAME_UPDATED';
  lobbyCode: string;
  serverTime: string;
}

@Injectable({ providedIn: 'root' })
export class GameEventsService {
  constructor(private readonly stompClient: StompClientService) {}

  subscribeLobbyGame(lobbyCode: string): Observable<GameEventDto> {
    return new Observable<GameEventDto>(subscriber => {
      const destination = `/topic/lobbies/${lobbyCode}/game`;
      const sub = this.stompClient.subscribe(destination).subscribe({
        next: message => {
          try {
            subscriber.next(JSON.parse(message.body) as GameEventDto);
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
