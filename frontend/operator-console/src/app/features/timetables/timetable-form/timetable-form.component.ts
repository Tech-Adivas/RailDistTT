import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { Router, ActivatedRoute } from '@angular/router';
import { TimetableStore } from '../../../store/timetable/timetable.store';
import { ErrorBannerComponent } from '../../../shared/components/error-banner/error-banner.component';

@Component({
  selector: 'app-timetable-form',
  standalone: true,
  imports: [ErrorBannerComponent],
  templateUrl: './timetable-form.component.html',
})
export class TimetableFormComponent implements OnInit {
  readonly store = inject(TimetableStore);
  readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  /** Populated when editing an existing timetable */
  readonly editId = signal<string | null>(null);
  readonly isEditMode = computed(() => this.editId() !== null);

  /** Form field signals */
  readonly name = signal<string>('');
  readonly lineId = signal<string>('');
  readonly description = signal<string>('');
  readonly effectiveDate = signal<string>('');
  readonly expiryDate = signal<string>('');

  /** Validation error signals */
  readonly nameError = signal<string | null>(null);
  readonly lineIdError = signal<string | null>(null);
  readonly descriptionError = signal<string | null>(null);
  readonly effectiveDateError = signal<string | null>(null);

  ngOnInit(): void {
    const id = this.route.snapshot.params['id'] as string | undefined;
    if (id) {
      this.editId.set(id);
      // Load existing data to pre-populate the form
      this.store.loadById(id);
      // Once loaded, populate signals
      const pollInterval = setInterval(() => {
        const selected = this.store.selected();
        if (selected && selected.id === id) {
          clearInterval(pollInterval);
          this.name.set(selected.name);
          this.lineId.set(selected.lineId);
          this.description.set(selected.description);
          this.effectiveDate.set(selected.effectiveDate);
          this.expiryDate.set(selected.expiryDate ?? '');
        }
      }, 100);
    }
  }

  private validate(): boolean {
    let valid = true;

    if (!this.name().trim()) {
      this.nameError.set('Name is required.');
      valid = false;
    } else {
      this.nameError.set(null);
    }

    if (!this.lineId().trim()) {
      this.lineIdError.set('Line ID is required.');
      valid = false;
    } else {
      this.lineIdError.set(null);
    }

    if (!this.description().trim()) {
      this.descriptionError.set('Description is required.');
      valid = false;
    } else {
      this.descriptionError.set(null);
    }

    if (!this.effectiveDate()) {
      this.effectiveDateError.set('Effective date is required.');
      valid = false;
    } else {
      this.effectiveDateError.set(null);
    }

    return valid;
  }

  async onSubmit(): Promise<void> {
    if (!this.validate()) return;

    const id = this.editId();

    if (id) {
      // Edit mode — only send changed fields
      const ok = await this.store.update(id, {
        name: this.name().trim(),
        description: this.description().trim(),
        effectiveDate: this.effectiveDate(),
        expiryDate: this.expiryDate() || null,
      });
      if (ok) {
        this.router.navigate(['/timetables', id]);
      }
    } else {
      // Create mode
      const newId = await this.store.create({
        lineId: this.lineId().trim(),
        name: this.name().trim(),
        description: this.description().trim(),
        effectiveDate: this.effectiveDate(),
        expiryDate: this.expiryDate() || undefined,
      });
      if (newId) {
        this.router.navigate(['/timetables', newId]);
      }
    }
  }

  onCancel(): void {
    const id = this.editId();
    if (id) {
      this.router.navigate(['/timetables', id]);
    } else {
      this.router.navigate(['/timetables']);
    }
  }
}
