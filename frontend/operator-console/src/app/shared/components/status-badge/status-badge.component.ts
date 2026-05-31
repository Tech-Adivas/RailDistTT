import { Component, input, computed } from '@angular/core';
import { TimetableStatus } from '../../../core/api/api.types';

@Component({
  selector: 'app-status-badge',
  standalone: true,
  template: `<span [class]="badgeClass()">{{ statusLabel() }}</span>`,
})
export class StatusBadgeComponent {
  readonly status = input.required<TimetableStatus>();

  readonly badgeClass = computed(() =>
    `status-badge status-badge--${this.status().toLowerCase().replace(/_/g, '-')}`
  );

  readonly statusLabel = computed(() => this.status().replace(/_/g, ' '));
}
