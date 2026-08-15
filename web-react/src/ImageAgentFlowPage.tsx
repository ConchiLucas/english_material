import { Alert, Skeleton, Typography } from 'antd';
import { useEffect, useState } from 'react';
import type { AIProviderConfigItem } from './api';
import { getImageAgentFlow } from './api';
import type { ImageAgentFlow } from './image-story-types';

const { Text, Title } = Typography;

interface ImageAgentFlowPageProps {
  providers: AIProviderConfigItem[];
  onDirtyChange: (dirty: boolean) => void;
}

export default function ImageAgentFlowPage({ providers, onDirtyChange }: ImageAgentFlowPageProps) {
  const [flow, setFlow] = useState<ImageAgentFlow | null>(null);
  const [loadError, setLoadError] = useState('');

  useEffect(() => {
    let active = true;
    setLoadError('');
    void getImageAgentFlow().then(
      (value) => {
        if (active) setFlow(value);
      },
      () => {
        if (active) setLoadError('无法读取图片 Agent 流程，请确认后端服务后重试。');
      },
    );
    return () => {
      active = false;
      onDirtyChange(false);
    };
  }, [onDirtyChange]);

  return (
    <section className="image-agent-workbench" aria-label="图片 Agent 工作台">
      <header className="image-workbench-head">
        <div>
          <Text className="page-eyebrow">IMAGE STORY WORKBENCH</Text>
          <Title id="image-workbench-title" level={2}>图片工作台</Title>
          <Text type="secondary">将已完成的英文故事拆分为分镜，并生成可人工审核的绘本图片。</Text>
        </div>
      </header>
      {!flow && !loadError && (
        <div className="image-workbench-loading" aria-label="正在加载图片 Agent 流程" aria-busy="true">
          <Skeleton active paragraph={{ rows: 7 }} />
        </div>
      )}
      {loadError && (
        <Alert type="error" showIcon message="图片流程加载失败" description={loadError} />
      )}
      {flow && (
        <div className="image-workbench-placeholder" data-provider-count={providers.length}>
          <Text type="secondary">图片 Agent 流程已加载，配置与运行面板将在此显示。</Text>
        </div>
      )}
    </section>
  );
}
