import { QueueEventEnvelope, api } from './api';

export type ConnectionState = 'connected' | 'reconnecting' | 'disconnected';

export interface QueueEventClientOptions {
  onEvent: (event: QueueEventEnvelope) => void;
  onStateChange: (state: ConnectionState) => void;
}

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
      this.options.onStateChange('connected');
      this.replaySinceCursor().catch(() => {
        // Reconcile via polling even if replay fails.
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
      try {
        const event = JSON.parse(message.data) as QueueEventEnvelope;
        this.handleEvent(event);
      } catch {
        // Ignore invalid payloads.
      }
    };
  }

  private handleEvent(event: QueueEventEnvelope) {
    if (this.seenEvents.has(event.eventId)) {
      return;
    }

    const currentVersion = this.latestJobVersion.get(event.jobId) ?? 0;
    if (event.jobVersion <= currentVersion) {
      this.seenEvents.add(event.eventId);
      return;
    }

    this.seenEvents.add(event.eventId);
    this.latestJobVersion.set(event.jobId, event.jobVersion);
    this.options.onEvent(event);
  }

  private async replaySinceCursor() {
    if (this.lastCursor <= 0) {
      return;
    }

    const events = await api.eventsSince(this.lastCursor, 200);
    for (const row of events) {
      this.lastCursor = Math.max(this.lastCursor, row.outboxId);
      const event = JSON.parse(row.payload) as QueueEventEnvelope;
      this.handleEvent(event);
    }
  }

  public registerOutboxCursor(outboxId: number) {
    if (outboxId > this.lastCursor) {
      this.lastCursor = outboxId;
    }
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
}

function resolveSocketUrl(): string {
  const apiBase = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:7070';
  const resolvedBase = new URL(apiBase, window.location.origin);
  const socketProtocol = resolvedBase.protocol === 'https:' ? 'wss:' : 'ws:';
  return new URL('/api/events/ws', `${socketProtocol}//${resolvedBase.host}`).toString();
}
