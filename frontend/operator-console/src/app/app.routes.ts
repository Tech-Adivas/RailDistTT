import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: '',
    redirectTo: 'timetables',
    pathMatch: 'full',
  },
  {
    path: 'timetables',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/timetables/timetable-list/timetable-list.component').then(
        m => m.TimetableListComponent
      ),
  },
  {
    path: 'timetables/new',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/timetables/timetable-form/timetable-form.component').then(
        m => m.TimetableFormComponent
      ),
  },
  {
    path: 'timetables/:id',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/timetables/timetable-detail/timetable-detail.component').then(
        m => m.TimetableDetailComponent
      ),
  },
  {
    path: 'timetables/:id/edit',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/timetables/timetable-form/timetable-form.component').then(
        m => m.TimetableFormComponent
      ),
  },
  {
    path: 'schedules',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/schedule/schedule-view.component').then(
        m => m.ScheduleViewComponent
      ),
  },
  {
    path: '**',
    redirectTo: 'timetables',
  },
];
