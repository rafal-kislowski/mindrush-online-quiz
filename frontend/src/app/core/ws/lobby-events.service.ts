import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { LobbyDto } from '../models/lobby.models';
import { StompClientService } from './stomp-client.service';

export interface LobbyEventDto {
  type: 'LOBBY_UPDATED' | 'LOBBY_SNAPSHOT' | 'LOBBY_KICKED' | 'LOBBY_BANNED';
  lobbyCode: string;
  serverTime: string;
  state?: LobbyDto | null;
}

@Injectable({ providedIn: 'root' })
export class LobbyEventsService {
  constructor(private readonly stompClient: StompClientService) {}

  subscribeLobby(lobbyCode: string): Observable<LobbyEventDto> {
    return new Observable<LobbyEventDto>(subscriber => {
      const parseAndEmit = (rawBody: string) => {
        try {
          subscriber.next(JSON.parse(rawBody) as LobbyEventDto);
        } catch (e) {
          subscriber.error(e);
        }
      };

      const topicDestination = `/topic/lobbies/${lobbyCode}/lobby`;
      const userDestination = `/user/queue/lobbies/${lobbyCode}/lobby`;

      const topicSub = this.stompClient.subscribe(topicDestination).subscribe({
        next: message => parseAndEmit(message.body),
        error: err => subscriber.error(err)
      });

      const userSub = this.stompClient.subscribe(userDestination).subscribe({
        next: message => parseAndEmit(message.body),
        error: err => subscriber.error(err)
      });

      return () => {
        topicSub.unsubscribe();
        userSub.unsubscribe();
      };
    });
  }

  subscribeLobbyUserQueue(lobbyCode: string): Observable<LobbyEventDto> {
    return new Observable<LobbyEventDto>(subscriber => {
      const parseAndEmit = (rawBody: string) => {
        try {
          subscriber.next(JSON.parse(rawBody) as LobbyEventDto);
        } catch (e) {
          subscriber.error(e);
        }
      };

      const userDestination = `/user/queue/lobbies/${lobbyCode}/lobby`;
      const userSub = this.stompClient.subscribe(userDestination).subscribe({
        next: message => parseAndEmit(message.body),
        error: err => subscriber.error(err)
      });

      return () => {
        userSub.unsubscribe();
      };
    });
  }
}
