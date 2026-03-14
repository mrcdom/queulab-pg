export type QueueStatus = 'PENDING' | 'PROCESSING' | 'RETRY' | 'DONE' | 'FAILED';

export interface QueueBacklog {
  queueName: string;
  readyJobs: number;
  processingJobs: number;
  failedJobs: number;
}

export interface DashboardSnapshot {
  statusCounts: Record<QueueStatus, number>;
  queueBacklogs: QueueBacklog[];
  averageWaitSeconds: number;
  averageProcessingSeconds: number;
  retryJobs: number;
  dlqJobs: number;
  activeWorkers: number;
}

export interface QueueJob {
  id: number;
  queueName: string;
  dedupKey: string | null;
  payload: Record<string, unknown>;
  status: QueueStatus;
  availableAt: string;
  attempts: number;
  maxAttempts: number;
  lockedAt: string | null;
  lockedBy: string | null;
  lastError: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface WorkerSnapshot {
  workerId: string;
  startedAt: string;
  lastHeartbeatAt: string;
  status: string;
  processedCount: number;
  failedCount: number;
}

export interface BurstResult {
  created: number;
  deduplicated: number;
  requested: number;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:7070';

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
    ...init,
  });

  if (!response.ok) {
    const body = (await response.json().catch(() => ({ message: 'Erro desconhecido' }))) as { message?: string };
    throw new Error(body.message ?? 'Erro ao consumir API');
  }

  return (await response.json()) as T;
}

export const api = {
  health: () => request<{ status: string; timestamp: string }>('/api/health'),
  dashboard: () => request<DashboardSnapshot>('/api/dashboard'),
  jobs: (params?: URLSearchParams) => request<QueueJob[]>(`/api/jobs${params ? `?${params}` : ''}`),
  dlq: () => request<QueueJob[]>('/api/dlq'),
  workers: () => request<WorkerSnapshot[]>('/api/workers'),
  enqueue: (payload: Record<string, unknown>) => request<{ jobId: number; created: boolean }>('/api/jobs', {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  burst: (payload: Record<string, unknown>) => request<BurstResult>('/api/simulator/burst', {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  scenario: (name: string) => request<BurstResult>(`/api/simulator/scenarios/${name}`, {
    method: 'POST',
  }),
  requeue: (jobId: number) => request<{ success: boolean; message: string }>(`/api/jobs/${jobId}/requeue`, {
    method: 'POST',
  }),
  reconcile: () => request<{ recovered: number }>('/api/admin/reconcile', {
    method: 'POST',
  }),
};