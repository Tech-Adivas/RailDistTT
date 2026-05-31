import { inject } from '@angular/core';
import { signalStore, withState, withMethods, patchState } from '@ngrx/signals';
import { firstValueFrom } from 'rxjs';
import { QueryApiService } from '../../core/api/query-api.service';
import { ScheduleView, ScheduleUpdateMessage } from '../../core/api/api.types';

interface ScheduleState {
  schedules: ScheduleView[];
  selected: ScheduleView | null;
  loading: boolean;
  error: string | null;
  liveUpdates: ScheduleUpdateMessage[];
}

const initialState: ScheduleState = {
  schedules: [],
  selected: null,
  loading: false,
  error: null,
  liveUpdates: [],
};

export const ScheduleStore = signalStore(
  { providedIn: 'root' },
  withState<ScheduleState>(initialState),
  withMethods((store, api = inject(QueryApiService)) => ({
    async loadActive(lineId: string, date?: string): Promise<void> {
      patchState(store, { loading: true, error: null });
      try {
        const schedules = await firstValueFrom(api.getActiveSchedules(lineId, date));
        patchState(store, { schedules, loading: false });
      } catch {
        patchState(store, { loading: false, error: 'Failed to load active schedules.' });
      }
    },

    async loadForTimetable(timetableId: string): Promise<void> {
      patchState(store, { loading: true, error: null });
      try {
        const selected = await firstValueFrom(api.getSchedule(timetableId));
        patchState(store, { selected, loading: false });
      } catch {
        patchState(store, { loading: false, error: 'Failed to load schedule.' });
      }
    },

    addLiveUpdate(msg: ScheduleUpdateMessage): void {
      // Prepend so newest is first; keep last 50 updates to avoid memory growth
      const liveUpdates = [msg, ...store.liveUpdates()].slice(0, 50);
      patchState(store, { liveUpdates });
    },

    clearError(): void {
      patchState(store, { error: null });
    },
  }))
);
