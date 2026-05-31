import { Component, inject } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from './core/auth/auth.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="layout">
      <aside class="sidebar">
        <div class="sidebar-header">
          <h1 class="sidebar-title">Railway<br><span>Platform</span></h1>
        </div>
        <nav class="sidebar-nav">
          <a routerLink="/timetables" routerLinkActive="active" class="sidebar-link">
            <span class="sidebar-icon">📋</span> Timetables
          </a>
          <a routerLink="/schedules" routerLinkActive="active" class="sidebar-link">
            <span class="sidebar-icon">📅</span> Schedules
          </a>
        </nav>
        <div class="sidebar-footer">
          <span class="text-sm text-muted">{{ authService.currentUser() }}</span>
          <button class="btn btn-sm btn-secondary" (click)="authService.logout()">
            Sign out
          </button>
        </div>
      </aside>
      <main class="main-content">
        <router-outlet />
      </main>
    </div>
  `,
})
export class AppComponent {
  readonly authService = inject(AuthService);
}
