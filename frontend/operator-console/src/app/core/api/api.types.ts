// ── Timetable ────────────────────────────────────────────────────────────────

export type TimetableStatus =
  | 'DRAFT'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'REJECTED'
  | 'ACTIVE'
  | 'EMERGENCY_ACTIVE'
  | 'SUPERSEDED'
  | 'CANCELLED';

export interface TimetableView {
  id: string;
  lineId: string;
  name: string;
  description: string;
  status: TimetableStatus;
  effectiveDate: string;   // ISO date: "YYYY-MM-DD"
  expiryDate: string | null;
  authorId: string;
  reviewerId: string | null;
  version: number;
}

export interface CreateTimetableRequest {
  lineId: string;
  name: string;
  description: string;
  effectiveDate: string;
  expiryDate?: string;
}

export interface UpdateTimetableRequest {
  name?: string;
  description?: string;
  effectiveDate?: string;
  expiryDate?: string | null;
}

export interface EmergencyActivateRequest {
  justification: string;
}

export interface RejectRequest {
  reason: string;
}

export interface RequestChangesRequest {
  feedback: string;
}

// ── Schedule ─────────────────────────────────────────────────────────────────

export interface ScheduleView {
  id: string;
  timetableId: string;
  lineId: string;
  effectiveDate: string;
  expiryDate: string | null;
  services: ScheduledServiceView[];
  computedAt: string;
}

export interface ScheduledServiceView {
  serviceId: string;
  stops: StopTimeView[];
}

export interface StopTimeView {
  stationCode: string;
  arrivalTime: string | null;
  departureTime: string | null;
}

// ── WebSocket / Push ──────────────────────────────────────────────────────────

export interface ScheduleUpdateMessage {
  timetableId: string;
  lineId: string;
  effectiveDate: string;
  isEmergency: boolean;
}

// ── Prediction ───────────────────────────────────────────────────────────────

export interface PredictionMessage {
  routeId: string;
  trainId: string | null;
  predictedDelayMinutes: number;
  confidenceScore: number;
  modelVersion: string;
  predictedAt: string;
  correlationId: string;
}

// ── Error ─────────────────────────────────────────────────────────────────────

export interface ApiError {
  status: number;
  errorCode: string;
  message: string;
  correlationId?: string;
}
