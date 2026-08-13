import { App as AntApp } from 'antd';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AIProviderConfigItem } from './api';
import StoryAgentFlowPage from './StoryAgentFlowPage';
import type { StoryAgentFlow, StoryAgentNode, StoryPromptVersion } from './story-flow-types';

const apiMocks = vi.hoisted(() => ({
  getStoryAgentFlow: vi.fn(),
  updateStoryAgent: vi.fn(),
  getStoryAgentVersions: vi.fn(),
  restoreStoryAgentVersion: vi.fn(),
  updateStoryFlowBudget: vi.fn(),
  createStoryRun: vi.fn(),
  getStoryRuns: vi.fn(),
  getStoryRun: vi.fn(),
  getStoryWordLibraries: vi.fn(),
  previewRandomStoryWords: vi.fn(),
}));

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
};

const originalScrollIntoView = HTMLElement.prototype.scrollIntoView;
const originalMatchMedia = window.matchMedia;
const originalRequestAnimationFrame = window.requestAnimationFrame;

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
  overrides: Partial<StoryAgentNode> = {},
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
  ...overrides,
});

const makeFlow = (): StoryAgentFlow => ({
  stages: [
    {
      key: 'planning',
      name: '策划与创意',
      note: '先约束学习目标，再并行提出故事方向。',
      order: 1,
      nodes: [
        readonlyNode('word-pack', 'Word Pack', 'planning', 10, 'PROGRAM', {
          roleType: 'INPUT',
          downstream: ['vocabulary-planner'],
        }),
        agent('vocabulary-planner', '用词策划 Agent', 'planning', 20, {
          roleType: 'PLANNER',
          upstream: ['word-pack', 'quality-decider'],
          downstream: ['pitch-humor', 'pitch-adventure', 'pitch-wonder'],
        }),
        agent('pitch-humor', '幽默创意 Agent', 'planning', 30, {
          parallelGroup: 'story-pitches',
          roleType: 'PITCH',
          upstream: ['vocabulary-planner', 'quality-decider'],
          downstream: ['story-director'],
        }),
        agent('pitch-adventure', '冒险创意 Agent', 'planning', 31, {
          parallelGroup: 'story-pitches',
          roleType: 'PITCH',
          upstream: ['vocabulary-planner', 'quality-decider'],
          downstream: ['story-director'],
        }),
        agent('pitch-wonder', '奇想创意 Agent', 'planning', 32, {
          parallelGroup: 'story-pitches',
          roleType: 'PITCH',
          upstream: ['vocabulary-planner', 'quality-decider'],
          downstream: ['story-director'],
        }),
        agent('story-director', '故事导演 Agent', 'planning', 40, {
          roleType: 'DIRECTOR',
          upstream: ['pitch-humor', 'pitch-adventure', 'pitch-wonder', 'quality-decider'],
          downstream: ['story-writer'],
        }),
      ],
    },
    {
      key: 'writing',
      name: '写作与候选',
      note: '同一主角、同一主线、逐场升级',
      order: 2,
      nodes: [
        agent('story-writer', '故事作家 Agent', 'writing', 10, {
          roleType: 'WRITER',
          systemPrompt: 'writer prompt',
          upstream: ['story-director', 'quality-decider'],
          downstream: ['hard-rule-check'],
        }),
        readonlyNode('hard-rule-check', '硬规则校验', 'writing', 20, 'PROGRAM', {
          roleType: 'VALIDATOR',
          upstream: ['story-writer', 'targeted-reviser'],
          downstream: ['candidate-snapshot'],
        }),
        readonlyNode('candidate-snapshot', '候选版本快照', 'writing', 30, 'PROGRAM', {
          roleType: 'SNAPSHOT',
          upstream: ['hard-rule-check'],
          downstream: ['review-fun', 'review-language', 'review-continuity'],
        }),
      ],
    },
    {
      key: 'quality',
      name: '独立质量委员会',
      note: '审核、评分与决策完全分离',
      order: 3,
      nodes: [
        agent('review-fun', '趣味审核员', 'quality', 10, {
          parallelGroup: 'quality-reviewers',
          roleType: 'REVIEWER',
          upstream: ['candidate-snapshot'],
          downstream: ['story-scorer'],
        }),
        agent('review-language', '语言用词审核员', 'quality', 11, {
          parallelGroup: 'quality-reviewers',
          roleType: 'REVIEWER',
          upstream: ['candidate-snapshot'],
          downstream: ['story-scorer'],
        }),
        agent('review-continuity', '剧情连续性审核员', 'quality', 12, {
          parallelGroup: 'quality-reviewers',
          roleType: 'REVIEWER',
          upstream: ['candidate-snapshot'],
          downstream: ['story-scorer'],
        }),
        agent('story-scorer', '独立评分员', 'quality', 20, {
          roleType: 'SCORER',
          upstream: ['review-fun', 'review-language', 'review-continuity'],
          downstream: ['quality-decider'],
        }),
        agent('quality-decider', '质量决策人', 'quality', 30, {
          roleType: 'DECIDER',
          upstream: ['story-scorer'],
          downstream: ['targeted-reviser', 'story-writer', 'story-director'],
        }),
      ],
    },
    {
      key: 'delivery',
      name: '修订与交付',
      note: '通过后进入人工审核',
      order: 4,
      nodes: [
        agent('targeted-reviser', '定向修订 Agent', 'delivery', 10, {
          roleType: 'REVISER',
          upstream: ['quality-decider'],
          downstream: ['hard-rule-check'],
        }),
        readonlyNode('budget-controller', '确定性预算控制器', 'delivery', 20, 'PROGRAM', {
          roleType: 'CONTROLLER',
        }),
        readonlyNode('human-review', '人工审核', 'delivery', 30, 'HUMAN', {
          roleType: 'HUMAN_REVIEW',
          upstream: ['quality-decider'],
        }),
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

  afterEach(() => {
    vi.restoreAllMocks();
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: originalMatchMedia,
    });
    Object.defineProperty(window, 'requestAnimationFrame', {
      configurable: true,
      writable: true,
      value: originalRequestAnimationFrame,
    });
    if (originalScrollIntoView) {
      Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
        configurable: true,
        writable: true,
        value: originalScrollIntoView,
      });
    } else {
      delete (HTMLElement.prototype as { scrollIntoView?: typeof HTMLElement.prototype.scrollIntoView }).scrollIntoView;
    }
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

  it('uses the backend 17-node catalog in its flow fixture', () => {
    const fixture = makeFlow();

    expect(fixture.stages.map(({ key, name }) => ({ key, name }))).toEqual([
      { key: 'planning', name: '策划与创意' },
      { key: 'writing', name: '写作与候选' },
      { key: 'quality', name: '独立质量委员会' },
      { key: 'delivery', name: '修订与交付' },
    ]);
    expect(fixture.stages.flatMap((stage) => stage.nodes.map((node) => node.key))).toEqual([
      'word-pack',
      'vocabulary-planner',
      'pitch-humor',
      'pitch-adventure',
      'pitch-wonder',
      'story-director',
      'story-writer',
      'hard-rule-check',
      'candidate-snapshot',
      'review-fun',
      'review-language',
      'review-continuity',
      'story-scorer',
      'quality-decider',
      'targeted-reviser',
      'budget-controller',
      'human-review',
    ]);
    expect(fixture.stages.flatMap((stage) => stage.nodes).filter((node) => node.editable)).toHaveLength(12);
    expect(fixture.stages.flatMap((stage) => stage.nodes).filter((node) => !node.editable)).toHaveLength(5);
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
    const savedWriter = agent('story-writer', '故事作家 Agent', 'writing', 10, {
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

  it('preserves newer prompt edits when an earlier save resolves', async () => {
    const user = userEvent.setup();
    const onDirtyChange = vi.fn();
    const pendingSave = deferred<StoryAgentNode>();
    apiMocks.updateStoryAgent
      .mockReturnValueOnce(pendingSave.promise)
      .mockResolvedValueOnce(agent('story-writer', '故事作家 Agent', 'writing', 10, {
        systemPrompt: 'submitted prompt with newer edits',
        promptVersion: 3,
        updatedAt: '2026-08-13T10:30:00Z',
      }));
    renderPage(onDirtyChange);
    await openWriter(user);

    const prompt = screen.getByRole('textbox', { name: 'System Prompt' });
    await user.clear(prompt);
    await user.type(prompt, 'submitted prompt');
    await user.click(screen.getByRole('button', { name: '保存提示词' }));
    await waitFor(() => expect(apiMocks.updateStoryAgent).toHaveBeenCalledTimes(1));
    await user.type(prompt, ' with newer edits');

    pendingSave.resolve(agent('story-writer', '故事作家 Agent', 'writing', 10, {
      systemPrompt: 'submitted prompt',
      promptVersion: 2,
      updatedAt: '2026-08-13T09:30:00Z',
    }));

    await waitFor(() => expect(within(screen.getByRole('button', { name: /故事作家 Agent/ }))
      .getByText('Prompt v2')).toBeInTheDocument());
    expect(screen.getByRole('heading', { name: '故事作家 Agent' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'System Prompt' })).toHaveValue('submitted prompt with newer edits');
    expect(onDirtyChange).toHaveBeenLastCalledWith(true);

    const secondSave = screen.getByRole('button', { name: /保存提示词/ });
    await waitFor(() => expect(secondSave).not.toHaveClass('ant-btn-loading'));
    await user.click(secondSave);
    await waitFor(() => expect(apiMocks.updateStoryAgent).toHaveBeenNthCalledWith(
      2,
      'story-writer',
      expect.objectContaining({
        systemPrompt: 'submitted prompt with newer edits',
        updatedAt: '2026-08-13T09:30:00Z',
      }),
    ));
    await waitFor(() => expect(within(screen.getByRole('button', { name: /故事作家 Agent/ }))
      .getByText('Prompt v3')).toBeInTheDocument());
    expect(onDirtyChange).toHaveBeenLastCalledWith(false);
  });

  it('merges a late save into its Agent card without leaving the newly selected Agent', async () => {
    const user = userEvent.setup();
    const pendingSave = deferred<StoryAgentNode>();
    apiMocks.updateStoryAgent.mockReturnValue(pendingSave.promise);
    renderPage();
    await openWriter(user);

    const prompt = screen.getByRole('textbox', { name: 'System Prompt' });
    await user.clear(prompt);
    await user.type(prompt, 'submitted prompt');
    await user.click(screen.getByRole('button', { name: '保存提示词' }));
    await waitFor(() => expect(apiMocks.updateStoryAgent).toHaveBeenCalledTimes(1));
    await user.click(screen.getByRole('button', { name: /定向修订 Agent/ }));
    const confirmButtons = await screen.findAllByRole('button', { name: /确\s*定/ });
    await user.click(confirmButtons.at(-1)!);
    expect(await screen.findByRole('heading', { name: '定向修订 Agent' })).toBeInTheDocument();

    pendingSave.resolve(agent('story-writer', '故事作家 Agent', 'writing', 10, {
      systemPrompt: 'submitted prompt',
      promptVersion: 2,
      updatedAt: '2026-08-13T09:30:00Z',
    }));

    await waitFor(() => expect(within(screen.getByRole('button', { name: /故事作家 Agent/ }))
      .getByText('Prompt v2')).toBeInTheDocument());
    expect(screen.getByRole('heading', { name: '定向修订 Agent' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'System Prompt' })).toHaveValue('targeted-reviser prompt');
  });

  it('confirms before switching a dirty Agent and synchronizes dirty state', async () => {
    const user = userEvent.setup();
    const onDirtyChange = vi.fn();
    renderPage(onDirtyChange);
    await openWriter(user);

    const prompt = screen.getByRole('textbox', { name: 'System Prompt' });
    await user.type(prompt, ' changed');
    expect(onDirtyChange).toHaveBeenLastCalledWith(true);
    await user.click(screen.getByRole('button', { name: /定向修订 Agent/ }));

    expect((await screen.findAllByText('切换 Agent？')).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: /取\s*消/ }));
    expect(screen.getByRole('heading', { name: '故事作家 Agent' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'System Prompt' })).toHaveValue('writer prompt changed');

    await user.click(screen.getByRole('button', { name: /定向修订 Agent/ }));
    const confirmButtons = await screen.findAllByRole('button', { name: /确\s*定/ });
    await user.click(confirmButtons.at(-1)!);

    expect(await screen.findByRole('heading', { name: '定向修订 Agent' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'System Prompt' })).toHaveValue('targeted-reviser prompt');
    expect(onDirtyChange).toHaveBeenLastCalledWith(false);
  });

  it('scrolls the detail panel into view after selecting a lower node on a narrow screen', async () => {
    const user = userEvent.setup();
    const scrollIntoView = vi.fn();
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      writable: true,
      value: scrollIntoView,
    });
    vi.spyOn(window, 'matchMedia').mockImplementation((query) => ({
      matches: query === '(max-width: 1100px)',
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }));
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      callback(0);
      return 1;
    });
    renderPage();

    await screen.findByRole('heading', { name: '策划与创意' });
    await user.click(screen.getByRole('button', { name: /定向修订 Agent/ }));

    expect(await screen.findByRole('heading', { name: '定向修订 Agent' })).toBeInTheDocument();
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' });
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
      'writing',
      10,
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

    await waitFor(() => expect(apiMocks.restoreStoryAgentVersion).toHaveBeenCalledWith(
      'story-writer',
      1,
      { updatedAt: '2026-08-10T08:00:00Z' },
    ));
    expect(await screen.findByRole('heading', { name: '故事作家 Agent' })).toBeInTheDocument();
    expect((await screen.findAllByText('Prompt v3')).length).toBeGreaterThan(0);
    expect(screen.getByRole('textbox', { name: 'System Prompt' })).toHaveValue('old writer prompt');
  });

  it('ignores a closed Agent version request and restores the currently open Agent history', async () => {
    const user = userEvent.setup();
    const writerVersions = deferred<StoryPromptVersion[]>();
    const polisherVersions = deferred<StoryPromptVersion[]>();
    const flow = makeFlow();
    const polisher = flow.stages
      .flatMap((stage) => stage.nodes)
      .find((node) => node.key === 'targeted-reviser');
    if (polisher) polisher.updatedAt = '2026-08-13T07:30:00Z';
    apiMocks.getStoryAgentFlow.mockResolvedValue(flow);
    apiMocks.getStoryAgentVersions
      .mockReturnValueOnce(writerVersions.promise)
      .mockReturnValueOnce(polisherVersions.promise);
    apiMocks.restoreStoryAgentVersion.mockResolvedValue(agent(
      'targeted-reviser',
      '定向修订 Agent',
      'delivery',
      10,
      { systemPrompt: 'polisher restored prompt', promptVersion: 10 },
    ));
    renderPage();
    await openWriter(user);

    await user.click(screen.getByRole('button', { name: '查看版本' }));
    await waitFor(() => expect(apiMocks.getStoryAgentVersions).toHaveBeenCalledWith('story-writer'));
    await user.click(screen.getByRole('button', { name: /关\s*闭/ }));
    await user.click(screen.getByRole('button', { name: /定向修订 Agent/ }));
    expect(await screen.findByRole('heading', { name: '定向修订 Agent' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '查看版本' }));
    await waitFor(() => expect(apiMocks.getStoryAgentVersions).toHaveBeenLastCalledWith('targeted-reviser'));

    polisherVersions.resolve([{
      version: 9,
      systemPrompt: 'polisher history prompt',
      aiProviderId: 'writer-provider',
      temperature: 0.4,
      enabled: true,
      createdAt: '2026-08-13T08:00:00Z',
    }]);
    expect(await screen.findByText('polisher history prompt')).toBeInTheDocument();

    await act(async () => {
      writerVersions.resolve([{
        version: 8,
        systemPrompt: 'stale writer history prompt',
        aiProviderId: 'writer-provider',
        temperature: 0.8,
        enabled: true,
        createdAt: '2026-08-12T08:00:00Z',
      }]);
      await writerVersions.promise;
    });
    expect(screen.queryByText('stale writer history prompt')).not.toBeInTheDocument();
    expect(screen.getByText('polisher history prompt')).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: /定向修订 Agent · 版本历史/ })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '恢复 Prompt v9' }));
    const confirmButtons = await screen.findAllByRole('button', { name: /确\s*定/ });
    await user.click(confirmButtons.at(-1)!);
    await waitFor(() => expect(apiMocks.restoreStoryAgentVersion).toHaveBeenCalledWith(
      'targeted-reviser',
      9,
      { updatedAt: '2026-08-13T07:30:00Z' },
    ));
  });

  it('restores with the latest timestamp after a save resolves while versions are open', async () => {
    const user = userEvent.setup();
    const pendingSave = deferred<StoryAgentNode>();
    apiMocks.updateStoryAgent.mockReturnValue(pendingSave.promise);
    apiMocks.getStoryAgentVersions.mockResolvedValue([{
      version: 1,
      systemPrompt: 'old writer prompt',
      aiProviderId: 'writer-provider',
      temperature: 0.5,
      enabled: true,
      createdAt: '2026-08-09T08:00:00Z',
    }]);
    apiMocks.restoreStoryAgentVersion.mockResolvedValue(agent(
      'story-writer',
      '故事作家 Agent',
      'writing',
      10,
      { systemPrompt: 'old writer prompt', promptVersion: 3 },
    ));
    renderPage();
    await openWriter(user);

    const prompt = screen.getByRole('textbox', { name: 'System Prompt' });
    await user.type(prompt, ' changed');
    await user.click(screen.getByRole('button', { name: '保存提示词' }));
    await waitFor(() => expect(apiMocks.updateStoryAgent).toHaveBeenCalledTimes(1));
    await user.click(screen.getByRole('button', { name: '查看版本' }));
    expect(await screen.findByRole('button', { name: '恢复 Prompt v1' })).toBeInTheDocument();

    pendingSave.resolve(agent('story-writer', '故事作家 Agent', 'writing', 10, {
      systemPrompt: 'writer prompt changed',
      promptVersion: 2,
      updatedAt: '2026-08-13T10:30:00Z',
    }));
    await waitFor(() => expect(within(screen.getByRole('button', { name: /故事作家 Agent/ }))
      .getByText('Prompt v2')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: '恢复 Prompt v1' }));
    const confirmButtons = await screen.findAllByRole('button', { name: /确\s*定/ });
    await user.click(confirmButtons.at(-1)!);

    await waitFor(() => expect(apiMocks.restoreStoryAgentVersion).toHaveBeenCalledWith(
      'story-writer',
      1,
      { updatedAt: '2026-08-13T10:30:00Z' },
    ));
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

  it('opens start-run dialog and creates a run from manual words', async () => {
    const user = userEvent.setup();
    apiMocks.createStoryRun.mockResolvedValue({
      runId: 'run-created', words: [{ word: 'book', meaning: '书' }], targetGrade: '三年级上册',
      status: 'QUEUED', totalTokens: 0, createdAt: '2026-08-13T20:00:00Z', startedAt: null, finishedAt: null,
    });
    apiMocks.getStoryRuns.mockResolvedValue([]);
    apiMocks.getStoryRun.mockResolvedValue({
      runId: 'run-created', words: [{ word: 'book', meaning: '书' }], targetGrade: '三年级上册',
      status: 'QUEUED', totalTokens: 0, createdAt: '2026-08-13T20:00:00Z', startedAt: null, finishedAt: null,
      finalStory: null, errorMessage: null, steps: [],
    });
    renderPage();
    await screen.findByRole('heading', { name: '策划与创意' });

    await user.click(screen.getByRole('button', { name: '开始运行' }));
    await user.type(screen.getByRole('textbox', { name: '目标单词' }), 'book 书\ngreen 绿色');
    await user.click(screen.getByRole('button', { name: '创建并运行' }));

    await waitFor(() => expect(apiMocks.createStoryRun).toHaveBeenCalledWith({
      targetGrade: '三年级上册',
      words: [{ word: 'book', meaning: '书' }, { word: 'green', meaning: '绿色' }],
    }));
    expect(await screen.findByRole('dialog', { name: '故事运行记录' })).toBeInTheDocument();
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
