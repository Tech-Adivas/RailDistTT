import { Component, Input, OnInit, OnDestroy, inject, signal, computed } from '@angular/core';
import { Subscription } from 'rxjs';
import { PredictionApiService } from '../../../core/api/prediction-api.service';
import { StompService } from '../../../core/websocket/stomp.service';
import { PredictionMessage } from '../../../core/api/api.types';

@Component({
  selector: 'app-prediction-panel',
  standalone: true,
  imports: [],
  templateUrl: './prediction-panel.component.html',
})
export class PredictionPanelComponent implements OnInit, OnDestroy {
  private readonly predictionApi = inject(PredictionApiService);
  private readonly stomp = inject(StompService);

  @Input({ required: true }) routeId!: string;

  readonly latestPrediction = signal<PredictionMessage | null>(null);
  readonly loading = signal<boolean>(false);
  readonly error = signal<string | null>(null);

  // Colour indicator based on predictedDelayMinutes
  readonly delayColour = computed(() => {
    const p = this.latestPrediction();
    if (!p || p.confidenceScore === 0) return 'neutral';
    if (p.predictedDelayMinutes < 5) return 'green';
    if (p.predictedDelayMinutes <= 15) return 'yellow';
    return 'red';
  });

  readonly delayLabel = computed(() => {
    const p = this.latestPrediction();
    if (!p) return '—';
    if (p.confidenceScore === 0) return 'Prediction unavailable';
    return `${p.predictedDelayMinutes} min`;
  });

  private stompSub?: Subscription;

  ngOnInit(): void {
    this.loadPrediction();
    this.subscribeToLivePredictions();
  }

  private loadPrediction(): void {
    this.loading.set(true);
    this.predictionApi.getPredictions(this.routeId).subscribe({
      next: (res) => {
        this.latestPrediction.set(res.predictions[0] ?? null);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Failed to load prediction');
        this.loading.set(false);
      }
    });
  }

  private subscribeToLivePredictions(): void {
    const topic = `/topic/lines/${this.routeId}/predictions`;
    this.stompSub = (this.stomp.subscribe(topic) as any).subscribe({
      next: (msg: any) => this.latestPrediction.set(msg as PredictionMessage),
      error: () => {} // silent — STOMP errors handled by StompService
    });
  }

  ngOnDestroy(): void {
    this.stompSub?.unsubscribe();
  }
}
