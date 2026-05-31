import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ScheduleView, TimetableView } from './api.types';

@Injectable({ providedIn: 'root' })
export class QueryApiService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/v1`;

  getTimetable(id: string): Observable<TimetableView> {
    return this.http.get<TimetableView>(`${this.base}/timetables/${id}`);
  }

  getSchedule(timetableId: string): Observable<ScheduleView> {
    return this.http.get<ScheduleView>(`${this.base}/timetables/${timetableId}/schedule`);
  }

  getActiveSchedules(lineId: string, date?: string): Observable<ScheduleView[]> {
    const params: Record<string, string> = { lineId };
    if (date) params['date'] = date;
    return this.http.get<ScheduleView[]>(`${this.base}/schedules/active`, { params });
  }
}
