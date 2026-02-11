import { Injectable } from '@angular/core';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import { BehaviorSubject, Observable, filter, firstValueFrom, take } from 'rxjs';
import { SessionService } from '../session/session.service';

export type StompConnectionState = 'disconnected' | 'connecting' | 'connected';

@Injectable({ providedIn: 'root' })
export class StompClientService {
  private client: Client | null = null;
  private connected = false;
  private connecting: Promise<void> | null = null;
  private readonly stateSubject = new BehaviorSubject<StompConnectionState>('disconnected');
  readonly state$ = this.stateSubject.asObservable();
  private readonly onConnectListeners = new Set<() => void>();

  constructor(private readonly sessionService: SessionService) {}

  async connect(): Promise<void> {
    if (this.connected) return;
    if (this.connecting) return this.connecting;
    this.stateSubject.next('connecting');
    this.connecting = (this.client ? this.waitForConnect() : this.connectInternal()).finally(() => {
      this.connecting = null;
    });
    return this.connecting;
  }

  subscribe(destination: string): Observable<IMessage> {
    return new Observable<IMessage>(subscriber => {
      let subscription: StompSubscription | null = null;

      const resubscribe = () => {
        if (!this.client || !this.connected) return;
        subscription?.unsubscribe();
        subscription = this.client.subscribe(destination, message => subscriber.next(message));
      };

      this.onConnectListeners.add(resubscribe);

      if (this.connected) resubscribe();
      void this.connect().catch(() => {
        // ignore initial connect errors; @stomp/stompjs will keep retrying (reconnectDelay)
      });

      return () => {
        this.onConnectListeners.delete(resubscribe);
        subscription?.unsubscribe();
      };
    });
  }

  publish(destination: string, body: unknown): void {
    const isString = typeof body === 'string';
    const payload = isString ? (body as string) : JSON.stringify(body ?? {});
    const headers = isString ? undefined : { 'content-type': 'application/json' };
    const doPublish = () => {
      if (!this.client || !this.connected) return;
      try {
        this.client.publish({ destination, body: payload, headers });
      } catch {
        // ignore
      }
    };

    if (this.connected) {
      doPublish();
      return;
    }
    void this.connect().then(doPublish).catch(() => {
      // ignore
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
    this.connected = false;

    return new Promise<void>((resolve, reject) => {
      let settled = false;
      const fail = (err: unknown) => {
        this.connected = false;
        this.stateSubject.next('disconnected');
        if (!settled) {
          settled = true;
          const e =
            err instanceof Error
              ? err
              : new Error(String(err ?? 'STOMP connection failed'));
          reject(e);
        }
      };

      client.onConnect = () => {
        this.connected = true;
        this.stateSubject.next('connected');
        for (const listener of Array.from(this.onConnectListeners)) {
          try {
            listener();
          } catch {
            // ignore
          }
        }
        settled = true;
        resolve();
      };
      client.onStompError = frame => fail(new Error(frame.body || 'STOMP error'));
      client.onWebSocketError = () => fail(new Error('WebSocket error'));
      client.onWebSocketClose = () => {
        const wasConnected = this.connected;
        this.connected = false;
        this.stateSubject.next('disconnected');
        if (!wasConnected) fail(new Error('WebSocket closed'));
      };
      client.onDisconnect = () => {
        const wasConnected = this.connected;
        this.connected = false;
        this.stateSubject.next('disconnected');
        if (!wasConnected) fail(new Error('STOMP disconnected'));
      };
      client.activate();
    });
  }

  private async waitForConnect(): Promise<void> {
    if (this.client && !this.client.active) this.client.activate();
    if (this.connected) return;
    await firstValueFrom(this.state$.pipe(
      filter(s => s === 'connected'),
      take(1)
    ));
  }

  private buildWsUrl(): string {
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    return `${proto}://${window.location.host}/ws`;
  }
}
