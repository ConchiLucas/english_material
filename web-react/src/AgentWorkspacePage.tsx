import {
  CheckCircleFilled,
  ExperimentOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
  WarningFilled,
} from '@ant-design/icons';
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Col,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Progress,
  Row,
  Segmented,
  Select,
  Skeleton,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useMemo, useState } from 'react';
import {
  AgentCategory,
  AgentDefinition,
  AgentTestResult,
  LocalCliConfig,
  createAgent,
  getAgentRuns,
  getAgents,
  testAgent,
  updateAgent,
} from './api';

const { Text, Title } = Typography;
const { TextArea } = Input;

const categoryMeta: Record<AgentCategory, { label: string; description: string }> = {
  planning: { label: '规划', description: '分组、词义与故事总策划' },
  creation: { label: '创作', description: '结构、场景、对白与教学强化' },
  review: { label: '审核', description: '难度、自然度、故事与修订' },
  visual: { label: '视觉', description: '分镜、视觉规则与图片质检' },
  learning: { label: '学习', description: '练习与学习材料组装' },
};

const categoryOptions = Object.entries(categoryMeta).map(([value, meta]) => ({ value, label: meta.label }));

const blankAgent = (sortOrder: number, cliId = ''): AgentDefinition => ({
  agentKey: `agent-${sortOrder}`,
  name: '新 Agent',
  category: 'planning',
  description: '',
  aiProviderId: cliId,
  systemPrompt: '你是一名英语学习内容专家。请严格根据输入完成唯一职责。',
  promptTemplate: '请根据以下结构化输入完成任务：\n{{input}}\n\n严格按照输出 Schema 返回 JSON。',
  inputSchema: '{\n  "type": "object",\n  "required": ["stage_profile", "target_words"],\n  "properties": {\n    "stage_profile": { "type": "string" },\n    "target_words": { "type": "array", "minItems": 1 }\n  }\n}',
  outputSchema: '{\n  "type": "object",\n  "required": ["result"],\n  "properties": {\n    "result": { "type": "object" },\n    "notes": { "type": "array" }\n  }\n}',
  hardRules: '不得遗漏目标词 ID；不得擅自改变目标学段；无法完成时必须明确说明。',
  evaluationRubric: '任务符合度 30%，数据完整性 25%，学段适配 20%，自然度 15%，结构清晰度 10%。',
  temperature: 0.4,
  maxTokens: 4096,
  retryLimit: 1,
  sortOrder,
});

const defaultTestInput = '{\n  "stage_profile": "小学三年级上册",\n  "target_words": [\n    { "id": 1, "word": "book", "meaning": "书" },\n    { "id": 2, "word": "friend", "meaning": "朋友" }\n  ],\n  "context": {\n    "story_count": 1,\n    "scene_goal": "两名学生在教室寻找一本书"\n  }\n}';

const statusMeta: Record<AgentTestResult['status'], { label: string; color: string }> = {
  PASSED: { label: '通过', color: 'success' },
  NEEDS_REVISION: { label: '需要修订', color: 'warning' },
  NEEDS_REVIEW: { label: '需要复核', color: 'processing' },
  FAILED: { label: '失败', color: 'error' },
  RUNNING: { label: '运行中', color: 'default' },
};

interface AgentWorkspacePageProps {
  cliConfig: LocalCliConfig;
}

export default function AgentWorkspacePage({ cliConfig }: AgentWorkspacePageProps) {
  const { message, modal } = AntApp.useApp();
  const [form] = Form.useForm<AgentDefinition>();
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [runs, setRuns] = useState<AgentTestResult[]>([]);
  const [selectedId, setSelectedId] = useState<number | 'new' | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [detailTab, setDetailTab] = useState<'config' | 'test' | 'runs'>('config');
  const [category, setCategory] = useState<AgentCategory | 'all'>('all');
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [testInput, setTestInput] = useState(defaultTestInput);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<AgentTestResult | null>(null);

  const defaultCli = useMemo(
    () => cliConfig.configs.find((item) => item.id === cliConfig.active && item.enabled),
    [cliConfig],
  );

  const selected = useMemo(
    () => agents.find((item) => item.ID === selectedId),
    [agents, selectedId],
  );

  const filteredAgents = useMemo(
    () => category === 'all' ? agents : agents.filter((item) => item.category === category),
    [agents, category],
  );

  const load = async () => {
    setLoading(true);
    setLoadError('');
    try {
      const [definitions, history] = await Promise.all([getAgents(), getAgentRuns()]);
      setAgents(definitions);
      setRuns(history);
      setSelectedId((current) => {
        if (current === 'new') return current;
        return definitions.some((item) => item.ID === current) ? current : null;
      });
    } catch {
      setLoadError('无法读取 Agent 工作台数据，请确认后端服务和本项目数据库可用。');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  useEffect(() => {
    if (selectedId === 'new') return;
    const current = agents.find((item) => item.ID === selectedId);
    if (current) {
      form.setFieldsValue(current);
      setDirty(false);
      setTestResult(null);
    }
  }, [agents, selectedId, form]);

  const openAgent = (item: AgentDefinition) => {
    setSelectedId(item.ID || null);
    form.setFieldsValue(item);
    setDirty(false);
    setTestResult(null);
    setDetailTab('config');
    setDrawerOpen(true);
  };

  const addAgent = () => {
    const nextSortOrder = Math.max(0, ...agents.map((item) => item.sortOrder || 0)) + 10;
    const draft = blankAgent(nextSortOrder, defaultCli?.id || '');
    setSelectedId('new');
    form.setFieldsValue(draft);
    setDirty(true);
    setTestResult(null);
    setDetailTab('config');
    setDrawerOpen(true);
  };

  const closeDrawer = () => {
    setDrawerOpen(false);
    setSelectedId(null);
    setDirty(false);
    setTestResult(null);
  };

  const requestCloseDrawer = () => {
    if (!dirty) {
      closeDrawer();
      return;
    }
    modal.confirm({
      title: '放弃未保存的更改？',
      content: '关闭详情后，本次编辑内容将不会保留。',
      okText: '放弃更改',
      cancelText: '继续编辑',
      okButtonProps: { danger: true },
      onOk: closeDrawer,
    });
  };

  const save = async () => {
    const value = await form.validateFields();
    setSaving(true);
    try {
      const saved = selectedId === 'new' ? await createAgent(value) : await updateAgent({ ...value, ID: selectedId || undefined });
      message.success(`Agent“${saved.name}”已保存`);
      setSelectedId(saved.ID || null);
      setDirty(false);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'Agent 保存失败，请检查字段。');
    } finally {
      setSaving(false);
    }
  };

  const runTest = async () => {
    if (selectedId === 'new' || !selectedId) {
      message.warning('请先保存 Agent，再运行测试。');
      return;
    }
    setTesting(true);
    setTestResult(null);
    try {
      const result = await testAgent(selectedId, testInput);
      setTestResult(result);
      setRuns((current) => [result, ...current.filter((item) => item.runId !== result.runId)]);
      if (result.status === 'PASSED') message.success('测试通过');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '测试请求失败。');
    } finally {
      setTesting(false);
    }
  };

  const defaultCliLabel = defaultCli
    ? `${defaultCli.label || defaultCli.id}${defaultCli.model ? ` · ${defaultCli.model}` : ''}`
    : '未配置';

  const agentColumns: ColumnsType<AgentDefinition> = [
    {
      title: 'Agent',
      dataIndex: 'name',
      width: 260,
      render: (value: string, record) => (
        <div className="agent-list-name">
          <strong>{value}</strong>
          <small>{record.agentKey}</small>
        </div>
      ),
    },
    {
      title: '职责分类',
      dataIndex: 'category',
      width: 120,
      render: (value: AgentCategory) => <Tag>{categoryMeta[value].label}</Tag>,
    },
    { title: '职责说明', dataIndex: 'description', ellipsis: true, render: (value: string) => value || '—' },
    { title: '执行 CLI', width: 240, render: () => <Text type="secondary">{defaultCliLabel}</Text> },
    {
      title: '更新时间',
      dataIndex: 'UpdatedAt',
      width: 176,
      render: (value?: string) => value
        ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value))
        : '—',
    },
    {
      title: '操作',
      key: 'actions',
      width: 96,
      fixed: 'right',
      render: (_, record) => <Button type="link" onClick={() => openAgent(record)}>详情</Button>,
    },
  ];

  const renderAgentList = () => (
    <Card
      className="agent-list-card"
      title={<Space size={10}><span>Agent 列表</span><Tag>{filteredAgents.length} 个</Tag></Space>}
      extra={(
        <Segmented
          className="agent-category-filter"
          value={category}
          onChange={(value) => setCategory(value as AgentCategory | 'all')}
          options={[{ value: 'all', label: '全部' }, ...categoryOptions]}
        />
      )}
    >
      <Table
        rowKey={(record) => record.ID || record.agentKey}
        columns={agentColumns}
        dataSource={filteredAgents}
        pagination={false}
        scroll={{ x: 1120 }}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该分类暂无 Agent" /> }}
      />
    </Card>
  );

  const renderEditor = () => (
    <div className="agent-detail-form">
      <Form
        form={form}
        layout="vertical"
        requiredMark="optional"
        onValuesChange={() => setDirty(true)}
      >
        <div className="form-section-title">身份与运行参数</div>
        <Row gutter={16}>
          <Col xs={24} xl={12}>
            <Form.Item label="Agent Key" name="agentKey" rules={[{ required: true, message: '请输入 Agent Key' }]}>
              <Input className="mono-field" placeholder="dialogue-writer" />
            </Form.Item>
          </Col>
          <Col xs={24} xl={12}>
            <Form.Item label="显示名称" name="name" rules={[{ required: true, message: '请输入显示名称' }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} xl={12}>
            <Form.Item label="职责分类" name="category" rules={[{ required: true, message: '请选择分类' }]}>
              <Select options={categoryOptions} />
            </Form.Item>
          </Col>
          <Col xs={24} xl={12}>
            <Form.Item label="执行 CLI" extra="所有 Agent 统一使用“本地 CLI 配置”中当前默认选中的配置。">
              <Input
                readOnly
                value={defaultCli ? `${defaultCli.label || defaultCli.id}${defaultCli.model ? ` · ${defaultCli.model}` : ''}` : ''}
                placeholder="请先配置默认本地 CLI"
              />
            </Form.Item>
            <Form.Item name="aiProviderId" hidden><Input /></Form.Item>
          </Col>
          <Col span={24}>
            <Form.Item label="职责说明" name="description"><Input /></Form.Item>
          </Col>
          <Col xs={24} sm={8}>
            <Form.Item label="Temperature" name="temperature"><InputNumber min={0} max={2} step={0.1} className="full-field" /></Form.Item>
          </Col>
          <Col xs={24} sm={8}>
            <Form.Item label="最大 Tokens" name="maxTokens"><InputNumber min={1} max={32768} className="full-field" /></Form.Item>
          </Col>
          <Col xs={24} sm={8}>
            <Form.Item label="结构失败重试次数" name="retryLimit"><InputNumber min={0} max={3} className="full-field" /></Form.Item>
          </Col>
          <Form.Item name="sortOrder" hidden><InputNumber /></Form.Item>
        </Row>

        <div className="form-section-title">提示词与质量约束</div>
        <Form.Item label="System Prompt" name="systemPrompt" rules={[{ required: true, message: '请输入 System Prompt' }]}>
          <TextArea className="agent-code-editor" autoSize={{ minRows: 6, maxRows: 14 }} />
        </Form.Item>
        <Form.Item label="任务提示词模板" name="promptTemplate" extra="支持 {{input}} 以及测试输入中的顶层字段变量，例如 {{stage_profile}}。" rules={[{ required: true, message: '请输入提示词模板' }]}>
          <TextArea className="agent-code-editor" autoSize={{ minRows: 5, maxRows: 12 }} />
        </Form.Item>
        <Row gutter={16}>
          <Col xs={24} xl={12}>
            <Form.Item label="硬性规则" name="hardRules"><TextArea autoSize={{ minRows: 5, maxRows: 12 }} /></Form.Item>
          </Col>
          <Col xs={24} xl={12}>
            <Form.Item label="评分量表" name="evaluationRubric"><TextArea autoSize={{ minRows: 5, maxRows: 12 }} /></Form.Item>
          </Col>
        </Row>
        <div className="form-section-title">输入与输出契约</div>
        <Row gutter={16}>
          <Col xs={24} xl={12}>
            <Form.Item label="输入 JSON Schema" name="inputSchema" rules={[{ required: true, message: '请输入输入 Schema' }]}>
              <TextArea className="agent-code-editor" autoSize={{ minRows: 10, maxRows: 20 }} spellCheck={false} />
            </Form.Item>
          </Col>
          <Col xs={24} xl={12}>
            <Form.Item label="输出 JSON Schema" name="outputSchema" rules={[{ required: true, message: '请输入输出 Schema' }]}>
              <TextArea className="agent-code-editor" autoSize={{ minRows: 10, maxRows: 20 }} spellCheck={false} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </div>
  );

  const renderTestPanel = () => {
    const meta = testResult ? statusMeta[testResult.status] : null;
    return (
      <section className="agent-detail-test" aria-label="Agent 在线测试">
        <div className="agent-test-head">
          <div><Text strong>在线测试</Text><Text type="secondary">真实调用并记录结果</Text></div>
          <ExperimentOutlined aria-hidden="true" />
        </div>
        {!defaultCli && <Alert type="warning" showIcon message="默认本地 CLI 不可用" description="请在配置管理中启用并选择一个默认本地 CLI；默认配置应为 Codex CLI。" />}
        <label className="agent-test-label" htmlFor="agent-test-input">测试输入 JSON</label>
        <TextArea id="agent-test-input" value={testInput} onChange={(event) => setTestInput(event.target.value)} className="agent-code-editor agent-test-input" spellCheck={false} />
        <Button block type="primary" icon={<ExperimentOutlined />} loading={testing} disabled={!defaultCli || dirty || selectedId === 'new'} onClick={() => void runTest()}>
          运行测试
        </Button>
        {dirty && <Text className="agent-test-hint" type="secondary">请先保存当前 Agent，再使用最新配置测试。</Text>}
        {testResult && meta && (
          <div className="agent-test-result" aria-live="polite">
            <div className="agent-test-score">
              <Progress type="circle" size={74} percent={testResult.overallScore || 0} format={(value) => testResult.overallScore == null ? '—' : value} status={testResult.status === 'FAILED' ? 'exception' : 'normal'} />
              <div><Tag color={meta.color}>{meta.label}</Tag><Text type="secondary">{testResult.durationMs} ms</Text></div>
            </div>
            <div className="agent-schema-status">
              {testResult.schemaValid ? <CheckCircleFilled /> : <WarningFilled />}
              <span>{testResult.schemaValid ? '输出 Schema 校验通过' : '输出 Schema 校验失败'}</span>
            </div>
            <Tabs
              size="small"
              items={[
                { key: 'output', label: '输出', children: <pre className="agent-output-view">{testResult.outputText || '没有可展示的输出。'}</pre> },
                { key: 'scores', label: '评分', children: <div className="agent-score-list">{Object.entries(testResult.dimensionScores).map(([name, score]) => <div key={name}><span>{name}</span><strong>{score}</strong></div>)}{!Object.keys(testResult.dimensionScores).length && <Text type="secondary">暂无维度评分</Text>}</div> },
                { key: 'issues', label: `问题 ${testResult.issues.length}`, children: testResult.issues.length ? <ul className="agent-issue-list">{testResult.issues.map((issue, index) => <li key={`${issue}-${index}`}>{issue}</li>)}</ul> : <Text type="secondary">没有发现问题</Text> },
              ]}
            />
          </div>
        )}
      </section>
    );
  };

  const runColumns: ColumnsType<AgentTestResult> = [
    { title: '时间', dataIndex: 'createdAt', width: 176, render: (value?: string) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' }).format(new Date(value)) : '—' },
    { title: '状态', dataIndex: 'status', width: 120, render: (value: AgentTestResult['status']) => <Tag color={statusMeta[value].color}>{statusMeta[value].label}</Tag> },
    { title: '结构校验', dataIndex: 'schemaValid', width: 112, render: (value: boolean) => value ? <Text type="success">通过</Text> : <Text type="danger">失败</Text> },
    { title: '评分', dataIndex: 'overallScore', width: 88, render: (value?: number) => value == null ? '—' : value },
    { title: '耗时', dataIndex: 'durationMs', width: 110, render: (value: number) => `${value} ms` },
    { title: '诊断', dataIndex: 'errorMessage', ellipsis: true, render: (value: string, record) => value || record.issues[0] || '无' },
  ];

  const selectedRuns = typeof selectedId === 'number'
    ? runs.filter((run) => run.agentId === selectedId)
    : [];

  const renderAgentRuns = () => selectedId === 'new' ? (
    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请先保存 Agent，再查看运行记录" />
  ) : (
    <div className="agent-detail-runs">
      <Table
        rowKey="runId"
        columns={runColumns}
        dataSource={selectedRuns}
        pagination={{ pageSize: 10, showSizeChanger: false }}
        scroll={{ x: 760 }}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该 Agent 暂无运行记录" /> }}
      />
    </div>
  );

  if (loading) return <section className="agent-workspace-page"><Skeleton active paragraph={{ rows: 12 }} /></section>;
  if (loadError) return <section className="agent-workspace-page"><Alert type="error" showIcon message="Agent 工作台加载失败" description={loadError} action={<Button icon={<ReloadOutlined />} onClick={() => void load()}>重新加载</Button>} /></section>;

  return (
    <section className="agent-workspace-page" aria-labelledby="agent-workspace-title">
      <div className="page-head agent-page-head">
        <div className="page-title-block">
          <Text className="page-eyebrow">AGENT WORKBENCH</Text>
          <Title id="agent-workspace-title" level={2}>Agent 工作台</Title>
          <Text type="secondary">从列表进入详情，集中编辑、测试并追踪每个 Agent 的质量结果。</Text>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={addAgent}>新增 Agent</Button>
      </div>
      {renderAgentList()}
      <Drawer
        className="agent-detail-drawer"
        title={selectedId === 'new' ? '新增 Agent' : selected?.name || 'Agent 详情'}
        width="min(1040px, 96vw)"
        open={drawerOpen}
        onClose={requestCloseDrawer}
        extra={(
          <Space>
            {dirty && <Tag color="warning">有未保存更改</Tag>}
            <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={() => void save()}>
              保存 Agent
            </Button>
          </Space>
        )}
      >
        <Tabs
          activeKey={detailTab}
          onChange={(key) => setDetailTab(key as typeof detailTab)}
          items={[
            { key: 'config', label: '配置编辑', children: renderEditor() },
            { key: 'test', label: '在线测试', children: renderTestPanel() },
            { key: 'runs', label: '运行记录', children: renderAgentRuns() },
          ]}
        />
      </Drawer>
    </section>
  );
}
