# frontend/operator-console

## Purpose

Angular 21 single-page application for railway operators to author, review, and approve timetables, monitor live distribution status, and manage track maintenance windows. Uses NgRx Signal Store for state management, zoneless change detection, and WebSocket/STOMP for live updates.

## Architecture

```mermaid
flowchart LR
  subgraph Angular App
    AUTH[Auth Interceptor\nJWT + silent refresh]
    CORR[Correlation ID Interceptor]
    STORE[NgRx Signal Store]
    COMP[Standalone Components\nOptimistic UI]
    WS[WebSocket STOMP Client\nauto-reconnect]
  end
  AUTH --> GW[API Gateway]
  CORR --> GW
  STORE --> COMP
  WS -->|live schedule updates| STORE
  GW -->|REST| STORE

  subgraph Error Handling
    E401[401 → silent refresh → retry]
    E5XX[5xx → non-blocking banner\nkeep last good state]
    DISC[WS disconnect → reconnect\n+ refetch on resume]
  end
```

## Tech & Versions

| Component | Version |
|-----------|---------|
| Angular | 21.0.x |
| NgRx Signals Store | 19.x |
| TypeScript | 5.8.x |
| Node.js | 22 LTS |

## Run Locally

```bash
cd frontend/operator-console
npm install
npm start
# App available at http://localhost:4200
# Requires API Gateway running at http://localhost:8080
```
