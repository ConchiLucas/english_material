import { App as AntApp } from 'antd';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AIProviderConfigItem } from './api';
import ImageAgentFlowPage from './ImageAgentFlowPage';
import type { ImageAgentFlow, ImageAgentNode, ImagePromptVersion, ImageStylePreset } from './image-story-types';

const apiMocks = vi.hoisted(() => ({
  getImageAgentFlow: vi.fn(), updateImageAgent: vi.fn(), getImageAgentVersions: vi.fn(),
  restoreImageAgentVersion: vi.fn(), updateImageFlowConfig: vi.fn(), getImageStylePresets: vi.fn(),
  createImageStylePreset: vi.fn(), updateImageStylePreset: vi.fn(), getImageSourceStories: vi.fn(), createImageRun: vi.fn(),
}));
const historyMocks = vi.hoisted(() => ({ render: vi.fn() }));

vi.mock('./api', async (importOriginal) => ({ ...await importOriginal<typeof import('./api')>(), ...apiMocks }));
vi.mock('./ImageRunHistory', () => ({
  default: (props: { open: boolean; initialRunId?: string; onClose: () => void; afterClose?: () => void }) => {
    historyMocks.render(props);
    return props.open ? <div role="dialog" aria-label="图片运行记录测试替身">
      <span>{props.initialRunId || '无初始批次'}</span>
      <button type="button" onClick={() => { props.onClose(); props.afterClose?.(); }}>关闭记录测试替身</button>
    </div> : null;
  },
}));

const deferred = <T,>() => { let resolve!: (value: T) => void; const promise = new Promise<T>((done) => { resolve = done; }); return { promise, resolve }; };
interface ConfirmLifecycle { title?: unknown; onCancel?: () => void; onOk?: () => void; afterClose?: () => void; }
const installConfirmHarness = () => {
  const confirms: ConfirmLifecycle[] = [];
  const confirm = vi.fn((config: ConfirmLifecycle) => { confirms.push(config); return { destroy: vi.fn(), update: vi.fn() }; });
  vi.spyOn(AntApp, 'useApp').mockReturnValue({ message: { error: vi.fn(), success: vi.fn() }, notification: {}, modal: { confirm } } as never);
  return { confirms, confirm };
};
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
  { id: 'image-two', label: 'Image Two', type: 'openai-compatible', base_url: '', api_key: '', model: 'image-two-model', max_tokens: 4096, capabilities: ['IMAGE_GENERATION', 'IMAGE_REFERENCE'], enabled: true },
  { id: 'image-three', label: 'Image Three', type: 'openai-compatible', base_url: '', api_key: '', model: 'image-three-model', max_tokens: 4096, capabilities: ['IMAGE_GENERATION', 'IMAGE_REFERENCE'], enabled: true },
  { id: 'image-no-ref', label: 'No refs', type: 'openai-compatible', base_url: '', api_key: '', model: 'bad', max_tokens: 4096, capabilities: ['IMAGE_GENERATION'], enabled: true },
  { id: 'anthropic-image', label: 'Anthropic Image', type: 'anthropic-compatible', base_url: '', api_key: '', model: 'anthropic-image-model', max_tokens: 4096, capabilities: ['IMAGE_GENERATION', 'IMAGE_REFERENCE'], enabled: true },
  { id: 'normalized-image', label: 'Normalized Image', type: ' OPENAI-COMPATIBLE ' as AIProviderConfigItem['type'], base_url: '', api_key: '', model: 'normalized-image-model', max_tokens: 4096, capabilities: [' IMAGE_GENERATION ', 'image_reference'], enabled: true },
];
const renderPage = (flow = makeFlow(), onDirtyChange = vi.fn()) => { apiMocks.getImageAgentFlow.mockResolvedValue(flow); apiMocks.getImageStylePresets.mockResolvedValue(flow.stylePresets); return render(<AntApp><ImageAgentFlowPage providers={providers} onDirtyChange={onDirtyChange} /></AntApp>); };

describe('ImageAgentFlowPage', () => {
  beforeEach(() => { vi.clearAllMocks(); apiMocks.getImageSourceStories.mockResolvedValue([]); });
  afterEach(() => { vi.restoreAllMocks(); });

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
    const modal = installConfirmHarness(); const user = userEvent.setup(); renderPage(); await user.type(await screen.findByLabelText('System Prompt'), ' changed'); await user.click(screen.getByRole('tab', { name: '画风预设' }));
    expect(modal.confirm).toHaveBeenCalledTimes(1); act(() => modal.confirms[0]?.onCancel?.()); expect(screen.getByLabelText('System Prompt')).toBeInTheDocument(); act(() => modal.confirms[0]?.afterClose?.());
    await user.click(screen.getByRole('tab', { name: '画风预设' })); act(() => { modal.confirms[1]?.onOk?.(); modal.confirms[1]?.afterClose?.(); }); expect(await screen.findByRole('heading', { name: '画风预设' })).toBeInTheDocument();
  });

  it('protects dirty edits when switching nodes', async () => {
    const user = userEvent.setup(); renderPage(); await user.type(await screen.findByLabelText('System Prompt'), ' changed');
    await user.click(screen.getByRole('button', { name: '连续性 Agent Agent' }));
    const titles = await screen.findAllByText('离开未保存的 Agent？'); const confirm = titles.at(-1)!.closest('.ant-modal-confirm')!; const buttons = confirm.querySelectorAll('button'); await user.click(buttons[buttons.length - 1] as HTMLButtonElement);
    expect(await screen.findByRole('heading', { name: '连续性 Agent' })).toBeInTheDocument();
  });

  it('keeps one dirty confirmation locked until afterClose', async () => {
    const modal = installConfirmHarness(); const user = userEvent.setup(); renderPage();
    await user.type(await screen.findByLabelText('System Prompt'), ' changed');
    await user.click(screen.getByRole('tab', { name: '画风预设' }));
    expect(modal.confirm).toHaveBeenCalledTimes(1);
    act(() => modal.confirms[0]?.onCancel?.());
    await user.click(screen.getByRole('tab', { name: '图片模型' }));
    expect(modal.confirm).toHaveBeenCalledTimes(1);
    act(() => modal.confirms[0]?.afterClose?.());
    await user.click(screen.getByRole('tab', { name: '图片模型' }));
    expect(modal.confirm).toHaveBeenCalledTimes(2);
  });

  it('does not recreate Agent dirty state when a discarded draft save resolves late', async () => {
    const modal = installConfirmHarness(); const pending = deferred<ImageAgentNode>(); const dirty = vi.fn();
    apiMocks.updateImageAgent.mockReturnValue(pending.promise); const user = userEvent.setup(); renderPage(makeFlow(), dirty);
    const prompt = await screen.findByLabelText('System Prompt'); await user.clear(prompt); await user.type(prompt, 'submitted');
    await user.click(screen.getByRole('button', { name: '保存 Agent' })); await user.clear(prompt); await user.type(prompt, 'discard me');
    await user.click(screen.getByRole('tab', { name: '画风预设' })); act(() => { modal.confirms[0]?.onOk?.(); modal.confirms[0]?.afterClose?.(); });
    await act(async () => { pending.resolve({ ...makeFlow().stages[0].nodes[0], systemPrompt: 'submitted', updatedAt: '2026-08-15T02:00:00Z', promptVersion: 2 }); await pending.promise; });
    await waitFor(() => expect(dirty).toHaveBeenLastCalledWith(false));
    await user.click(screen.getByRole('tab', { name: '图片模型' })); expect(modal.confirm).toHaveBeenCalledTimes(1);
    expect(await screen.findByRole('heading', { name: '图片模型' })).toBeInTheDocument();
  });

  it('preserves a reincarnated Agent draft when an older save resolves', async () => {
    const modal = installConfirmHarness(); const pending = deferred<ImageAgentNode>(); const dirty = vi.fn(); apiMocks.updateImageAgent.mockReturnValueOnce(pending.promise).mockResolvedValueOnce({ ...makeFlow().stages[0].nodes[0], systemPrompt: 'reincarnated', temperature: 1.4, updatedAt: '2026-08-15T03:00:00Z', promptVersion: 3 });
    const user = userEvent.setup(); renderPage(makeFlow(), dirty); const prompt = await screen.findByLabelText('System Prompt'); await user.clear(prompt); await user.type(prompt, 'submitted'); await user.click(screen.getByRole('button', { name: '保存 Agent' }));
    await user.click(screen.getByRole('tab', { name: '画风预设' })); act(() => { modal.confirms[0]?.onOk?.(); modal.confirms[0]?.afterClose?.(); }); await user.click(screen.getByRole('tab', { name: 'Agent 配置' }));
    const reincarnated = await screen.findByLabelText('System Prompt'); await user.clear(reincarnated); await user.type(reincarnated, 'reincarnated'); fireEvent.change(screen.getByRole('spinbutton', { name: 'Temperature' }), { target: { value: '1.4' } });
    await act(async () => { pending.resolve({ ...makeFlow().stages[0].nodes[0], systemPrompt: 'submitted', updatedAt: '2026-08-15T02:00:00Z', promptVersion: 2 }); await pending.promise; });
    expect(reincarnated).toHaveValue('reincarnated'); expect(screen.getByRole('spinbutton', { name: 'Temperature' })).toHaveValue('1.4'); await waitFor(() => expect(dirty).toHaveBeenLastCalledWith(true));
    await user.click(screen.getByRole('button', { name: /保存 Agent/ })); await waitFor(() => expect(apiMocks.updateImageAgent).toHaveBeenCalledTimes(2)); expect(apiMocks.updateImageAgent.mock.calls[1]).toEqual(['image-story-analyst', expect.objectContaining({ systemPrompt: 'reincarnated', temperature: 1.4, updatedAt: '2026-08-15T02:00:00Z' })]);
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
    await user.click(screen.getByRole('button', { name: '新增画风' })); await user.type(screen.getByLabelText('画风名称'), '新画风'); await user.type(screen.getByLabelText('正向风格约束'), 'bright'); await user.type(screen.getByLabelText('负向约束'), '  text  '); await user.type(screen.getByLabelText('画风说明'), '  明亮说明  '); await user.click(screen.getByRole('button', { name: '保存画风' }));
    expect(apiMocks.createImageStylePreset).toHaveBeenCalledWith(expect.objectContaining({ name: '新画风', positivePrompt: 'bright', negativePrompt: 'text', description: '明亮说明', enabled: true })); await user.click(screen.getByRole('button', { name: '编辑 水彩绘本' })); expect(screen.getByLabelText('画风名称')).toBeEnabled();
  });

  it('blocks saving a style when negative constraints are blank', async () => {
    const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('tab', { name: '画风预设' }));
    await user.click(screen.getByRole('button', { name: '编辑 水彩绘本' }));
    await user.clear(screen.getByLabelText('负向约束')); await user.type(screen.getByLabelText('负向约束'), '   ');
    await user.click(screen.getByRole('button', { name: '保存画风' }));

    expect(apiMocks.updateImageStylePreset).not.toHaveBeenCalled();
    expect((await screen.findAllByText('负向约束不能为空')).length).toBeGreaterThan(0);
  });

  it('blocks saving a style when its description is blank', async () => {
    const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('tab', { name: '画风预设' }));
    await user.click(screen.getByRole('button', { name: '编辑 水彩绘本' }));
    await user.clear(screen.getByLabelText('画风说明')); await user.type(screen.getByLabelText('画风说明'), '   ');
    await user.click(screen.getByRole('button', { name: '保存画风' }));

    expect(apiMocks.updateImageStylePreset).not.toHaveBeenCalled();
    expect((await screen.findAllByText('画风说明不能为空')).length).toBeGreaterThan(0);
  });

  it('merges a late style create identity into a newer draft and next saves with PUT', async () => {
    const pending = deferred<ImageStylePreset>(); apiMocks.createImageStylePreset.mockReturnValue(pending.promise);
    apiMocks.updateImageStylePreset.mockResolvedValue(style({ id: 8, key: 'new', name: '用户继续修改', builtIn: false, updatedAt: '2026-08-15T03:00:00Z' }));
    const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('tab', { name: '画风预设' })); await user.click(screen.getByRole('button', { name: '新增画风' }));
    await user.type(screen.getByLabelText('画风名称'), '初稿'); await user.type(screen.getByLabelText('正向风格约束'), 'bright'); await user.type(screen.getByLabelText('负向约束'), 'text'); await user.type(screen.getByLabelText('画风说明'), '初稿说明'); await user.click(screen.getByRole('button', { name: '保存画风' }));
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

  it('guards a dirty style before editing another preset or creating a new one', async () => {
    const modal = installConfirmHarness(); const flow = makeFlow(); flow.stylePresets.push(style({ id: 8, key: 'ink', name: '线稿风', builtIn: false }));
    const user = userEvent.setup(); renderPage(flow); await user.click(await screen.findByRole('tab', { name: '画风预设' }));
    await user.click(screen.getByRole('button', { name: '编辑 水彩绘本' })); await user.type(screen.getByLabelText('画风名称'), ' changed');
    await user.click(screen.getByRole('button', { name: '编辑 线稿风' })); expect(modal.confirm).toHaveBeenCalledTimes(1);
    act(() => modal.confirms[0]?.onCancel?.()); expect(screen.getByLabelText('画风名称')).toHaveValue('水彩绘本 changed');
    act(() => modal.confirms[0]?.afterClose?.()); await user.click(screen.getByRole('button', { name: '新增画风' })); expect(modal.confirm).toHaveBeenCalledTimes(2);
    act(() => { modal.confirms[1]?.onOk?.(); modal.confirms[1]?.afterClose?.(); }); expect(screen.getByLabelText('画风名称')).toHaveValue('');
  });

  it('rebases a reincarnated existing style on an older save timestamp', async () => {
    const modal = installConfirmHarness(); const pending = deferred<ImageStylePreset>(); const dirty = vi.fn(); const flow = makeFlow(); flow.stylePresets.push(style({ id: 8, key: 'ink', name: '线稿风', builtIn: false }));
    apiMocks.updateImageStylePreset.mockReturnValueOnce(pending.promise).mockResolvedValueOnce(style({ name: '水彩新稿', positivePrompt: 'fresh watercolor', updatedAt: '2026-08-15T03:00:00Z' }));
    const user = userEvent.setup(); renderPage(flow, dirty); await user.click(await screen.findByRole('tab', { name: '画风预设' })); await user.click(screen.getByRole('button', { name: '编辑 水彩绘本' })); await user.clear(screen.getByLabelText('画风名称')); await user.type(screen.getByLabelText('画风名称'), '待保存水彩'); await user.click(screen.getByRole('button', { name: '保存画风' }));
    await user.click(screen.getByRole('button', { name: '编辑 线稿风' })); act(() => { modal.confirms[0]?.onOk?.(); modal.confirms[0]?.afterClose?.(); }); await user.click(screen.getByRole('button', { name: '编辑 水彩绘本' }));
    await user.clear(screen.getByLabelText('画风名称')); await user.type(screen.getByLabelText('画风名称'), '水彩新稿'); await user.clear(screen.getByLabelText('正向风格约束')); await user.type(screen.getByLabelText('正向风格约束'), 'fresh watercolor');
    await act(async () => { pending.resolve(style({ name: '待保存水彩', updatedAt: '2026-08-15T02:00:00Z' })); await pending.promise; });
    expect(screen.getByLabelText('画风名称')).toHaveValue('水彩新稿'); expect(screen.getByLabelText('正向风格约束')).toHaveValue('fresh watercolor'); await waitFor(() => expect(dirty).toHaveBeenLastCalledWith(true));
    await user.click(screen.getByRole('button', { name: /保存画风/ })); await waitFor(() => expect(apiMocks.updateImageStylePreset).toHaveBeenCalledTimes(2)); expect(apiMocks.updateImageStylePreset.mock.calls[1]).toEqual([7, expect.objectContaining({ name: '水彩新稿', positivePrompt: 'fresh watercolor', updatedAt: '2026-08-15T02:00:00Z' })]); await waitFor(() => expect(dirty).toHaveBeenLastCalledWith(false));
  });

  it('keeps a late created style identity away from a newly selected draft', async () => {
    const modal = installConfirmHarness(); const pending = deferred<ImageStylePreset>(); apiMocks.createImageStylePreset.mockReturnValueOnce(pending.promise).mockResolvedValueOnce(style({ id: 9, key: 'second', name: '第二个', builtIn: false }));
    const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('tab', { name: '画风预设' })); await user.click(screen.getByRole('button', { name: '新增画风' }));
    await user.type(screen.getByLabelText('画风名称'), '第一个'); await user.type(screen.getByLabelText('正向风格约束'), 'bright'); await user.type(screen.getByLabelText('负向约束'), 'text'); await user.type(screen.getByLabelText('画风说明'), '第一个说明'); await user.click(screen.getByRole('button', { name: '保存画风' }));
    await user.click(screen.getByRole('button', { name: '新增画风' })); act(() => { modal.confirms[0]?.onOk?.(); modal.confirms[0]?.afterClose?.(); });
    await act(async () => { pending.resolve(style({ id: 8, key: 'first', name: '第一个', positivePrompt: 'bright', builtIn: false, updatedAt: '2026-08-15T02:00:00Z' })); await pending.promise; });
    expect(screen.getByLabelText('画风名称')).toHaveValue(''); await user.type(screen.getByLabelText('画风名称'), '第二个'); await user.type(screen.getByLabelText('正向风格约束'), 'clean'); await user.type(screen.getByLabelText('负向约束'), 'words'); await user.type(screen.getByLabelText('画风说明'), '第二个说明'); await waitFor(() => expect(screen.getByRole('button', { name: /保存画风/ })).not.toBeDisabled()); await user.click(screen.getByRole('button', { name: /保存画风/ }));
    await waitFor(() => expect(apiMocks.createImageStylePreset).toHaveBeenCalledTimes(2)); expect(apiMocks.updateImageStylePreset).not.toHaveBeenCalled();
  });

  it('filters image providers by both capabilities and keeps fixed limits read-only', async () => {
    const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('tab', { name: '图片模型' })); expect(screen.getByText('1536 × 864')).toBeInTheDocument(); expect(screen.getByText('每 Scene 最多 5 张')).toBeInTheDocument(); expect(screen.getByText('全篇最多 20 张')).toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: '图片 Provider' })); expect(screen.getAllByText('Image · image-model').length).toBeGreaterThan(0); expect(screen.getByText('Normalized Image · normalized-image-model')).toBeInTheDocument(); expect(screen.queryByText('No refs · bad')).not.toBeInTheDocument(); expect(screen.queryByText('Anthropic Image · anthropic-image-model')).not.toBeInTheDocument();
  });

  it('treats a saved non-openai-compatible image provider as invalid', async () => {
    const flow = makeFlow(); flow.config.imageProviderId = 'anthropic-image'; const user = userEvent.setup(); renderPage(flow);
    await user.click(await screen.findByRole('tab', { name: '图片模型' }));

    expect(screen.getByText('当前图片 Provider 已不可用')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '保存图片模型' })).toBeDisabled();
  });

  it('saves image flow config with optimistic timestamp', async () => {
    apiMocks.updateImageFlowConfig.mockResolvedValue({ ...makeFlow().config, updatedAt: '2026-08-15T02:00:00Z' }); const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('tab', { name: '图片模型' })); await user.click(screen.getByRole('button', { name: '保存图片模型' }));
    expect(apiMocks.updateImageFlowConfig).toHaveBeenCalledWith({ imageProviderId: 'image-ok', width: 1536, height: 864, maxShotsPerScene: 5, maxShotsPerStory: 20, updatedAt: '2026-08-15T01:00:00Z' });
  });

  it('does not recreate image-model dirty state when its discarded save resolves late', async () => {
    const modal = installConfirmHarness(); const pending = deferred<ImageAgentFlow['config']>(); const dirty = vi.fn(); apiMocks.updateImageFlowConfig.mockReturnValue(pending.promise);
    const user = userEvent.setup(); renderPage(makeFlow(), dirty); await user.click(await screen.findByRole('tab', { name: '图片模型' }));
    await user.click(screen.getByRole('combobox', { name: '图片 Provider' })); await user.click(await screen.findByText('Image Two · image-two-model')); await user.click(screen.getByRole('button', { name: '保存图片模型' }));
    await user.click(screen.getByRole('tab', { name: '画风预设' })); act(() => { modal.confirms[0]?.onOk?.(); modal.confirms[0]?.afterClose?.(); });
    await act(async () => { pending.resolve({ ...makeFlow().config, imageProviderId: 'image-two', updatedAt: '2026-08-15T02:00:00Z' }); await pending.promise; });
    await waitFor(() => expect(dirty).toHaveBeenLastCalledWith(false)); await user.click(screen.getByRole('tab', { name: 'Agent 配置' })); expect(modal.confirm).toHaveBeenCalledTimes(1);
  });

  it('preserves a reincarnated image-model draft when an older save resolves', async () => {
    const modal = installConfirmHarness(); const pending = deferred<ImageAgentFlow['config']>(); const dirty = vi.fn(); apiMocks.updateImageFlowConfig.mockReturnValueOnce(pending.promise).mockResolvedValueOnce({ ...makeFlow().config, imageProviderId: 'image-three', updatedAt: '2026-08-15T03:00:00Z' });
    const user = userEvent.setup(); renderPage(makeFlow(), dirty); await user.click(await screen.findByRole('tab', { name: '图片模型' })); await user.click(screen.getByRole('combobox', { name: '图片 Provider' })); await user.click(await screen.findByText('Image Two · image-two-model')); await user.click(screen.getByRole('button', { name: '保存图片模型' }));
    await user.click(screen.getByRole('tab', { name: '画风预设' })); act(() => { modal.confirms[0]?.onOk?.(); modal.confirms[0]?.afterClose?.(); }); await user.click(screen.getByRole('tab', { name: '图片模型' })); await user.click(screen.getByRole('combobox', { name: '图片 Provider' })); await user.click(await screen.findByText('Image Three · image-three-model'));
    await act(async () => { pending.resolve({ ...makeFlow().config, imageProviderId: 'image-two', updatedAt: '2026-08-15T02:00:00Z' }); await pending.promise; });
    expect(screen.getByRole('combobox', { name: '图片 Provider' }).closest('.ant-select')).toHaveTextContent('Image Three'); await waitFor(() => expect(dirty).toHaveBeenLastCalledWith(true));
    await user.click(screen.getByRole('button', { name: /保存图片模型/ })); await waitFor(() => expect(apiMocks.updateImageFlowConfig).toHaveBeenCalledTimes(2)); expect(apiMocks.updateImageFlowConfig.mock.calls[1][0]).toEqual(expect.objectContaining({ imageProviderId: 'image-three', updatedAt: '2026-08-15T02:00:00Z' }));
  });

  it('keeps a newer image-model draft dirty and advances its timestamp after a late save', async () => {
    const pending = deferred<ImageAgentFlow['config']>(); apiMocks.updateImageFlowConfig.mockReturnValueOnce(pending.promise).mockResolvedValueOnce({ ...makeFlow().config, updatedAt: '2026-08-15T03:00:00Z' });
    const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('tab', { name: '图片模型' })); await user.click(screen.getByRole('combobox', { name: '图片 Provider' })); await user.click(await screen.findByText('Image Two · image-two-model')); await user.click(screen.getByRole('button', { name: '保存图片模型' }));
    await user.click(screen.getByRole('combobox', { name: '图片 Provider' })); await user.click(screen.getAllByText('Image · image-model').at(-1)!); await act(async () => { pending.resolve({ ...makeFlow().config, imageProviderId: 'image-two', updatedAt: '2026-08-15T02:00:00Z' }); await pending.promise; });
    await waitFor(() => expect(screen.getByRole('button', { name: /保存图片模型/ })).not.toBeDisabled()); await user.click(screen.getByRole('button', { name: /保存图片模型/ })); await waitFor(() => expect(apiMocks.updateImageFlowConfig).toHaveBeenCalledTimes(2)); expect(apiMocks.updateImageFlowConfig.mock.calls[1][0]).toEqual(expect.objectContaining({ imageProviderId: 'image-ok', updatedAt: '2026-08-15T02:00:00Z' }));
  });

  it('previews a source story and prevents double-submit when creating a batch', async () => {
    apiMocks.getImageSourceStories.mockResolvedValue([{ runId: 'story-run-1', words: [{ word: 'book', meaning: '书' }], wordsError: null, targetGrade: '三年级上册', status: 'COMPLETED', finalStory: 'Scene 1: A Book\nAmy opens the book.', createdAt: '2026-08-14T01:00:00Z', finishedAt: '2026-08-14T01:05:00Z' }]);
    const pending = deferred<{ runId: string }>(); apiMocks.createImageRun.mockReturnValue(pending.promise); const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('button', { name: '开始生成' })); const dialogs = await screen.findAllByRole('dialog'); const startDialog = dialogs.at(-1)!;
    await user.click(within(startDialog).getByRole('combobox', { name: '故事批次' })); const storyOptions = await screen.findAllByText(/story-run-1/); await user.click(storyOptions.at(-1)!); expect(within(startDialog).getByText('book')).toBeInTheDocument(); expect(within(startDialog).getByText(/Amy opens the book/)).toBeInTheDocument(); expect(within(startDialog).getByText('16:9 · 每个 Scene 1–5 张 · 最多 20 张')).toBeInTheDocument();
    await user.click(within(startDialog).getByRole('combobox', { name: '画风预设' })); const styleOptions = screen.getAllByText('水彩绘本'); await user.click(styleOptions.at(-1)!); const create = within(startDialog).getByRole('button', { name: '创建图片批次' }); await user.click(create); await user.click(create); expect(apiMocks.createImageRun).toHaveBeenCalledTimes(1); expect(apiMocks.createImageRun).toHaveBeenCalledWith({ storyRunId: 'story-run-1', stylePresetId: 7 });
    await act(async () => { pending.resolve({ runId: 'image-run-1' }); await pending.promise; });
    const history = await screen.findByRole('dialog', { name: '图片运行记录测试替身' });
    expect(history).toHaveTextContent('image-run-1');
  });

  it('opens read-only image records from the header without discarding a dirty configuration draft', async () => {
    const modal = installConfirmHarness(); const dirty = vi.fn(); const user = userEvent.setup(); renderPage(makeFlow(), dirty);
    const prompt = await screen.findByLabelText('System Prompt'); await user.type(prompt, ' unsaved history-safe draft');
    const historyButton = screen.getByRole('button', { name: '图片记录' }); await user.click(historyButton);

    expect(modal.confirm).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog', { name: '图片运行记录测试替身' })).toHaveTextContent('无初始批次');
    await user.click(screen.getByRole('button', { name: '关闭记录测试替身' }));
    expect(screen.queryByRole('dialog', { name: '图片运行记录测试替身' })).not.toBeInTheDocument();
    expect(historyButton).toHaveFocus();
    expect(prompt).toHaveValue('image-story-analyst prompt unsaved history-safe draft');
    expect(dirty).toHaveBeenLastCalledWith(true);
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

  it('clears a selected story that is absent from the latest source response', async () => {
    const first = { runId: 'story-run-1', words: [], wordsError: null, targetGrade: '三年级上册', status: 'COMPLETED', finalStory: 'A complete story.', createdAt: '2026-08-14T01:00:00Z', finishedAt: null };
    const second = { ...first, runId: 'story-run-2', finalStory: 'Another complete story.' }; apiMocks.getImageSourceStories.mockResolvedValueOnce([first]).mockResolvedValueOnce([second]);
    const user = userEvent.setup(); renderPage(); await user.click(await screen.findByRole('button', { name: '开始生成' })); let dialog = (await screen.findAllByRole('dialog')).at(-1)!;
    await user.click(within(dialog).getByRole('combobox', { name: '故事批次' })); await user.click((await screen.findAllByText(/story-run-1/)).at(-1)!); await user.click(within(dialog).getByRole('combobox', { name: '画风预设' })); await user.click(screen.getAllByText('水彩绘本').at(-1)!);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' })); fireEvent.click(screen.getByRole('button', { name: '开始生成' })); await waitFor(() => expect(apiMocks.getImageSourceStories).toHaveBeenCalledTimes(2)); dialog = (await screen.findAllByRole('dialog')).at(-1)!; await waitFor(() => expect(within(dialog).getByRole('button', { name: '创建图片批次' })).toBeDisabled());
    fireEvent.click(within(dialog).getByRole('button', { name: '创建图片批次' })); expect(apiMocks.createImageRun).not.toHaveBeenCalled();
  });

  it('blocks a selected style after it becomes disabled even when another style is enabled', async () => {
    const source = { runId: 'story-run-1', words: [], wordsError: null, targetGrade: '三年级上册', status: 'COMPLETED', finalStory: 'A complete story.', createdAt: '2026-08-14T01:00:00Z', finishedAt: null }; apiMocks.getImageSourceStories.mockResolvedValue([source]);
    const flow = makeFlow(); flow.stylePresets.push(style({ id: 8, key: 'ink', name: '线稿风', builtIn: false })); apiMocks.updateImageStylePreset.mockResolvedValue(style({ enabled: false, updatedAt: '2026-08-15T02:00:00Z' }));
    const user = userEvent.setup(); renderPage(flow); await user.click(await screen.findByRole('button', { name: '开始生成' })); let dialog = (await screen.findAllByRole('dialog')).at(-1)!;
    await user.click(within(dialog).getByRole('combobox', { name: '画风预设' })); await user.click(screen.getAllByText('水彩绘本').at(-1)!); fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }));
    fireEvent.click(screen.getByRole('tab', { name: '画风预设' })); await user.click(await screen.findByRole('button', { name: '编辑 水彩绘本' })); await user.click(screen.getByRole('switch', { name: '启用画风' })); await user.click(screen.getByRole('button', { name: '保存画风' })); await waitFor(() => expect(apiMocks.updateImageStylePreset).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '开始生成' })); dialog = (await screen.findAllByRole('dialog')).at(-1)!; await user.click(within(dialog).getByRole('combobox', { name: '故事批次' })); await user.click((await screen.findAllByText(/story-run-1/)).at(-1)!); await waitFor(() => expect(within(dialog).getByRole('button', { name: '创建图片批次' })).toBeDisabled());
    fireEvent.click(within(dialog).getByRole('button', { name: '创建图片批次' })); expect(apiMocks.createImageRun).not.toHaveBeenCalled();
  });

  it('shows preflight as a direct dependency of both generation programs', async () => {
    const user = userEvent.setup(); renderPage(); const detail = await screen.findByLabelText('图片节点详情');
    await user.click(screen.getByRole('button', { name: '分镜图生成 PROGRAM' })); expect(within(detail).getByText(/出图校对 Agent.*参考图生成/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '文字合成 PROGRAM' })); expect(within(detail).getByText(/出图校对 Agent.*分镜图生成/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '出图校对 Agent Agent' })); expect(within(detail).getByText(/参考图生成.*分镜图生成.*文字合成/)).toBeInTheDocument();
  });

  it('uses the single-column detail-first layout at 1300px and scrolls the selected detail', async () => {
    vi.spyOn(window, 'matchMedia').mockImplementation((query) => ({ matches: query === '(max-width: 1320px)', media: query, onchange: null, addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn() } as MediaQueryList));
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => { callback(0); return 1; }); const user = userEvent.setup(); renderPage(); const detail = await screen.findByLabelText('图片节点详情'); const scroll = vi.fn(); detail.scrollIntoView = scroll;
    await user.click(screen.getByRole('button', { name: '连续性 Agent Agent' })); expect(scroll).toHaveBeenCalledWith({ block: 'start', behavior: 'smooth' });
  });

  it('blocks an empty or invalid image provider and has no review, scoring, or redraw controls', async () => {
    const flow = makeFlow(); flow.config.imageProviderId = 'image-no-ref'; const user = userEvent.setup(); const view = renderPage(flow); await user.click(await screen.findByRole('tab', { name: '图片模型' }));
    expect(screen.getByText('当前图片 Provider 已不可用')).toBeInTheDocument(); expect(screen.getByRole('button', { name: '保存图片模型' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /评分|审核|重绘|重新生成/ })).not.toBeInTheDocument();
    view.unmount(); const emptyFlow = makeFlow(); emptyFlow.config.imageProviderId = null; apiMocks.getImageAgentFlow.mockResolvedValue(emptyFlow);
    render(<AntApp><ImageAgentFlowPage providers={providers.filter((item) => item.id === 'text-ok')} onDirtyChange={vi.fn()} /></AntApp>); await user.click(await screen.findByRole('tab', { name: '图片模型' }));
    expect(screen.getByText('没有可执行的 OpenAI-compatible 图片 Provider')).toBeInTheDocument(); expect(screen.getByRole('button', { name: '保存图片模型' })).toBeDisabled();
  });
});
