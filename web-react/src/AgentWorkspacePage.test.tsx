import { App as AntApp } from 'antd';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AgentDefinition, AgentTestResult, LocalCliConfig } from './api';
import AgentWorkspacePage from './AgentWorkspacePage';

const apiMocks = vi.hoisted(() => ({
  getAgents: vi.fn(),
  getAgentRuns: vi.fn(),
  createAgent: vi.fn(),
  updateAgent: vi.fn(),
  testAgent: vi.fn(),
}));

vi.mock('./api', async () => {
  const actual = await vi.importActual<typeof import('./api')>('./api');
  return { ...actual, ...apiMocks };
});

const agents: AgentDefinition[] = [
  {
    ID: 1,
    CreatedAt: '2026-08-09T10:00:00Z',
    UpdatedAt: '2026-08-09T12:00:00Z',
    agentKey: 'story-portfolio',
    name: '故事组总策划 Agent',
    category: 'planning',
    description: '规划故事主题和差异化方向。',
    aiProviderId: 'codex',
    systemPrompt: 'system story',
    promptTemplate: '{{input}}',
    inputSchema: '{"type":"object"}',
    outputSchema: '{"type":"object"}',
    hardRules: 'keep ids',
    evaluationRubric: 'quality',
    temperature: 0.2,
    maxTokens: 4096,
    retryLimit: 1,
    sortOrder: 10,
  },
  {
    ID: 2,
    CreatedAt: '2026-08-09T10:00:00Z',
    UpdatedAt: '2026-08-09T13:00:00Z',
    agentKey: 'word-usage',
    name: '词义与用法 Agent',
    category: 'planning',
    description: '分析词义与用法。',
    aiProviderId: 'codex',
    systemPrompt: 'system word',
    promptTemplate: '{{input}}',
    inputSchema: '{"type":"object"}',
    outputSchema: '{"type":"object"}',
    hardRules: 'keep ids',
    evaluationRubric: 'quality',
    temperature: 0.2,
    maxTokens: 4096,
    retryLimit: 1,
    sortOrder: 20,
  },
];

const runs: AgentTestResult[] = [
  {
    runId: 101,
    agentId: 1,
    agentKey: 'story-portfolio',
    agentName: '故事组总策划 Agent',
    aiProviderId: 'codex',
    status: 'NEEDS_REVISION',
    inputJson: '{}',
    outputText: '{}',
    schemaValid: true,
    overallScore: 82,
    dimensionScores: {},
    issues: ['故事诊断'],
    durationMs: 1200,
    errorMessage: '',
    createdAt: '2026-08-09T12:30:00Z',
  },
  {
    runId: 102,
    agentId: 2,
    agentKey: 'word-usage',
    agentName: '词义与用法 Agent',
    aiProviderId: 'codex',
    status: 'FAILED',
    inputJson: '{}',
    outputText: '',
    schemaValid: false,
    dimensionScores: {},
    issues: ['词义诊断'],
    durationMs: 800,
    errorMessage: '',
    createdAt: '2026-08-09T12:40:00Z',
  },
];

const cliConfig: LocalCliConfig = {
  active: 'codex',
  configs: [{
    id: 'codex',
    label: 'Codex CLI',
    command: 'codex',
    defaultArgs: [],
    model: 'gpt-5.6-luna',
    reasoningEffort: 'high',
    workingDirectory: '',
    timeoutSeconds: 120,
    enabled: true,
    active: true,
  }],
};

const renderPage = () => render(
  <AntApp>
    <AgentWorkspacePage cliConfig={cliConfig} />
  </AntApp>,
);

describe('AgentWorkspacePage list and detail workflow', () => {
  beforeEach(() => {
    apiMocks.getAgents.mockResolvedValue(agents);
    apiMocks.getAgentRuns.mockResolvedValue(runs);
    apiMocks.createAgent.mockResolvedValue({ ...agents[0], ID: 3, name: '新 Agent' });
    apiMocks.updateAgent.mockImplementation(async (value: AgentDefinition) => value);
    apiMocks.testAgent.mockResolvedValue(runs[0]);
  });

  it('shows one Agent table without top-level workflow or run tabs', async () => {
    renderPage();

    expect((await screen.findAllByText('故事组总策划 Agent')).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('table')).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: '详情' })).toHaveLength(2);
    expect(screen.queryByRole('tab', { name: 'Agent 配置' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: '流程视图' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: '运行记录' })).not.toBeInTheDocument();
  });

  it('opens details and shows only the selected Agent run history', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click((await screen.findAllByRole('button', { name: '详情' }))[0]);
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByRole('tab', { name: '配置编辑' })).toBeInTheDocument();
    expect(within(drawer).getByRole('tab', { name: '在线测试' })).toBeInTheDocument();

    await user.click(within(drawer).getByRole('tab', { name: '运行记录' }));
    expect(within(drawer).getByText('故事诊断')).toBeInTheDocument();
    expect(within(drawer).queryByText('词义诊断')).not.toBeInTheDocument();
  });

  it('opens the shared detail drawer for a new Agent', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /新增 Agent/ }));
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getAllByText('新增 Agent').length).toBeGreaterThan(0);
    expect(within(drawer).getByLabelText('Agent Key')).toHaveValue('agent-30');
  });

  it('keeps the detail drawer mounted while saved data refreshes', async () => {
    const user = userEvent.setup();
    let finishRefresh: ((value: AgentDefinition[]) => void) | undefined;
    const pendingRefresh = new Promise<AgentDefinition[]>((resolve) => { finishRefresh = resolve; });
    apiMocks.getAgents
      .mockResolvedValueOnce(agents)
      .mockReturnValueOnce(pendingRefresh);
    renderPage();

    await user.click((await screen.findAllByRole('button', { name: '详情' }))[0]);
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByRole('button', { name: /保存 Agent/ }));
    await waitFor(() => expect(apiMocks.updateAgent).toHaveBeenCalledTimes(1));

    expect(screen.getByRole('dialog')).toBeInTheDocument();
    finishRefresh?.(agents);
  });
});
