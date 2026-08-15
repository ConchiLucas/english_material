import { Alert, Button, Empty, Skeleton } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getImageRun, getImageRuns } from './api';
import type { ImageAsset, ImageRunDetail, ImageRunStep, ImageRunSummary, ImageShot } from './image-story-types';

interface ImageRunHistoryProps {
  open: boolean;
  onClose: () => void;
  initialRunId?: string;
}

type GalleryTab = 'final' | 'reference';
interface Preview { asset: ImageAsset; label: string; }

const activeStatuses = new Set(['QUEUED', 'PLANNING', 'GENERATING_REFERENCES', 'GENERATING_SHOTS', 'COMPOSITING']);
const formatDate = (value: string) => new Date(value).toLocaleString('zh-CN', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
});
const newestFirst = (values: ImageRunSummary[]) => [...values].sort((left, right) =>
  Date.parse(right.createdAt) - Date.parse(left.createdAt) || right.createdAt.localeCompare(left.createdAt));
const stepOrder = (values: ImageRunStep[]) => [...values].sort((left, right) =>
  left.sequence - right.sequence || left.id - right.id);
const shotOrder = (values: ImageShot[]) => [...values].sort((left, right) =>
  left.sequence - right.sequence || left.id - right.id);
const errorText = (error: unknown) => error instanceof Error ? error.message.slice(0, 240) : '图片记录加载失败';
const wordsText = (run?: Pick<ImageRunSummary, 'words'> | null) => run?.words.map((word) => word.word).join(' ') || '没有记录输入单词';

export default function ImageRunHistory({ open, onClose, initialRunId }: ImageRunHistoryProps) {
  const [runs, setRuns] = useState<ImageRunSummary[]>([]);
  const [selectedRunId, setSelectedRunId] = useState('');
  const [detail, setDetail] = useState<ImageRunDetail | null>(null);
  const [selectedStepId, setSelectedStepId] = useState<number | null>(null);
  const [galleryTab, setGalleryTab] = useState<GalleryTab>('final');
  const [preview, setPreview] = useState<Preview | null>(null);
  const [brokenAssets, setBrokenAssets] = useState<Set<number>>(new Set());
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const listRequestRef = useRef(0);
  const detailRequestRef = useRef(0);
  const selectedRunRef = useRef('');

  const loadDetail = useCallback(async (runId: string, preserveStep = false) => {
    const requestId = ++detailRequestRef.current;
    const loaded = await getImageRun(runId);
    if (!loaded) throw new Error('图片运行详情为空');
    if (requestId !== detailRequestRef.current || selectedRunRef.current !== runId) return;
    const orderedSteps = stepOrder(loaded.steps);
    setError('');
    setDetail({ ...loaded, steps: orderedSteps, shots: shotOrder(loaded.shots) });
    setSelectedStepId((current) => preserveStep && orderedSteps.some((step) => step.id === current)
      ? current
      : orderedSteps[0]?.id ?? null);
    return loaded;
  }, []);

  const load = useCallback(async () => {
    const requestId = ++listRequestRef.current;
    setLoading(true); setError('');
    try {
      const loaded = newestFirst(await getImageRuns());
      if (requestId !== listRequestRef.current) return;
      setRuns(loaded);
      const current = selectedRunRef.current;
      const runId = loaded.some((run) => run.runId === current) ? current : initialRunId || loaded[0]?.runId || '';
      selectedRunRef.current = runId;
      setSelectedRunId(runId);
      setGalleryTab('final'); setPreview(null); setBrokenAssets(new Set());
      if (runId) await loadDetail(runId);
      else { setDetail(null); setSelectedStepId(null); }
    } catch (loadError) {
      if (requestId === listRequestRef.current) setError(errorText(loadError));
    } finally {
      if (requestId === listRequestRef.current) setLoading(false);
    }
  }, [initialRunId, loadDetail]);

  const switchRun = (runId: string) => {
    if (runId === selectedRunRef.current) return;
    detailRequestRef.current += 1;
    selectedRunRef.current = runId;
    setSelectedRunId(runId); setDetail(null); setSelectedStepId(null); setGalleryTab('final'); setPreview(null); setBrokenAssets(new Set()); setError('');
    void loadDetail(runId).catch((loadError) => {
      if (selectedRunRef.current === runId) setError(errorText(loadError));
    });
  };

  useEffect(() => {
    if (open) void load();
    else { listRequestRef.current += 1; detailRequestRef.current += 1; }
  }, [load, open]);

  useEffect(() => {
    if (!open || !detail || !activeStatuses.has(detail.status)) return undefined;
    let cancelled = false;
    let timer = 0;
    const poll = () => {
      void loadDetail(detail.runId, true).catch((pollError) => {
        if (!cancelled && selectedRunRef.current === detail.runId) {
          setError(errorText(pollError));
          timer = window.setTimeout(poll, 2000);
        }
      });
    };
    timer = window.setTimeout(poll, 2000);
    return () => { cancelled = true; window.clearTimeout(timer); };
  }, [detail, loadDetail, open]);

  useEffect(() => () => { listRequestRef.current += 1; detailRequestRef.current += 1; }, []);
  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape') preview ? setPreview(null) : onClose(); };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose, open, preview]);

  const selectedStep = detail?.steps.find((step) => step.id === selectedStepId) ?? null;
  const finalAssets = useMemo(() => new Map((detail?.assets ?? [])
    .filter((asset) => asset.assetType === 'FINAL' && asset.shotKey)
    .map((asset) => [asset.shotKey as string, asset])), [detail]);
  const referenceAssets = useMemo(() => (detail?.assets ?? []).filter((asset) => asset.assetType === 'REFERENCE'), [detail]);
  const markBroken = (assetId: number) => setBrokenAssets((current) => new Set(current).add(assetId));

  if (!open) return null;

  const renderImage = (asset: ImageAsset, label: string) => brokenAssets.has(asset.id)
    ? <div className="image-story-history-asset-error">图片文件加载失败</div>
    : <button type="button" className="image-story-history-image-button" aria-label={`查看 ${label}大图`} onClick={() => setPreview({ asset, label })}>
      <img src={asset.contentUrl} alt={label} onError={() => markBroken(asset.id)} />
    </button>;

  return <div className="image-story-history" role="dialog" aria-modal="true" aria-label="图片运行记录">
    <section className="image-story-history-upper">
      <header className="image-story-history-controls">
        <span>{detail ? `${detail.targetGrade} · ${detail.stylePresetName ?? '未命名画风'}` : '图片批次'}</span>
        <div><Button onClick={() => void load()}>刷新</Button><Button onClick={onClose}>关闭图片记录</Button></div>
      </header>
      {error && <Alert className="image-story-history-error" type="error" showIcon message={error} />}
      {loading && runs.length === 0 && !detail ? <div className="image-story-history-state"><Skeleton active paragraph={{ rows: 5 }} /></div> : runs.length === 0 && !detail ? <Empty description="暂无图片批次" /> : <>
        <div className="image-story-history-batches" role="list" aria-label="图片批次列表">
          {runs.map((run) => <button key={run.runId} type="button" aria-label={`批次 ${run.runId} ${formatDate(run.createdAt)}`} className={run.runId === selectedRunId ? 'is-selected' : ''} onClick={() => switchRun(run.runId)}>
            <span><strong>{formatDate(run.createdAt)}</strong><small>{run.runId}</small></span>
            <span className="image-story-history-batch-words" style={{ whiteSpace: 'nowrap' }}>{wordsText(run)}</span>
          </button>)}
        </div>
        <div className="image-story-history-words" role="region" aria-label="当前批次单词"><strong>输入单词</strong><span>{wordsText(detail)}</span></div>
        {detail ? <div className="image-story-history-audit">
          <aside className="image-story-history-steps" role="list" aria-label="已执行步骤">
            {detail.steps.length ? detail.steps.map((step) => <button key={step.id} type="button" className={step.id === selectedStepId ? 'is-selected' : ''} aria-label={`${step.nodeName} 第 ${step.sequence} 步`} onClick={() => setSelectedStepId(step.id)}>
              <span>{String(step.sequence).padStart(2, '0')}</span><strong>{step.nodeName}</strong><small>{step.nodeKind}</small><em>{step.status}</em>
            </button>) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前批次还没有执行步骤" />}
          </aside>
          <section className="image-story-history-io" role="region" aria-label="完整输入">
            <header><strong>完整输入</strong>{selectedStep && <span>{selectedStep.nodeName}</span>}</header>
            <pre>{selectedStep?.inputJson || '没有保存输入内容'}</pre>
          </section>
          <section className="image-story-history-io" role="region" aria-label="完整输出">
            <header><strong>完整输出</strong>{selectedStep && <span>{selectedStep.status}</span>}</header>
            {selectedStep?.errorMessage && <Alert type="error" showIcon message={selectedStep.errorMessage} />}
            <pre>{selectedStep?.rawOutput || '没有原始输出'}</pre>
          </section>
        </div> : <div className="image-story-history-state">{error || '正在加载当前批次…'}</div>}
      </>}
    </section>

    <section className="image-story-history-gallery" role="region" aria-label="图片结果">
      <header><div role="tablist" aria-label="图片结果类型">
        <button type="button" role="tab" aria-selected={galleryTab === 'final'} className={galleryTab === 'final' ? 'is-active' : ''} onClick={() => setGalleryTab('final')}>最终分镜图</button>
        <button type="button" role="tab" aria-selected={galleryTab === 'reference'} className={galleryTab === 'reference' ? 'is-active' : ''} onClick={() => setGalleryTab('reference')}>参考设定图</button>
      </div></header>
      <div className="image-story-history-gallery-scroll">
        {galleryTab === 'final' ? detail?.shots.length ? detail.shots.map((shot) => {
          const asset = finalAssets.get(shot.shotKey); const label = `Scene ${shot.sceneIndex} Shot ${shot.shotIndex} 最终分镜图`;
          return <article className="image-story-history-shot" key={shot.id}>
            <div className="image-story-history-thumb">{asset ? renderImage(asset, label) : <div className="image-story-history-asset-error"><strong>{shot.status === 'FAILED' ? '分镜生成失败' : '图片尚未生成'}</strong><span>缺少最终图片资产</span></div>}</div>
            <div><strong>{`Scene ${shot.sceneIndex} · Shot ${shot.shotIndex}`}</strong><dl><dt>故事片段</dt><dd>{shot.sourceExcerpt || '未记录'}</dd>{shot.dialogue && <><dt>对话</dt><dd>{`${shot.speaker ? `${shot.speaker}：` : ''}${shot.dialogue}`}</dd></>}{shot.caption && <><dt>字幕</dt><dd>{shot.caption}</dd></>}<dt>最终提示词</dt><dd>{shot.prompt || '未记录'}</dd></dl></div>
          </article>;
        }) : <Empty description={detail && activeStatuses.has(detail.status) ? '最终分镜图生成中…' : '没有最终分镜记录'} /> : referenceAssets.length ? referenceAssets.map((asset) => {
          const label = `${asset.assetKey} 参考设定图`;
          return <article className="image-story-history-reference" key={asset.id}>{renderImage(asset, label)}<div><strong>{asset.assetKey}</strong><p>{asset.prompt || '未记录参考图提示词'}</p></div></article>;
        }) : <Empty description={`参考设定图未生成${detail?.errorMessage ? `：${detail.errorMessage}` : detail && activeStatuses.has(detail.status) ? '，当前批次仍在执行' : ''}`} />}
      </div>
    </section>

    {preview && <div className="image-story-history-preview" role="dialog" aria-modal="true" aria-label="图片大图预览" onClick={() => setPreview(null)}>
      <div onClick={(event) => event.stopPropagation()}><img src={preview.asset.contentUrl} alt={`${preview.label}大图`} onError={() => markBroken(preview.asset.id)} /><Button onClick={() => setPreview(null)}>关闭大图</Button></div>
    </div>}
  </div>;
}
