import { App as AntApp } from 'antd';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AIProviderConfigItem } from './api';
import StoryAgentFlowPage from './StoryAgentFlowPage';
import type { StoryAgentFlow, StoryAgentNode } from './story-flow-types';

const apiMocks = vi.hoisted(() => ({
  getStoryAgentFlow: vi.fn(),
  updateStoryAgent: vi.fn(),
  getStoryAgentVersions: vi.fn(),
  restoreStoryAgentVersion: vi.fn(),
  updateStoryFlowBudget: vi.fn(),
}));

vi.mock('./api', async (importOriginal) => ({
  ...await importOriginal<typeof import('./api')>(),
  ...apiMocks,
}));

const agent = (
  key: string,
  name: string,
  stageKey: string,
  order: number,
  overrides: Partial<StoryAgentNode> = {},
): StoryAgentNode => ({
  key,
  name,
  nodeKind: 'AGENT',
  roleType: 'CREATOR',
  stageKey,
  order,
  description: `${name} description`,
  variables: ['topic', 'level'],
  upstream: [],
  downstream: [],
  systemPrompt: `${key} prompt`,
  aiProviderId: 'writer-provider',
  temperature: 0.7,
  enabled: true,
  promptVersion: 1,
  updatedAt: '2026-08-10T08:00:00Z',
  editable: true,
  ...overrides,
});

const readonlyNode = (
  key: string,
  name: string,
  stageKey: string,
  order: number,
  nodeKind: 'PROGRAM' | 'HUMAN' = 'PROGRAM',
): StoryAgentNode => ({
  key,
  name,
  nodeKind,
  roleType: nodeKind,
  stageKey,
  order,
  description: `${name} description`,
  variables: [],
  upstream: [],
  downstream: [],
  editable: false,
});

const makeFlow = (): StoryAgentFlow => ({
  stages: [
    {
      key: 'planning',
      name: '策划与创意',
      note: '先约束学习目标，再并行提出故事方向。',
      order: 1,
      nodes: [
        agent('vocabulary-planner', '词汇策划 Agent', 'planning', 1, { downstream: ['pitch-a'] }),
        agent('pitch-a', '创意提案 A', 'planning', 2, { parallelGroup: 'story-pitches', roleType: 'PITCH' }),
        agent('pitch-b', '创意提案 B', 'planning', 3, { parallelGroup: 'story-pitches', roleType: 'PITCH' }),
        agent('pitch-c', '创意提案 C', 'planning', 4, { parallelGroup: 'story-pitches', roleType: 'PITCH' }),
        readonlyNode('merge-pitches', '创意合并器', 'planning', 5),
        agent('story-director', '故事导演 Agent', 'planning', 6),
      ],
    },
    {
      key: 'production',
      name: '故事生产',
      note: '写作后执行硬规则检查，再进入语言润色。',
      order: 2,
      nodes: [
        agent('story-writer', '故事作家 Agent', 'production', 1, {
          systemPrompt: 'writer prompt',
          upstream: ['story-director'],
          downstream: ['hard-rules'],
        }),
        readonlyNode('hard-rules', '硬规则校验', 'production', 2),
        agent('language-polisher', '语言润色 Agent', 'production', 3),
      ],
    },
    {
      key: 'quality',
      name: '独立质量委员会',
      note: '审核、评分与决策完全分离',
      order: 3,
      nodes: [
        agent('review-a', '语言审核员', 'quality', 1, { parallelGroup: 'quality-reviewers', roleType: 'SCORER' }),
        agent('review-b', '教学审核员', 'quality', 2, { parallelGroup: 'quality-reviewers', roleType: 'SCORER' }),
        agent('review-c', '情节审核员', 'quality', 3, { parallelGroup: 'quality-reviewers', roleType: 'SCORER' }),
        readonlyNode('review-router', '审核结果路由', 'quality', 4),
        agent('quality-decider', '质量决策 Agent', 'quality', 5),
      ],
    },
    {
      key: 'delivery',
      name: '交付与人工审核',
      note: '人工确认最终故事后交付。',
      order: 4,
      nodes: [
        agent('final-polisher', '交付润色 Agent', 'delivery', 1),
        readonlyNode('delivery-gate', '交付门禁', 'delivery', 2),
        readonlyNode('human-editor', '人工编辑审核', 'delivery', 3, 'HUMAN'),
      ],
    },
  ],
  budget: {
    maxQualityRounds: 3,
    maxLocalRevisions: 2,
    maxWriterRewrites: 2,
    maxDirectorReturns: 1,
    maxPitchReturns: 1,
    maxPlanReturns: 1,
    maxTotalTokens: 120000,
    updatedAt: '2026-08-10T08:00:00Z',
  },
});

const providers: AIProviderConfigItem[] = [
  {
    id: 'writer-provider',
    label: 'Writer Text',
    type: 'openai-compatible',
    base_url: 'https://example.test',
    api_key: '',
    model: 'writer-model',
    max_tokens: 4096,
    capabilities: ['TEXT_GENERATION'],
    enabled: true,
  },
  {
    id: 'audio-provider',
    label: 'Audio Only',
    type: 'mimo-tts',
    base_url: 'https://audio.example.test',
    api_key: '',
    model: 'tts-model',
    max_tokens: 1024,
    capabilities: ['AUDIO_GENERATION'],
    enabled: true,
  },
  {
    id: 'disabled-text',
    label: 'Disabled Text',
    type: 'anthropic-compatible',
    base_url: 'https://disabled.example.test',
    api_key: '',
    model: 'disabled-model',
    max_tokens: 4096,
    capabilities: ['text_generation'],
    enabled: false,
  },
];

const renderPage = (onDirtyChange = vi.fn()) => render(
  <AntApp>
    <StoryAgentFlowPage providers={providers} onDirtyChange={onDirtyChange} />
  </AntApp>,
);

const openWriter = async (user: ReturnType<typeof userEvent.setup>) => {
  await screen.findByRole('heading', { name: '策划与创意' });
  await user.click(screen.getByRole('button', { name: /故事作家 Agent/ }));
  return screen.findByRole('heading', { name: '故事作家 Agent' });
};

describe('StoryAgentFlowPage', () => {
  beforeEach(() => {
    apiMocks.getStoryAgentFlow.mockReset().mockResolvedValue(makeFlow());
    apiMocks.updateStoryAgent.mockReset();
    apiMocks.getStoryAgentVersions.mockReset();
    apiMocks.restoreStoryAgentVersion.mockReset();
    apiMocks.updateStoryFlowBudget.mockReset();
  });

  it('renders four stages and switches prompt details when an Agent is clicked', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByRole('heading', { name: '策划与创意' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '独立质量委员会' })).toBeInTheDocument();
    expect(screen.getAllByRole('article')).toHaveLength(4);
    expect(screen.getByText('12 个 Agent')).toBeInTheDocument();
    expect(screen.getByText('5 个程序 / 人工节点')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /故事作家 Agent/ }));

    expect(await screen.findByRole('heading', { name: '故事作家 Agent' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'System Prompt' })).toHaveValue('writer prompt');
  });

  it('labels backend parallel groups and exposes the quality return constraint', async () => {
    renderPage();

    expect(await screen.findByRole('group', { name: '三个匿名创意提案并行' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: '三位独立审核员并行' })).toBeInTheDocument();
    expect(screen.getByText('不通过时按决策定向回退，受预算上限约束')).toBeInTheDocument();
    expect(screen.getAllByText('创意提案').length).toBeGreaterThan(0);
    expect(screen.getAllByText('评分审核').length).toBeGreaterThan(0);
  });

  it('shows read-only details for program nodes', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole('heading', { name: '策划与创意' });
    await user.click(screen.getByRole('button', { name: /硬规则校验/ }));

    expect(await screen.findByRole('heading', { name: '硬规则校验' })).toBeInTheDocument();
    expect(screen.getByText('该节点不使用 Prompt')).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'System Prompt' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '保存提示词' })).not.toBeInTheDocument();
  });

  it('saves a changed prompt and keeps the selected Agent visible', async () => {
    const user = userEvent.setup();
    const savedWriter = agent('story-writer', '故事作家 Agent', 'production', 1, {
      systemPrompt: 'new writer prompt',
      aiProviderId: 'writer-provider',
      promptVersion: 2,
      updatedAt: '2026-08-13T09:30:00Z',
    });
    apiMocks.updateStoryAgent.mockResolvedValue(savedWriter);
    renderPage();
    await openWriter(user);

    const prompt = screen.getByRole('textbox', { name: 'System Prompt' });
    await user.clear(prompt);
    await user.type(prompt, 'new writer prompt');
    await user.click(screen.getByRole('button', { name: '保存提示词' }));

    await waitFor(() => expect(apiMocks.updateStoryAgent).toHaveBeenCalledWith(
      'story-writer',
      expect.objectContaining({
        systemPrompt: 'new writer prompt',
        aiProviderId: 'writer-provider',
        updatedAt: '2026-08-10T08:00:00Z',
      }),
    ));
    expect(screen.getByRole('heading', { name: '故事作家 Agent' })).toBeInTheDocument();
    expect((await screen.findAllByText('Prompt v2')).length).toBeGreaterThan(0);
  });

  it('confirms before switching a dirty Agent and synchronizes dirty state', async () => {
    const user = userEvent.setup();
    const onDirtyChange = vi.fn();
    renderPage(onDirtyChange);
    await openWriter(user);

    const prompt = screen.getByRole('textbox', { name: 'System Prompt' });
    await user.type(prompt, ' changed');
    expect(onDirtyChange).toHaveBeenLastCalledWith(true);
    await user.click(screen.getByRole('button', { name: /语言润色 Agent/ }));

    expect((await screen.findAllByText('切换 Agent？')).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: /取\s*消/ }));
    expect(screen.getByRole('heading', { name: '故事作家 Agent' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'System Prompt' })).toHaveValue('writer prompt changed');

    await user.click(screen.getByRole('button', { name: /语言润色 Agent/ }));
    const confirmButtons = await screen.findAllByRole('button', { name: /确\s*定/ });
    await user.click(confirmButtons.at(-1)!);

    expect(await screen.findByRole('heading', { name: '语言润色 Agent' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'System Prompt' })).toHaveValue('language-polisher prompt');
    expect(onDirtyChange).toHaveBeenLastCalledWith(false);
  });

  it('only offers enabled text generation providers and saves the selected provider', async () => {
    const user = userEvent.setup();
    apiMocks.updateStoryAgent.mockImplementation(async (_key: string, value: object) => ({
      ...makeFlow().stages[1].nodes[0],
      ...value,
      promptVersion: 2,
    }));
    renderPage();
    await openWriter(user);

    await user.click(screen.getByRole('combobox', { name: 'AI Provider' }));
    expect(await screen.findByRole('option', { name: /Writer Text/ })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /Audio Only/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /Disabled Text/ })).not.toBeInTheDocument();
    await user.keyboard('{Escape}');
    await user.click(screen.getByRole('button', { name: '保存提示词' }));

    await waitFor(() => expect(apiMocks.updateStoryAgent).toHaveBeenCalledWith(
      'story-writer',
      expect.objectContaining({ aiProviderId: 'writer-provider' }),
    ));
  });

  it('loads versions and restores a historical prompt as the latest version', async () => {
    const user = userEvent.setup();
    apiMocks.getStoryAgentVersions.mockResolvedValue([
      {
        version: 1,
        systemPrompt: 'old writer prompt',
        aiProviderId: 'writer-provider',
        temperature: 0.5,
        enabled: true,
        createdAt: '2026-08-09T08:00:00Z',
      },
      {
        version: 2,
        systemPrompt: 'writer prompt',
        aiProviderId: 'writer-provider',
        temperature: 0.7,
        enabled: true,
        createdAt: '2026-08-12T08:00:00Z',
      },
    ]);
    apiMocks.restoreStoryAgentVersion.mockResolvedValue(agent(
      'story-writer',
      '故事作家 Agent',
      'production',
      1,
      { systemPrompt: 'old writer prompt', promptVersion: 3 },
    ));
    renderPage();
    await openWriter(user);

    await user.click(screen.getByRole('button', { name: '查看版本' }));
    await waitFor(() => expect(apiMocks.getStoryAgentVersions).toHaveBeenCalledWith('story-writer'));
    const versionButtons = await screen.findAllByRole('button', { name: /恢复 Prompt v/ });
    expect(versionButtons.map((button) => button.getAttribute('aria-label'))).toEqual([
      '恢复 Prompt v2',
      '恢复 Prompt v1',
    ]);
    await user.click(await screen.findByRole('button', { name: '恢复 Prompt v1' }));
    expect((await screen.findAllByText('恢复 Prompt v1？')).length).toBeGreaterThan(0);
    const confirmButtons = await screen.findAllByRole('button', { name: /确\s*定/ });
    await user.click(confirmButtons.at(-1)!);

    await waitFor(() => expect(apiMocks.restoreStoryAgentVersion).toHaveBeenCalledWith('story-writer', 1));
    expect(await screen.findByRole('heading', { name: '故事作家 Agent' })).toBeInTheDocument();
    expect((await screen.findAllByText('Prompt v3')).length).toBeGreaterThan(0);
    expect(screen.getByRole('textbox', { name: 'System Prompt' })).toHaveValue('old writer prompt');
  });

  it('updates the quality budget and refreshes the header summary', async () => {
    const user = userEvent.setup();
    apiMocks.updateStoryFlowBudget.mockImplementation(async (value: object) => ({
      ...value,
      updatedAt: '2026-08-13T10:00:00Z',
    }));
    renderPage();

    await screen.findByRole('heading', { name: '策划与创意' });
    await user.click(screen.getByRole('button', { name: '质量预算' }));
    const rounds = await screen.findByRole('spinbutton', { name: '最大质量轮次' });
    await user.type(rounds, '5', { initialSelectionStart: 0, initialSelectionEnd: 1 });
    await user.click(screen.getByRole('button', { name: '保存质量预算' }));

    await waitFor(() => expect(apiMocks.updateStoryFlowBudget).toHaveBeenCalledWith({
      maxQualityRounds: 5,
      maxLocalRevisions: 2,
      maxWriterRewrites: 2,
      maxDirectorReturns: 1,
      maxPitchReturns: 1,
      maxPlanReturns: 1,
      maxTotalTokens: 120000,
    }));
    expect(await screen.findByText('最多 5 轮 · 120,000 Token')).toBeInTheDocument();
  });

  it('shows a load failure and reloads the flow on request', async () => {
    const user = userEvent.setup();
    apiMocks.getStoryAgentFlow
      .mockRejectedValueOnce(new Error('flow unavailable'))
      .mockResolvedValueOnce(makeFlow());
    renderPage();

    expect(await screen.findByText('flow unavailable')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '重新加载' }));

    expect(await screen.findByRole('heading', { name: '策划与创意' })).toBeInTheDocument();
    expect(apiMocks.getStoryAgentFlow).toHaveBeenCalledTimes(2);
  });
});
