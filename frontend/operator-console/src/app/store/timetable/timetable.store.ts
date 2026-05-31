import { computed, inject } from '@angular/core';
import { signalStore, withState, withComputed, withMethods, patchState } from '@ngrx/signals';
import { firstValueFrom } from 'rxjs';
import { TimetableApiService } from '../../core/api/timetable-api.service';
import {
  TimetableView,
  TimetableStatus,
  CreateTimetableRequest,
  UpdateTimetableRequest,
  RejectRequest,
  RequestChangesRequest,
  EmergencyActivateRequest,
} from '../../core/api/api.types';

interface TimetableState {
  timetables: TimetableView[];
  selected: TimetableView | null;
  loading: boolean;
  submitting: boolean;
  error: string | null;
  lineIdFilter: string;
  statusFilter: TimetableStatus | '';
}

const initialState: TimetableState = {
  timetables: [],
  selected: null,
  loading: false,
  submitting: false,
  error: null,
  lineIdFilter: '',
  statusFilter: '',
};

export const TimetableStore = signalStore(
  { providedIn: 'root' },
  withState<TimetableState>(initialState),
  withComputed(({ timetables }) => ({
    draftCount: computed(() => timetables().filter(t => t.status === 'DRAFT').length),
    pendingCount: computed(() => timetables().filter(t => t.status === 'PENDING_REVIEW').length),
    activeCount: computed(() =>
      timetables().filter(t => t.status === 'ACTIVE' || t.status === 'EMERGENCY_ACTIVE').length
    ),
  })),
  withMethods((store, api = inject(TimetableApiService)) => ({
    async loadByLine(lineId: string, status?: TimetableStatus): Promise<void> {
      patchState(store, { loading: true, error: null, lineIdFilter: lineId });
      try {
        const timetables = await firstValueFrom(api.listByLine(lineId, status));
        patchState(store, { timetables, loading: false });
      } catch {
        patchState(store, { loading: false, error: 'Failed to load timetables.' });
      }
    },

    async loadById(id: string): Promise<void> {
      patchState(store, { loading: true, error: null });
      try {
        const selected = await firstValueFrom(api.getById(id));
        patchState(store, { selected, loading: false });
      } catch {
        patchState(store, { loading: false, error: 'Timetable not found.' });
      }
    },

    async create(req: CreateTimetableRequest): Promise<string | null> {
      patchState(store, { submitting: true, error: null });
      try {
        const { id } = await firstValueFrom(api.create(req));
        patchState(store, { submitting: false });
        return id;
      } catch {
        patchState(store, { submitting: false, error: 'Failed to create timetable.' });
        return null;
      }
    },

    async update(id: string, req: UpdateTimetableRequest): Promise<boolean> {
      patchState(store, { submitting: true, error: null });
      try {
        await firstValueFrom(api.update(id, req));
        patchState(store, { submitting: false });
        return true;
      } catch {
        patchState(store, { submitting: false, error: 'Failed to update timetable.' });
        return false;
      }
    },

    async submit(id: string): Promise<boolean> {
      patchState(store, { submitting: true, error: null });
      try {
        await firstValueFrom(api.submit(id));
        // Optimistic update: reflect status change locally
        const timetables = store.timetables().map(t =>
          t.id === id ? { ...t, status: 'PENDING_REVIEW' as TimetableStatus } : t
        );
        const selected =
          store.selected()?.id === id
            ? { ...store.selected()!, status: 'PENDING_REVIEW' as TimetableStatus }
            : store.selected();
        patchState(store, { submitting: false, timetables, selected });
        return true;
      } catch {
        patchState(store, { submitting: false, error: 'Failed to submit timetable.' });
        return false;
      }
    },

    async approve(id: string): Promise<boolean> {
      patchState(store, { submitting: true, error: null });
      try {
        await firstValueFrom(api.approve(id));
        patchState(store, {
          submitting: false,
          timetables: store.timetables().map(t =>
            t.id === id ? { ...t, status: 'APPROVED' as TimetableStatus } : t
          ),
        });
        return true;
      } catch {
        patchState(store, { submitting: false, error: 'Failed to approve timetable.' });
        return false;
      }
    },

    async reject(id: string, req: RejectRequest): Promise<boolean> {
      patchState(store, { submitting: true, error: null });
      try {
        await firstValueFrom(api.reject(id, req));
        patchState(store, {
          submitting: false,
          timetables: store.timetables().map(t =>
            t.id === id ? { ...t, status: 'REJECTED' as TimetableStatus } : t
          ),
        });
        return true;
      } catch {
        patchState(store, { submitting: false, error: 'Failed to reject timetable.' });
        return false;
      }
    },

    async requestChanges(id: string, req: RequestChangesRequest): Promise<boolean> {
      patchState(store, { submitting: true, error: null });
      try {
        await firstValueFrom(api.requestChanges(id, req));
        patchState(store, {
          submitting: false,
          timetables: store.timetables().map(t =>
            t.id === id ? { ...t, status: 'DRAFT' as TimetableStatus } : t
          ),
        });
        return true;
      } catch {
        patchState(store, { submitting: false, error: 'Failed to request changes.' });
        return false;
      }
    },

    async cancel(id: string): Promise<boolean> {
      patchState(store, { submitting: true, error: null });
      try {
        await firstValueFrom(api.cancel(id));
        patchState(store, {
          submitting: false,
          timetables: store.timetables().map(t =>
            t.id === id ? { ...t, status: 'CANCELLED' as TimetableStatus } : t
          ),
        });
        return true;
      } catch {
        patchState(store, { submitting: false, error: 'Failed to cancel timetable.' });
        return false;
      }
    },

    async emergencyActivate(id: string, req: EmergencyActivateRequest): Promise<boolean> {
      patchState(store, { submitting: true, error: null });
      try {
        await firstValueFrom(api.emergencyActivate(id, req));
        patchState(store, {
          submitting: false,
          timetables: store.timetables().map(t =>
            t.id === id ? { ...t, status: 'EMERGENCY_ACTIVE' as TimetableStatus } : t
          ),
        });
        return true;
      } catch {
        patchState(store, { submitting: false, error: 'Emergency activation failed.' });
        return false;
      }
    },

    clearError(): void {
      patchState(store, { error: null });
    },
  }))
);
