import { QueueEventEnvelope, api } from './api';

export type ConnectionState = 'connected' | 'reconnecting' | 'disconnected';

export interface QueueEventClientOptions {
  onEvent: (event: QueueEventEnvelope) => void;
  onStateChange: (state: ConnectionState) => void;
}

interface QueueEventStreamMessage {
  outboxId: number;
  event: QueueEventEnvelope;
}

const CURSOR_STORAGE_KEY = 'queue.events.cursor';

export class QueueEventClient {
  private readonly options: QueueEventClientOptions;
  private socket: WebSocket | null = null;
  private reconnectTimer: number | null = null;
  private readonly seenEvents = new Set<string>();
  private readonly latestJobVersion = new Map<number, number>();
  private lastCursor = 0;
  private closedManually = false;

  constructor(options: QueueEventClientOptions) {
    this.options = options;
    this.lastCursor = this.readPersistedCursor();
  }

  start() {
    this.closedManually = false;
    this.connect();
  }

  stop() {
    this.closedManually = true;
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.socket?.close();
    this.socket = null;
    this.options.onStateChange('disconnected');
  }

  setCurrentVersion(jobId: number, version: number) {
    const current = this.latestJobVersion.get(jobId) ?? 0;
    if (version > current) {
      this.latestJobVersion.set(jobId, version);
    }
  }

  private connect() {
    const socketUrl = resolveSocketUrl();
    this.options.onStateChange('reconnecting');
    this.socket = new WebSocket(socketUrl);

    this.socket.onopen = () => {
      this.replaySinceCursor()
        .catch(() => {
          // Polling fallback will recover eventual consistency.
        })
        .finally(() => {
          this.options.onStateChange('connected');
        });
    };

    this.socket.onclose = () => {
      if (this.closedManually) {
        this.options.onStateChange('disconnected');
        return;
      }
      this.scheduleReconnect();
    };

    this.socket.onerror = () => {
      if (!this.closedManually) {
        this.options.onStateChange('reconnecting');
      }
    };

    this.socket.onmessage = (message) => {
      this.handleIncomingMessage(message.data);
    };
  }

  private handleIncomingMessage(rawPayload: unknown) {
    try {
      const parsed = JSON.parse(String(rawPayload)) as unknown;
      const streamMessage = normalizeStreamMessage(parsed);
      if (streamMessage === null) {
        return;
      }
      this.handleEvent(streamMessage.event, streamMessage.outboxId);
    } catch {
      // Ignore invalid payloads.
    }
  }

  private handleEvent(event: QueueEventEnvelope, outboxId?: number) {
    if (this.seenEvents.has(event.eventId)) {
      return;
    }

    const currentVersion = this.latestJobVersion.get(event.jobId) ?? 0;
    if (event.jobVersion <= currentVersion) {
      this.seenEvents.add(event.eventId);
      if (outboxId !== undefined) {
        this.registerOutboxCursor(outboxId);
      }
      return;
    }

    this.seenEvents.add(event.eventId);
    this.latestJobVersion.set(event.jobId, event.jobVersion);
    if (outboxId !== undefined) {
      this.registerOutboxCursor(outboxId);
    }
    this.options.onEvent(event);
  }

  private async replaySinceCursor() {
    if (this.lastCursor <= 0) {
      return;
    }

    let cursor = this.lastCursor;
    while (true) {
      const events = await api.eventsSince(cursor, 200);
      if (events.length === 0) {
        break;
      }

      for (const row of events) {
        cursor = Math.max(cursor, row.outboxId);
        const parsed = JSON.parse(row.payload) as QueueEventEnvelope;
        this.handleEvent(parsed, row.outboxId);
      }

      if (events.length < 200) {
        break;
      }
    }
  }

  public registerOutboxCursor(outboxId: number) {
    if (outboxId <= this.lastCursor) {
      return;
    }
    this.lastCursor = outboxId;
    this.persistCursor(this.lastCursor);
  }

  private scheduleReconnect() {
    this.options.onStateChange('reconnecting');
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
    }
    this.reconnectTimer = window.setTimeout(() => {
      this.connect();
    }, 1000);
  }

  private readPersistedCursor(): number {
    try {
      const raw = window.localStorage.getItem(CURSOR_STORAGE_KEY);
      if (raw === null) {
        return 0;
      }
      const parsed = Number(raw);
      return Number.isFinite(parsed) && parsed > 0 ? Math.floor(parsed) : 0;
    } catch {
      return 0;
    }
  }

  private persistCursor(cursor: number) {
    try {
      window.localStorage.setItem(CURSOR_STORAGE_KEY, String(cursor));
    } catch {
      // Ignore local storage write failures.
    }
  }
}

function resolveSocketUrl(): string {
  const apiBase = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:7070';
  const resolvedBase = new URL(apiBase, window.location.origin);
  const socketProtocol = resolvedBase.protocol === 'https:' ? 'wss:' : 'ws:';
  return new URL('/api/events/ws', `${socketProtocol}//${resolvedBase.host}`).toString();
}

function normalizeStreamMessage(payload: unknown): QueueEventStreamMessage | null {
  if (typeof payload !== 'object' || payload === null) {
    return null;
  }

  const candidate = payload as Record<string, unknown>;
  if (typeof candidate.outboxId === 'number' && typeof candidate.event === 'object' && candidate.event !== null) {
    return {
      outboxId: candidate.outboxId,
      event: candidate.event as QueueEventEnvelope,
    };
  }

  if (typeof candidate.eventId === 'string') {
    return {
      outboxId: 0,
      event: candidate as unknown as QueueEventEnvelope,
    };
  }

  return null;
}
