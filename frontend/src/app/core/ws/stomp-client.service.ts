import { Injectable } from '@angular/core';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import { firstValueFrom, Observable } from 'rxjs';
import { SessionService } from '../session/session.service';

@Injectable({ providedIn: 'root' })
export class StompClientService {
  private client: Client | null = null;
  private connected = false;
  private connecting: Promise<void> | null = null;

  constructor(private readonly sessionService: SessionService) {}

  async connect(): Promise<void> {
    if (this.connected) return;
    if (this.connecting) return this.connecting;
    this.connecting = this.connectInternal();
    return this.connecting;
  }

  subscribe(destination: string): Observable<IMessage> {
    return new Observable<IMessage>(subscriber => {
      let subscription: StompSubscription | null = null;

      this.connect()
        .then(() => {
          if (!this.client) throw new Error('STOMP client not initialized');
          subscription = this.client.subscribe(destination, message => subscriber.next(message));
        })
        .catch(err => subscriber.error(err));

      return () => subscription?.unsubscribe();
    });
  }

  private async connectInternal(): Promise<void> {
    await firstValueFrom(this.sessionService.ensure());

    const wsUrl = this.buildWsUrl();
    const client = new Client({
      brokerURL: wsUrl,
      reconnectDelay: 2000,
      heartbeatIncoming: 0,
      heartbeatOutgoing: 0
    });
    this.client = client;

    return new Promise<void>((resolve, reject) => {
      client.onConnect = () => {
        this.connected = true;
        resolve();
      };
      client.onStompError = frame => reject(new Error(frame.body || 'STOMP error'));
      client.activate();
    });
  }

  private buildWsUrl(): string {
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    return `${proto}://${window.location.host}/ws`;
  }
}

