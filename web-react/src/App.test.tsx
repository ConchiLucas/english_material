import { App as AntApp } from 'antd';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';

const apiMocks = vi.hoisted(() => ({
  getConnections: vi.fn(),
  getAIConfig: vi.fn(),
  getLocalCliConfig: vi.fn(),
  getStoryAgentFlow: vi.fn(),
}));

vi.mock('./api', async (importOriginal) => ({
  ...await importOriginal<typeof import('./api')>(),
  ...apiMocks,
}));

vi.mock('./StoryAgentFlowPage', () => ({
  default: ({
    providers,
    onDirtyChange,
  }: {
    providers: Array<{ id: string; label: string }>;
    onDirtyChange: (dirty: boolean) => void;
  }) => (
    <section aria-label="Agent 流程工作台">
      <span>{`Providers: ${providers.length} ${providers.map((provider) => provider.label).join(', ')}`}</span>
      <button type="button" onClick={() => onDirtyChange(true)}>标记 Agent 修改</button>
    </section>
  ),
}));

describe('App primary navigation', () => {
  beforeEach(() => {
    apiMocks.getConnections.mockReset().mockResolvedValue([]);
    apiMocks.getAIConfig.mockReset().mockResolvedValue({ active: '', providers: [] });
    apiMocks.getLocalCliConfig.mockReset().mockResolvedValue({ active: '', configs: [] });
    apiMocks.getStoryAgentFlow.mockReset().mockResolvedValue({ stages: [], budget: {} });
  });

  it('exposes configuration, word browsing, and Agent workbench navigation', async () => {
    render(<AntApp><App /></AntApp>);

    const navigation = await screen.findByRole('menu', { name: '主导航' });
    expect(within(navigation).getByRole('menuitem', { name: /配置管理/ })).toBeInTheDocument();
    expect(within(navigation).getByRole('menuitem', { name: /去重单词表/ })).toBeInTheDocument();
    expect(within(navigation).getByRole('menuitem', { name: /Agent 工作台/ })).toBeInTheDocument();
    expect(within(navigation).getAllByRole('menuitem')).toHaveLength(3);
  });

  it('opens the Agent flow workbench from primary navigation', async () => {
    const user = userEvent.setup();
    render(<AntApp><App /></AntApp>);

    const navigation = await screen.findByRole('menu', { name: '主导航' });
    await user.click(within(navigation).getByRole('menuitem', { name: /Agent 工作台/ }));

    expect(screen.getByRole('region', { name: 'Agent 流程工作台' })).toBeInTheDocument();
  });

  it('renders the Agent workbench with available providers when an unrelated request fails', async () => {
    const user = userEvent.setup();
    apiMocks.getConnections.mockRejectedValue(new Error('database unavailable'));
    apiMocks.getAIConfig.mockResolvedValue({
      active: 'writer',
      providers: [{
        id: 'writer',
        label: 'Writer Provider',
        type: 'openai-compatible',
        base_url: 'https://example.test',
        api_key: '',
        model: 'writer-model',
        max_tokens: 4096,
      }],
    });

    render(<AntApp><App /></AntApp>);

    const navigation = await screen.findByRole('menu', { name: '主导航' });
    await user.click(within(navigation).getByRole('menuitem', { name: /Agent 工作台/ }));

    expect(await screen.findByRole('region', { name: 'Agent 流程工作台' })).toBeInTheDocument();
    expect(await screen.findByText('Providers: 1 Writer Provider')).toBeInTheDocument();
  });

  it('confirms before leaving a dirty Agent workbench', async () => {
    const user = userEvent.setup();
    render(<AntApp><App /></AntApp>);

    const navigation = await screen.findByRole('menu', { name: '主导航' });
    await user.click(within(navigation).getByRole('menuitem', { name: /Agent 工作台/ }));
    await user.click(await screen.findByRole('button', { name: '标记 Agent 修改' }));
    await user.click(within(navigation).getByRole('menuitem', { name: /配置管理/ }));

    expect((await screen.findAllByText('离开 Agent 工作台？')).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: /取\s*消/ }));
    expect(screen.getByRole('region', { name: 'Agent 流程工作台' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '标记 Agent 修改' }));
    await user.click(within(navigation).getByRole('menuitem', { name: /配置管理/ }));
    const confirmButtons = await screen.findAllByRole('button', { name: '确认离开' });
    await user.click(confirmButtons.at(-1)!);

    expect(await screen.findByRole('heading', { name: '环境数据库配置' })).toBeInTheDocument();
  });
});
