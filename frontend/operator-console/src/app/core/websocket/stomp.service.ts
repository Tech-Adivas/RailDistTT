import { Injectable, inject, signal, OnDestroy } from '@angular/core';
import { Client, IMessage, StompHeaders } from '@stomp/stompjs';
import { Observable, Subject } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthService } from '../auth/auth.service';
import { ScheduleUpdateMessage } from '../api/api.types';

interface InternalMessage {
  topic: string;
  payload: ScheduleUpdateMessage;
}

@Injectable({ providedIn: 'root' })
export class StompService implements OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly messageSubject = new Subject<InternalMessage>();
  private client!: Client;

  readonly connected = signal<boolean>(false);

  connect(): void {
    const token = this.auth.getToken();
    const headers: StompHeaders = token ? { Authorization: `Bearer ${token}` } : {};

    this.client = new Client({
      brokerURL: environment.wsUrl,
      connectHeaders: headers,
      reconnectDelay: 5_000,
      onConnect: () => {
        this.connected.set(true);
        console.info('[STOMP] Connected to', environment.wsUrl);
      },
      onDisconnect: () => {
        this.connected.set(false);
        console.warn('[STOMP] Disconnected — will reconnect in 5s');
      },
      onStompError: (frame) => {
        console.error('[STOMP] Error', frame.headers['message']);
      },
    });

    this.client.activate();
  }

  subscribe(topic: string): Observable<ScheduleUpdateMessage> {
    return new Observable(observer => {
      if (!this.client?.connected) {
        observer.error(new Error('STOMP client not connected'));
        return;
      }
      const sub = this.client.subscribe(topic, (msg: IMessage) => {
        try {
          const payload = JSON.parse(msg.body) as ScheduleUpdateMessage;
          this.messageSubject.next({ topic, payload });
          observer.next(payload);
        } catch (e) {
          console.error('[STOMP] Failed to parse message', e);
        }
      });
      return () => sub.unsubscribe();
    });
  }

  disconnect(): void {
    this.client?.deactivate();
    this.connected.set(false);
  }

  ngOnDestroy(): void {
    this.client?.deactivate();
  }
}
