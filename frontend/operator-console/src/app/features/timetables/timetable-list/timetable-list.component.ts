import { Component, OnInit, inject, signal } from '@angular/core';
import { Router, ActivatedRoute } from '@angular/router';
import { TimetableStore } from '../../../store/timetable/timetable.store';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';
import { ErrorBannerComponent } from '../../../shared/components/error-banner/error-banner.component';

@Component({
  selector: 'app-timetable-list',
  standalone: true,
  imports: [StatusBadgeComponent, ErrorBannerComponent],
  templateUrl: './timetable-list.component.html',
})
export class TimetableListComponent implements OnInit {
  readonly store = inject(TimetableStore);
  readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly lineId = signal<string>('');

  ngOnInit(): void {
    const paramLineId = this.route.snapshot.queryParamMap.get('lineId');
    if (paramLineId) {
      this.lineId.set(paramLineId);
      this.store.loadByLine(paramLineId);
    }
  }

  search(): void {
    const id = this.lineId();
    if (id) {
      this.store.loadByLine(id);
    }
  }
}
