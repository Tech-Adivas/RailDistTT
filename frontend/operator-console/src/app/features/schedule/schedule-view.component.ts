import { Component, OnInit, OnDestroy, inject, signal } from '@angular/core';
import { Router, ActivatedRoute } from '@angular/router';
import { Subscription } from 'rxjs';
import { ScheduleStore } from '../../store/schedule/schedule.store';
import { StompService } from '../../core/websocket/stomp.service';
import { ErrorBannerComponent } from '../../shared/components/error-banner/error-banner.component';

@Component({
  selector: 'app-schedule-view',
  standalone: true,
  imports: [ErrorBannerComponent],
  templateUrl: './schedule-view.component.html',
})
export class ScheduleViewComponent implements OnInit, OnDestroy {
  readonly store = inject(ScheduleStore);
  readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly stomp = inject(StompService);

  readonly stompConnected = this.stomp.connected;

  /** Query param state */
  readonly lineId = signal<string>('');
  readonly date = signal<string>('');
  readonly timetableId = signal<string | null>(null);

  /** Live update flash indicator */
  readonly liveUpdateCount = signal<number>(0);

  private stompSub?: Subscription;

  ngOnInit(): void {
    const qp = this.route.snapshot.queryParamMap;
    const lineIdParam = qp.get('lineId');
    const dateParam = qp.get('date');
    const timetableIdParam = qp.get('timetableId');

    if (lineIdParam) this.lineId.set(lineIdParam);
    if (dateParam) this.date.set(dateParam);
    if (timetableIdParam) this.timetableId.set(timetableIdParam);

    if (timetableIdParam) {
      // Load schedule for a specific timetable
      this.store.loadForTimetable(timetableIdParam);
    } else if (lineIdParam) {
      // Load active schedules for a line
      this.store.loadActive(lineIdParam, dateParam ?? undefined);
      this.connectStomp(lineIdParam);
    }
  }

  private connectStomp(lineId: string): void {
    try {
      this.stomp.connect();
      const topic = `/topic/lines/${lineId}/schedule`;
      this.stompSub = this.stomp.subscribe(topic).subscribe({
        next: (msg) => {
          this.liveUpdateCount.update(n => n + 1);
          this.store.addLiveUpdate(msg);
          // Refresh active schedules on live update
          this.store.loadActive(lineId, this.date() || undefined);
        },
        error: (err) => {
          console.warn('[ScheduleView] STOMP subscribe error', err);
        },
      });
    } catch (e) {
      console.warn('[ScheduleView] STOMP connect failed', e);
    }
  }

  search(): void {
    const lId = this.lineId();
    if (lId) {
      this.store.loadActive(lId, this.date() || undefined);
      if (!this.stompConnected()) {
        this.connectStomp(lId);
      }
    }
  }

  ngOnDestroy(): void {
    this.stompSub?.unsubscribe();
    this.stomp.disconnect();
  }
}
