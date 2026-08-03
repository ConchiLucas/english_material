import { CheckCircleFilled, CodeOutlined, DatabaseOutlined, DeleteOutlined, EditOutlined, PlusOutlined, RobotOutlined, SaveOutlined } from '@ant-design/icons';
import { App as AntApp, Button, Card, Col, Empty, Form, Input, InputNumber, Layout, Menu, Modal, Row, Select, Space, Spin, Switch, Typography, message } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { AIConfig, AIProviderConfigItem, ConnectionConfig, LocalCliConfig, LocalCliConfigItem, createConnection, deleteConnection, getAIConfig, getConnections, getLocalCliConfig, saveAIConfig, saveLocalCliConfig, testConnectionPayload, updateConnection } from './api';

const { Header, Sider, Content } = Layout;
const { Title, Text } = Typography;
type Tab = 'database' | 'ai' | 'cli';
const newProvider = (i: number): AIProviderConfigItem => ({ id: `provider-${i}`, label: '', type: 'openai-compatible', base_url: '', api_key: '', model: '', max_tokens: 4096, enabled: true });
const newCli = (i: number): LocalCliConfigItem => ({ id: `cli-${i}`, label: '', enabled: true, command: '', defaultArgs: [], model: '', reasoningEffort: 'low', workingDirectory: '/Users/conchi/workforce/python_workforce/english_material', timeoutSeconds: 300 });

export default function App() {
  const [tab, setTab] = useState<Tab>('database');
  const [connections, setConnections] = useState<ConnectionConfig[]>([]);
  const [ai, setAi] = useState<AIConfig>({ active: '', providers: [] });
  const [cli, setCli] = useState<LocalCliConfig>({ active: '', configs: [] });
  const [loading, setLoading] = useState(true);
  const [connectionOpen, setConnectionOpen] = useState(false);
  const [editingConnection, setEditingConnection] = useState<ConnectionConfig | null>(null);
  const [connectionForm] = Form.useForm<ConnectionConfig>();
  const [aiForm] = Form.useForm<AIProviderConfigItem>();
  const [cliForm] = Form.useForm<LocalCliConfigItem & { argsText: string }>();
  const [selectedAi, setSelectedAi] = useState('');
  const [selectedCli, setSelectedCli] = useState('');

  const reload = async () => {
    setLoading(true);
    try {
      const [db, providers, commands] = await Promise.all([getConnections(), getAIConfig(), getLocalCliConfig()]);
      setConnections(db); setAi(providers); setCli(commands);
      setSelectedAi((current) => current || providers.active || providers.providers[0]?.id || '');
      setSelectedCli((current) => current || commands.active || commands.configs[0]?.id || '');
    } catch { message.error('加载配置失败，请检查后端服务。'); } finally { setLoading(false); }
  };
  useEffect(() => { void reload(); }, []);
  const currentAi = useMemo(() => ai.providers.find((item) => item.id === selectedAi), [ai, selectedAi]);
  const currentCli = useMemo(() => cli.configs.find((item) => item.id === selectedCli), [cli, selectedCli]);
  useEffect(() => { if (currentAi) aiForm.setFieldsValue(currentAi); }, [currentAi, aiForm]);
  useEffect(() => { if (currentCli) cliForm.setFieldsValue({ ...currentCli, argsText: currentCli.defaultArgs.join(' ') }); }, [currentCli, cliForm]);

  const saveConnection = async () => {
    const value = await connectionForm.validateFields();
    try {
      if (editingConnection?.ID) await updateConnection({ ...value, ID: editingConnection.ID });
      else await createConnection(value);
      message.success('数据库配置已保存'); setConnectionOpen(false); await reload();
    } catch { message.error('数据库配置保存失败'); }
  };
  const updateAiDraft = () => {
    const value = aiForm.getFieldsValue();
    if (!currentAi || !value.id) return;
    setAi((old) => ({ ...old, providers: old.providers.map((item) => item.id === selectedAi ? { ...item, ...value } : item) }));
  };
  const updateCliDraft = () => {
    const value = cliForm.getFieldsValue();
    if (!currentCli || !value.id) return;
    setCli((old) => ({ ...old, configs: old.configs.map((item) => item.id === selectedCli ? { ...item, ...value, defaultArgs: (value.argsText || '').split(/\s+/).filter(Boolean) } : item) }));
  };
  const saveAi = async () => { updateAiDraft(); const next = { ...ai }; try { await saveAIConfig(next); message.success('AI 配置已保存'); await reload(); } catch { message.error('AI 配置保存失败'); } };
  const saveCli = async () => { updateCliDraft(); const next = { ...cli }; try { await saveLocalCliConfig(next); message.success('CLI 配置已保存'); await reload(); } catch { message.error('CLI 配置保存失败'); } };
  const renderDatabase = () => <section className="panel-page"><div className="page-head"><div><Title level={3}>环境数据库配置</Title><Text type="secondary">管理本项目可用的数据库连接。</Text></div><Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditingConnection(null); connectionForm.resetFields(); connectionForm.setFieldsValue({ connectionType: 'postgresql', port: 5432 }); setConnectionOpen(true); }}>新增数据源</Button></div>{connections.length ? <Row gutter={[16, 16]}>{connections.map((item) => <Col xs={24} xl={12} key={item.ID}><Card className="selection-card"><div className="card-row"><Space><DatabaseOutlined /><Title level={5}>{item.connectionName}</Title></Space><Space><Button icon={<EditOutlined />} onClick={() => { setEditingConnection(item); connectionForm.setFieldsValue(item); setConnectionOpen(true); }} /><Button danger icon={<DeleteOutlined />} onClick={() => Modal.confirm({ title: '删除数据库配置', onOk: async () => { await deleteConnection(item.ID!); await reload(); } })} /></Space></div><div className="meta-grid"><Text>Host: {item.connectionUrl}</Text><Text>Port: {item.port}</Text><Text>DB: {item.databaseName}</Text><Text>User: {item.dbLoginName}</Text></div></Card></Col>)}</Row> : <Empty description="暂无数据库配置" />}</section>;
  const editor = (kind: 'ai' | 'cli') => {
    const isAi = kind === 'ai'; const items = isAi ? ai.providers : cli.configs; const selected = isAi ? currentAi : currentCli;
    const add = () => { if (isAi) { const item = newProvider(items.length + 1); setAi((old) => ({ active: old.active || item.id, providers: [...old.providers, item] })); setSelectedAi(item.id); } else { const item = newCli(items.length + 1); setCli((old) => ({ active: old.active || item.id, configs: [...old.configs, item] })); setSelectedCli(item.id); } };
    const remove = () => { if (!selected) return; if (isAi) { const rest = ai.providers.filter((x) => x.id !== selected.id); setAi({ active: rest[0]?.id || '', providers: rest }); setSelectedAi(rest[0]?.id || ''); } else { const rest = cli.configs.filter((x) => x.id !== selected.id); setCli({ active: rest[0]?.id || '', configs: rest }); setSelectedCli(rest[0]?.id || ''); } };
    const setDefault = () => { if (!selected) return; if (isAi) setAi((old) => ({ ...old, active: selected.id })); else setCli((old) => ({ ...old, active: selected.id })); };
    return <section className="panel-page">
      <div className="page-head"><div><Title level={3}>{isAi ? 'AI 配置' : '本地 CLI 配置'}</Title><Text type="secondary">{isAi ? '管理模型厂商与默认选项。' : '维护本地命令行工具及默认项。'}</Text></div><Space><Button icon={<PlusOutlined />} onClick={add}>添加 {isAi ? 'AI' : 'CLI'}</Button><Button type="primary" icon={<SaveOutlined />} onClick={isAi ? saveAi : saveCli}>保存配置</Button></Space></div>
      {!items.length ? <Empty description="暂无配置"><Button type="primary" onClick={add}>添加配置</Button></Empty> : <div className="ai-layout">
        <div className="ai-list">{items.map((item) => <button type="button" key={item.id} className={(isAi ? selectedAi : selectedCli) === item.id ? 'ai-item active' : 'ai-item'} onClick={() => { if (isAi) { updateAiDraft(); setSelectedAi(item.id); } else { updateCliDraft(); setSelectedCli(item.id); } }}><div><Text strong>{item.label || item.id}</Text><Text type="secondary">{isAi ? (item as AIProviderConfigItem).model : (item as LocalCliConfigItem).command}</Text></div>{(isAi ? ai.active : cli.active) === item.id && <CheckCircleFilled className="active-mark" />}</button>)}</div>
        <Card className="ai-editor">{selected && <><div className="card-row editor-head"><Title level={4}>{selected.label || selected.id}</Title><Space><Button onClick={setDefault}>设为默认</Button><Button danger icon={<DeleteOutlined />} onClick={remove}>删除</Button></Space></div>
          {isAi ? <Form form={aiForm} layout="vertical" onValuesChange={updateAiDraft}><Row gutter={16}><Col span={12}><Form.Item label="配置 ID" name="id" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item label="显示名称" name="label"><Input /></Form.Item></Col><Col span={12}><Form.Item label="接口类型" name="type"><Select options={['openai-compatible', 'anthropic-compatible', 'mimo-tts'].map((value) => ({ value, label: value }))} /></Form.Item></Col><Col span={12}><Form.Item label="模型名称" name="model" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={24}><Form.Item label="Base URL" name="base_url" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item label="API Key" name="api_key"><Input.Password autoComplete="off" /></Form.Item></Col><Col span={12}><Form.Item label="最大 Tokens" name="max_tokens"><InputNumber min={1} className="full-field" /></Form.Item></Col></Row></Form> : <Form form={cliForm} layout="vertical" onValuesChange={updateCliDraft}><Row gutter={16}><Col span={6}><Form.Item label="启用" name="enabled" valuePropName="checked"><Switch /></Form.Item></Col><Col span={9}><Form.Item label="配置 ID" name="id" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={9}><Form.Item label="显示名称" name="label"><Input /></Form.Item></Col><Col span={12}><Form.Item label="命令路径" name="command" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item label="默认参数" name="argsText"><Input /></Form.Item></Col><Col span={12}><Form.Item label="模型" name="model"><Input /></Form.Item></Col><Col span={12}><Form.Item label="推理强度" name="reasoningEffort"><Select options={['low', 'medium', 'high', 'xhigh'].map((value) => ({ value, label: value }))} /></Form.Item></Col><Col span={16}><Form.Item label="默认工作目录" name="workingDirectory"><Input /></Form.Item></Col><Col span={8}><Form.Item label="超时（秒）" name="timeoutSeconds"><InputNumber min={1} className="full-field" /></Form.Item></Col></Row></Form>}
        </>}</Card></div>}
    </section>;
  };
  return <AntApp><Layout className="app-shell"><Header className="top-header"><div className="brand"><CodeOutlined /><span>English Material</span><Text type="secondary">配置中心</Text></div></Header><Layout><Sider width={280} className="app-sider"><Title level={4}>配置管理</Title><Text type="secondary">连接、模型与本地工具</Text><Menu mode="inline" selectedKeys={[tab]} onClick={({ key }) => setTab(key as Tab)} items={[{ key: 'database', icon: <DatabaseOutlined />, label: '数据库配置' }, { key: 'ai', icon: <RobotOutlined />, label: 'AI 配置' }, { key: 'cli', icon: <CodeOutlined />, label: '本地 CLI 配置' }]} /></Sider><Content className="app-content"><Spin spinning={loading}>{tab === 'database' ? renderDatabase() : editor(tab)}</Spin></Content></Layout><Modal title={editingConnection ? '编辑数据源' : '新增数据源'} open={connectionOpen} onCancel={() => setConnectionOpen(false)} onOk={() => void saveConnection()} width={720}><Form form={connectionForm} layout="vertical"><Row gutter={16}><Col span={12}><Form.Item label="连接名称" name="connectionName" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item label="数据库类型" name="connectionType" rules={[{ required: true }]}><Select options={['postgresql','mysql','mssql','oracle','sqlite'].map((x) => ({ value: x, label: x }))} /></Form.Item></Col><Col span={12}><Form.Item label="Host 地址" name="connectionUrl" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item label="端口" name="port" rules={[{ required: true }]}><InputNumber className="full-field" /></Form.Item></Col><Col span={12}><Form.Item label="数据库名" name="databaseName" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item label="环境名称" name="envName"><Input placeholder="local / dev / prod" /></Form.Item></Col><Col span={12}><Form.Item label="用户名" name="dbLoginName" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item label="密码" name="dbLoginPassword"><Input.Password autoComplete="off" /></Form.Item></Col></Row><Button onClick={async () => { try { await testConnectionPayload(await connectionForm.validateFields()); message.success('连接成功'); } catch { message.error('连接失败'); } }}>测试连接</Button></Form></Modal></Layout></AntApp>;
}
