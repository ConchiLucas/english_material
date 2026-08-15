import { CheckCircleFilled, DeleteOutlined, PlusOutlined, SaveOutlined } from '@ant-design/icons';
import {
  Alert, App as AntApp, Button, Card, Col, Empty, Form, Input, Row, Select, Space, Switch, Tag, Typography,
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import type { AIConfig, AIProviderConfigItem } from './api';
import { isImageProviderConfig } from './image-provider-policy';

const { Title, Text } = Typography;

interface ImageModelConfigPageProps {
  config: AIConfig;
  saving: boolean;
  onChange: (next: AIConfig) => void;
  onSave: (next: AIConfig) => Promise<void>;
  onBootstrap: (sourceProviderId: string) => Promise<void>;
}

interface ImageProviderFormValue extends Omit<AIProviderConfigItem, 'options' | 'capabilities'> {
  quality: string;
}

const TARGET_ID = 'antigravity-gemini-image';
const fixedOptions = (quality: string) => ({ responseFormat: 'b64_json', quality, size: '1536x864' });

const nextImageProviderId = (providers: AIProviderConfigItem[]) => {
  const ids = new Set(providers.map((provider) => provider.id));
  if (!ids.has(TARGET_ID)) return TARGET_ID;
  let suffix = 2;
  while (ids.has(`${TARGET_ID}-${suffix}`)) suffix += 1;
  return `${TARGET_ID}-${suffix}`;
};

const newImageProvider = (providers: AIProviderConfigItem[]): AIProviderConfigItem => ({
  id: nextImageProviderId(providers),
  label: 'Antigravity Gemini Image',
  type: 'openai-compatible',
  base_url: '',
  api_key: '',
  model: 'gemini-3-pro-image',
  max_tokens: 4096,
  capabilities: ['IMAGE_GENERATION', 'IMAGE_REFERENCE'],
  options: fixedOptions('hd'),
  enabled: true,
});

const toFormValue = (provider: AIProviderConfigItem): ImageProviderFormValue => ({
  ...provider,
  api_key: provider.api_key ?? '',
  quality: typeof provider.options?.quality === 'string' ? provider.options.quality : 'hd',
});

const fromFormValue = (value: ImageProviderFormValue): AIProviderConfigItem => ({
  id: value.id.trim(),
  label: value.label.trim(),
  type: 'openai-compatible',
  base_url: value.base_url.trim().replace(/\/+$/, ''),
  api_key: value.api_key?.trim() ?? '',
  model: value.model.trim(),
  max_tokens: value.max_tokens > 0 ? value.max_tokens : 4096,
  capabilities: ['IMAGE_GENERATION', 'IMAGE_REFERENCE'],
  options: fixedOptions(value.quality),
  enabled: value.enabled !== false,
});

const providerLabel = (provider: AIProviderConfigItem) =>
  `${provider.label || provider.id}${provider.model ? ` · ${provider.model}` : ''}`;

export default function ImageModelConfigPage({
  config, saving, onChange, onSave, onBootstrap,
}: ImageModelConfigPageProps) {
  const { message, modal } = AntApp.useApp();
  const [form] = Form.useForm<ImageProviderFormValue>();
  const imageProviders = useMemo(() => config.providers.filter(isImageProviderConfig), [config.providers]);
  const sourceProviders = useMemo(() => config.providers.filter((provider) => {
    const name = `${provider.id} ${provider.label}`.toLowerCase();
    return !isImageProviderConfig(provider)
      && provider.enabled !== false
      && provider.type?.trim().toLowerCase() === 'openai-compatible'
      && !!provider.base_url?.trim()
      && !!provider.model?.trim()
      && name.includes('antigravity');
  }), [config.providers]);
  const [selectedId, setSelectedId] = useState(imageProviders[0]?.id ?? '');
  const [sourceId, setSourceId] = useState('');
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState('');
  const [bootstrapping, setBootstrapping] = useState(false);
  const selected = imageProviders.find((provider) => provider.id === selectedId) ?? imageProviders[0] ?? null;

  useEffect(() => {
    if (selected && selected.id !== selectedId) setSelectedId(selected.id);
  }, [selected, selectedId]);

  useEffect(() => {
    if (selected) form.setFieldsValue(toFormValue(selected));
    else form.resetFields();
  }, [selected, form]);

  const replaceSelected = (value: ImageProviderFormValue) => {
    if (!selected) return config;
    const normalized = fromFormValue(value);
    const next = {
      ...config,
      providers: config.providers.map((provider) => provider.id === selected.id ? normalized : provider),
    };
    onChange(next);
    if (normalized.id !== selected.id) setSelectedId(normalized.id);
    setDirty(true);
    return next;
  };

  const add = () => {
    const provider = newImageProvider(config.providers);
    onChange({ ...config, providers: [...config.providers, provider] });
    setSelectedId(provider.id);
    setDirty(true);
  };

  const remove = () => {
    if (!selected) return;
    modal.confirm({
      title: `删除图片模型“${selected.label || selected.id}”？`,
      content: '该配置将在保存后删除。',
      okText: '删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: () => {
        const providers = config.providers.filter((provider) => provider.id !== selected.id);
        onChange({ ...config, providers });
        setSelectedId(providers.find(isImageProviderConfig)?.id ?? '');
        setDirty(true);
      },
    });
  };

  const save = async () => {
    if (!selected) return;
    setError('');
    try {
      const values = await form.validateFields();
      const next = replaceSelected(values);
      await onSave(next);
      setDirty(false);
      message.success('图片模型配置已保存');
    } catch (reason) {
      if (reason instanceof Error) setError('图片模型配置保存失败，请检查字段或后端服务。');
    }
  };

  const bootstrap = async () => {
    if (!sourceId) return;
    setBootstrapping(true);
    setError('');
    try {
      await onBootstrap(sourceId);
      message.success('Antigravity 图片模型已添加');
    } catch {
      setError('无法复用该 Antigravity 配置，请检查来源配置。');
    } finally {
      setBootstrapping(false);
    }
  };

  return (
    <section className="panel-page" aria-labelledby="image-model-title">
      <div className="page-head">
        <div className="page-title-block">
          <Text className="page-eyebrow">CONFIGURATION / IMAGE MODEL</Text>
          <div className="title-status-row">
            <Title id="image-model-title" level={2}>图片模型配置</Title>
            {dirty && <Tag color="warning">有未保存更改</Tag>}
          </div>
          <Text type="secondary">管理图片生成与多参考图模型，配置不会调用模型或消耗额度。</Text>
        </div>
        <Space className="page-actions" wrap>
          <Button aria-label="添加图片模型" icon={<PlusOutlined />} onClick={add}>添加图片模型</Button>
          <Button aria-label="保存配置" type="primary" icon={<SaveOutlined />} loading={saving} disabled={!selected} onClick={() => void save()}>
            保存配置
          </Button>
        </Space>
      </div>

      {sourceProviders.length > 0 && !config.providers.some((provider) => provider.id === TARGET_ID) && (
        <Card className="image-model-bootstrap">
          <div>
            <Text strong>复用现有 Antigravity 凭据</Text>
            <Text type="secondary">密钥只在后端复制，不会返回或显示在页面。</Text>
          </div>
          <Space wrap>
            <Select
              aria-label="凭据来源"
              value={sourceId || undefined}
              placeholder="选择 Antigravity Provider"
              onChange={setSourceId}
              options={sourceProviders.map((provider) => ({ value: provider.id, label: providerLabel(provider) }))}
              style={{ minWidth: 300 }}
            />
            <Button loading={bootstrapping} disabled={!sourceId} onClick={() => void bootstrap()}>
              从现有 Antigravity 配置添加
            </Button>
          </Space>
        </Card>
      )}

      {error && <Alert className="image-model-feedback" type="error" showIcon message={error} />}

      {!imageProviders.length ? (
        <Card className="empty-panel">
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无图片模型配置">
            <Button aria-label="添加图片模型" type="primary" icon={<PlusOutlined />} onClick={add}>添加图片模型</Button>
          </Empty>
        </Card>
      ) : (
        <div className="editor-layout">
          <div className="config-list" role="list" aria-label="图片模型配置列表">
            <div className="list-heading"><Text strong>配置列表</Text><Text type="secondary">{imageProviders.length} 项</Text></div>
            <div className="list-items">
              {imageProviders.map((provider) => (
                <div role="listitem" key={provider.id}>
                  <button
                    type="button"
                    className={provider.id === selected?.id ? 'config-item active' : 'config-item'}
                    aria-pressed={provider.id === selected?.id}
                    onClick={() => setSelectedId(provider.id)}
                  >
                    <span className="config-item-copy">
                      <span className="config-item-title">{provider.label || provider.id}</span>
                      <span className="config-item-detail">{provider.model}</span>
                    </span>
                    {provider.enabled !== false && <CheckCircleFilled aria-label="已启用" />}
                  </button>
                </div>
              ))}
            </div>
          </div>

          <Card className="config-editor">
            {selected && (
              <>
                <div className="card-row editor-head">
                  <div><Text className="editor-kicker">当前图片模型</Text><Title className="editor-title" level={3}>{selected.label || selected.id}</Title></div>
                  <Button danger icon={<DeleteOutlined />} onClick={remove}>删除</Button>
                </div>
                <Form<ImageProviderFormValue>
                  form={form}
                  layout="vertical"
                  requiredMark={false}
                  onValuesChange={(_, values) => replaceSelected(values)}
                >
                  <div className="form-section-title">基础信息</div>
                  <Row gutter={16}>
                    <Col xs={24} lg={6}><Form.Item label="启用" name="enabled" valuePropName="checked"><Switch /></Form.Item></Col>
                    <Col xs={24} lg={9}><Form.Item label="配置 ID" name="id" rules={[{ required: true, message: '请输入配置 ID' }]}><Input /></Form.Item></Col>
                    <Col xs={24} lg={9}><Form.Item label="显示名称" name="label"><Input /></Form.Item></Col>
                    <Col xs={24} lg={12}><Form.Item label="接口类型" name="type"><Input disabled /></Form.Item></Col>
                    <Col xs={24} lg={12}><Form.Item label="模型名称" name="model" rules={[{ required: true, message: '请输入模型名称' }]}><Input /></Form.Item></Col>
                  </Row>
                  <div className="form-section-title">连接与输出</div>
                  <Row gutter={16}>
                    <Col span={24}><Form.Item label="Base URL" name="base_url" rules={[{ required: true, message: '请输入 Base URL' }]}><Input /></Form.Item></Col>
                    <Col xs={24} lg={12}><Form.Item label="API Key" name="api_key" extra="保存后不回显；留空不覆盖同 ID 已保存密钥。"><Input.Password autoComplete="new-password" /></Form.Item></Col>
                    <Col xs={24} lg={12}><Form.Item label="质量" name="quality"><Select aria-label="质量" options={['standard', 'medium', 'hd'].map((value) => ({ value, label: value }))} /></Form.Item></Col>
                  </Row>
                  <div className="image-model-fixed-spec">
                    <Tag color="blue">IMAGE_GENERATION</Tag>
                    <Tag color="purple">IMAGE_REFERENCE</Tag>
                    <Text code>b64_json</Text>
                    <Text strong>1536 × 864</Text>
                  </div>
                </Form>
              </>
            )}
          </Card>
        </div>
      )}
    </section>
  );
}
