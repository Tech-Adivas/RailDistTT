import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { PredictionMessage } from './api.types';

@Injectable({ providedIn: 'root' })
export class PredictionApiService {
  private readonly http = inject(HttpClient);

  getPredictions(routeId: string, trainId?: string): Observable<{ predictions: PredictionMessage[] }> {
    let params = new HttpParams().set('routeId', routeId);
    if (trainId) params = params.set('trainId', trainId);
    return this.http.get<{ predictions: PredictionMessage[] }>('/api/v1/predictions', { params });
  }
}
