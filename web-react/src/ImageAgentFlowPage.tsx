import {
  Alert, App as AntApp, Button, Empty, Form, Input, InputNumber, Modal, Select, Skeleton, Spin, Switch, Tag,
} from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { AIProviderConfigItem } from './api';
import {
  createImageRun, createImageStylePreset, getImageAgentFlow, getImageAgentVersions, getImageSourceStories,
  restoreImageAgentVersion, updateImageAgent, updateImageFlowConfig, updateImageStylePreset,
} from './api';
import type {
  ImageAgentFlow, ImageAgentNode, ImagePromptVersion, ImageSourceStory, ImageStylePreset,
} from './image-story-types';
import { hasProviderCapability, isExecutableImageProvider } from './image-provider-policy';
import ImageRunHistory from './ImageRunHistory';

interface ImageAgentFlowPageProps { providers: AIProviderConfigItem[]; onDirtyChange: (dirty: boolean) => void; }
interface AgentDraft { systemPrompt: string; aiProviderId: string; temperature: number; enabled: boolean; updatedAt: string | null; }
interface StyleDraft { id: number | null; name: string; positivePrompt: string; negativePrompt: string; description: string; enabled: boolean; updatedAt: string | null; }
interface ModelDraft { providerId: string; updatedAt: string | null; }
type TabKey = 'agents' | 'styles' | 'model';

const fromNode = (node: ImageAgentNode): AgentDraft => ({ systemPrompt: node.systemPrompt ?? '', aiProviderId: node.aiProviderId ?? '', temperature: node.temperature ?? 0.7, enabled: node.enabled !== false, updatedAt: node.updatedAt });
const sameDraft = (a: AgentDraft, b: AgentDraft) => a.systemPrompt === b.systemPrompt && a.aiProviderId === b.aiProviderId && a.temperature === b.temperature && a.enabled === b.enabled && a.updatedAt === b.updatedAt;
const sameModelDraft = (a: ModelDraft, b: ModelDraft) => a.providerId === b.providerId && a.updatedAt === b.updatedAt;
const fromStyle = (preset?: ImageStylePreset): StyleDraft => preset ? ({ id: preset.id, name: preset.name, positivePrompt: preset.positivePrompt, negativePrompt: preset.negativePrompt, description: preset.description, enabled: preset.enabled, updatedAt: preset.updatedAt }) : ({ id: null, name: '', positivePrompt: '', negativePrompt: '', description: '', enabled: true, updatedAt: null });
const sameStyle = (a: StyleDraft, b: StyleDraft) => JSON.stringify(a) === JSON.stringify(b);
const textProvider = (provider: AIProviderConfigItem) => provider.enabled !== false && hasProviderCapability(provider, 'TEXT_GENERATION');
const providerLabel = (provider: AIProviderConfigItem) => `${provider.label || provider.id}${provider.model ? ` · ${provider.model}` : ''}`;
const errorText = (error: unknown) => error instanceof Error ? error.message.slice(0, 240) : '请求失败，请稍后重试';
const formatDate = (value?: string | null) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未记录';

export default function ImageAgentFlowPage({ providers, onDirtyChange }: ImageAgentFlowPageProps) {
  const { message, modal } = AntApp.useApp();
  const [flow, setFlow] = useState<ImageAgentFlow | null>(null);
  const [loading, setLoading] = useState(true); const [loadError, setLoadError] = useState('');
  const [tab, setTab] = useState<TabKey>('agents'); const [selectedKey, setSelectedKey] = useState('');
  const [draft, setDraft] = useState<AgentDraft | null>(null); const [dirty, setDirty] = useState(false); const [saving, setSaving] = useState(false);
  const [versionsOpen, setVersionsOpen] = useState(false); const [versions, setVersions] = useState<ImagePromptVersion[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false); const [versionsError, setVersionsError] = useState(''); const [restoring, setRestoring] = useState<number | null>(null);
  const [styleDraft, setStyleDraft] = useState<StyleDraft | null>(null); const [styleSaving, setStyleSaving] = useState(false);
  const [modelProviderId, setModelProviderId] = useState(''); const [modelUpdatedAt, setModelUpdatedAt] = useState<string | null>(null); const [modelSaving, setModelSaving] = useState(false);
  const [startOpen, setStartOpen] = useState(false);
  const [sources, setSources] = useState<ImageSourceStory[]>([]); const [sourcesLoading, setSourcesLoading] = useState(false); const [sourcesError, setSourcesError] = useState('');
  const [storyRunId, setStoryRunId] = useState(''); const [stylePresetId, setStylePresetId] = useState<number | null>(null); const [creating, setCreating] = useState(false); const [createdRunId, setCreatedRunId] = useState('');
  const [historyOpen, setHistoryOpen] = useState(false);
  const flowRef = useRef<ImageAgentFlow | null>(null); const selectedKeyRef = useRef(''); const draftRef = useRef<AgentDraft | null>(null); const agentCleanBaselineRef = useRef<AgentDraft | null>(null);
  const modelDraftRef = useRef<ModelDraft>({ providerId: '', updatedAt: null }); const modelCleanBaselineRef = useRef<ModelDraft>({ providerId: '', updatedAt: null });
  const dirtyRef = useRef(false); const confirmOpenRef = useRef(false); const versionRequestRef = useRef(0); const sourceRequestRef = useRef(0); const loadRequestRef = useRef(0); const detailRef = useRef<HTMLElement | null>(null); const onDirtyRef = useRef(onDirtyChange);
  const historyButtonRef = useRef<HTMLButtonElement | null>(null);
  const agentDraftGenerationRef = useRef(0); const styleDraftGenerationRef = useRef(0); const modelDraftGenerationRef = useRef(0);

  const nodes = useMemo(() => flow?.stages.flatMap((stage) => stage.nodes) ?? [], [flow]);
  const selected = useMemo(() => nodes.find((item) => item.key === selectedKey) ?? null, [nodes, selectedKey]);
  const textProviders = useMemo(() => providers.filter(textProvider), [providers]); const imageProviders = useMemo(() => providers.filter(isExecutableImageProvider), [providers]);
  const currentTextProviderValid = !!draft && textProviders.some((item) => item.id === draft.aiProviderId);
  const currentImageProviderValid = imageProviders.some((item) => item.id === modelProviderId);
  const savedImageProviderValid = imageProviders.some((item) => item.id === flow?.config.imageProviderId);
  const enabledStyles = useMemo(() => (flow?.stylePresets ?? []).filter((item) => item.enabled), [flow]);
  const selectedStory = sources.find((item) => item.runId === storyRunId) ?? null;
  const selectedStoryValid = !!selectedStory?.finalStory.trim();
  const selectedStyleValid = stylePresetId !== null && enabledStyles.some((item) => item.id === stylePresetId);
  const savedStyle = styleDraft?.id === null ? null : flow?.stylePresets.find((item) => item.id === styleDraft?.id) ?? null;
  const styleDirty = !!styleDraft && (styleDraft.id === null || !savedStyle || !sameStyle(styleDraft, fromStyle(savedStyle)));
  const styleNegativeMissing = !!styleDraft && !styleDraft.negativePrompt.trim();
  const styleDescriptionMissing = !!styleDraft && !styleDraft.description.trim();
  const modelDirty = !!flow && modelProviderId !== (flow.config.imageProviderId ?? '');
  const pageDirty = dirty || styleDirty || modelDirty;
  const nodeNames = useMemo(() => new Map(nodes.map((item) => [item.key, item.name])), [nodes]);
  const downstreamByNode = useMemo(() => {
    const result = new Map<string, string[]>();
    nodes.forEach((item) => (item.upstream ?? []).forEach((source) => result.set(source, [...(result.get(source) ?? []), item.key])));
    return result;
  }, [nodes]);
  const requiredAgentProblems = useMemo(() => {
    const agents = nodes.filter((item) => item.editable);
    const problems: string[] = [];
    if (agents.length !== 9) problems.push(`必需 Agent 配置不完整（当前 ${agents.length}/9）`);
    agents.forEach((agent) => {
      if (agent.enabled !== true) problems.push(`${agent.name} 已停用`);
      if (!textProviders.some((provider) => provider.id === agent.aiProviderId)) problems.push(`${agent.name} 的文本 Provider 不可用`);
    });
    return problems;
  }, [nodes, textProviders]);

  useEffect(() => { onDirtyRef.current = onDirtyChange; }, [onDirtyChange]);
  useEffect(() => { selectedKeyRef.current = selectedKey; draftRef.current = draft; }, [selectedKey, draft]);
  useEffect(() => { dirtyRef.current = pageDirty; onDirtyRef.current(pageDirty); }, [pageDirty]);
  useEffect(() => () => onDirtyRef.current(false), []);
  useEffect(() => { if (!pageDirty) return; const before = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; }; window.addEventListener('beforeunload', before); return () => window.removeEventListener('beforeunload', before); }, [pageDirty]);

  const loadFlow = useCallback(async () => {
    const request = ++loadRequestRef.current;
    setLoading(true); setLoadError('');
    try {
      const loaded = await getImageAgentFlow(); if (request !== loadRequestRef.current) return; flowRef.current = loaded; setFlow(loaded);
      const initial = loaded.stages.flatMap((stage) => stage.nodes).find((item) => item.editable);
      agentDraftGenerationRef.current += 1; styleDraftGenerationRef.current += 1; modelDraftGenerationRef.current += 1;
      const initialDraft = initial ? fromNode(initial) : null; agentCleanBaselineRef.current = initialDraft; draftRef.current = initialDraft;
      setSelectedKey(initial?.key ?? ''); setDraft(initialDraft); setDirty(false); setStyleDraft(null);
      const providerId = loaded.config.imageProviderId ?? ''; const modelDraft = { providerId, updatedAt: loaded.config.updatedAt }; modelDraftRef.current = modelDraft; modelCleanBaselineRef.current = modelDraft; setModelProviderId(providerId); setModelUpdatedAt(loaded.config.updatedAt);
    } catch (error) { if (request === loadRequestRef.current) setLoadError(errorText(error)); } finally { if (request === loadRequestRef.current) setLoading(false); }
  }, []);
  useEffect(() => { void loadFlow(); return () => { loadRequestRef.current += 1; versionRequestRef.current += 1; sourceRequestRef.current += 1; }; }, [loadFlow]);

  const mergeNode = (replacement: ImageAgentNode) => {
    const current = flowRef.current; if (!current) return;
    const next = { ...current, stages: current.stages.map((stage) => ({ ...stage, nodes: stage.nodes.map((item) => item.key === replacement.key ? replacement : item) })) };
    flowRef.current = next; setFlow(next);
  };
  const replaceStyles = (styles: ImageStylePreset[]) => { const current = flowRef.current; if (!current) return; const next = { ...current, stylePresets: styles }; flowRef.current = next; setFlow(next); };
  const scrollDetail = () => { if (!window.matchMedia?.('(max-width: 1320px)').matches) return; window.requestAnimationFrame(() => detailRef.current?.scrollIntoView?.({ block: 'start', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })); };
  const selectNodeNow = (node: ImageAgentNode) => { const nextDraft = node.editable ? fromNode(node) : null; agentDraftGenerationRef.current += 1; agentCleanBaselineRef.current = nextDraft; draftRef.current = nextDraft; versionRequestRef.current += 1; setVersionsOpen(false); setSelectedKey(node.key); setDraft(nextDraft); setDirty(false); scrollDetail(); };
  const discardUnsaved = () => {
    agentDraftGenerationRef.current += 1; styleDraftGenerationRef.current += 1; modelDraftGenerationRef.current += 1;
    const persisted = flowRef.current;
    const persistedNode = persisted?.stages.flatMap((stage) => stage.nodes).find((item) => item.key === selectedKeyRef.current);
    const nextDraft = persistedNode?.editable ? fromNode(persistedNode) : null; agentCleanBaselineRef.current = nextDraft; draftRef.current = nextDraft; setDraft(nextDraft); setDirty(false); setStyleDraft(null);
    const modelDraft = { providerId: persisted?.config.imageProviderId ?? '', updatedAt: persisted?.config.updatedAt ?? null }; modelDraftRef.current = modelDraft; modelCleanBaselineRef.current = modelDraft; setModelProviderId(modelDraft.providerId); setModelUpdatedAt(modelDraft.updatedAt);
  };
  const confirmDiscard = (action: () => void) => {
    if (!dirtyRef.current) { action(); return; } if (confirmOpenRef.current) return; confirmOpenRef.current = true;
    modal.confirm({ title: '离开未保存的 Agent？', content: '当前配置尚未保存，继续将丢失这些修改。', okText: '确定', cancelText: '取消', onOk: () => { discardUnsaved(); action(); }, onCancel: () => undefined, afterClose: () => { confirmOpenRef.current = false; } });
  };
  const changeTab = (next: TabKey) => { if (next !== tab) confirmDiscard(() => setTab(next)); };
  const updateDraft = <K extends keyof AgentDraft>(key: K, value: AgentDraft[K]) => { if (!draft || !selected) return; const next = { ...draft, [key]: value }; draftRef.current = next; setDraft(next); setDirty(!sameDraft(next, fromNode(selected))); };

  const saveAgent = async () => {
    if (!selected || !draft || saving) return; const prompt = draft.systemPrompt.trim();
    if (!prompt) return void message.error('System Prompt 不能为空'); if (prompt.length > 20_000) return void message.error('System Prompt 不能超过 20000 字符');
    if (!currentTextProviderValid) return void message.error('请选择已启用的文本生成 Provider'); if (!Number.isFinite(draft.temperature) || draft.temperature < 0 || draft.temperature > 2) return void message.error('Temperature 必须在 0 到 2 之间');
    const key = selected.key; const submitted = { ...draft }; const generation = agentDraftGenerationRef.current; setSaving(true);
    try {
      const saved = await updateImageAgent(key, { systemPrompt: prompt, aiProviderId: draft.aiProviderId, temperature: draft.temperature, enabled: draft.enabled, updatedAt: draft.updatedAt }); mergeNode(saved);
      const current = draftRef.current;
      if (selectedKeyRef.current === key && current) {
        const cleanBaseline = agentCleanBaselineRef.current; const savedDraft = fromNode(saved);
        const canSynchronize = generation === agentDraftGenerationRef.current ? sameDraft(current, submitted) : !!cleanBaseline && sameDraft(current, cleanBaseline);
        agentCleanBaselineRef.current = savedDraft;
        if (canSynchronize) { draftRef.current = savedDraft; setDraft(savedDraft); setDirty(false); }
        else { const next = { ...current, updatedAt: saved.updatedAt }; draftRef.current = next; setDraft(next); setDirty(true); }
      }
      message.success('Agent 配置已保存');
    } catch (error) { message.error(errorText(error)); } finally { setSaving(false); }
  };

  const openVersions = async () => {
    if (!selected?.editable) return; const key = selected.key; const request = ++versionRequestRef.current;
    setVersionsOpen(true); setVersions([]); setVersionsError(''); setVersionsLoading(true);
    try { const result = await getImageAgentVersions(key); if (request === versionRequestRef.current) setVersions([...result].sort((a, b) => b.version - a.version)); }
    catch (error) { if (request === versionRequestRef.current) setVersionsError(errorText(error)); }
    finally { if (request === versionRequestRef.current) setVersionsLoading(false); }
  };
  const closeVersions = () => { versionRequestRef.current += 1; setVersionsOpen(false); };
  const restoreVersion = (version: ImagePromptVersion) => {
    const key = selectedKey; if (!key || restoring !== null) return;
    modal.confirm({ title: `恢复 Prompt v${version.version}？`, content: '恢复会追加一个新的最新版本，不会删除历史记录。', okText: '确定', cancelText: '取消', onOk: async () => {
      setRestoring(version.version);
      try { const latest = flowRef.current?.stages.flatMap((stage) => stage.nodes).find((item) => item.key === key); const restored = await restoreImageAgentVersion(key, version.version, { updatedAt: latest?.updatedAt ?? null }); mergeNode(restored); if (selectedKeyRef.current === key) { const next = fromNode(restored); agentCleanBaselineRef.current = next; setDraft(next); draftRef.current = next; setDirty(false); } closeVersions(); message.success('版本已恢复'); }
      catch (error) { message.error(errorText(error)); throw error; } finally { setRestoring(null); }
    } });
  };

  const selectStyleNow = (preset?: ImageStylePreset) => { styleDraftGenerationRef.current += 1; setStyleDraft(fromStyle(preset)); };
  const editStyle = (preset?: ImageStylePreset) => {
    if (preset && styleDraft?.id === preset.id) return;
    if (styleDirty) confirmDiscard(() => selectStyleNow(preset)); else selectStyleNow(preset);
  };
  const saveStyle = async () => {
    if (!styleDraft || styleSaving) return; const submitted = { ...styleDraft }; const generation = styleDraftGenerationRef.current; const name = submitted.name.trim(); const positivePrompt = submitted.positivePrompt.trim();
    if (!name || !positivePrompt) return void message.error('画风名称和正向风格约束不能为空');
    if (!submitted.negativePrompt.trim()) return void message.error('负向约束不能为空');
    if (!submitted.description.trim()) return void message.error('画风说明不能为空');
    setStyleSaving(true);
    try {
      const saved = submitted.id === null ? await createImageStylePreset({ name, positivePrompt, negativePrompt: submitted.negativePrompt.trim(), description: submitted.description.trim(), enabled: submitted.enabled }) : await updateImageStylePreset(submitted.id, { name, positivePrompt, negativePrompt: submitted.negativePrompt.trim(), description: submitted.description.trim(), enabled: submitted.enabled, updatedAt: submitted.updatedAt });
      const currentStyles = flowRef.current?.stylePresets ?? []; const nextStyles = submitted.id === null ? [...currentStyles, saved] : currentStyles.map((item) => item.id === saved.id ? saved : item); replaceStyles(nextStyles);
      setStyleDraft((current) => {
        if (!current) return current;
        if (generation !== styleDraftGenerationRef.current) return current.id !== null && current.id === saved.id ? { ...current, updatedAt: saved.updatedAt } : current;
        if (sameStyle(current, submitted)) return fromStyle(saved);
        if (submitted.id === null && current.id === null) return { ...current, id: saved.id, updatedAt: saved.updatedAt };
        return current.id === saved.id ? { ...current, updatedAt: saved.updatedAt } : current;
      }); message.success('画风预设已保存');
    } catch (error) { message.error(errorText(error)); } finally { setStyleSaving(false); }
  };

  const saveModel = async () => {
    if (!currentImageProviderValid || modelSaving) return void message.error('请选择同时支持图片生成和多参考图的 Provider'); const submitted = { providerId: modelProviderId, updatedAt: modelUpdatedAt }; const generation = modelDraftGenerationRef.current; setModelSaving(true);
    try {
      const saved = await updateImageFlowConfig({ imageProviderId: submitted.providerId, width: 1536, height: 864, maxShotsPerScene: 5, maxShotsPerStory: 20, updatedAt: submitted.updatedAt }); const currentFlow = flowRef.current; if (currentFlow) { const next = { ...currentFlow, config: saved }; flowRef.current = next; setFlow(next); }
      const currentDraft = modelDraftRef.current; const cleanBaseline = modelCleanBaselineRef.current; const savedDraft = { providerId: saved.imageProviderId ?? '', updatedAt: saved.updatedAt };
      const canSynchronize = generation === modelDraftGenerationRef.current ? sameModelDraft(currentDraft, submitted) : sameModelDraft(currentDraft, cleanBaseline);
      modelCleanBaselineRef.current = savedDraft;
      if (canSynchronize) { modelDraftRef.current = savedDraft; setModelProviderId(savedDraft.providerId); setModelUpdatedAt(savedDraft.updatedAt); }
      else { const nextDraft = { ...currentDraft, updatedAt: saved.updatedAt }; modelDraftRef.current = nextDraft; setModelUpdatedAt(saved.updatedAt); }
      message.success('图片模型已保存');
    }
    catch (error) { message.error(errorText(error)); } finally { setModelSaving(false); }
  };

  const loadSources = async () => {
    const request = ++sourceRequestRef.current; setSourcesLoading(true); setSourcesError('');
    try { const result = await getImageSourceStories(); if (request === sourceRequestRef.current) setSources(result.filter((item) => item.finalStory.trim())); }
    catch (error) { if (request === sourceRequestRef.current) setSourcesError(errorText(error)); } finally { if (request === sourceRequestRef.current) setSourcesLoading(false); }
  };
  useEffect(() => { if (startOpen) void loadSources(); else sourceRequestRef.current += 1; }, [startOpen]);
  const createRun = async () => {
    if (creating) return;
    if (!selectedStoryValid || !selectedStyleValid || !savedImageProviderValid || requiredAgentProblems.length > 0) return void message.error('当前故事、画风或模型配置已失效，请重新选择后再创建');
    setCreating(true);
    try { const created = await createImageRun({ storyRunId, stylePresetId }); setCreatedRunId(created.runId); setStartOpen(false); setHistoryOpen(true); message.success('图片批次已创建'); }
    catch (error) { message.error(errorText(error)); } finally { setCreating(false); }
  };

  if (loading) return <section className="image-agent-workbench image-story-state" aria-label="图片 Agent 工作台"><div aria-label="正在加载图片 Agent 流程"><Skeleton active paragraph={{ rows: 8 }} /></div></section>;
  if (loadError) return <section className="image-agent-workbench image-story-state" aria-label="图片 Agent 工作台"><Alert type="error" showIcon message="图片流程加载失败" description={loadError} action={<Button onClick={() => void loadFlow()}>重新加载</Button>} /></section>;
  if (!flow) return <section className="image-agent-workbench image-story-state" aria-label="图片 Agent 工作台"><Empty description="暂无图片流程" /></section>;

  const relationNames = (keys: string[]) => keys.length ? keys.map((key) => nodeNames.get(key) ?? key).join('、') : '无';

  const renderAgentTab = () => <div className="image-story-agent-grid">
    <div className="image-story-flow" aria-label="图片 Agent 固定流程">
      {flow.stages.map((stage) => <article className="image-story-stage" key={stage.key}>
        <header><span>{String(stage.order).padStart(2, '0')}</span><div><h3>{stage.name}</h3><p>{stage.note}</p></div></header>
        <div className="image-story-nodes">{stage.nodes.map((item) => <button key={item.key} type="button" className={`image-story-node${item.key === selectedKey ? ' is-selected' : ''}${item.nodeKind === 'PROGRAM' ? ' is-program' : ''}`} aria-pressed={item.key === selectedKey} aria-label={`${item.name} ${item.nodeKind === 'AGENT' ? 'Agent' : 'PROGRAM'}`} onClick={() => item.key !== selectedKey && confirmDiscard(() => selectNodeNow(item))}><span>{item.roleType}</span><strong>{item.name}</strong><small>{item.description}</small><em>{item.editable ? `Prompt v${item.promptVersion ?? 0}` : '程序 · 0 Token'}</em></button>)}</div>
      </article>)}
    </div>
    <aside ref={detailRef} className="image-story-editor" aria-label="图片节点详情">{selected ? <>
      <header><Tag>{selected.roleType}</Tag><h3>{selected.name}</h3><p>{selected.description}</p></header>
      <dl className="image-story-relations"><div><dt>上游</dt><dd>{relationNames(selected.upstream ?? [])}</dd></div><div><dt>下游</dt><dd>{relationNames(downstreamByNode.get(selected.key) ?? [])}</dd></div></dl>
      {selected.editable && draft ? <Form layout="vertical">
        <div className="image-story-status"><Form.Item label="启用"><Switch aria-label="启用 Agent" checked={draft.enabled} onChange={(value) => updateDraft('enabled', value)} /></Form.Item><span>{`Prompt v${selected.promptVersion ?? 0} · ${formatDate(selected.updatedAt)}`}</span></div>
        <Form.Item label="System Prompt" required><Input.TextArea aria-label="System Prompt" rows={13} maxLength={20000} showCount value={draft.systemPrompt} onChange={(event) => updateDraft('systemPrompt', event.target.value)} /></Form.Item>
        <div className="image-story-variables"><span>输入变量</span>{selected.variables.map((variable) => <Tag key={variable}>{`{{${variable}}}`}</Tag>)}</div>
        <Form.Item label="文本 Provider" validateStatus={currentTextProviderValid ? undefined : 'error'} help={currentTextProviderValid ? undefined : '当前 Provider 已不可用'}><Select aria-label="文本 Provider" value={draft.aiProviderId || undefined} onChange={(value) => updateDraft('aiProviderId', value)} options={[...(!currentTextProviderValid && draft.aiProviderId ? [{ value: draft.aiProviderId, label: `${draft.aiProviderId}（不可用）`, disabled: true }] : []), ...textProviders.map((item) => ({ value: item.id, label: providerLabel(item) }))]} /></Form.Item>
        <Form.Item label="Temperature"><InputNumber aria-label="Temperature" min={0} max={2} step={0.1} value={draft.temperature} onChange={(value) => updateDraft('temperature', value ?? 0)} /></Form.Item>
        <div className="image-story-editor-actions"><Button onClick={() => void openVersions()}>版本历史</Button><Button type="primary" loading={saving} onClick={() => void saveAgent()}>保存 Agent</Button></div>
      </Form> : <div className="image-story-readonly"><strong>程序节点 · 0 Token</strong><p>该步骤由后端确定性执行，不使用 Prompt，也不会调用文本模型。</p></div>}
    </> : <Empty description="请选择节点" />}</aside>
  </div>;

  const renderStyleTab = () => <section className="image-story-style-panel">
    <header><div><h3>画风预设</h3><p>内置预设也可以编辑，历史批次保留自己的快照。</p></div><Button onClick={() => editStyle()}>新增画风</Button></header>
    <div className="image-story-style-list">{flow.stylePresets.map((preset) => <article key={preset.id} className={!preset.enabled ? 'is-disabled' : ''}><div><strong>{preset.name}</strong><Tag>{preset.enabled ? '启用' : '停用'}</Tag>{preset.builtIn && <Tag>内置</Tag>}</div><p>{preset.description || preset.positivePrompt}</p><Button aria-label={`编辑 ${preset.name}`} onClick={() => editStyle(preset)}>编辑</Button></article>)}</div>
    {styleDraft && <Form layout="vertical" className="image-story-style-form">
      <Form.Item label="画风名称" required><Input aria-label="画风名称" value={styleDraft.name} onChange={(event) => setStyleDraft({ ...styleDraft, name: event.target.value })} /></Form.Item>
      <Form.Item label="正向风格约束" required><Input.TextArea aria-label="正向风格约束" rows={4} value={styleDraft.positivePrompt} onChange={(event) => setStyleDraft({ ...styleDraft, positivePrompt: event.target.value })} /></Form.Item>
      <Form.Item label="负向约束" required validateStatus={styleNegativeMissing ? 'error' : undefined} help={styleNegativeMissing ? '负向约束不能为空' : undefined}><Input.TextArea aria-label="负向约束" rows={3} value={styleDraft.negativePrompt} onChange={(event) => setStyleDraft({ ...styleDraft, negativePrompt: event.target.value })} /></Form.Item>
      <Form.Item label="说明" required validateStatus={styleDescriptionMissing ? 'error' : undefined} help={styleDescriptionMissing ? '画风说明不能为空' : undefined}><Input.TextArea aria-label="画风说明" rows={2} value={styleDraft.description} onChange={(event) => setStyleDraft({ ...styleDraft, description: event.target.value })} /></Form.Item>
      <Form.Item label="启用"><Switch aria-label="启用画风" checked={styleDraft.enabled} onChange={(value) => setStyleDraft({ ...styleDraft, enabled: value })} /></Form.Item>
      <div><Button onClick={() => setStyleDraft(null)}>取消</Button><Button type="primary" loading={styleSaving} onClick={() => void saveStyle()}>保存画风</Button></div>
    </Form>}
  </section>;

  const renderModelTab = () => <section className="image-story-model-panel">
    <h3>图片模型</h3><p>只显示已启用、使用 OpenAI-compatible 协议且同时支持图片生成和多参考图的 Provider。</p>
    {!currentImageProviderValid && modelProviderId && <Alert type="warning" showIcon message="当前图片 Provider 已不可用" />}
    {imageProviders.length === 0 && <Alert type="warning" showIcon message="没有可执行的 OpenAI-compatible 图片 Provider" />}
    <Form layout="vertical"><Form.Item label="图片 Provider" validateStatus={currentImageProviderValid ? undefined : 'error'}><Select aria-label="图片 Provider" value={modelProviderId || undefined} placeholder="选择图片 Provider" onChange={(providerId) => { modelDraftRef.current = { ...modelDraftRef.current, providerId }; setModelProviderId(providerId); }} options={[...(!currentImageProviderValid && modelProviderId ? [{ value: modelProviderId, label: `${modelProviderId}（不可用）`, disabled: true }] : []), ...imageProviders.map((item) => ({ value: item.id, label: providerLabel(item) }))]} /></Form.Item><div className="image-story-fixed-spec"><strong>1536 × 864</strong><span>16:9 横版</span><strong>每 Scene 最多 5 张</strong><strong>全篇最多 20 张</strong></div><Button type="primary" loading={modelSaving} disabled={!currentImageProviderValid} onClick={() => void saveModel()}>保存图片模型</Button></Form>
  </section>;

  const renderStartContent = () => <div className="image-story-start">
    <p>选择已有最终故事和启用画风。九个 Agent 完成规划后，每个分镜只调用一次图片模型。</p>
    {sourcesError && <Alert type="error" showIcon message="故事批次加载失败" description={sourcesError} />}
    {sourcesLoading ? <Spin /> : <Form layout="vertical">
      <Form.Item label="故事批次"><Select aria-label="故事批次" value={storyRunId || undefined} placeholder="选择已有故事批次" onChange={setStoryRunId} options={sources.map((item) => ({ value: item.runId, label: `${formatDate(item.createdAt)} · ${item.targetGrade || '不限制'} · ${item.runId}` }))} /></Form.Item>
      <Form.Item label="画风预设"><Select aria-label="画风预设" value={stylePresetId ?? undefined} placeholder="选择启用的画风" onChange={setStylePresetId} options={enabledStyles.map((item) => ({ value: item.id, label: item.name }))} /></Form.Item>
      {selectedStory && <div className="image-story-source-preview"><div><strong>{selectedStory.targetGrade || '不限制'}</strong>{selectedStory.words.map((word) => <Tag key={`${word.word}-${word.meaning}`}>{word.word}</Tag>)}</div><pre>{selectedStory.finalStory}</pre></div>}
      <Alert type="info" showIcon message="16:9 · 每个 Scene 1–5 张 · 最多 20 张" description="根据故事含义动态拆成多张图片，生成后由人工审核；第一版不自动重绘。" />
      {sources.length === 0 && <Alert type="warning" showIcon message="没有可用的故事批次，请先完成英文故事生成。" />}
      {enabledStyles.length === 0 && <Alert type="warning" showIcon message="没有启用的画风预设，请先启用或新增画风。" />}
      {!!storyRunId && !selectedStoryValid && <Alert type="warning" showIcon message="之前选择的故事已不可用，请重新选择故事批次。" />}
      {stylePresetId !== null && !selectedStyleValid && <Alert type="warning" showIcon message="之前选择的画风已停用或不存在，请重新选择画风。" />}
      {!savedImageProviderValid && <Alert type="warning" showIcon message="尚未配置可用的图片 Provider，请先在图片模型中保存配置。" />}
      {requiredAgentProblems.length > 0 && <Alert type="warning" showIcon message="必需 Agent 尚未就绪" description={<ul>{requiredAgentProblems.map((problem) => <li key={problem}>{problem}</li>)}</ul>} />}
      <Button type="primary" loading={creating} disabled={creating || !selectedStoryValid || !selectedStyleValid || !savedImageProviderValid || requiredAgentProblems.length > 0} onClick={() => void createRun()}>创建图片批次</Button>
    </Form>}
  </div>;

  return <section className="image-agent-workbench" aria-label="图片 Agent 工作台"><header className="image-workbench-head"><div><span className="page-eyebrow">IMAGE STORY WORKBENCH</span><h2>图片工作台</h2><p>优化九个规划 Agent、画风和固定图片模型，并从已有故事创建绘本批次。</p></div><div><Button ref={historyButtonRef} onClick={() => setHistoryOpen(true)}>图片记录</Button><Button onClick={() => confirmDiscard(() => setStartOpen(true))}>开始生成</Button></div></header><div className="image-story-tabs" role="tablist" aria-label="图片工作台页面">{([['agents', 'Agent 配置'], ['styles', '画风预设'], ['model', '图片模型']] as const).map(([key, label]) => <button key={key} type="button" role="tab" aria-selected={tab === key} className={tab === key ? 'is-active' : ''} onClick={() => changeTab(key)}>{label}</button>)}</div>{tab === 'agents' ? renderAgentTab() : tab === 'styles' ? renderStyleTab() : renderModelTab()}
    <Modal title="开始生成图片故事" open={startOpen} footer={null} onCancel={() => setStartOpen(false)} width={820} destroyOnHidden>{renderStartContent()}</Modal>
    <Modal title={`${selected?.name ?? 'Agent'} · 版本历史`} open={versionsOpen} footer={<Button onClick={closeVersions}>关闭</Button>} onCancel={closeVersions} width={760} destroyOnHidden>{versionsLoading ? <div aria-label="正在加载版本历史" className="image-story-version-loading"><Spin /><Skeleton active paragraph={{ rows: 3 }} /></div> : versionsError ? <Alert type="error" showIcon message="版本历史加载失败" description={versionsError} /> : versions.length === 0 ? <Empty description="暂无 Prompt 版本" /> : <div className="image-story-version-list">{versions.map((version) => <article key={version.version}><header><div><strong>{`Prompt v${version.version}`}</strong><span>{formatDate(version.createdAt)}</span></div><Button aria-label={`恢复 Prompt v${version.version}`} loading={restoring === version.version} onClick={() => restoreVersion(version)}>恢复</Button></header><p>{`${version.aiProviderId ?? '无 Provider'} · Temperature ${version.temperature} · ${version.enabled ? '启用' : '停用'}`}</p><pre>{version.systemPrompt}</pre></article>)}</div>}</Modal>
    <ImageRunHistory open={historyOpen} initialRunId={createdRunId || undefined} onClose={() => setHistoryOpen(false)} afterClose={() => historyButtonRef.current?.focus()} />
  </section>;
}
