import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { TimetableStore } from './timetable.store';
import { TimetableApiService } from '../../core/api/timetable-api.service';
import { TimetableView } from '../../core/api/api.types';

const MOCK_TIMETABLE: TimetableView = {
  id: 'tt-001',
  lineId: 'GWR-PAD-BRI',
  name: 'Winter 2025',
  description: 'Winter service timetable',
  status: 'DRAFT',
  effectiveDate: '2025-12-01',
  expiryDate: null,
  authorId: 'user-1',
  reviewerId: null,
  version: 1,
};

describe('TimetableStore', () => {

  describe('loadByLine should populate timetables on success', () => {
    it('sets timetables array from API response', async () => {
      const apiSpy = jasmine.createSpyObj<TimetableApiService>(
        'TimetableApiService',
        ['listByLine']
      );
      apiSpy.listByLine.and.returnValue(of([MOCK_TIMETABLE]));

      TestBed.configureTestingModule({
        providers: [
          TimetableStore,
          { provide: TimetableApiService, useValue: apiSpy },
        ],
      });

      const store = TestBed.inject(TimetableStore);
      await store.loadByLine('GWR-PAD-BRI');

      expect(store.timetables().length).toBe(1);
      expect(store.timetables()[0].id).toBe('tt-001');
      expect(store.loading()).toBe(false);
      expect(store.error()).toBeNull();
      expect(apiSpy.listByLine).toHaveBeenCalledWith('GWR-PAD-BRI', undefined);
    });
  });

  describe('submit should optimistically update status to PENDING_REVIEW', () => {
    it('updates the timetable status in state without reloading from API', async () => {
      const listSpy = jasmine.createSpyObj<TimetableApiService>(
        'TimetableApiService',
        ['listByLine', 'submit']
      );
      listSpy.listByLine.and.returnValue(of([MOCK_TIMETABLE]));
      listSpy.submit.and.returnValue(of(undefined));

      TestBed.configureTestingModule({
        providers: [
          TimetableStore,
          { provide: TimetableApiService, useValue: listSpy },
        ],
      });

      const store = TestBed.inject(TimetableStore);
      // Populate the store first
      await store.loadByLine('GWR-PAD-BRI');
      expect(store.timetables()[0].status).toBe('DRAFT');

      // Submit the timetable
      const result = await store.submit('tt-001');

      expect(result).toBe(true);
      expect(store.timetables()[0].status).toBe('PENDING_REVIEW');
      expect(store.submitting()).toBe(false);
      expect(listSpy.submit).toHaveBeenCalledWith('tt-001');
    });
  });

  describe('create should return null and set error on failure', () => {
    it('returns null and populates error signal when API throws', async () => {
      const apiSpy = jasmine.createSpyObj<TimetableApiService>(
        'TimetableApiService',
        ['create']
      );
      apiSpy.create.and.returnValue(throwError(() => new Error('HTTP 500')));

      TestBed.configureTestingModule({
        providers: [
          TimetableStore,
          { provide: TimetableApiService, useValue: apiSpy },
        ],
      });

      const store = TestBed.inject(TimetableStore);
      const id = await store.create({
        lineId: 'GWR-PAD-BRI',
        name: 'Fail Test',
        description: 'Should fail',
        effectiveDate: '2025-12-01',
      });

      expect(id).toBeNull();
      expect(store.error()).toBe('Failed to create timetable.');
      expect(store.submitting()).toBe(false);
    });
  });

});
