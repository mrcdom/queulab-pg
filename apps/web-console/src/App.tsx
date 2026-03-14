import { FormEvent, useEffect, useState } from 'react';
import { api, DashboardSnapshot, QueueJob, QueueStatus, WorkerSnapshot } from './lib/api';
import { MetricCard } from './components/MetricCard';
import { SectionCard } from './components/SectionCard';
import { DataTable } from './components/DataTable';

type View = 'dashboard' | 'jobs' | 'dlq' | 'workers' | 'simulator';

const statusOrder: QueueStatus[] = ['PENDING', 'PROCESSING', 'RETRY', 'DONE', 'FAILED'];
const scenarioButtons = [
  { key: 'happy-path', label: 'Fluxo feliz' },
  { key: 'transient-failures', label: 'Retry e backoff' },
  { key: 'permanent-failures', label: 'Falha permanente' },
  { key: 'duplicate-messages', label: 'Duplicidade' },
  { key: 'scheduled-jobs', label: 'Jobs agendados' },
];

const emptyDashboard: DashboardSnapshot = {
  statusCounts: {
    PENDING: 0,
    PROCESSING: 0,
    RETRY: 0,
    DONE: 0,
    FAILED: 0,
  },
  queueBacklogs: [],
  averageWaitSeconds: 0,
  averageProcessingSeconds: 0,
  retryJobs: 0,
  dlqJobs: 0,
  activeWorkers: 0,
};

export default function App() {
  const [view, setView] = useState<View>('dashboard');
  const [dashboard, setDashboard] = useState<DashboardSnapshot>(emptyDashboard);
  const [jobs, setJobs] = useState<QueueJob[]>([]);
  const [dlq, setDlq] = useState<QueueJob[]>([]);
  const [workers, setWorkers] = useState<WorkerSnapshot[]>([]);
  const [selectedJob, setSelectedJob] = useState<QueueJob | null>(null);
  const [search, setSearch] = useState('');
  const [queueFilter, setQueueFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [manualQueue, setManualQueue] = useState('notification.send');
  const [manualDedup, setManualDedup] = useState('');
  const [manualAvailableAt, setManualAvailableAt] = useState('');
  const [manualPayload, setManualPayload] = useState(`{
  "channel": "EMAIL",
  "recipient": "demo@queue-lab.local",
  "template": "confirmation",
  "message": "Seu evento foi confirmado",
  "transientFailuresBeforeSuccess": 0,
  "forcePermanentFailure": false
}`);
  const [burstCount, setBurstCount] = useState(20);
  const [burstTransientFailures, setBurstTransientFailures] = useState(0);
  const [burstPermanentFailures, setBurstPermanentFailures] = useState(0);
  const [burstUseDedup, setBurstUseDedup] = useState(false);
  const [burstRepeatDedup, setBurstRepeatDedup] = useState(false);

  async function loadAll() {
    try {
      const params = new URLSearchParams();
      if (queueFilter) {
        params.set('queueName', queueFilter);
      }
      if (statusFilter) {
        params.set('status', statusFilter);
      }
      if (search) {
        params.set('search', search);
      }

      const [nextDashboard, nextJobs, nextDlq, nextWorkers] = await Promise.all([
        api.dashboard(),
        api.jobs(params),
        api.dlq(),
        api.workers(),
      ]);

      setDashboard(nextDashboard);
      setJobs(nextJobs);
      setDlq(nextDlq);
      setWorkers(nextWorkers);
      if (selectedJob) {
        const refreshedSelection = nextJobs.find((job) => job.id === selectedJob.id) ?? nextDlq.find((job) => job.id === selectedJob.id) ?? null;
        setSelectedJob(refreshedSelection);
      }
      setError(null);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Falha ao carregar dados');
    }
  }

  useEffect(() => {
    loadAll();
    const timer = window.setInterval(loadAll, 4000);
    return () => window.clearInterval(timer);
  }, [queueFilter, statusFilter, search]);

  async function handleManualSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      const payload = JSON.parse(manualPayload) as Record<string, unknown>;
      await api.enqueue({
        queueName: manualQueue,
        dedupKey: manualDedup || undefined,
        availableAt: manualAvailableAt ? new Date(manualAvailableAt).toISOString() : undefined,
        maxAttempts: 6,
        payload,
      });
      setMessage('Mensagem enfileirada com sucesso.');
      await loadAll();
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : 'Falha ao enviar mensagem');
    }
  }

  async function handleBurstSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      const result = await api.burst({
        queueName: manualQueue,
        count: burstCount,
        transientFailuresBeforeSuccess: burstTransientFailures,
        permanentFailures: burstPermanentFailures,
        useDeduplication: burstUseDedup,
        repeatDedupKey: burstRepeatDedup,
        dedupPrefix: 'console',
        maxAttempts: 6,
      });
      setMessage(`Burst executado: ${result.created} criados, ${result.deduplicated} deduplicados.`);
      await loadAll();
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : 'Falha ao executar burst');
    }
  }

  async function handleScenario(name: string) {
    try {
      const result = await api.scenario(name);
      setMessage(`Cenario executado: ${result.created} mensagens criadas.`);
      await loadAll();
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : 'Falha ao executar cenario');
    }
  }

  async function handleRequeue(jobId: number) {
    try {
      await api.requeue(jobId);
      setMessage(`Job ${jobId} reenfileirado.`);
      await loadAll();
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : 'Falha ao reenfileirar job');
    }
  }

  async function handleReconcile() {
    try {
      const result = await api.reconcile();
      setMessage(`${result.recovered} jobs reconciliados.`);
      await loadAll();
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : 'Falha ao reconciliar jobs');
    }
  }

  const uniqueQueues = Array.from(new Set([...dashboard.queueBacklogs.map((item) => item.queueName), ...jobs.map((job) => job.queueName)])).sort();

  return (
    <div className="app-shell">
      <div className="aurora aurora--left" />
      <div className="aurora aurora--right" />
      <header className="hero">
        <div>
          <p className="eyebrow">QueueLab PG</p>
          <h1>PostgreSQL como fila observável, concorrente e demonstrável.</h1>
          <p className="hero-copy">
            Console única para validar enfileiramento transacional, wake-up com LISTEN/NOTIFY, retries com backoff,
            DLQ lógica e recuperação operacional.
          </p>
        </div>
        <div className="hero-actions">
          <button type="button" className="ghost-button" onClick={loadAll}>Atualizar agora</button>
          <button type="button" className="primary-button" onClick={handleReconcile}>Reconciliar stuck jobs</button>
        </div>
      </header>

      <nav className="view-tabs">
        {[
          ['dashboard', 'Dashboard'],
          ['jobs', 'Fila'],
          ['dlq', 'DLQ'],
          ['workers', 'Workers'],
          ['simulator', 'Simulador'],
        ].map(([key, label]) => (
          <button
            key={key}
            type="button"
            className={view === key ? 'tab-button tab-button--active' : 'tab-button'}
            onClick={() => setView(key as View)}
          >
            {label}
          </button>
        ))}
      </nav>

      {message ? <div className="feedback feedback--success">{message}</div> : null}
      {error ? <div className="feedback feedback--error">{error}</div> : null}

      <main className="content-grid">
        <section className="content-main">
          {view === 'dashboard' ? (
            <>
              <div className="metric-grid">
                {statusOrder.map((status) => (
                  <MetricCard
                    key={status}
                    label={status}
                    value={String(dashboard.statusCounts[status] ?? 0)}
                    tone={status === 'FAILED' ? 'danger' : status === 'PROCESSING' ? 'accent' : 'default'}
                  />
                ))}
                <MetricCard label="Workers ativos" value={String(dashboard.activeWorkers)} tone="accent" />
                <MetricCard label="Wait médio" value={`${dashboard.averageWaitSeconds.toFixed(1)}s`} />
                <MetricCard label="Processamento médio" value={`${dashboard.averageProcessingSeconds.toFixed(1)}s`} />
              </div>

              <SectionCard title="Backlog por fila" subtitle="Volume pronto, em processamento e falhas definitivas por queue.">
                <DataTable columns={<tr><th>Fila</th><th>Ready</th><th>Processing</th><th>Failed</th></tr>}>
                  {dashboard.queueBacklogs.map((item) => (
                    <tr key={item.queueName}>
                      <td>{item.queueName}</td>
                      <td>{item.readyJobs}</td>
                      <td>{item.processingJobs}</td>
                      <td>{item.failedJobs}</td>
                    </tr>
                  ))}
                </DataTable>
              </SectionCard>
            </>
          ) : null}

          {view === 'jobs' ? (
            <SectionCard title="Fila em tempo real" subtitle="Polling curto para acompanhar o estado operacional." action={(
              <div className="filters">
                <select value={queueFilter} onChange={(event) => setQueueFilter(event.target.value)}>
                  <option value="">Todas as filas</option>
                  {uniqueQueues.map((queue) => <option key={queue} value={queue}>{queue}</option>)}
                </select>
                <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
                  <option value="">Todos os status</option>
                  {statusOrder.map((status) => <option key={status} value={status}>{status}</option>)}
                </select>
                <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Buscar payload, worker ou dedup" />
              </div>
            )}>
              <JobsTable jobs={jobs} onSelect={setSelectedJob} />
            </SectionCard>
          ) : null}

          {view === 'dlq' ? (
            <SectionCard title="Dead Letter Queue lógica" subtitle="Jobs com falha definitiva, aptos a análise e reenfileiramento.">
              <DataTable columns={<tr><th>ID</th><th>Fila</th><th>Erro</th><th>Tentativas</th><th>Ação</th></tr>}>
                {dlq.map((job) => (
                  <tr key={job.id}>
                    <td>{job.id}</td>
                    <td>{job.queueName}</td>
                    <td className="truncate">{job.lastError ?? 'Sem erro registrado'}</td>
                    <td>{job.attempts}/{job.maxAttempts}</td>
                    <td>
                      <button type="button" className="ghost-button" onClick={() => handleRequeue(job.id)}>Reenfileirar</button>
                    </td>
                  </tr>
                ))}
              </DataTable>
            </SectionCard>
          ) : null}

          {view === 'workers' ? (
            <SectionCard title="Workers ativos" subtitle="Heartbeat, throughput e falhas por worker registrado.">
              <DataTable columns={<tr><th>Worker</th><th>Status</th><th>Heartbeat</th><th>Processados</th><th>Falhas</th></tr>}>
                {workers.map((worker) => (
                  <tr key={worker.workerId}>
                    <td>{worker.workerId}</td>
                    <td>{worker.status}</td>
                    <td>{formatDate(worker.lastHeartbeatAt)}</td>
                    <td>{worker.processedCount}</td>
                    <td>{worker.failedCount}</td>
                  </tr>
                ))}
              </DataTable>
            </SectionCard>
          ) : null}

          {view === 'simulator' ? (
            <div className="simulator-grid">
              <SectionCard title="Envio manual" subtitle="Crie um job individual com payload livre e agendamento opcional.">
                <form className="stack-form" onSubmit={handleManualSubmit}>
                  <label>
                    Queue name
                    <input value={manualQueue} onChange={(event) => setManualQueue(event.target.value)} />
                  </label>
                  <label>
                    Dedup key
                    <input value={manualDedup} onChange={(event) => setManualDedup(event.target.value)} placeholder="notification:123" />
                  </label>
                  <label>
                    Available at
                    <input type="datetime-local" value={manualAvailableAt} onChange={(event) => setManualAvailableAt(event.target.value)} />
                  </label>
                  <label>
                    Payload JSON
                    <textarea rows={12} value={manualPayload} onChange={(event) => setManualPayload(event.target.value)} />
                  </label>
                  <button type="submit" className="primary-button">Enfileirar mensagem</button>
                </form>
              </SectionCard>

              <SectionCard title="Carga controlada" subtitle="Simule burst, retries, falhas permanentes e deduplicação.">
                <form className="stack-form" onSubmit={handleBurstSubmit}>
                  <label>
                    Quantidade
                    <input type="number" min={1} max={500} value={burstCount} onChange={(event) => setBurstCount(Number(event.target.value))} />
                  </label>
                  <label>
                    Jobs com falhas transitórias antes do sucesso
                    <input type="number" min={0} max={20} value={burstTransientFailures} onChange={(event) => setBurstTransientFailures(Number(event.target.value))} />
                  </label>
                  <label>
                    Jobs com falha permanente
                    <input type="number" min={0} max={20} value={burstPermanentFailures} onChange={(event) => setBurstPermanentFailures(Number(event.target.value))} />
                  </label>
                  <label className="checkbox-row">
                    <input type="checkbox" checked={burstUseDedup} onChange={(event) => setBurstUseDedup(event.target.checked)} />
                    Ativar deduplicação
                  </label>
                  <label className="checkbox-row">
                    <input type="checkbox" checked={burstRepeatDedup} onChange={(event) => setBurstRepeatDedup(event.target.checked)} />
                    Repetir a mesma dedup key no lote
                  </label>
                  <button type="submit" className="primary-button">Executar burst</button>
                </form>

                <div className="scenario-grid">
                  {scenarioButtons.map((scenario) => (
                    <button key={scenario.key} type="button" className="ghost-button" onClick={() => handleScenario(scenario.key)}>
                      {scenario.label}
                    </button>
                  ))}
                </div>
              </SectionCard>
            </div>
          ) : null}
        </section>

        <aside className="content-side">
          <SectionCard title="Detalhe do job" subtitle="Selecione um registro para inspecionar payload, lock e erro recente.">
            {selectedJob ? (
              <div className="job-detail">
                <div className="detail-line"><span>ID</span><strong>{selectedJob.id}</strong></div>
                <div className="detail-line"><span>Status</span><strong>{selectedJob.status}</strong></div>
                <div className="detail-line"><span>Fila</span><strong>{selectedJob.queueName}</strong></div>
                <div className="detail-line"><span>Dedup</span><strong>{selectedJob.dedupKey ?? 'Sem dedup'}</strong></div>
                <div className="detail-line"><span>Worker</span><strong>{selectedJob.lockedBy ?? 'Livre'}</strong></div>
                <div className="detail-line"><span>Available at</span><strong>{formatDate(selectedJob.availableAt)}</strong></div>
                <div className="detail-line"><span>Criado em</span><strong>{formatDate(selectedJob.createdAt)}</strong></div>
                <div className="detail-line"><span>Atualizado em</span><strong>{formatDate(selectedJob.updatedAt)}</strong></div>
                <div className="detail-block">
                  <span>Último erro</span>
                  <p>{selectedJob.lastError ?? 'Sem erro registrado.'}</p>
                </div>
                <div className="detail-block">
                  <span>Payload</span>
                  <pre>{JSON.stringify(selectedJob.payload, null, 2)}</pre>
                </div>
              </div>
            ) : (
              <p className="empty-copy">Nenhum job selecionado.</p>
            )}
          </SectionCard>

          <SectionCard title="Indicadores rápidos" subtitle="Resumo operacional para acompanhar a demo ao vivo.">
            <div className="mini-metrics">
              <div><span>Retries pendentes</span><strong>{dashboard.retryJobs}</strong></div>
              <div><span>Jobs em DLQ</span><strong>{dashboard.dlqJobs}</strong></div>
              <div><span>Workers ativos</span><strong>{dashboard.activeWorkers}</strong></div>
            </div>
          </SectionCard>
        </aside>
      </main>
    </div>
  );
}

function JobsTable({ jobs, onSelect }: { jobs: QueueJob[]; onSelect: (job: QueueJob) => void }) {
  return (
    <DataTable columns={<tr><th>ID</th><th>Fila</th><th>Status</th><th>Attempts</th><th>Available at</th><th>Worker</th></tr>}>
      {jobs.map((job) => (
        <tr key={job.id} onClick={() => onSelect(job)} className="row-clickable">
          <td>{job.id}</td>
          <td>{job.queueName}</td>
          <td><span className={`pill pill--${job.status.toLowerCase()}`}>{job.status}</span></td>
          <td>{job.attempts}/{job.maxAttempts}</td>
          <td>{formatDate(job.availableAt)}</td>
          <td>{job.lockedBy ?? '-'}</td>
        </tr>
      ))}
    </DataTable>
  );
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value));
}