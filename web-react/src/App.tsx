import {
  ApartmentOutlined,
  CheckCircleFilled,
  BookOutlined,
  CodeOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  PictureOutlined,
  ReloadOutlined,
  RobotOutlined,
  SaveOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Col,
  Empty,
  Form,
  Input,
  InputNumber,
  Layout,
  Menu,
  Modal,
  Row,
  Select,
  Skeleton,
  Space,
  Switch,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  AIConfig,
  AIProviderConfigItem,
  ConnectionConfig,
  LocalCliConfig,
  LocalCliConfigItem,
  createConnection,
  deleteConnection,
  getAIConfig,
  getConnections,
  getLocalCliConfig,
  saveAIConfig,
  saveLocalCliConfig,
  testConnectionPayload,
  updateConnection,
} from './api';
import StoryAgentFlowPage from './StoryAgentFlowPage';
import ImageAgentFlowPage from './ImageAgentFlowPage';
import WordCleanPage from './WordCleanPage';

const { Header, Sider, Content } = Layout;
const { Title, Text } = Typography;

type ConfigTab = 'database' | 'ai' | 'cli';
type WorkspaceSection = 'config' | 'word-clean' | 'agents' | 'image-agents';
type BusyAction = 'connection-save' | 'connection-test' | 'ai-save' | 'cli-save' | `delete-${number}` | null;

const newProvider = (index: number): AIProviderConfigItem => ({
  id: `provider-${index}`,
  label: '',
  type: 'openai-compatible',
  base_url: '',
  api_key: '',
  model: '',
  max_tokens: 4096,
  enabled: true,
});

const newCli = (index: number): LocalCliConfigItem => ({
  id: `cli-${index}`,
  label: '',
  enabled: true,
  command: '',
  defaultArgs: [],
  model: '',
  reasoningEffort: 'low',
  workingDirectory: '/Users/conchi/workforce/python_workforce/english_material',
  timeoutSeconds: 300,
});

const configNavigationItems = [
  { key: 'database', icon: <DatabaseOutlined />, label: '数据库配置' },
  { key: 'ai', icon: <RobotOutlined />, label: 'AI 配置' },
  { key: 'cli', icon: <CodeOutlined />, label: '本地 CLI 配置' },
];

const workspaceNavigationItems = [
  { key: 'config', icon: <SettingOutlined />, label: <><span className="nav-label-full">配置管理</span><span className="nav-label-short">配置</span></> },
  { key: 'word-clean', icon: <BookOutlined />, label: <><span className="nav-label-full">去重单词表</span><span className="nav-label-short">词表</span></> },
  { key: 'agents', icon: <ApartmentOutlined />, label: <><span className="nav-label-full">Agent 工作台</span><span className="nav-label-short">Agent</span></> },
  { key: 'image-agents', icon: <PictureOutlined />, label: <><span className="nav-label-full">图片工作台</span><span className="nav-label-short">图片</span></> },
];

export default function App() {
  const { message, modal } = AntApp.useApp();
  const leaveAgentConfirmOpen = useRef(false);
  const leaveImageAgentConfirmOpen = useRef(false);
  const [section, setSection] = useState<WorkspaceSection>('config');
  const [tab, setTab] = useState<ConfigTab>('database');
  const [connections, setConnections] = useState<ConnectionConfig[]>([]);
  const [ai, setAi] = useState<AIConfig>({ active: '', providers: [] });
  const [cli, setCli] = useState<LocalCliConfig>({ active: '', configs: [] });
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [busy, setBusy] = useState<BusyAction>(null);
  const [connectionOpen, setConnectionOpen] = useState(false);
  const [editingConnection, setEditingConnection] = useState<ConnectionConfig | null>(null);
  const [connectionTest, setConnectionTest] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [connectionForm] = Form.useForm<ConnectionConfig>();
  const [aiForm] = Form.useForm<AIProviderConfigItem>();
  const [cliForm] = Form.useForm<LocalCliConfigItem & { argsText: string }>();
  const [selectedAi, setSelectedAi] = useState('');
  const [selectedCli, setSelectedCli] = useState('');
  const [aiDirty, setAiDirty] = useState(false);
  const [cliDirty, setCliDirty] = useState(false);
  const [agentDirty, setAgentDirty] = useState(false);
  const [imageAgentDirty, setImageAgentDirty] = useState(false);

  const reload = async () => {
    setLoading(true);
    setLoadError('');
    const results = await Promise.all([
      getConnections().then(
        (databaseConnections) => {
          setConnections(databaseConnections);
          return true;
        },
        () => false,
      ),
      getAIConfig().then(
        (providers) => {
          setAi(providers);
          setSelectedAi((current) =>
            providers.providers.some((item) => item.id === current)
              ? current
              : providers.active || providers.providers[0]?.id || '',
          );
          return true;
        },
        () => false,
      ),
      getLocalCliConfig().then(
        (commands) => {
          setCli(commands);
          setSelectedCli((current) =>
            commands.configs.some((item) => item.id === current)
              ? current
              : commands.active || commands.configs[0]?.id || '',
          );
          return true;
        },
        () => false,
      ),
    ]);
    if (results.some((succeeded) => !succeeded)) {
      setLoadError('无法读取配置。请确认后端服务已启动，然后重试。');
    }
    setLoading(false);
  };

  useEffect(() => {
    void reload();
  }, []);

  const currentAi = useMemo(
    () => ai.providers.find((item) => item.id === selectedAi),
    [ai, selectedAi],
  );
  const currentCli = useMemo(
    () => cli.configs.find((item) => item.id === selectedCli),
    [cli, selectedCli],
  );

  useEffect(() => {
    if (tab === 'ai' && currentAi) {
      aiForm.setFieldsValue({
        ...currentAi,
        options: currentAi.options as Record<string, {} | undefined> | undefined,
      });
    }
  }, [tab, currentAi, aiForm]);

  useEffect(() => {
    if (tab === 'cli' && currentCli) {
      cliForm.setFieldsValue({ ...currentCli, argsText: currentCli.defaultArgs.join(' ') });
    }
  }, [tab, currentCli, cliForm]);

  const openNewConnection = () => {
    setEditingConnection(null);
    setConnectionTest(null);
    setConnectionOpen(true);
    window.setTimeout(() => {
      connectionForm.resetFields();
      connectionForm.setFieldsValue({ connectionType: 'postgresql', port: 5432 });
    }, 0);
  };

  const openConnection = (item: ConnectionConfig) => {
    setEditingConnection(item);
    setConnectionTest(null);
    setConnectionOpen(true);
    window.setTimeout(() => {
      connectionForm.resetFields();
      connectionForm.setFieldsValue({ ...item, dbLoginPassword: undefined });
    }, 0);
  };

  const saveConnection = async () => {
    const value = await connectionForm.validateFields();
    setBusy('connection-save');
    try {
      if (editingConnection?.ID) {
        await updateConnection({ ...value, ID: editingConnection.ID });
      } else {
        await createConnection(value);
      }
      message.success(`数据库配置“${value.connectionName}”已保存`);
      setConnectionOpen(false);
      await reload();
    } catch {
      setConnectionTest({ type: 'error', text: '保存失败，请检查字段内容或后端服务后重试。' });
    } finally {
      setBusy(null);
    }
  };

  const testConnection = async () => {
    const value = await connectionForm.validateFields();
    setBusy('connection-test');
    setConnectionTest(null);
    try {
      await testConnectionPayload(value);
      setConnectionTest({
        type: 'success',
        text: `已连接到 ${value.connectionUrl}:${value.port}/${value.databaseName}`,
      });
    } catch {
      setConnectionTest({ type: 'error', text: '连接失败，请检查地址、端口、数据库名和凭据。' });
    } finally {
      setBusy(null);
    }
  };

  const updateAiDraft = () => {
    const value = aiForm.getFieldsValue();
    if (!currentAi || !value.id) return ai;
    const next = {
      ...ai,
      providers: ai.providers.map((item) =>
        item.id === selectedAi ? { ...item, ...value } : item,
      ),
    };
    setAi(next);
    return next;
  };

  const updateCliDraft = () => {
    const value = cliForm.getFieldsValue();
    if (!currentCli || !value.id) return cli;
    const next = {
      ...cli,
      configs: cli.configs.map((item) =>
        item.id === selectedCli
          ? {
              ...item,
              ...value,
              defaultArgs: (value.argsText || '').split(/\s+/).filter(Boolean),
            }
          : item,
      ),
    };
    setCli(next);
    return next;
  };

  const saveAi = async () => {
    const next = updateAiDraft();
    setBusy('ai-save');
    try {
      await saveAIConfig(next);
      setAiDirty(false);
      message.success('AI 配置已保存');
      await reload();
    } catch {
      message.error('AI 配置保存失败，请检查后端服务。');
    } finally {
      setBusy(null);
    }
  };

  const saveCli = async () => {
    const next = updateCliDraft();
    setBusy('cli-save');
    try {
      await saveLocalCliConfig(next);
      setCliDirty(false);
      message.success('CLI 配置已保存');
      await reload();
    } catch {
      message.error('CLI 配置保存失败，请检查后端服务。');
    } finally {
      setBusy(null);
    }
  };

  const confirmDeleteConnection = (item: ConnectionConfig) => {
    modal.confirm({
      title: `删除数据库配置“${item.connectionName}”？`,
      content: '删除后无法从本页面恢复，请确认该连接已不再使用。',
      okText: '确认删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      autoFocusButton: 'cancel',
      onOk: async () => {
        if (!item.ID) return;
        setBusy(`delete-${item.ID}`);
        try {
          await deleteConnection(item.ID);
          message.success(`数据库配置“${item.connectionName}”已删除`);
          await reload();
        } catch {
          message.error('删除失败，请稍后重试。');
        } finally {
          setBusy(null);
        }
      },
    });
  };

  const changeSection = (nextSection: WorkspaceSection) => {
    if (nextSection === section) return;
    if (section === 'agents' && nextSection !== 'agents' && agentDirty) {
      if (leaveAgentConfirmOpen.current) return;
      leaveAgentConfirmOpen.current = true;
      modal.confirm({
        title: '离开 Agent 工作台？',
        content: '尚有未保存修改，离开后将丢失。',
        okText: '确认离开',
        cancelText: '取消',
        autoFocusButton: 'cancel',
        onOk: () => {
          setAgentDirty(false);
          setSection(nextSection);
        },
        afterClose: () => {
          leaveAgentConfirmOpen.current = false;
        },
      });
      return;
    }
    if (section === 'image-agents' && nextSection !== 'image-agents' && imageAgentDirty) {
      if (leaveImageAgentConfirmOpen.current) return;
      leaveImageAgentConfirmOpen.current = true;
      modal.confirm({
        title: '离开图片工作台？',
        content: '尚有未保存修改，离开后将丢失。',
        okText: '确认离开',
        cancelText: '取消',
        autoFocusButton: 'cancel',
        onOk: () => {
          setImageAgentDirty(false);
          setSection(nextSection);
        },
        afterClose: () => {
          leaveImageAgentConfirmOpen.current = false;
        },
      });
      return;
    }
    setSection(nextSection);
  };

  const renderDatabase = () => (
    <section className="panel-page" aria-labelledby="database-title">
      <div className="page-head">
        <div className="page-title-block">
          <Text className="page-eyebrow">CONFIGURATION / DATABASE</Text>
          <Title id="database-title" level={2}>环境数据库配置</Title>
          <Text type="secondary">管理本项目可用的数据库连接与访问凭据。</Text>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={openNewConnection}>
          新增数据源
        </Button>
      </div>

      {connections.length ? (
        <Row gutter={[16, 16]}>
          {connections.map((item) => (
            <Col xs={24} lg={12} xl={8} key={item.ID}>
              <Card className="selection-card">
                <div className="card-row connection-title-row">
                  <div className="resource-title">
                    <span className="resource-icon" aria-hidden="true"><DatabaseOutlined /></span>
                    <div>
                      <Title level={4}>{item.connectionName}</Title>
                      <Space size={6} wrap>
                        <Tag>{item.connectionType}</Tag>
                        {item.envName && <Tag color="blue">{item.envName}</Tag>}
                      </Space>
                    </div>
                  </div>
                  <Space size={4}>
                    <Tooltip title="编辑数据库配置">
                      <Button
                        type="text"
                        icon={<EditOutlined />}
                        aria-label={`编辑数据库配置 ${item.connectionName}`}
                        onClick={() => openConnection(item)}
                      />
                    </Tooltip>
                    <Tooltip title="删除数据库配置">
                      <Button
                        type="text"
                        danger
                        icon={<DeleteOutlined />}
                        loading={busy === `delete-${item.ID}`}
                        aria-label={`删除数据库配置 ${item.connectionName}`}
                        onClick={() => confirmDeleteConnection(item)}
                      />
                    </Tooltip>
                  </Space>
                </div>
                <dl className="meta-grid">
                  <div><dt>主机</dt><dd title={item.connectionUrl}>{item.connectionUrl}</dd></div>
                  <div><dt>端口</dt><dd>{item.port}</dd></div>
                  <div><dt>数据库</dt><dd title={item.databaseName}>{item.databaseName}</dd></div>
                  <div><dt>用户名</dt><dd title={item.dbLoginName}>{item.dbLoginName}</dd></div>
                </dl>
              </Card>
            </Col>
          ))}
        </Row>
      ) : (
        <Card className="empty-panel">
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              <div className="empty-copy">
                <Text strong>暂无数据库配置</Text>
                <Text type="secondary">新增一个数据源后，即可集中维护连接信息与凭据。</Text>
              </div>
            }
          >
            <Button type="primary" icon={<PlusOutlined />} onClick={openNewConnection}>
              新增数据源
            </Button>
          </Empty>
        </Card>
      )}
    </section>
  );

  const renderEditor = (kind: 'ai' | 'cli') => {
    const isAi = kind === 'ai';
    const items = isAi ? ai.providers : cli.configs;
    const selected = isAi ? currentAi : currentCli;
    const isDirty = isAi ? aiDirty : cliDirty;

    const add = () => {
      if (isAi) {
        const item = newProvider(items.length + 1);
        setAi((old) => ({ active: old.active || item.id, providers: [...old.providers, item] }));
        setSelectedAi(item.id);
        setAiDirty(true);
      } else {
        const item = newCli(items.length + 1);
        setCli((old) => ({ active: old.active || item.id, configs: [...old.configs, item] }));
        setSelectedCli(item.id);
        setCliDirty(true);
      }
    };

    const remove = () => {
      if (!selected) return;
      modal.confirm({
        title: `删除配置“${selected.label || selected.id}”？`,
        content: '该配置将在保存后删除。',
        okText: '删除',
        cancelText: '取消',
        okButtonProps: { danger: true },
        autoFocusButton: 'cancel',
        onOk: () => {
          if (isAi) {
            const rest = ai.providers.filter((item) => item.id !== selected.id);
            setAi({ active: ai.active === selected.id ? rest[0]?.id || '' : ai.active, providers: rest });
            setSelectedAi(rest[0]?.id || '');
            setAiDirty(true);
          } else {
            const rest = cli.configs.filter((item) => item.id !== selected.id);
            setCli({ active: cli.active === selected.id ? rest[0]?.id || '' : cli.active, configs: rest });
            setSelectedCli(rest[0]?.id || '');
            setCliDirty(true);
          }
        },
      });
    };

    const setDefault = () => {
      if (!selected) return;
      if (isAi) {
        setAi((old) => ({ ...old, active: selected.id }));
        setAiDirty(true);
      } else {
        setCli((old) => ({ ...old, active: selected.id }));
        setCliDirty(true);
      }
    };

    const selectItem = (id: string) => {
      if (isAi) {
        updateAiDraft();
        setSelectedAi(id);
      } else {
        updateCliDraft();
        setSelectedCli(id);
      }
      window.setTimeout(() => document.querySelector<HTMLElement>('.editor-title')?.focus(), 0);
    };

    return (
      <section className="panel-page" aria-labelledby={`${kind}-title`}>
        <div className="page-head">
          <div className="page-title-block">
            <Text className="page-eyebrow">CONFIGURATION / {isAi ? 'AI' : 'LOCAL CLI'}</Text>
            <div className="title-status-row">
              <Title id={`${kind}-title`} level={2}>{isAi ? 'AI 配置' : '本地 CLI 配置'}</Title>
              {isDirty && <Tag color="warning">有未保存更改</Tag>}
            </div>
            <Text type="secondary">
              {isAi ? '管理模型服务、访问密钥与默认模型。' : '维护本地命令行工具、执行参数与默认项。'}
            </Text>
          </div>
          <Space className="page-actions" wrap>
            <Button icon={<PlusOutlined />} onClick={add}>添加 {isAi ? 'AI' : 'CLI'}</Button>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={busy === (isAi ? 'ai-save' : 'cli-save')}
              disabled={!items.length}
              onClick={() => void (isAi ? saveAi() : saveCli())}
            >
              保存配置
            </Button>
          </Space>
        </div>

        {!items.length ? (
          <Card className="empty-panel">
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={
                <div className="empty-copy">
                  <Text strong>暂无{isAi ? ' AI' : ' CLI'} 配置</Text>
                  <Text type="secondary">添加第一项配置后即可设置默认项。</Text>
                </div>
              }
            >
              <Button type="primary" icon={<PlusOutlined />} onClick={add}>添加配置</Button>
            </Empty>
          </Card>
        ) : (
          <div className="editor-layout">
            <div className="config-list" aria-label={`${isAi ? 'AI' : 'CLI'} 配置列表`}>
              <div className="list-heading">
                <Text strong>配置列表</Text>
                <Text type="secondary">{items.length} 项</Text>
              </div>
              <div className="list-items">
                {items.map((item) => {
                  const active = (isAi ? ai.active : cli.active) === item.id;
                  const selectedItem = (isAi ? selectedAi : selectedCli) === item.id;
                  const detail = isAi
                    ? (item as AIProviderConfigItem).model
                    : (item as LocalCliConfigItem).command;
                  return (
                    <button
                      type="button"
                      key={item.id}
                      className={selectedItem ? 'config-item active' : 'config-item'}
                      aria-pressed={selectedItem}
                      onClick={() => selectItem(item.id)}
                    >
                      <span className="config-item-copy">
                        <span className="config-item-title">{item.label || item.id}</span>
                        <span className="config-item-detail" title={detail}>{detail || '尚未填写详细信息'}</span>
                      </span>
                      {active && (
                        <span className="default-indicator">
                          <CheckCircleFilled aria-hidden="true" />
                          <span>默认</span>
                        </span>
                      )}
                    </button>
                  );
                })}
              </div>
            </div>

            <Card className="config-editor">
              {selected && (
                <>
                  <div className="card-row editor-head">
                    <div>
                      <Text className="editor-kicker">当前配置</Text>
                      <Title className="editor-title" tabIndex={-1} level={3}>
                        {selected.label || selected.id}
                      </Title>
                    </div>
                    <Space wrap>
                      {(isAi ? ai.active : cli.active) === selected.id ? (
                        <Tag icon={<CheckCircleFilled />} color="success">默认配置</Tag>
                      ) : (
                        <Button onClick={setDefault}>设为默认</Button>
                      )}
                      <Button danger icon={<DeleteOutlined />} onClick={remove}>删除</Button>
                    </Space>
                  </div>

                  {isAi ? (
                    <Form
                      key="ai-config-form"
                      form={aiForm}
                      layout="vertical"
                      requiredMark="optional"
                      onValuesChange={() => {
                        updateAiDraft();
                        setAiDirty(true);
                      }}
                    >
                      <div className="form-section-title">基础信息</div>
                      <Row gutter={16}>
                        <Col xs={24} lg={12}>
                          <Form.Item label="配置 ID" name="id" rules={[{ required: true, message: '请输入配置 ID' }]}>
                            <Input className="mono-field" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} lg={12}>
                          <Form.Item label="显示名称" name="label">
                            <Input />
                          </Form.Item>
                        </Col>
                        <Col xs={24} lg={12}>
                          <Form.Item label="接口类型" name="type">
                            <Select options={['openai-compatible', 'anthropic-compatible', 'mimo-tts'].map((value) => ({ value, label: value }))} />
                          </Form.Item>
                        </Col>
                        <Col xs={24} lg={12}>
                          <Form.Item label="模型名称" name="model" rules={[{ required: true, message: '请输入模型名称' }]}>
                            <Input className="mono-field" />
                          </Form.Item>
                        </Col>
                      </Row>
                      <div className="form-section-title">连接与限制</div>
                      <Row gutter={16}>
                        <Col span={24}>
                          <Form.Item label="Base URL" name="base_url" rules={[{ required: true, message: '请输入 Base URL' }]}>
                            <Input className="mono-field" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} lg={12}>
                          <Form.Item label="API Key" name="api_key" extra="保存后不回显；留空表示不覆盖已有密钥。">
                            <Input.Password className="mono-field" autoComplete="new-password" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} lg={12}>
                          <Form.Item label="最大 Tokens" name="max_tokens">
                            <InputNumber min={1} className="full-field" />
                          </Form.Item>
                        </Col>
                      </Row>
                    </Form>
                  ) : (
                    <Form
                      key="cli-config-form"
                      form={cliForm}
                      layout="vertical"
                      requiredMark="optional"
                      onValuesChange={() => {
                        updateCliDraft();
                        setCliDirty(true);
                      }}
                    >
                      <div className="form-section-title">基础信息</div>
                      <Row gutter={16}>
                        <Col xs={24} lg={6}>
                          <Form.Item label="启用" name="enabled" valuePropName="checked">
                            <Switch checkedChildren="启用" unCheckedChildren="停用" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} lg={9}>
                          <Form.Item label="配置 ID" name="id" rules={[{ required: true, message: '请输入配置 ID' }]}>
                            <Input className="mono-field" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} lg={9}>
                          <Form.Item label="显示名称" name="label"><Input /></Form.Item>
                        </Col>
                      </Row>
                      <div className="form-section-title">执行设置</div>
                      <Row gutter={16}>
                        <Col span={24}>
                          <Form.Item label="命令路径" name="command" rules={[{ required: true, message: '请输入命令路径' }]}>
                            <Input className="mono-field" />
                          </Form.Item>
                        </Col>
                        <Col span={24}>
                          <Form.Item label="默认参数" name="argsText" extra="多个参数请使用空格分隔。">
                            <Input className="mono-field" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} lg={12}>
                          <Form.Item label="模型" name="model"><Input className="mono-field" /></Form.Item>
                        </Col>
                        <Col xs={24} lg={12}>
                          <Form.Item label="推理强度" name="reasoningEffort">
                            <Select options={['low', 'medium', 'high', 'xhigh'].map((value) => ({ value, label: value }))} />
                          </Form.Item>
                        </Col>
                        <Col xs={24} lg={16}>
                          <Form.Item label="默认工作目录" name="workingDirectory">
                            <Input className="mono-field" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} lg={8}>
                          <Form.Item label="超时时间（秒）" name="timeoutSeconds">
                            <InputNumber min={1} max={86400} className="full-field" />
                          </Form.Item>
                        </Col>
                      </Row>
                    </Form>
                  )}
                </>
              )}
            </Card>
          </div>
        )}
      </section>
    );
  };

  const content = section === 'agents'
    ? <StoryAgentFlowPage providers={ai.providers} connections={connections} onDirtyChange={setAgentDirty} />
    : section === 'image-agents'
      ? <ImageAgentFlowPage providers={ai.providers} onDirtyChange={setImageAgentDirty} />
    : loading ? (
    <div className="panel-page loading-panel" aria-busy="true" aria-label="正在加载配置">
      <Skeleton active paragraph={{ rows: 8 }} />
    </div>
  ) : loadError ? (
    <div className="panel-page error-panel">
      <Alert
        type="error"
        showIcon
        message="配置加载失败"
        description={loadError}
        action={<Button icon={<ReloadOutlined />} onClick={() => void reload()}>重新加载</Button>}
      />
    </div>
  ) : section === 'word-clean'
    ? <WordCleanPage connections={connections} />
    : tab === 'database'
      ? renderDatabase()
      : renderEditor(tab);

  return (
    <Layout className="app-shell">
      <Header className="top-header">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true"><CodeOutlined /></span>
          <span className="brand-name">English Material</span>
          <span className="brand-subtitle">英语素材工作台</span>
        </div>
        <Menu
          className="primary-nav"
          mode="horizontal"
          theme="dark"
          aria-label="主导航"
          selectedKeys={[section]}
          onClick={({ key }) => changeSection(key as WorkspaceSection)}
          items={workspaceNavigationItems}
        />
      </Header>
      {section === 'config' && (
        <nav className="config-mobile-nav" aria-label="配置管理导航">
          <Menu
            mode="horizontal"
            theme="dark"
            selectedKeys={[tab]}
            onClick={({ key }) => setTab(key as ConfigTab)}
            items={configNavigationItems}
          />
        </nav>
      )}
      <Layout className={`workspace-layout ${section === 'config' ? 'config-workspace' : section === 'agents' ? 'story-workspace' : section === 'image-agents' ? 'image-workspace' : 'word-workspace'}`}>
        {section === 'config' && (
          <Sider width={264} className="app-sider" aria-label="配置管理导航">
            <div className="sidebar-copy">
              <Title level={3}>配置管理</Title>
              <Text type="secondary">连接、模型与本地工具</Text>
            </div>
            <Menu
              mode="inline"
              theme="dark"
              selectedKeys={[tab]}
              onClick={({ key }) => setTab(key as ConfigTab)}
              items={configNavigationItems}
            />
          </Sider>
        )}
        <Content className={`app-content ${section === 'word-clean' ? 'word-workspace-content' : section === 'agents' ? 'story-workspace-content' : section === 'image-agents' ? 'image-workspace-content' : ''}`} role="main">{content}</Content>
      </Layout>

      <Modal
        title={editingConnection ? '编辑数据库连接' : '新增数据库连接'}
        open={connectionOpen}
        onCancel={() => setConnectionOpen(false)}
        width={720}
        destroyOnHidden
        footer={[
          <Button key="test" loading={busy === 'connection-test'} onClick={() => void testConnection()}>
            测试连接
          </Button>,
          <Button key="cancel" onClick={() => setConnectionOpen(false)}>取消</Button>,
          <Button key="save" type="primary" loading={busy === 'connection-save'} onClick={() => void saveConnection()}>
            保存配置
          </Button>,
        ]}
      >
        <Form form={connectionForm} layout="vertical" requiredMark="optional">
          <div className="form-section-title">基础信息</div>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item label="连接名称" name="connectionName" rules={[{ required: true, message: '请输入连接名称' }]}>
                <Input autoFocus />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="数据库类型" name="connectionType" rules={[{ required: true, message: '请选择数据库类型' }]}>
                <Select options={['postgresql', 'mysql', 'mssql', 'oracle', 'sqlite'].map((value) => ({ value, label: value }))} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="环境名称" name="envName"><Input placeholder="local / dev / prod" /></Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="数据库名" name="databaseName" rules={[{ required: true, message: '请输入数据库名' }]}>
                <Input className="mono-field" />
              </Form.Item>
            </Col>
          </Row>
          <div className="form-section-title">连接与凭据</div>
          <Row gutter={16}>
            <Col xs={24} md={16}>
              <Form.Item label="Host 地址" name="connectionUrl" rules={[{ required: true, message: '请输入 Host 地址' }]}>
                <Input className="mono-field" />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item label="端口" name="port" rules={[{ required: true, message: '请输入端口' }]}>
                <InputNumber min={1} max={65535} className="full-field" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="用户名" name="dbLoginName" rules={[{ required: true, message: '请输入用户名' }]}>
                <Input className="mono-field" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                label="密码"
                name="dbLoginPassword"
                extra={editingConnection ? '留空表示不覆盖已保存的密码。' : '密码仅用于建立数据库连接。'}
              >
                <Input.Password className="mono-field" autoComplete="new-password" />
              </Form.Item>
            </Col>
          </Row>
          {connectionTest && (
            <Alert
              className="connection-feedback"
              type={connectionTest.type}
              showIcon
              message={connectionTest.type === 'success' ? '连接测试成功' : '操作未完成'}
              description={connectionTest.text}
            />
          )}
        </Form>
      </Modal>
    </Layout>
  );
}
