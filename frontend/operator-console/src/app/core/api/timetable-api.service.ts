import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  TimetableView,
  CreateTimetableRequest,
  UpdateTimetableRequest,
  EmergencyActivateRequest,
  RejectRequest,
  RequestChangesRequest,
} from './api.types';

@Injectable({ providedIn: 'root' })
export class TimetableApiService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/v1/timetables`;

  listByLine(lineId: string, status?: string): Observable<TimetableView[]> {
    const params: Record<string, string> = {};
    if (status) params['status'] = status;
    return this.http.get<TimetableView[]>(
      `${environment.apiBaseUrl}/api/v1/lines/${lineId}/timetables`,
      { params }
    );
  }

  getById(id: string): Observable<TimetableView> {
    return this.http.get<TimetableView>(`${this.base}/${id}`);
  }

  create(req: CreateTimetableRequest): Observable<{ id: string }> {
    return this.http.post<{ id: string }>(this.base, req);
  }

  update(id: string, req: UpdateTimetableRequest): Observable<void> {
    return this.http.patch<void>(`${this.base}/${id}`, req);
  }

  submit(id: string): Observable<void> {
    return this.http.post<void>(`${this.base}/${id}/submit`, {});
  }

  approve(id: string): Observable<void> {
    return this.http.post<void>(`${this.base}/${id}/approve`, {});
  }

  reject(id: string, req: RejectRequest): Observable<void> {
    return this.http.post<void>(`${this.base}/${id}/reject`, req);
  }

  requestChanges(id: string, req: RequestChangesRequest): Observable<void> {
    return this.http.post<void>(`${this.base}/${id}/request-changes`, req);
  }

  cancel(id: string): Observable<void> {
    return this.http.post<void>(`${this.base}/${id}/cancel`, {});
  }

  emergencyActivate(id: string, req: EmergencyActivateRequest): Observable<void> {
    return this.http.post<void>(`${this.base}/${id}/emergency-activate`, req);
  }
}
