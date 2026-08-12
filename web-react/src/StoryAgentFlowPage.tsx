import {
  Alert,
  App as AntApp,
  Button,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Skeleton,
  Spin,
  Switch,
  Tag,
} from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  getStoryAgentFlow,
  getStoryAgentVersions,
  restoreStoryAgentVersion,
  updateStoryAgent,
  updateStoryFlowBudget,
} from './api';
import type { AIProviderConfigItem } from './api';
import type {
  StoryAgentFlow,
  StoryAgentNode,
  StoryFlowBudget,
  StoryPromptVersion,
} from './story-flow-types';

interface StoryAgentFlowPageProps {
  providers: AIProviderConfigItem[];
  onDirtyChange: (dirty: boolean) => void;
}

interface AgentDraft {
  systemPrompt: string;
  aiProviderId: string;
  temperature: number;
  enabled: boolean;
  updatedAt: string | null;
}

type BudgetField = Exclude<keyof StoryFlowBudget, 'updatedAt'>;

const roleLabels: Record<string, string> = {
  CREATOR: '创作',
  PLANNER: '策划',
  WRITER: '写作',
  REVIEWER: '审核',
  DECIDER: '决策',
  PITCH: '创意提案',
  DIRECTOR: '导演',
  SCORER: '评分审核',
  REVISER: '修订',
  VALIDATOR: '规则校验',
  SNAPSHOT: '快照',
  CONTROLLER: '流程控制',
  HUMAN_REVIEW: '人工审核',
  INPUT: '输入',
  PROGRAM: '程序',
  HUMAN: '人工',
};

const budgetRules: Record<BudgetField, { min: number; max: number }> = {
  maxQualityRounds: { min: 1, max: 20 },
  maxLocalRevisions: { min: 0, max: 20 },
  maxWriterRewrites: { min: 0, max: 20 },
  maxDirectorReturns: { min: 0, max: 20 },
  maxPitchReturns: { min: 0, max: 20 },
  maxPlanReturns: { min: 0, max: 20 },
  maxTotalTokens: { min: 1000, max: 10000000 },
};

const draftFromNode = (node: StoryAgentNode): AgentDraft => ({
  systemPrompt: node.systemPrompt ?? '',
  aiProviderId: node.aiProviderId ?? '',
  temperature: node.temperature ?? 0.7,
  enabled: node.enabled !== false,
  updatedAt: node.updatedAt ?? null,
});

const draftsEqual = (left: AgentDraft, right: AgentDraft) => (
  left.systemPrompt === right.systemPrompt
  && left.aiProviderId === right.aiProviderId
  && left.temperature === right.temperature
  && left.enabled === right.enabled
);

const errorText = (error: unknown) => error instanceof Error ? error.message : '请求失败，请稍后重试';

const formatDate = (value?: string | null) => {
  if (!value) return '未记录';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
};

const parallelLabel = (group: string) => {
  if (group === 'pitch' || group === 'story-pitches') return '三个匿名创意提案并行';
  if (group === 'reviews' || group === 'quality-reviewers') return '三位独立审核员并行';
  return '并行节点';
};

const providerSupportsText = (provider: AIProviderConfigItem) => (
  provider.enabled !== false
  && (provider.capabilities ?? []).some((capability) => capability.toUpperCase() === 'TEXT_GENERATION')
);

const providerLabel = (provider: AIProviderConfigItem) => {
  const name = provider.label || provider.id;
  return provider.model ? `${name} · ${provider.model}` : name;
};

export default function StoryAgentFlowPage({ providers, onDirtyChange }: StoryAgentFlowPageProps) {
  const { message, modal } = AntApp.useApp();
  const [flow, setFlow] = useState<StoryAgentFlow | null>(null);
  const [selectedKey, setSelectedKey] = useState('');
  const [draft, setDraft] = useState<AgentDraft | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [versionsOpen, setVersionsOpen] = useState(false);
  const [versions, setVersions] = useState<StoryPromptVersion[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [versionsError, setVersionsError] = useState('');
  const [restoringVersion, setRestoringVersion] = useState<number | null>(null);
  const [budgetOpen, setBudgetOpen] = useState(false);
  const [budgetDraft, setBudgetDraft] = useState<StoryFlowBudget | null>(null);
  const [budgetSaving, setBudgetSaving] = useState(false);
  const switchConfirmOpen = useRef(false);
  const onDirtyChangeRef = useRef(onDirtyChange);

  const allNodes = useMemo(() => flow?.stages.flatMap((stage) => stage.nodes) ?? [], [flow]);
  const selectedNode = useMemo(
    () => allNodes.find((node) => node.key === selectedKey) ?? null,
    [allNodes, selectedKey],
  );
  const nodeNames = useMemo(
    () => new Map(allNodes.map((node) => [node.key, node.name])),
    [allNodes],
  );
  const validProviders = useMemo(() => providers.filter(providerSupportsText), [providers]);
  const providerIsValid = !!draft && validProviders.some((provider) => provider.id === draft.aiProviderId);

  useEffect(() => {
    onDirtyChangeRef.current = onDirtyChange;
  }, [onDirtyChange]);

  useEffect(() => {
    onDirtyChangeRef.current(dirty);
  }, [dirty]);

  useEffect(() => () => onDirtyChangeRef.current(false), []);

  useEffect(() => {
    if (!dirty) return undefined;
    const preventUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', preventUnload);
    return () => window.removeEventListener('beforeunload', preventUnload);
  }, [dirty]);

  const selectNode = useCallback((node: StoryAgentNode) => {
    setSelectedKey(node.key);
    setDraft(node.editable ? draftFromNode(node) : null);
    setDirty(false);
  }, []);

  const loadFlow = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const loaded = await getStoryAgentFlow();
      setFlow(loaded);
      const nodes = loaded.stages.flatMap((stage) => stage.nodes);
      const initial = nodes.find((node) => node.editable);
      setSelectedKey(initial?.key ?? '');
      setDraft(initial ? draftFromNode(initial) : null);
      setDirty(false);
    } catch (error) {
      setLoadError(errorText(error));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadFlow();
  }, [loadFlow]);

  const replaceNode = useCallback((replacement: StoryAgentNode) => {
    setFlow((current) => current ? {
      ...current,
      stages: current.stages.map((stage) => ({
        ...stage,
        nodes: stage.nodes.map((node) => node.key === replacement.key ? replacement : node),
      })),
    } : current);
    setSelectedKey(replacement.key);
    setDraft(replacement.editable ? draftFromNode(replacement) : null);
    setDirty(false);
  }, []);

  const updateDraft = <K extends keyof AgentDraft>(key: K, value: AgentDraft[K]) => {
    if (!draft || !selectedNode) return;
    const next = { ...draft, [key]: value };
    setDraft(next);
    setDirty(!draftsEqual(next, draftFromNode(selectedNode)));
  };

  const handleNodeClick = (node: StoryAgentNode) => {
    if (node.key === selectedKey || switchConfirmOpen.current) return;
    if (!dirty) {
      selectNode(node);
      return;
    }
    switchConfirmOpen.current = true;
    modal.confirm({
      title: '切换 Agent？',
      content: '当前未保存修改将丢失。是否继续切换？',
      okText: '确定',
      cancelText: '取消',
      onOk: () => {
        selectNode(node);
        switchConfirmOpen.current = false;
      },
      onCancel: () => {
        switchConfirmOpen.current = false;
      },
      afterClose: () => {
        switchConfirmOpen.current = false;
      },
    });
  };

  const handleSave = async () => {
    if (!selectedNode || !draft) return;
    const prompt = draft.systemPrompt.trim();
    if (!prompt) {
      message.error('System Prompt 不能为空');
      return;
    }
    if (!providerIsValid) {
      message.error('请选择已启用的文本生成配置');
      return;
    }
    if (!Number.isFinite(draft.temperature) || draft.temperature < 0 || draft.temperature > 2) {
      message.error('Temperature 必须在 0 到 2 之间');
      return;
    }
    setSaving(true);
    try {
      const saved = await updateStoryAgent(selectedNode.key, {
        systemPrompt: prompt,
        aiProviderId: draft.aiProviderId,
        temperature: draft.temperature,
        enabled: draft.enabled,
        updatedAt: draft.updatedAt,
      });
      replaceNode(saved);
      message.success('提示词已保存');
    } catch (error) {
      message.error(errorText(error));
    } finally {
      setSaving(false);
    }
  };

  const openVersions = async () => {
    if (!selectedNode) return;
    const key = selectedNode.key;
    setVersionsOpen(true);
    setVersions([]);
    setVersionsError('');
    setVersionsLoading(true);
    try {
      const loaded = await getStoryAgentVersions(key);
      setVersions([...loaded].sort((left, right) => right.version - left.version));
    } catch (error) {
      setVersionsError(errorText(error));
    } finally {
      setVersionsLoading(false);
    }
  };

  const confirmRestore = (version: StoryPromptVersion) => {
    if (!selectedNode || restoringVersion !== null) return;
    const key = selectedNode.key;
    const dirtyWarning = dirty ? '当前未保存修改也会丢失。' : '';
    modal.confirm({
      title: `恢复 Prompt v${version.version}？`,
      content: `${dirtyWarning}恢复会生成新的最新版本，且不会删除任何历史版本。`,
      okText: '确定',
      cancelText: '取消',
      onOk: async () => {
        setRestoringVersion(version.version);
        try {
          const restored = await restoreStoryAgentVersion(key, version.version);
          replaceNode(restored);
          setVersionsOpen(false);
          message.success('历史提示词已恢复为最新版本');
        } catch (error) {
          message.error(errorText(error));
          throw error;
        } finally {
          setRestoringVersion(null);
        }
      },
    });
  };

  const openBudget = () => {
    if (!flow) return;
    setBudgetDraft({ ...flow.budget });
    setBudgetOpen(true);
  };

  const saveBudget = async () => {
    if (!budgetDraft) return;
    const payload: StoryFlowBudget = {
      maxQualityRounds: budgetDraft.maxQualityRounds,
      maxLocalRevisions: budgetDraft.maxLocalRevisions,
      maxWriterRewrites: budgetDraft.maxWriterRewrites,
      maxDirectorReturns: budgetDraft.maxDirectorReturns,
      maxPitchReturns: budgetDraft.maxPitchReturns,
      maxPlanReturns: budgetDraft.maxPlanReturns,
      maxTotalTokens: budgetDraft.maxTotalTokens,
    };
    const invalid = (Object.keys(budgetRules) as BudgetField[]).some((key) => {
      const value = payload[key];
      const rule = budgetRules[key];
      return !Number.isInteger(value) || value < rule.min || value > rule.max;
    });
    if (invalid) {
      message.error('请填写范围内的整数预算');
      return;
    }
    setBudgetSaving(true);
    try {
      const saved = await updateStoryFlowBudget(payload);
      setFlow((current) => current ? { ...current, budget: saved } : current);
      setBudgetOpen(false);
      message.success('质量预算已保存');
    } catch (error) {
      message.error(errorText(error));
    } finally {
      setBudgetSaving(false);
    }
  };

  const relatedNames = (keys: string[]) => (
    keys.length ? keys.map((key) => nodeNames.get(key) ?? key).join('、') : '无'
  );

  const renderNode = (node: StoryAgentNode) => {
    const selected = node.key === selectedKey;
    const provider = providers.find((item) => item.id === node.aiProviderId);
    return (
      <button
        type="button"
        className={`story-agent-node story-agent-node--${node.nodeKind.toLowerCase()}${selected ? ' is-selected' : ''}`}
        aria-pressed={selected}
        aria-label={`${node.name} ${node.nodeKind === 'AGENT' ? 'Agent' : node.nodeKind}`}
        onClick={() => handleNodeClick(node)}
        key={node.key}
      >
        <span className="story-agent-node-topline">
          <span className="story-agent-role">{roleLabels[node.roleType] ?? node.roleType}</span>
          {selected && <span className="story-agent-selected-mark" aria-hidden="true">✓ 已选</span>}
        </span>
        <strong>{node.name}</strong>
        <span className="story-agent-node-description">{node.description}</span>
        <span className="story-agent-node-meta">
          {node.editable
            ? <>
              <span>{`Prompt v${node.promptVersion ?? 0}`}</span>
              <span>{provider?.label || node.aiProviderId || '未选择 Provider'}</span>
              <span>{node.enabled === false ? '停用' : '启用'}</span>
            </>
            : <span>{`非 Agent · 0 Token`}</span>}
        </span>
      </button>
    );
  };

  const renderStageNodes = (nodes: StoryAgentNode[]) => {
    const content: React.ReactNode[] = [];
    let index = 0;
    while (index < nodes.length) {
      const node = nodes[index];
      if (node.parallelGroup) {
        const grouped: StoryAgentNode[] = [node];
        let nextIndex = index + 1;
        while (nextIndex < nodes.length && nodes[nextIndex].parallelGroup === node.parallelGroup) {
          grouped.push(nodes[nextIndex]);
          nextIndex += 1;
        }
        const label = parallelLabel(node.parallelGroup);
        content.push(
          <div className="story-parallel-group" role="group" aria-label={label} key={`${node.parallelGroup}-${node.key}`}>
            <span className="story-parallel-label">{label}</span>
            <div className="story-parallel-nodes">{grouped.map(renderNode)}</div>
          </div>,
        );
        index = nextIndex;
      } else {
        content.push(renderNode(node));
        index += 1;
      }
    }
    return content;
  };

  if (loading) {
    return (
      <section aria-label="Agent 流程工作台" className="story-workbench story-workbench-state">
        <Skeleton active paragraph={{ rows: 9 }} />
      </section>
    );
  }

  if (loadError) {
    return (
      <section aria-label="Agent 流程工作台" className="story-workbench story-workbench-state">
        <Alert
          type="error"
          showIcon
          message="Agent 流程加载失败"
          description={loadError}
          action={<Button onClick={() => void loadFlow()}>重新加载</Button>}
        />
      </section>
    );
  }

  if (!flow || allNodes.length === 0) {
    return (
      <section aria-label="Agent 流程工作台" className="story-workbench story-workbench-state">
        <Empty description="暂无 Agent 流程节点" />
      </section>
    );
  }

  const agentCount = allNodes.filter((node) => node.editable).length;
  const readonlyCount = allNodes.length - agentCount;

  return (
    <section aria-label="Agent 流程工作台" className="story-workbench">
      <header className="story-workbench-head">
        <div className="story-workbench-title">
          <span className="story-workbench-eyebrow">STORY AGENT ORCHESTRATION</span>
          <h2>英文故事 Agent 流转工作台</h2>
          <p>配置多 Agent 如何层层协作；本版只管理流程与 Prompt，不执行故事。</p>
        </div>
        <div className="story-workbench-summary">
          <div className="story-workbench-stats" aria-label="流程统计">
            <strong>{`${agentCount} 个 Agent`}</strong>
            <span>{`${readonlyCount} 个程序 / 人工节点`}</span>
            <span>{`最多 ${flow.budget.maxQualityRounds} 轮 · ${flow.budget.maxTotalTokens.toLocaleString('en-US')} Token`}</span>
          </div>
          <Button type="primary" onClick={openBudget}>质量预算</Button>
        </div>
      </header>

      <div className="story-workbench-grid">
        <div className="story-flow-canvas" aria-label="故事 Agent 流程画布">
          {flow.stages.map((stage) => (
            <article className={`story-flow-stage story-flow-stage--${stage.key}`} key={stage.key}>
              <header>
                <span className="story-flow-stage-index">{String(stage.order).padStart(2, '0')}</span>
                <div>
                  <h3>{stage.name}</h3>
                  <p>{stage.note}</p>
                  {stage.key === 'quality' && (
                    <p className="story-flow-stage-hint">不通过时按决策定向回退，受预算上限约束</p>
                  )}
                </div>
              </header>
              <div className="story-flow-stage-nodes">{renderStageNodes(stage.nodes)}</div>
            </article>
          ))}
        </div>

        <aside className="story-agent-detail" aria-label="Agent 节点详情">
          {selectedNode ? (
            <>
              <div className="story-agent-detail-head">
                <span className="story-agent-role">{roleLabels[selectedNode.roleType] ?? selectedNode.roleType}</span>
                <span>{selectedNode.editable ? '可编辑 Agent' : '只读节点'}</span>
                <h3>{selectedNode.name}</h3>
                <p>{selectedNode.description}</p>
              </div>

              <dl className="story-agent-relations">
                <div><dt>上游</dt><dd>{relatedNames(selectedNode.upstream)}</dd></div>
                <div><dt>下游</dt><dd>{relatedNames(selectedNode.downstream)}</dd></div>
              </dl>

              {selectedNode.editable && draft ? (
                <Form layout="vertical" className="story-agent-form">
                  <div className="story-agent-form-status">
                    <Form.Item label="启用状态">
                      <Switch
                        aria-label="启用 Agent"
                        checked={draft.enabled}
                        onChange={(value) => updateDraft('enabled', value)}
                      />
                    </Form.Item>
                    <div>
                      <strong>{`Prompt v${selectedNode.promptVersion ?? 0}`}</strong>
                      <span>{`更新于 ${formatDate(selectedNode.updatedAt)}`}</span>
                    </div>
                  </div>
                  <Form.Item label="System Prompt" required>
                    <Input.TextArea
                      aria-label="System Prompt"
                      rows={12}
                      value={draft.systemPrompt}
                      onChange={(event) => updateDraft('systemPrompt', event.target.value)}
                    />
                  </Form.Item>
                  <div className="story-agent-variables" aria-label="动态变量">
                    <span>动态变量</span>
                    <div>
                      {selectedNode.variables.length
                        ? selectedNode.variables.map((variable) => <Tag key={variable}>{`{{${variable}}}`}</Tag>)
                        : <span>无动态变量</span>}
                    </div>
                  </div>
                  <Form.Item
                    label="AI Provider"
                    validateStatus={providerIsValid ? undefined : 'error'}
                    help={providerIsValid ? undefined : '请选择已启用的文本生成配置'}
                  >
                    <Select
                      aria-label="AI Provider"
                      value={draft.aiProviderId || undefined}
                      onChange={(value) => updateDraft('aiProviderId', value)}
                      options={[
                        ...(!providerIsValid && draft.aiProviderId ? [{
                          value: draft.aiProviderId,
                          label: `${draft.aiProviderId}（不可用）`,
                          disabled: true,
                        }] : []),
                        ...validProviders.map((provider) => ({ value: provider.id, label: providerLabel(provider) })),
                      ]}
                    />
                  </Form.Item>
                  <Form.Item label="Temperature">
                    <InputNumber
                      aria-label="Temperature"
                      min={0}
                      max={2}
                      step={0.1}
                      value={draft.temperature}
                      onChange={(value) => updateDraft('temperature', value ?? 0)}
                    />
                  </Form.Item>
                  <div className="story-agent-detail-actions">
                    <Button onClick={() => void openVersions()}>查看版本</Button>
                    <Button type="primary" loading={saving} onClick={() => void handleSave()}>保存提示词</Button>
                  </div>
                </Form>
              ) : (
                <div className="story-readonly-detail">
                  <strong>该节点不使用 Prompt</strong>
                  <p>{`${selectedNode.nodeKind === 'HUMAN' ? '人工节点' : '程序节点'} · 0 Token`}</p>
                  <dl>
                    <div><dt>输入</dt><dd>{selectedNode.variables.length ? selectedNode.variables.join('、') : relatedNames(selectedNode.upstream)}</dd></div>
                    <div><dt>输出</dt><dd>{relatedNames(selectedNode.downstream)}</dd></div>
                  </dl>
                </div>
              )}
            </>
          ) : <Empty description="请选择一个节点" />}
        </aside>
      </div>

      <Modal
        title={selectedNode ? `${selectedNode.name} · 版本历史` : '版本历史'}
        open={versionsOpen}
        width={760}
        footer={<Button onClick={() => setVersionsOpen(false)}>关闭</Button>}
        onCancel={() => setVersionsOpen(false)}
        destroyOnHidden
      >
        {versionsLoading ? (
          <div className="story-version-loading"><Spin /><Skeleton active paragraph={{ rows: 4 }} /></div>
        ) : versionsError ? (
          <Alert type="error" showIcon message="版本历史加载失败" description={versionsError} />
        ) : versions.length === 0 ? (
          <Empty description="暂无 Prompt 版本" />
        ) : (
          <div className="story-version-list">
            {versions.map((version) => (
              <article className="story-version-item" key={version.version}>
                <div className="story-version-head">
                  <div>
                    <strong>{`Prompt v${version.version}`}</strong>
                    <span>{formatDate(version.createdAt)}</span>
                  </div>
                  <Button
                    aria-label={`恢复 Prompt v${version.version}`}
                    loading={restoringVersion === version.version}
                    disabled={restoringVersion !== null && restoringVersion !== version.version}
                    onClick={() => confirmRestore(version)}
                  >恢复</Button>
                </div>
                <div className="story-version-meta">
                  <span>{`Provider：${version.aiProviderId}`}</span>
                  <span>{`Temperature：${version.temperature}`}</span>
                  <span>{version.enabled ? '启用' : '停用'}</span>
                </div>
                <pre>{version.systemPrompt}</pre>
              </article>
            ))}
          </div>
        )}
      </Modal>

      <Modal
        title="质量预算"
        open={budgetOpen}
        onCancel={() => setBudgetOpen(false)}
        footer={[
          <Button key="cancel" onClick={() => setBudgetOpen(false)}>取消</Button>,
          <Button key="save" type="primary" loading={budgetSaving} onClick={() => void saveBudget()}>保存质量预算</Button>,
        ]}
      >
        {budgetDraft && (
          <Form layout="vertical" className="story-budget-form">
            {([
              ['maxQualityRounds', '最大质量轮次', 1],
              ['maxLocalRevisions', '局部修订', 1],
              ['maxWriterRewrites', '正文重写', 1],
              ['maxDirectorReturns', '导演回退', 1],
              ['maxPitchReturns', '创意重做', 1],
              ['maxPlanReturns', '用词重做', 1],
              ['maxTotalTokens', '最大总 Token', 1000],
            ] as Array<[BudgetField, string, number]>).map(([key, label, step]) => (
              <Form.Item label={label} key={key}>
                <InputNumber
                  aria-label={label}
                  min={budgetRules[key].min}
                  max={budgetRules[key].max}
                  step={step}
                  value={budgetDraft[key]}
                  onChange={(value) => setBudgetDraft((current) => current ? {
                    ...current,
                    [key]: value ?? budgetRules[key].min,
                  } : current)}
                />
              </Form.Item>
            ))}
          </Form>
        )}
      </Modal>
    </section>
  );
}
