import { CloudServerOutlined, ExperimentOutlined, SaveOutlined } from '@ant-design/icons';
import { Alert, App as AntApp, Button, Card, Col, Form, Input, Row, Space, Switch, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import {
  getMinioConfig,
  saveMinioConfig,
  testMinioConfig,
  type MinioConfig,
  type MinioConfigUpdate,
} from './api';

const { Title, Text } = Typography;

const defaults: MinioConfig = {
  enabled: true,
  endpoint: '',
  accessKeyId: '',
  useSsl: false,
  bucketName: 'english-material',
  basePath: 'image-story',
  secretConfigured: false,
  updatedAt: null,
};

export default function MinioConfigPage() {
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<MinioConfigUpdate>();
  const [config, setConfig] = useState<MinioConfig>(defaults);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<'test' | 'save' | null>(null);
  const [loadError, setLoadError] = useState('');
  const [dirty, setDirty] = useState(false);

  const apply = (next: MinioConfig) => {
    setConfig(next);
    form.setFieldsValue({
      enabled: next.enabled,
      endpoint: next.endpoint,
      accessKeyId: next.accessKeyId,
      secretAccessKey: '',
      useSsl: next.useSsl,
      bucketName: next.bucketName,
      basePath: next.basePath,
      updatedAt: next.updatedAt,
    });
    setDirty(false);
  };

  useEffect(() => {
    let active = true;
    void getMinioConfig().then(
      (value) => { if (active) apply(value); },
      () => { if (active) setLoadError('无法读取 MinIO 配置，请确认后端服务可用。'); },
    ).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  const values = async () => {
    const value = await form.validateFields();
    return {
      ...value,
      endpoint: value.endpoint.trim(),
      accessKeyId: value.accessKeyId.trim(),
      secretAccessKey: value.secretAccessKey?.trim() || '',
      bucketName: value.bucketName.trim(),
      basePath: value.basePath.trim(),
      updatedAt: config.updatedAt,
    };
  };

  const test = async () => {
    setBusy('test');
    try {
      await testMinioConfig(await values());
      message.success('MinIO 连接验证成功');
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'MinIO 连接验证失败');
    } finally {
      setBusy(null);
    }
  };

  const save = async () => {
    setBusy('save');
    try {
      const saved = await saveMinioConfig(await values());
      apply(saved);
      message.success('MinIO 配置已保存');
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'MinIO 配置保存失败');
    } finally {
      setBusy(null);
    }
  };

  return (
    <section className="panel-page" aria-labelledby="minio-title">
      <div className="page-head">
        <div className="page-title-block">
          <Text className="page-eyebrow">CONFIGURATION / MINIO</Text>
          <div className="title-status-row">
            <Title id="minio-title" level={2}>MinIO 配置</Title>
            {dirty && <Tag color="warning">有未保存更改</Tag>}
          </div>
          <Text type="secondary">配置图片故事资产使用的私有对象存储。</Text>
        </div>
        <Space className="page-actions" wrap>
          <Button aria-label="测试连接" icon={<ExperimentOutlined />} loading={busy === 'test'} onClick={() => void test()}>
            测试连接
          </Button>
          <Button aria-label="保存配置" type="primary" icon={<SaveOutlined />} loading={busy === 'save'} onClick={() => void save()}>
            保存配置
          </Button>
        </Space>
      </div>

      {loadError && <Alert type="error" showIcon message={loadError} />}
      <Card className="config-editor" loading={loading}>
        <div className="card-row editor-head">
          <div>
            <Text className="editor-kicker">图片资产存储</Text>
            <Title level={3}><CloudServerOutlined /> 私有 Bucket</Title>
          </div>
          <Tag color={config.secretConfigured ? 'success' : 'warning'}>
            {config.secretConfigured ? '密钥已配置' : '密钥未配置'}
          </Tag>
        </div>
        <Form
          form={form}
          layout="vertical"
          requiredMark="optional"
          onValuesChange={() => setDirty(true)}
        >
          <div className="form-section-title">连接与认证</div>
          <Row gutter={16}>
            <Col xs={24} md={6}>
              <Form.Item label="启用" name="enabled" valuePropName="checked">
                <Switch checkedChildren="启用" unCheckedChildren="停用" />
              </Form.Item>
            </Col>
            <Col xs={24} md={18}>
              <Form.Item label="Endpoint" name="endpoint" rules={[{ required: true, message: '请输入 host:port' }]}>
                <Input placeholder="minio.internal:9000" className="mono-field" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="Access Key" name="accessKeyId" rules={[{ required: true, message: '请输入 Access Key' }]}>
                <Input autoComplete="off" className="mono-field" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                label="Secret Key"
                name="secretAccessKey"
                extra={config.secretConfigured ? '已保存密钥；留空表示继续使用现有密钥。' : '首次启用时必须填写。'}
              >
                <Input.Password aria-label="Secret Key" autoComplete="new-password" className="mono-field" />
              </Form.Item>
            </Col>
            <Col xs={24} md={6}>
              <Form.Item label="使用 SSL" name="useSsl" valuePropName="checked">
                <Switch checkedChildren="HTTPS" unCheckedChildren="HTTP" />
              </Form.Item>
            </Col>
          </Row>

          <div className="form-section-title">对象位置</div>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item label="Bucket" name="bucketName" rules={[{ required: true, message: '请输入 Bucket' }]}>
                <Input className="mono-field" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="基础路径" name="basePath" rules={[{ required: true, message: '请输入基础路径' }]}>
                <Input className="mono-field" />
              </Form.Item>
            </Col>
          </Row>
          <Alert
            type="info"
            showIcon
            message="Bucket 保持私有"
            description="浏览器不会直接访问 MinIO；图片仍通过后端资产 ID 接口读取。保存前会验证建桶及读写删除权限。"
          />
        </Form>
      </Card>
    </section>
  );
}
