import { Alert, Button, Empty, Skeleton } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { getStoryRun, getStoryRuns } from './api';
import type { StoryRunDetail, StoryRunStep, StoryRunSummary } from './story-flow-types';

interface StoryRunHistoryProps {
  open: boolean;
  onClose: () => void;
  initialRunId?: string;
}

const activeStatuses = new Set(['QUEUED', 'RUNNING']);
const formatDate = (value: string) => new Date(value).toLocaleString('zh-CN', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
});
const copy = async (text: string) => navigator.clipboard?.writeText(text);

export default function StoryRunHistory({ open, onClose, initialRunId }: StoryRunHistoryProps) {
  const [runs, setRuns] = useState<StoryRunSummary[]>([]);
  const [selectedRunId, setSelectedRunId] = useState('');
  const [detail, setDetail] = useState<StoryRunDetail | null>(null);
  const [selectedStepId, setSelectedStepId] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const loadDetail = useCallback(async (runId: string, preserveStep = false) => {
    const loaded = await getStoryRun(runId);
    if (!loaded) throw new Error('运行详情为空');
    setDetail(loaded);
    setSelectedStepId((current) => preserveStep && loaded.steps.some((step) => step.id === current)
      ? current
      : loaded.steps[0]?.id ?? null);
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const loaded = await getStoryRuns();
      setRuns(loaded);
      const runId = initialRunId || loaded[0]?.runId || '';
      setSelectedRunId(runId);
      if (runId) await loadDetail(runId);
      else setDetail(null);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '运行记录加载失败');
    } finally {
      setLoading(false);
    }
  }, [initialRunId, loadDetail]);

  useEffect(() => {
    if (open) void load();
  }, [load, open]);

  useEffect(() => {
    if (!open || !detail || !activeStatuses.has(detail.status)) return undefined;
    const timer = window.setInterval(() => {
      void loadDetail(detail.runId, true);
    }, 2000);
    return () => window.clearInterval(timer);
  }, [detail, loadDetail, open]);

  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose, open]);

  if (!open) return null;
  const selectedStep: StoryRunStep | null = detail?.steps.find((step) => step.id === selectedStepId) ?? null;

  return (
    <div className="story-run-history" role="dialog" aria-modal="true" aria-label="故事运行记录">
      <header className="story-run-history-head">
        <strong>运行记录</strong>
        <div>
          <Button onClick={() => void load()}>刷新</Button>
          <Button onClick={onClose}>关闭</Button>
        </div>
      </header>
      {loading && !detail ? <div className="story-run-history-state"><Skeleton active /></div> : error ? (
        <div className="story-run-history-state"><Alert type="error" showIcon message={error} /></div>
      ) : runs.length === 0 && !detail ? <Empty description="暂无运行批次" /> : (
        <>
          <div className="story-run-history-main">
            <aside className="story-run-batches" aria-label="运行批次">
              <h3>批次</h3>
              <div>
                {runs.map((run) => (
                  <button
                    key={run.runId}
                    type="button"
                    className={run.runId === selectedRunId ? 'is-selected' : ''}
                    aria-label={`批次 ${run.runId} ${formatDate(run.createdAt)}`}
                    onClick={() => {
                      setSelectedRunId(run.runId);
                      void loadDetail(run.runId);
                    }}
                  >
                    <strong>{formatDate(run.createdAt)}</strong>
                    <span>{`${run.words.length} 个单词`}</span>
                  </button>
                ))}
              </div>
            </aside>
            <aside className="story-run-agents" aria-label="已执行 Agent">
              <h3>已执行 Agent</h3>
              <div>
                {detail?.steps.map((step) => (
                  <button
                    key={step.id}
                    type="button"
                    className={step.id === selectedStepId ? 'is-selected' : ''}
                    aria-label={`${step.agentName} 第 ${step.sequence} 次调用`}
                    onClick={() => setSelectedStepId(step.id)}
                  >
                    <span>{String(step.sequence).padStart(2, '0')}</span>
                    <strong>{step.agentName}</strong>
                  </button>
                ))}
              </div>
            </aside>
            <main className="story-run-inspector">
              <div className="story-run-words" aria-label="本批次输入单词">
                {detail?.words.map((word) => <span key={`${word.word}-${word.meaning}`}>{word.word}</span>)}
              </div>
              {selectedStep ? (
                <div className="story-run-io">
                  <section>
                    <header><strong>Agent 输入</strong><Button size="small" onClick={() => void copy(selectedStep.inputJson)}>复制</Button></header>
                    <pre>{selectedStep.inputJson}</pre>
                  </section>
                  <section>
                    <header><strong>Agent 输出</strong><Button size="small" onClick={() => void copy(selectedStep.outputText)}>复制</Button></header>
                    <pre>{selectedStep.outputText}</pre>
                  </section>
                </div>
              ) : <Empty description="当前批次还没有 Agent 执行记录" />}
            </main>
          </div>
          <section className="story-run-result" aria-label="最终故事结果">
            <header><strong>最终故事结果</strong><Button size="small" onClick={() => void copy(detail?.finalStory ?? '')}>复制</Button></header>
            <pre>{detail?.finalStory || (detail?.status === 'FAILED' ? detail.errorMessage : '故事生成中…')}</pre>
          </section>
        </>
      )}
    </div>
  );
}
