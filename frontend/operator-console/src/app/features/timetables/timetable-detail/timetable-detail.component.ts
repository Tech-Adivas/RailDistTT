import { Component, OnInit, OnDestroy, inject, signal } from '@angular/core';
import { Router, ActivatedRoute } from '@angular/router';
import { Subscription } from 'rxjs';
import { TimetableStore } from '../../../store/timetable/timetable.store';
import { AuthService } from '../../../core/auth/auth.service';
import { StompService } from '../../../core/websocket/stomp.service';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';
import { ErrorBannerComponent } from '../../../shared/components/error-banner/error-banner.component';

@Component({
  selector: 'app-timetable-detail',
  standalone: true,
  imports: [StatusBadgeComponent, ErrorBannerComponent],
  templateUrl: './timetable-detail.component.html',
})
export class TimetableDetailComponent implements OnInit, OnDestroy {
  readonly store = inject(TimetableStore);
  readonly router = inject(Router);
  readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly stomp = inject(StompService);

  /** STOMP connected state exposed to template */
  readonly stompConnected = this.stomp.connected;

  /** Inline form state for reject */
  readonly showRejectForm = signal<boolean>(false);
  readonly rejectReason = signal<string>('');

  /** Inline form state for request-changes */
  readonly showRequestChangesForm = signal<boolean>(false);
  readonly requestChangesFeedback = signal<string>('');

  /** Inline form state for emergency activation */
  readonly showEmergencyForm = signal<boolean>(false);
  readonly emergencyJustification = signal<string>('');

  /** Track live update count for the indicator */
  readonly liveUpdateCount = signal<number>(0);

  private stompSub?: Subscription;

  ngOnInit(): void {
    const id = this.route.snapshot.params['id'] as string;
    if (id) {
      this.store.loadById(id);
    }

    // Connect STOMP once the timetable loads and subscribe to its line topic
    // We defer by waiting for selected to be populated
    const checkAndSubscribe = setInterval(() => {
      const selected = this.store.selected();
      if (selected) {
        clearInterval(checkAndSubscribe);
        this.connectStomp(selected.lineId);
      }
    }, 200);
  }

  private connectStomp(lineId: string): void {
    try {
      this.stomp.connect();
      const topic = `/topic/lines/${lineId}/schedule`;
      this.stompSub = this.stomp.subscribe(topic).subscribe({
        next: (_msg) => {
          this.liveUpdateCount.update(n => n + 1);
          // Refresh timetable data to reflect any status changes from schedule push
          const id = this.route.snapshot.params['id'] as string;
          if (id) {
            this.store.loadById(id);
          }
        },
        error: (err) => {
          console.warn('[TimetableDetail] STOMP subscribe error', err);
        },
      });
    } catch (e) {
      console.warn('[TimetableDetail] STOMP connect failed', e);
    }
  }

  async onSubmit(): Promise<void> {
    const id = this.store.selected()?.id;
    if (id) await this.store.submit(id);
  }

  async onApprove(): Promise<void> {
    const id = this.store.selected()?.id;
    if (id) await this.store.approve(id);
  }

  async onReject(): Promise<void> {
    const id = this.store.selected()?.id;
    const reason = this.rejectReason().trim();
    if (id && reason) {
      const ok = await this.store.reject(id, { reason });
      if (ok) {
        this.showRejectForm.set(false);
        this.rejectReason.set('');
      }
    }
  }

  async onRequestChanges(): Promise<void> {
    const id = this.store.selected()?.id;
    const feedback = this.requestChangesFeedback().trim();
    if (id && feedback) {
      const ok = await this.store.requestChanges(id, { feedback });
      if (ok) {
        this.showRequestChangesForm.set(false);
        this.requestChangesFeedback.set('');
      }
    }
  }

  async onCancel(): Promise<void> {
    const id = this.store.selected()?.id;
    if (id && confirm('Are you sure you want to cancel this timetable?')) {
      const ok = await this.store.cancel(id);
      if (ok) this.router.navigate(['/timetables']);
    }
  }

  async onEmergencyActivate(): Promise<void> {
    const id = this.store.selected()?.id;
    const justification = this.emergencyJustification().trim();
    if (id && justification) {
      const ok = await this.store.emergencyActivate(id, { justification });
      if (ok) {
        this.showEmergencyForm.set(false);
        this.emergencyJustification.set('');
      }
    }
  }

  ngOnDestroy(): void {
    this.stompSub?.unsubscribe();
    this.stomp.disconnect();
  }
}
