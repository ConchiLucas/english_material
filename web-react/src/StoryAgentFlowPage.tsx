import type { AIProviderConfigItem } from './api';

interface StoryAgentFlowPageProps {
  providers: AIProviderConfigItem[];
  onDirtyChange: (dirty: boolean) => void;
}

export default function StoryAgentFlowPage(_props: StoryAgentFlowPageProps) {
  return <section aria-label="Agent 流程工作台">Agent 流程工作台</section>;
}
