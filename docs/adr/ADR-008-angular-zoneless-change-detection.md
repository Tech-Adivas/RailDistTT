# ADR-008: Angular Zoneless Change Detection for Operator Console

## Status
Accepted

## Context
The operator console is an Angular 21 single-page application that serves as the primary interface
for railway operations staff. Its most demanding feature is the live timetable board: a STOMP
WebSocket subscription receives schedule-update events from the distribution-service at rates that
can reach several messages per second during peak disruption periods. Each incoming message must
update the visible timetable grid with minimal perceptible latency.

Angular's default change detection model relies on Zone.js, a library that monkey-patches every
browser async API (setTimeout, Promise, XMLHttpRequest, addEventListener, etc.) and notifies Angular
after each task completes so Angular can run change detection across the entire component tree. For
applications with infrequent events this is transparent, but for a high-frequency WebSocket stream
it means every incoming message triggers a full application-wide change detection cycle — even for
components that have nothing to do with the updated data. The Zone.js bundle itself adds roughly
10 KB (gzip) to the initial load.

Angular 19 introduced `provideZonelessChangeDetection()` as an experimental API. Angular 21 promoted
it to stable. The Angular team has explicitly stated that zoneless is the long-term direction of the
framework and that Zone.js-based change detection is in maintenance mode. The operator console is
greenfield code and is therefore the correct place to adopt the stable zoneless API rather than
inheriting Zone.js patterns that will need to be migrated later.

The operator console uses NgRx Signals Store (introduced in NgRx 18, stable in NgRx 19+) for all
shared state. The Signals Store is natively signal-based and requires no Zone.js integration: store
slices are Angular signals, and components that read them are automatically scheduled for re-render
when signal values change — independently of any Zone.js task boundary.

## Decision
`provideZonelessChangeDetection()` is configured in `app.config.ts` as the application-wide change
detection strategy. Zone.js is removed from `angular.json` polyfills entirely. All shared UI state
is held in NgRx Signals Store slices (typed signal collections). Components read store signals
directly via store injection — no `AsyncPipe`, no manual `ChangeDetectorRef.markForCheck()` calls,
and no RxJS `subscribe()` calls that write to component properties outside a signal setter.

STOMP WebSocket messages arrive in the `TimetableWebSocketService`, which calls
`patchState(store, ...)` on the relevant Signals Store slice inside the message handler. Because
`patchState` updates a signal, Angular's scheduler is notified of exactly the state that changed,
and only components that read that signal are re-evaluated. Components that display unrelated data
are not touched.

All components are authored as standalone components (no NgModules) and use the `input()`,
`output()`, and `model()` signal-based component APIs introduced in Angular 17+ to ensure full
signal lineage from store to template.

## Consequences

### Positive
- Zone.js is eliminated from the bundle, saving approximately 10 KB (gzip) on initial load.
- Change detection runs only when a signal's value actually changes, not on every browser async
  task. High-frequency STOMP messages update only the specific signal slice they affect; components
  displaying other data are never scheduled for re-render.
- The reactivity model is uniform: everything flows through signals, making the data-flow graph
  explicit and traceable in DevTools.
- Aligns with Angular's published long-term roadmap; the codebase will not require a future
  zoneless migration.
- Eliminates a class of subtle bugs caused by Zone.js patching race conditions with third-party
  libraries.

### Negative / Trade-offs
- Third-party Angular libraries that internally rely on Zone.js patching for their own change
  detection notifications may not work correctly. Every third-party dependency must be audited
  before inclusion.
- Patterns common in Zone.js-era Angular code (e.g., writing to a component property inside a
  `setTimeout` callback and expecting the view to update automatically) will silently produce no
  update. Developers unfamiliar with signal reactivity can produce hard-to-diagnose UI staleness
  bugs.
- The team requires training on the signal reactivity model, the NgRx Signals Store API, and the
  signal-based component input/output APIs. Developers experienced only with `@Input()`/`@Output()`
  and `AsyncPipe` will need a learning period.
- Angular DevTools support for zoneless debugging was still maturing as of Angular 21; some
  profiling views may be less informative than in Zone.js mode.

## Alternatives Considered

| Alternative | Reason Rejected |
|---|---|
| Zone.js with `OnPush` on all components | Reduces unnecessary CD cycles but does not eliminate them; Zone.js overhead remains; still triggers CD on every async task boundary even with OnPush; requires manual `markForCheck()` in many places |
| Zone.js with RxJS `AsyncPipe` throughout | Mixes two reactivity models (RxJS streams and Angular signals); `AsyncPipe` subscribes at the template level which works with Zone.js but is awkward and verbose with zoneless; explicitly rejected as the NgRx Signals team recommends against mixing |
| React (instead of Angular) | Out of scope — the operator console tech stack was fixed before Phase 8; not an Angular architectural decision |
| NgRx Component Store (non-signal) | Older API; does not integrate cleanly with zoneless; superseded by NgRx Signals Store for new code |
