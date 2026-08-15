import { App as AntApp } from 'antd';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AIProviderConfigItem } from './api';
import ImageAgentFlowPage from './ImageAgentFlowPage';
import type { ImageAgentFlow, ImageAgentNode, ImagePromptVersion, ImageStylePreset } from './image-story-types';

const apiMocks = vi.hoisted(() => ({
  getImageAgentFlow: vi.fn(), updateImageAgent: vi.fn(), getImageAgentVersions: vi.fn(),
  restoreImageAgentVersion: vi.fn(), updateImageFlowConfig: vi.fn(), getImageStylePresets: vi.fn(),
  createImageStylePreset: vi.fn(), updateImageStylePreset: vi.fn(), getImageSourceStories: vi.fn(), createImageRun: vi.fn(),
}));

vi.mock('./api', async (importOriginal) => ({ ...await importOriginal<typeof import('./api')>(), ...apiMocks }));

const deferred = <T,>() => { let resolve!: (value: T) => void; const promise = new Promise<T>((done) => { resolve = done; }); return { promise, resolve }; };
const node = (key: string, name: string, stageKey: string, order: number, overrides: Partial<ImageAgentNode> = {}): ImageAgentNode => ({
  key, name, stageKey, order, nodeKind: 'AGENT', roleType: 'PLANNER', parallelGroup: null,
  description: `${name} description`, variables: ['storySnapshot'], systemPrompt: `${key} prompt`, aiProviderId: 'text-ok',
  temperature: 0.7, enabled: true, promptVersion: 1, updatedAt: '2026-08-15T01:00:00Z', editable: true, ...overrides,
});
const program = (key: string, name: string, stageKey: string, order: number): ImageAgentNode => node(key, name, stageKey, order, {
  nodeKind: 'PROGRAM', roleType: 'PROGRAM', editable: false, systemPrompt: null, aiProviderId: null,
  temperature: null, enabled: null, promptVersion: null, updatedAt: null,
});
const style = (overrides: Partial<ImageStylePreset> = {}): ImageStylePreset => ({
  id: 7, key: 'watercolor', name: '水彩绘本', positivePrompt: 'soft watercolor', negativePrompt: 'dark',
  description: '明亮的儿童绘本', enabled: true, builtIn: true, updatedAt: '2026-08-15T01:00:00Z', ...overrides,
});
const makeFlow = (): ImageAgentFlow => ({
  stages: [
    { key: 'understanding', name: '故事理解', note: '并行理解', order: 1, nodes: [node('image-story-analyst', '故事分析 Agent', 'understanding', 10, { parallelGroup: 'image-foundation' }), node('image-continuity-designer', '连续性 Agent', 'understanding', 20, { parallelGroup: 'image-foundation' }), node('image-art-director', '美术导演 Agent', 'understanding', 30, { parallelGroup: 'image-foundation' })] },
    { key: 'storyboarding', name: '分镜决策', note: '双提案', order: 2, nodes: [node('image-action-storyboarder', '动作分镜 Agent', 'storyboarding', 10, { parallelGroup: 'image-storyboards' }), node('image-learning-storyboarder', '学习分镜 Agent', 'storyboarding', 20, { parallelGroup: 'image-storyboards' }), node('image-storyboard-director', '分镜总监 Agent', 'storyboarding', 30)] },
    { key: 'prompting', name: '提示词准备', note: '准备计划', order: 3, nodes: [node('image-reference-planner', '参考图规划 Agent', 'prompting', 10), node('image-shot-prompt-engineer', '镜头提示词 Agent', 'prompting', 20), node('image-prompt-preflight', '出图校对 Agent', 'prompting', 30)] },
    { key: 'generation', name: '图片生成', note: '确定性程序', order: 4, nodes: [program('reference-image-generator', '参考图生成', 'generation', 10), program('shot-image-generator', '分镜图生成', 'generation', 20), program('text-compositor', '文字合成', 'generation', 30)] },
  ], config: { imageProviderId: 'image-ok', width: 1536, height: 864, maxShotsPerScene: 5, maxShotsPerStory: 20, updatedAt: '2026-08-15T01:00:00Z' }, stylePresets: [style()],
});
const providers: AIProviderConfigItem[] = [
  { id: 'text-ok', label: 'Text', type: 'openai-compatible', base_url: '', api_key: '', model: 'text-model', max_tokens: 4096, capabilities: ['TEXT_GENERATION'], enabled: true },
  { id: 'text-off', label: 'Text Off', type: 'openai-compatible', base_url: '', api_key: '', model: 'off', max_tokens: 4096, capabilities: ['TEXT_GENERATION'], enabled: false },
  { id: 'image-ok', label: 'Image', type: 'openai-compatible', base_url: '', api_key: '', model: 'image-model', max_tokens: 4096, capabilities: ['IMAGE_GENERATION', 'IMAGE_REFERENCE'], enabled: true },
  { id: 'image-no-ref', label: 'No refs', type: 'openai-compatible', base_url: '', api_key: '', model: 'bad', max_tokens: 4096, capabilities: ['IMAGE_GENERATION'], enabled: true },
];
const renderPage = (flow = makeFlow(), onDirtyChange = vi.fn()) => { apiMocks.getImageAgentFlow.mockResolvedValue(flow); apiMocks.getImageStylePresets.mockResolvedValue(flow.stylePresets); return render(<AntApp><ImageAgentFlowPage providers={providers} onDirtyChange={onDirtyChange} /></AntApp>); };

describe('ImageAgentFlowPage', () => {
  beforeEach(() => { vi.clearAllMocks(); apiMocks.getImageSourceStories.mockResolvedValue([]); });

  it('renders four fixed stages, nine Agents and three clickable read-only programs', async () => {
    const user = userEvent.setup(); renderPage(); expect(await screen.findByText('故事理解')).toBeInTheDocument();
    const canvas = screen.getByLabelText('图片 Agent 固定流程'); expect(within(canvas).getAllByRole('button', { name: /Agent$/ })).toHaveLength(9); expect(within(canvas).getAllByRole('button', { name: /PROGRAM$/ })).toHaveLength(3);
    await user.click(screen.getByRole('button', { name: '参考图生成 PROGRAM' })); expect(screen.getByText('程序节点 · 0 Token')).toBeInTheDocument();
  });

  it('keeps exactly three configuration tabs and opens generation from the header modal', async () => {
    const user = userEvent.setup(); renderPage();
    const tabs = await screen.findAllByRole('tab');
    expect(tabs.map((item) => item.textContent)).toEqual(['Agent 配置', '画风预设', '图片模型']);
    expect(screen.queryByRole('tab', { name: '开始生成' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '开始生成' }));
    expect(await screen.findByRole('dialog', { name: '开始生成图片故事' })).toBeInTheDocument();
  });

  it('derives and displays fixed upstream and downstream relationships', async () => {
    const user = userEvent.setup(); renderPage();
    const detail = await screen.findByLabelText('图片节点详情');
    expect(within(detail).getByText('上游')).toBeInTheDocument();
    expect(within(detail).getByText('无')).toBeInTheDocument();
    expect(within(detail).getByText(/动作分镜 Agent.*学习分镜 Agent/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '分镜总监 Agent Agent' }));
    expect(within(detail).getByText(/故事分析 Agent.*连续性 Agent.*美术导演 Agent.*动作分镜 Agent.*学习分镜 Agent/)).toBeInTheDocument();
    expect(within(detail).getByText(/参考图规划 Agent.*镜头提示词 Agent/)).toBeInTheDocument();
  });

  it('filters text providers and warns for an invalid saved provider', async () => {
    const flow = makeFlow(); flow.stages[0].nodes[0].aiProviderId = 'deleted-provider'; renderPage(flow);
    await screen.findByRole('combobox', { name: '文本 Provider' }); expect(screen.getByText('当前 Provider 已不可用')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('combobox', { name: '文本 Provider' })); expect(screen.getByText('Text · text-model')).toBeInTheDocument(); expect(screen.queryByText('Text Off · off')).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: '保存 Agent' }));
    expect(apiMocks.updateImageAgent).not.toHaveBeenCalled();
  });

  it('trims prompt and saves the exact editable payload', async () => {
    apiMocks.updateImageAgent.mockImplementation(async (_key, value) => ({ ...makeFlow().stages[0].nodes[0], ...value, updatedAt: '2026-08-15T02:00:00Z', promptVersion: 2 }));
    const dirty = vi.fn(); const user = userEvent.setup(); renderPage(makeFlow(), dirty); const prompt = await screen.findByLabelText('System Prompt');
    await user.clear(prompt); await user.type(prompt, '  revised prompt  '); fireEvent.change(screen.getByRole('spinbutton', { name: 'Temperature' }), { target: { value: '1.2' } }); await user.click(screen.getByRole('switch', { name: '启用 Agent' })); await user.click(screen.getByRole('button', { name: '保存 Agent' }));
    expect(apiMocks.updateImageAgent).toHaveBeenCalledWith('image-story-analyst', { systemPrompt: 'revised prompt', aiProviderId: 'text-ok', temperature: 1.2, enabled: false, updatedAt: '2026-08-15T01:00:00Z' });
    await waitFor(() => expect(dirty).toHaveBeenLastCalledWith(false));
  });

  it('keeps a newer draft when a late save resolves and advances its timestamp', async () => {
    const pending = deferred<ImageAgentNode>(); apiMocks.updateImageAgent.mockReturnValue(pending.promise); const user = userEvent.setup(); renderPage(); const prompt = await screen.findByLabelText('System Prompt');
    await user.clear(prompt); await user.type(prompt, 'first'); await user.click(screen.getByRole('button', { name: '保存 Agent' })); await user.clear(prompt); await user.type(prompt, 'newer draft');
    await act(async () => { pending.resolve({ ...makeFlow().stages[0].nodes[0], systemPrompt: 'first', updatedAt: '2026-08-15T02:00:00Z', promptVersion: 2 }); await pending.promise; }); expect(prompt).toHaveValue('newer draft');
    apiMocks.updateImageAgent.mockResolvedValue(makeFlow().stages[0].nodes[0]); await waitFor(() => expect(screen.getByRole('button', { name: /保存 Agent/ })).toBeInTheDocument()); await user.click(screen.getByRole('button', { name: /保存 Agent/ })); await waitFor(() => expect(apiMocks.updateImageAgent).toHaveBeenCalledTimes(2)); expect(apiMocks.updateImageAgent.mock.calls[1][1].updatedAt).toBe('2026-08-15T02:00:00Z');
  });

  it('protects dirty edits when switching tabs and discards only after confirmation', async () => {
    const user = userEvent.setup(); renderPage(); await user.type(await screen.findByLabelText('System Prompt'), ' changed'); await user.click(screen.getByRole('tab', { name: '画风预设' }));
    const titles = await screen.findAllByText('离开未保存的 Agent？'); const firstConfirm = titles.at(-1)!.closest('.ant-modal-confirm')!; await user.click(firstConfirm.querySelectorAll('button')[0] as HTMLButtonElement); expect(screen.getByLabelText('System Prompt')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: '画风预设' })); const nextTitles = await screen.findAllByText('离开未保存的 Agent？'); const secondConfirm = nextTitles.at(-1)!.closest('.ant-modal-confirm')!; const secondButtons = secondConfirm.querySelectorAll('button'); await user.click(secondButtons[secondButtons.length - 1] as HTMLButtonElement); expect(await screen.findByRole('heading', { name: '画风预设' })).toBeInTheDocument();
  });

  it('protects dirty edits when switching nodes', async () => {
    const user = userEvent.setup(); renderPage(); await user.type(await screen.findByLabelText('System Prompt'), ' changed');
    await user.click(screen.getByRole('button', { name: '连续性 Agent Agent' }));
    const titles = await screen.findAllByText('离开未保存的 Agent？'); const confirm = titles.at(-1)!.closest('.ant-modal-confirm')!; const buttons = confirm.querySelectorAll('button'); await user.click(buttons[buttons.length - 1] as HTMLButtonElement);
    expect(await screen.findByRole('heading', { name: '连续性 Agent' })).toBeInTheDocument();
  });

  it('loads versions newest-first and restores using the latest timestamp', async () => {
    const versions: ImagePromptVersion[] = [{ version: 1, systemPrompt: 'old', aiProviderId: 'text-ok', temperature: 0.4, enabled: true, createdAt: '2026-08-14T01:00:00Z' }, { version: 2, systemPrompt: 'new', aiProviderId: 'text-ok', temperature: 0.5, enabled: true, createdAt: '2026-08-15T01:00:00Z' }];
    apiMocks.getImageAgentVersions.mockResolvedValue(versions); apiMocks.restoreImageAgentVersion.mockResolvedValue({ ...makeFlow().stages[0].nodes[0], systemPrompt: 'old', updatedAt: '2026-08-15T03:00:00Z', promptVersion: 3 });
    const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('button', { name: '版本历史' })); const dialogs = await screen.findAllByRole('dialog'); const dialog = dialogs.at(-1)!; const items = await within(dialog).findAllByText(/Prompt v[12]/); expect(items[0]).toHaveTextContent('Prompt v2');
    await user.click(within(dialog).getByRole('button', { name: '恢复 Prompt v1' })); const restoreTitles = await screen.findAllByText('恢复 Prompt v1？'); const restoreConfirm = restoreTitles.at(-1)!.closest('.ant-modal-confirm')!; const restoreButtons = restoreConfirm.querySelectorAll('button'); await user.click(restoreButtons[restoreButtons.length - 1] as HTMLButtonElement);
    await waitFor(() => expect(apiMocks.restoreImageAgentVersion).toHaveBeenCalledWith('image-story-analyst', 1, { updatedAt: '2026-08-15T01:00:00Z' }));
  });

  it('shows version loading and empty states', async () => {
    const pending = deferred<ImagePromptVersion[]>(); apiMocks.getImageAgentVersions.mockReturnValue(pending.promise); const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('button', { name: '版本历史' }));
    expect(screen.getByLabelText('正在加载版本历史')).toBeInTheDocument(); await act(async () => pending.resolve([])); expect(await screen.findByText('暂无 Prompt 版本')).toBeInTheDocument();
  });

  it('creates styles and permits editing a built-in preset', async () => {
    apiMocks.createImageStylePreset.mockResolvedValue(style({ id: 8, key: 'new', name: '新画风', builtIn: false })); const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('tab', { name: '画风预设' }));
    await user.click(screen.getByRole('button', { name: '新增画风' })); await user.type(screen.getByLabelText('画风名称'), '新画风'); await user.type(screen.getByLabelText('正向风格约束'), 'bright'); await user.click(screen.getByRole('button', { name: '保存画风' }));
    expect(apiMocks.createImageStylePreset).toHaveBeenCalledWith(expect.objectContaining({ name: '新画风', positivePrompt: 'bright', enabled: true })); await user.click(screen.getByRole('button', { name: '编辑 水彩绘本' })); expect(screen.getByLabelText('画风名称')).toBeEnabled();
  });

  it('merges a late style create identity into a newer draft and next saves with PUT', async () => {
    const pending = deferred<ImageStylePreset>(); apiMocks.createImageStylePreset.mockReturnValue(pending.promise);
    apiMocks.updateImageStylePreset.mockResolvedValue(style({ id: 8, key: 'new', name: '用户继续修改', builtIn: false, updatedAt: '2026-08-15T03:00:00Z' }));
    const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('tab', { name: '画风预设' })); await user.click(screen.getByRole('button', { name: '新增画风' }));
    await user.type(screen.getByLabelText('画风名称'), '初稿'); await user.type(screen.getByLabelText('正向风格约束'), 'bright'); await user.click(screen.getByRole('button', { name: '保存画风' }));
    await user.clear(screen.getByLabelText('画风名称')); await user.type(screen.getByLabelText('画风名称'), '用户继续修改');
    await act(async () => { pending.resolve(style({ id: 8, key: 'new', name: '初稿', positivePrompt: 'bright', builtIn: false, updatedAt: '2026-08-15T02:00:00Z' })); await pending.promise; });
    expect(screen.getByLabelText('画风名称')).toHaveValue('用户继续修改');
    await waitFor(() => expect(screen.getByRole('button', { name: /保存画风/ })).toBeInTheDocument()); await user.click(screen.getByRole('button', { name: /保存画风/ }));
    await waitFor(() => expect(apiMocks.updateImageStylePreset).toHaveBeenCalledWith(8, expect.objectContaining({ name: '用户继续修改', updatedAt: '2026-08-15T02:00:00Z' })));
    expect(apiMocks.createImageStylePreset).toHaveBeenCalledTimes(1);
  });

  it('updates and disables a built-in style with its optimistic timestamp', async () => {
    apiMocks.updateImageStylePreset.mockResolvedValue(style({ enabled: false, updatedAt: '2026-08-15T02:00:00Z' })); const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('tab', { name: '画风预设' })); await user.click(screen.getByRole('button', { name: '编辑 水彩绘本' }));
    await user.click(screen.getByRole('switch', { name: '启用画风' })); await user.click(screen.getByRole('button', { name: '保存画风' }));
    expect(apiMocks.updateImageStylePreset).toHaveBeenCalledWith(7, expect.objectContaining({ enabled: false, updatedAt: '2026-08-15T01:00:00Z' }));
  });

  it('filters image providers by both capabilities and keeps fixed limits read-only', async () => {
    const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('tab', { name: '图片模型' })); expect(screen.getByText('1536 × 864')).toBeInTheDocument(); expect(screen.getByText('每 Scene 最多 5 张')).toBeInTheDocument(); expect(screen.getByText('全篇最多 20 张')).toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: '图片 Provider' })); expect(screen.getAllByText('Image · image-model').length).toBeGreaterThan(0); expect(screen.queryByText('No refs · bad')).not.toBeInTheDocument();
  });

  it('saves image flow config with optimistic timestamp', async () => {
    apiMocks.updateImageFlowConfig.mockResolvedValue({ ...makeFlow().config, updatedAt: '2026-08-15T02:00:00Z' }); const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('tab', { name: '图片模型' })); await user.click(screen.getByRole('button', { name: '保存图片模型' }));
    expect(apiMocks.updateImageFlowConfig).toHaveBeenCalledWith({ imageProviderId: 'image-ok', width: 1536, height: 864, maxShotsPerScene: 5, maxShotsPerStory: 20, updatedAt: '2026-08-15T01:00:00Z' });
  });

  it('previews a source story and prevents double-submit when creating a batch', async () => {
    apiMocks.getImageSourceStories.mockResolvedValue([{ runId: 'story-run-1', words: [{ word: 'book', meaning: '书' }], wordsError: null, targetGrade: '三年级上册', status: 'COMPLETED', finalStory: 'Scene 1: A Book\nAmy opens the book.', createdAt: '2026-08-14T01:00:00Z', finishedAt: '2026-08-14T01:05:00Z' }]);
    const pending = deferred<{ runId: string }>(); apiMocks.createImageRun.mockReturnValue(pending.promise); const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('button', { name: '开始生成' })); const dialogs = await screen.findAllByRole('dialog'); const startDialog = dialogs.at(-1)!;
    await user.click(within(startDialog).getByRole('combobox', { name: '故事批次' })); const storyOptions = await screen.findAllByText(/story-run-1/); await user.click(storyOptions.at(-1)!); expect(within(startDialog).getByText('book')).toBeInTheDocument(); expect(within(startDialog).getByText(/Amy opens the book/)).toBeInTheDocument(); expect(within(startDialog).getByText('16:9 · 每个 Scene 1–5 张 · 最多 20 张')).toBeInTheDocument();
    await user.click(within(startDialog).getByRole('combobox', { name: '画风预设' })); const styleOptions = screen.getAllByText('水彩绘本'); await user.click(styleOptions.at(-1)!); const create = within(startDialog).getByRole('button', { name: '创建图片批次' }); await user.click(create); await user.click(create); expect(apiMocks.createImageRun).toHaveBeenCalledTimes(1); expect(apiMocks.createImageRun).toHaveBeenCalledWith({ storyRunId: 'story-run-1', stylePresetId: 7 });
    await act(async () => { pending.resolve({ runId: 'image-run-1' }); await pending.promise; }); expect(await screen.findByText('批次已创建：image-run-1')).toBeInTheDocument();
  });

  it('blocks generation with explicit reasons when prerequisites are unavailable', async () => {
    const flow = makeFlow(); flow.stylePresets = [style({ enabled: false })]; flow.config.imageProviderId = null; renderPage(flow); await userEvent.setup().click(await screen.findByRole('button', { name: '开始生成' }));
    expect(screen.getByText(/没有可用的故事批次/)).toBeInTheDocument(); expect(screen.getByText(/没有启用的画风预设/)).toBeInTheDocument(); expect(screen.getByText(/尚未配置可用的图片 Provider/)).toBeInTheDocument(); expect(screen.getByRole('button', { name: '创建图片批次' })).toBeDisabled();
  });

  it('blocks generation when a required Agent is disabled or has an invalid text provider', async () => {
    apiMocks.getImageSourceStories.mockResolvedValue([{ runId: 'story-run-1', words: [], wordsError: null, targetGrade: '三年级上册', status: 'COMPLETED', finalStory: 'Scene 1: Story\nA story.', createdAt: '2026-08-14T01:00:00Z', finishedAt: null }]);
    const flow = makeFlow(); flow.stages[0].nodes[0].enabled = false; flow.stages[0].nodes[1].aiProviderId = 'deleted-provider'; const user = userEvent.setup(); renderPage(flow); await user.click(await screen.findByRole('button', { name: '开始生成' }));
    const dialogs = await screen.findAllByRole('dialog'); const startDialog = dialogs.at(-1)!;
    expect(within(startDialog).getByText(/故事分析 Agent.*已停用/)).toBeInTheDocument(); expect(within(startDialog).getByText(/连续性 Agent.*文本 Provider 不可用/)).toBeInTheDocument(); expect(within(startDialog).getByRole('button', { name: '创建图片批次' })).toBeDisabled();
  });

  it('blocks an empty or invalid image provider and has no review, scoring, or redraw controls', async () => {
    const flow = makeFlow(); flow.config.imageProviderId = 'image-no-ref'; const user = userEvent.setup(); const view = renderPage(flow); await user.click(await screen.findByRole('tab', { name: '图片模型' }));
    expect(screen.getByText('当前图片 Provider 已不可用')).toBeInTheDocument(); expect(screen.getByRole('button', { name: '保存图片模型' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /评分|审核|重绘|重新生成/ })).not.toBeInTheDocument();
    view.unmount(); const emptyFlow = makeFlow(); emptyFlow.config.imageProviderId = null; apiMocks.getImageAgentFlow.mockResolvedValue(emptyFlow);
    render(<AntApp><ImageAgentFlowPage providers={providers.filter((item) => !item.capabilities?.includes('IMAGE_GENERATION'))} onDirtyChange={vi.fn()} /></AntApp>); await user.click(await screen.findByRole('tab', { name: '图片模型' }));
    expect(screen.getByText('没有同时支持图片生成和多参考图的 Provider')).toBeInTheDocument(); expect(screen.getByRole('button', { name: '保存图片模型' })).toBeDisabled();
  });
});
