import { App as AntApp } from 'antd';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';

const apiMocks = vi.hoisted(() => ({
  getConnections: vi.fn(),
  getAIConfig: vi.fn(),
  getLocalCliConfig: vi.fn(),
  getMinioConfig: vi.fn(),
  getStoryAgentFlow: vi.fn(),
  getImageAgentFlow: vi.fn(),
  updateImageFlowConfig: vi.fn(),
  bootstrapAntigravityImageProvider: vi.fn(),
  saveAIConfig: vi.fn(),
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

vi.mock('./ImageAgentFlowPage', () => ({
  default: ({
    providers,
    onDirtyChange,
  }: {
    providers: Array<{ id: string; label: string }>;
    onDirtyChange: (dirty: boolean) => void;
  }) => (
    <section aria-label="图片 Agent 工作台">
      <span>{`Image Providers: ${providers.length} ${providers.map((provider) => provider.label).join(', ')}`}</span>
      <button type="button" onClick={() => onDirtyChange(true)}>标记图片 Agent 修改</button>
    </section>
  ),
}));

vi.mock('./ImageModelConfigPage', () => ({
  default: ({
    config,
    onBootstrap,
  }: {
    config: { providers: Array<{ id: string; label: string }> };
    onBootstrap: (sourceProviderId: string) => Promise<void>;
  }) => (
    <section aria-label="图片模型配置页面">
      <h2>图片模型配置</h2>
      <span>{`Configured image providers: ${config.providers.map((provider) => provider.label).join(', ')}`}</span>
      <button type="button" onClick={() => void onBootstrap('antigravity-gemini-3-1-pro')}>引导图片模型</button>
    </section>
  ),
}));

vi.mock('./AgentGeneratedResultsPage', () => ({
  default: () => <section aria-label="Agent 生成结果">结果归档</section>,
}));

interface ConfirmLifecycle {
  title?: unknown;
  onCancel?: () => void;
  onOk?: () => void;
  afterClose?: () => void;
}

const installConfirmHarness = () => {
  const confirms: ConfirmLifecycle[] = [];
  const confirm = vi.fn((config: ConfirmLifecycle) => {
    confirms.push(config);
    return { destroy: vi.fn(), update: vi.fn() };
  });
  vi.spyOn(AntApp, 'useApp').mockReturnValue({
    message: {},
    notification: {},
    modal: { confirm },
  } as never);
  return { confirms, confirm };
};

describe('App primary navigation', () => {
  beforeEach(() => {
    apiMocks.getConnections.mockReset().mockResolvedValue([]);
    apiMocks.getAIConfig.mockReset().mockResolvedValue({ active: '', providers: [] });
    apiMocks.getLocalCliConfig.mockReset().mockResolvedValue({ active: '', configs: [] });
    apiMocks.getMinioConfig.mockReset().mockResolvedValue({
      enabled: false,
      endpoint: '',
      accessKeyId: '',
      useSsl: false,
      bucketName: 'english-material',
      basePath: 'image-story',
      secretConfigured: false,
      updatedAt: null,
    });
    apiMocks.getStoryAgentFlow.mockReset().mockResolvedValue({ stages: [], budget: {} });
    apiMocks.getImageAgentFlow.mockReset().mockResolvedValue({
      stages: [],
      stylePresets: [],
      config: {
        imageProviderId: null,
        width: 1536,
        height: 864,
        maxShotsPerScene: 5,
        maxShotsPerStory: 20,
        updatedAt: '2026-08-15T01:00:00Z',
      },
    });
    apiMocks.updateImageFlowConfig.mockReset().mockResolvedValue({});
    apiMocks.bootstrapAntigravityImageProvider.mockReset().mockResolvedValue({
      active: 'antigravity-gemini-3-1-pro',
      providers: [{
        id: 'antigravity-gemini-image',
        label: 'Antigravity Gemini Image',
        type: 'openai-compatible',
        base_url: 'https://antigravity.example/v1',
        api_key: '',
        model: 'gemini-3-pro-image',
        max_tokens: 4096,
        capabilities: ['IMAGE_GENERATION', 'IMAGE_REFERENCE'],
        options: { responseFormat: 'b64_json', quality: 'hd', size: '1536x864' },
        enabled: true,
      }],
    });
    apiMocks.saveAIConfig.mockReset().mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('exposes all primary destinations in their fixed order', async () => {
    render(<AntApp><App /></AntApp>);

    const navigation = await screen.findByRole('menu', { name: '主导航' });
    expect(within(navigation).getAllByRole('menuitem').map((item) => item.textContent)).toEqual([
      expect.stringContaining('英语素材项目'),
      expect.stringContaining('配置管理'),
      expect.stringContaining('去重单词表'),
      expect.stringContaining('Agent 工作台'),
      expect.stringContaining('图片工作台'),
    ]);
  });

  it('opens Agent generated results from the expandable English material project', async () => {
    const user = userEvent.setup();
    render(<AntApp><App /></AntApp>);

    const navigation = await screen.findByRole('menu', { name: '主导航' });
    expect(screen.queryByRole('menuitem', { name: 'Agent 生成结果' })).not.toBeInTheDocument();
    await user.click(within(navigation).getByRole('menuitem', { name: /英语素材项目/ }));
    await user.click(await screen.findByRole('menuitem', { name: 'Agent 生成结果' }));

    expect(await screen.findByRole('region', { name: 'Agent 生成结果' })).toBeInTheDocument();
  });

  it('uses the existing unsaved guard before opening Agent generated results', async () => {
    const user = userEvent.setup();
    const modal = installConfirmHarness();
    render(<AntApp><App /></AntApp>);

    const navigation = await screen.findByRole('menu', { name: '主导航' });
    await user.click(within(navigation).getByRole('menuitem', { name: /Agent 工作台/ }));
    await user.click(await screen.findByRole('button', { name: '标记 Agent 修改' }));
    await user.click(within(navigation).getByRole('menuitem', { name: /英语素材项目/ }));
    await user.click(await screen.findByRole('menuitem', { name: 'Agent 生成结果' }));

    expect(modal.confirm).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('region', { name: 'Agent 流程工作台' })).toBeInTheDocument();
    modal.confirms[0]?.onOk?.();
    expect(await screen.findByRole('region', { name: 'Agent 生成结果' })).toBeInTheDocument();
  });

  it('opens the Agent flow workbench from primary navigation', async () => {
    const user = userEvent.setup();
    render(<AntApp><App /></AntApp>);

    const navigation = await screen.findByRole('menu', { name: '主导航' });
    await user.click(within(navigation).getByRole('menuitem', { name: /Agent 工作台/ }));

    expect(screen.getByRole('region', { name: 'Agent 流程工作台' })).toBeInTheDocument();
  });

  it('shows image model configuration after local CLI in the configuration menu', async () => {
    const user = userEvent.setup();
    render(<AntApp><App /></AntApp>);

    const menus = await screen.findAllByRole('navigation', { name: '配置管理导航' });
    expect(within(menus[0]).getAllByRole('menuitem').map((item) => item.textContent)).toEqual([
      '数据库配置',
      'AI 配置',
      '本地 CLI 配置',
      'MinIO 配置',
      '图片模型配置',
    ]);

    await user.click(within(menus[0]).getByRole('menuitem', { name: /图片模型配置/ }));
    expect(await screen.findByRole('heading', { name: '图片模型配置' })).toBeInTheDocument();
  });

  it('opens MinIO configuration even when unrelated configuration loading fails', async () => {
    apiMocks.getConnections.mockRejectedValueOnce(new Error('database unavailable'));
    const user = userEvent.setup();
    render(<AntApp><App /></AntApp>);

    const menus = await screen.findAllByRole('navigation', { name: '配置管理导航' });
    await user.click(within(menus[0]).getByRole('menuitem', { name: /MinIO 配置/ }));

    expect(await screen.findByRole('heading', { name: 'MinIO 配置' })).toBeInTheDocument();
    expect(await screen.findByDisplayValue('english-material')).toBeInTheDocument();
  });

  it('bootstraps Antigravity and selects it when the image flow has no executable provider', async () => {
    const user = userEvent.setup();
    render(<AntApp><App /></AntApp>);

    const menus = await screen.findAllByRole('navigation', { name: '配置管理导航' });
    await user.click(within(menus[0]).getByRole('menuitem', { name: /图片模型配置/ }));
    await user.click(await screen.findByRole('button', { name: '引导图片模型' }));

    expect(apiMocks.bootstrapAntigravityImageProvider).toHaveBeenCalledWith('antigravity-gemini-3-1-pro');
    expect(apiMocks.updateImageFlowConfig).toHaveBeenCalledWith({
      imageProviderId: 'antigravity-gemini-image',
      width: 1536,
      height: 864,
      maxShotsPerScene: 5,
      maxShotsPerStory: 20,
      updatedAt: '2026-08-15T01:00:00Z',
    });

    const navigation = screen.getByRole('menu', { name: '主导航' });
    await user.click(within(navigation).getByRole('menuitem', { name: /图片工作台/ }));
    expect(await screen.findByText('Image Providers: 1 Antigravity Gemini Image')).toBeInTheDocument();
  });

  it('does not overwrite an existing executable image provider after bootstrap', async () => {
    const user = userEvent.setup();
    const existing = {
      id: 'existing-image',
      label: 'Existing Image',
      type: 'openai-compatible',
      base_url: 'https://images.example/v1',
      api_key: '',
      model: 'existing-model',
      max_tokens: 4096,
      capabilities: ['IMAGE_GENERATION', 'IMAGE_REFERENCE'],
      options: { responseFormat: 'b64_json', quality: 'hd', size: '1536x864' },
      enabled: true,
    };
    apiMocks.getImageAgentFlow.mockResolvedValue({
      stages: [], stylePresets: [],
      config: {
        imageProviderId: existing.id,
        width: 1536,
        height: 864,
        maxShotsPerScene: 5,
        maxShotsPerStory: 20,
        updatedAt: '2026-08-15T01:00:00Z',
      },
    });
    apiMocks.bootstrapAntigravityImageProvider.mockResolvedValue({
      active: '',
      providers: [existing, {
        ...existing,
        id: 'antigravity-gemini-image',
        label: 'Antigravity Gemini Image',
        model: 'gemini-3-pro-image',
      }],
    });
    render(<AntApp><App /></AntApp>);

    const menus = await screen.findAllByRole('navigation', { name: '配置管理导航' });
    await user.click(within(menus[0]).getByRole('menuitem', { name: /图片模型配置/ }));
    await user.click(await screen.findByRole('button', { name: '引导图片模型' }));

    expect(apiMocks.updateImageFlowConfig).not.toHaveBeenCalled();
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

  it('renders the image workbench with available providers when an unrelated request fails', async () => {
    const user = userEvent.setup();
    apiMocks.getConnections.mockRejectedValue(new Error('database unavailable'));
    apiMocks.getAIConfig.mockResolvedValue({
      active: 'illustrator',
      providers: [{
        id: 'illustrator',
        label: 'Illustrator Provider',
        type: 'openai-compatible',
        base_url: 'https://example.test',
        api_key: '',
        model: 'image-model',
        max_tokens: 4096,
      }],
    });

    render(<AntApp><App /></AntApp>);

    const navigation = await screen.findByRole('menu', { name: '主导航' });
    await user.click(within(navigation).getByRole('menuitem', { name: /图片工作台/ }));

    expect(await screen.findByRole('region', { name: '图片 Agent 工作台' })).toBeInTheDocument();
    expect(await screen.findByText('Image Providers: 1 Illustrator Provider')).toBeInTheDocument();
  });

  it('confirms before leaving a dirty Agent workbench', async () => {
    const user = userEvent.setup();
    const modal = installConfirmHarness();
    render(<AntApp><App /></AntApp>);

    const navigation = await screen.findByRole('menu', { name: '主导航' });
    await user.click(within(navigation).getByRole('menuitem', { name: /Agent 工作台/ }));
    await user.click(await screen.findByRole('button', { name: '标记 Agent 修改' }));
    await user.click(within(navigation).getByRole('menuitem', { name: /配置管理/ }));

    expect(modal.confirm).toHaveBeenCalledTimes(1);
    expect(modal.confirms[0]?.title).toBe('离开 Agent 工作台？');
    modal.confirms[0]?.onCancel?.();
    expect(screen.getByRole('region', { name: 'Agent 流程工作台' })).toBeInTheDocument();
    modal.confirms[0]?.afterClose?.();

    await user.click(screen.getByRole('button', { name: '标记 Agent 修改' }));
    await user.click(within(navigation).getByRole('menuitem', { name: /配置管理/ }));
    expect(modal.confirm).toHaveBeenCalledTimes(2);
    modal.confirms[1]?.onOk?.();

    expect(await screen.findByRole('heading', { name: '环境数据库配置' })).toBeInTheDocument();
  });

  it('guards image workbench edits independently and preserves the page on cancel', async () => {
    const user = userEvent.setup();
    const modal = installConfirmHarness();
    render(<AntApp><App /></AntApp>);

    const navigation = await screen.findByRole('menu', { name: '主导航' });
    await user.click(within(navigation).getByRole('menuitem', { name: /图片工作台/ }));
    await user.click(await screen.findByRole('button', { name: '标记图片 Agent 修改' }));
    await user.click(within(navigation).getByRole('menuitem', { name: /Agent 工作台/ }));

    expect(modal.confirm).toHaveBeenCalledTimes(1);
    expect(modal.confirms[0]?.title).toBe('离开图片工作台？');
    modal.confirms[0]?.onCancel?.();
    expect(screen.getByRole('region', { name: '图片 Agent 工作台' })).toBeInTheDocument();
    modal.confirms[0]?.afterClose?.();

    await user.click(within(navigation).getByRole('menuitem', { name: /Agent 工作台/ }));
    expect(modal.confirm).toHaveBeenCalledTimes(2);
    modal.confirms[1]?.onOk?.();
    expect(await screen.findByRole('region', { name: 'Agent 流程工作台' })).toBeInTheDocument();

    await user.click(within(navigation).getByRole('menuitem', { name: /配置管理/ }));
    expect(await screen.findByRole('heading', { name: '环境数据库配置' })).toBeInTheDocument();
    expect(screen.queryByText('离开 Agent 工作台？')).not.toBeInTheDocument();
  });

  it('keeps the story dirty confirmation locked until its cancelled modal closes', async () => {
    const user = userEvent.setup();
    const modal = installConfirmHarness();
    render(<AntApp><App /></AntApp>);

    const navigation = await screen.findByRole('menu', { name: '主导航' });
    await user.click(within(navigation).getByRole('menuitem', { name: /Agent 工作台/ }));
    await user.click(screen.getByRole('button', { name: '标记 Agent 修改' }));
    await user.click(within(navigation).getByRole('menuitem', { name: /配置管理/ }));
    expect(modal.confirm).toHaveBeenCalledTimes(1);

    modal.confirms[0]?.onCancel?.();
    await user.click(within(navigation).getByRole('menuitem', { name: /图片工作台/ }));

    expect(modal.confirm).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('region', { name: 'Agent 流程工作台' })).toBeInTheDocument();
    modal.confirms[0]?.afterClose?.();

    await user.click(within(navigation).getByRole('menuitem', { name: /去重单词表/ }));
    expect(modal.confirm).toHaveBeenCalledTimes(2);
    modal.confirms[1]?.onOk?.();
    expect(await screen.findByRole('region', { name: '去重单词表' })).toBeInTheDocument();
  });

  it('keeps the image dirty confirmation locked until its cancelled modal closes', async () => {
    const user = userEvent.setup();
    const modal = installConfirmHarness();
    render(<AntApp><App /></AntApp>);

    const navigation = await screen.findByRole('menu', { name: '主导航' });
    await user.click(within(navigation).getByRole('menuitem', { name: /图片工作台/ }));
    await user.click(screen.getByRole('button', { name: '标记图片 Agent 修改' }));
    await user.click(within(navigation).getByRole('menuitem', { name: /配置管理/ }));
    expect(modal.confirm).toHaveBeenCalledTimes(1);

    modal.confirms[0]?.onCancel?.();
    await user.click(within(navigation).getByRole('menuitem', { name: /Agent 工作台/ }));

    expect(modal.confirm).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('region', { name: '图片 Agent 工作台' })).toBeInTheDocument();
    modal.confirms[0]?.afterClose?.();

    await user.click(within(navigation).getByRole('menuitem', { name: /去重单词表/ }));
    expect(modal.confirm).toHaveBeenCalledTimes(2);
    modal.confirms[1]?.onOk?.();
    expect(await screen.findByRole('region', { name: '去重单词表' })).toBeInTheDocument();
  });
});
