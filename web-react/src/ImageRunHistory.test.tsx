import { App as AntApp } from 'antd';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useRef, useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ImageRunHistory from './ImageRunHistory';
import type { ImageRunDetail, ImageRunSummary } from './image-story-types';

const apiMocks = vi.hoisted(() => ({ getImageRuns: vi.fn(), getImageRun: vi.fn(), imageAssetUrl: vi.fn() }));
vi.mock('./api', async (importOriginal) => ({
  ...await importOriginal<typeof import('./api')>(),
  ...apiMocks,
}));

const summary = (runId: string, createdAt: string, words: string[], status = 'COMPLETED'): ImageRunSummary => ({
  runId,
  storyRunId: `story-${runId}`,
  stylePresetId: 7,
  stylePresetName: '水彩绘本',
  targetGrade: '三年级上册',
  words: words.map((word) => ({ word, meaning: `${word} meaning` })),
  wordsError: null,
  status,
  expectedImageCount: 2,
  generatedImageCount: status === 'COMPLETED' ? 2 : 0,
  totalTextTokens: 100,
  errorMessage: null,
  createdAt,
  startedAt: createdAt,
  finishedAt: status === 'COMPLETED' ? createdAt : null,
});

const detail = (runId: string, words: string[], status = 'COMPLETED'): ImageRunDetail => ({
  ...summary(runId, '2026-08-15T03:00:00Z', words, status),
  storySnapshot: 'Scene 1: Cake\nThe elephant lifts the cakes high.',
  stylePresetId: '7',
  styleSnapshotJson: '{"name":"水彩绘本"}',
  flowSnapshotJson: '{"width":1536,"height":864}',
  agentSnapshotSchemaVersion: 1,
  agentSnapshots: [],
  agentSnapshotError: null,
  steps: [
    {
      id: 22, sequence: 2, stageKey: 'generation', nodeKey: 'text-compositor', nodeName: '文字合成', nodeKind: 'PROGRAM',
      promptVersion: null, providerId: null, providerModel: null, inputJson: '{"shotKey":"scene-1-shot-1"}', rawOutput: 'final image saved',
      parsedOutputJson: null, errorMessage: null, status: 'COMPLETED', inputTokens: 0, outputTokens: 0, totalTokens: 0,
      durationMs: 12, startedAt: '2026-08-15T03:00:02Z', finishedAt: '2026-08-15T03:00:03Z', createdAt: '2026-08-15T03:00:02Z',
    },
    {
      id: 11, sequence: 1, stageKey: 'understanding', nodeKey: 'image-story-analyst', nodeName: '故事结构分析', nodeKind: 'AGENT',
      promptVersion: 3, providerId: 'text-provider', providerModel: 'gemini', inputJson: '{"storySnapshot":"complete input snapshot"}',
      rawOutput: 'STORY_ANALYSIS_JSON_BEGIN\n{"scenes":["complete raw output"]}\nSTORY_ANALYSIS_JSON_END', parsedOutputJson: '{"scenes":[]}',
      errorMessage: null, status: 'COMPLETED', inputTokens: 10, outputTokens: 20, totalTokens: 30, durationMs: 100,
      startedAt: '2026-08-15T03:00:00Z', finishedAt: '2026-08-15T03:00:01Z', createdAt: '2026-08-15T03:00:00Z',
    },
  ],
  shots: [{
    id: 31, shotKey: 'scene-2-shot-3', sceneIndex: 2, shotIndex: 3, sequence: 1,
    sourceExcerpt: 'The elephant lifts the cakes high.', visualGoal: 'Show the rescue clearly.', speaker: 'Toby',
    dialogue: 'I can help!', caption: 'The cakes are safe.', textAnchorJson: '{"x":0.6,"y":0.2}',
    prompt: 'Wide watercolor view, Toby lifts the cakes.', negativePrompt: 'text, watermark',
    referenceAssetKeysJson: '["toby-character"]', status: 'COMPLETED', createdAt: '2026-08-15T03:00:02Z',
  }],
  assets: [
    {
      id: 41, assetType: 'REFERENCE', assetKey: 'toby-character', shotKey: null, mime: 'image/png', width: 1536, height: 864,
      sha256: 'reference-hash', providerId: 'image-provider', providerModel: 'image-model', providerRequestId: 'req-ref',
      prompt: 'Toby character reference board', negativePrompt: 'text', providerMetadataJson: '{}',
      contentUrl: '/api/image-assets/41/content', createdAt: '2026-08-15T03:00:01Z',
    },
    {
      id: 42, assetType: 'FINAL', assetKey: 'scene-2-shot-3', shotKey: 'scene-2-shot-3', mime: 'image/png', width: 1536, height: 864,
      sha256: 'final-hash', providerId: null, providerModel: null, providerRequestId: null,
      prompt: 'Wide watercolor view, Toby lifts the cakes.', negativePrompt: 'text', providerMetadataJson: '{}',
      contentUrl: '/api/image-assets/42/content', createdAt: '2026-08-15T03:00:03Z',
    },
  ],
});

describe('ImageRunHistory', () => {
  beforeEach(() => {
    apiMocks.imageAssetUrl.mockReset().mockImplementation((assetId: number | string) => `/api/image-assets/${assetId}/content`);
    apiMocks.getImageRuns.mockReset().mockResolvedValue([
      summary('run-old', '2026-08-13T03:00:00Z', ['friend']),
      summary('run-new', '2026-08-15T03:00:00Z', ['book', 'green', 'cake']),
    ]);
    apiMocks.getImageRun.mockReset().mockImplementation(async (runId: string) => detail(
      runId,
      runId === 'run-new' ? ['book', 'green', 'cake'] : ['friend'],
    ));
  });

  afterEach(() => vi.useRealTimers());

  it('shows newest-first batches, keeps each batch words on one line, and switches the single detail', async () => {
    const user = userEvent.setup();
    render(<AntApp><ImageRunHistory open onClose={vi.fn()} /></AntApp>);

    const batchList = await screen.findByRole('list', { name: '图片批次列表' });
    expect(batchList).toHaveClass('image-story-history-batches');
    const batchButtons = within(batchList).getAllByRole('button');
    expect(batchButtons[0]).toHaveAccessibleName(/run-new/);
    expect(batchButtons[0].querySelector('.image-story-history-batch-words')).toHaveTextContent('book green cake');
    expect(batchButtons[0].querySelector('.image-story-history-batch-words')).toHaveStyle({ whiteSpace: 'nowrap' });
    expect(screen.getByRole('region', { name: '当前批次单词' })).toHaveTextContent('bookgreen cake'.replace('bookgreen', 'book green'));
    expect(screen.getByRole('region', { name: '当前批次单词' })).not.toHaveTextContent('friend');

    await user.click(batchButtons[1]);
    await waitFor(() => expect(apiMocks.getImageRun).toHaveBeenCalledWith('run-old'));
    expect(await screen.findByRole('region', { name: '当前批次单词' })).toHaveTextContent('friend');
    expect(screen.getByRole('region', { name: '当前批次单词' })).not.toHaveTextContent('book');
  });

  it('renders actual AGENT and PROGRAM steps by sequence with complete raw input and output', async () => {
    const user = userEvent.setup();
    render(<AntApp><ImageRunHistory open initialRunId="run-new" onClose={vi.fn()} /></AntApp>);

    const stepList = await screen.findByRole('list', { name: '已执行步骤' });
    const steps = within(stepList).getAllByRole('button');
    expect(steps.map((step) => step.textContent)).toEqual([
      expect.stringContaining('故事结构分析'),
      expect.stringContaining('文字合成'),
    ]);
    expect(steps[0]).toHaveTextContent('AGENT');
    expect(steps[1]).toHaveTextContent('PROGRAM');
    expect(screen.getByRole('region', { name: '完整输入' })).toHaveTextContent('{"storySnapshot":"complete input snapshot"}');
    expect(screen.getByRole('region', { name: '完整输出' })).toHaveTextContent('STORY_ANALYSIS_JSON_BEGIN');
    expect(screen.getByRole('region', { name: '完整输出' })).toHaveTextContent('complete raw output');

    await user.click(steps[1]);
    expect(screen.getByRole('region', { name: '完整输入' })).toHaveTextContent('{"shotKey":"scene-1-shot-1"}');
    expect(screen.getByRole('region', { name: '完整输出' })).toHaveTextContent('final image saved');
  });

  it('defaults to final shots, switches to references, and exposes all shot context without extra controls', async () => {
    const user = userEvent.setup();
    render(<AntApp><ImageRunHistory open initialRunId="run-new" onClose={vi.fn()} /></AntApp>);

    const gallery = await screen.findByRole('region', { name: '图片结果' });
    expect(within(gallery).getByRole('tab', { name: '最终分镜图' })).toHaveAttribute('aria-selected', 'true');
    expect(within(gallery).getByText('Scene 2 · Shot 3')).toBeInTheDocument();
    expect(within(gallery).getByText('The elephant lifts the cakes high.')).toBeInTheDocument();
    expect(within(gallery).getByText('Toby：I can help!')).toBeInTheDocument();
    expect(within(gallery).getByText('The cakes are safe.')).toBeInTheDocument();
    expect(within(gallery).getByText('Wide watercolor view, Toby lifts the cakes.')).toBeInTheDocument();
    expect(within(gallery).getByRole('img', { name: 'Scene 2 Shot 3 最终分镜图' })).toHaveAttribute('src', '/api/image-assets/42/content');
    expect(screen.queryByRole('button', { name: /预算停止|已通过|评分|审核|重绘|重新生成/ })).not.toBeInTheDocument();

    await user.click(within(gallery).getByRole('tab', { name: '参考设定图' }));
    expect(within(gallery).getByRole('img', { name: 'toby-character 参考设定图' })).toHaveAttribute('src', '/api/image-assets/41/content');
    expect(within(gallery).getByText('Toby character reference board')).toBeInTheDocument();
  });

  it('opens an image preview and closes it without changing the selected batch', async () => {
    const user = userEvent.setup();
    render(<AntApp><ImageRunHistory open initialRunId="run-new" onClose={vi.fn()} /></AntApp>);
    const previewTrigger = await screen.findByRole('button', { name: '查看 Scene 2 Shot 3 最终分镜图大图' });
    await user.click(previewTrigger);

    const previewClose = await screen.findByRole('button', { name: '关闭大图' });
    const preview = previewClose.closest('[role="dialog"]') as HTMLElement;
    expect(within(preview).getByRole('img', { name: 'Scene 2 Shot 3 最终分镜图大图' })).toHaveAttribute('src', '/api/image-assets/42/content');
    await user.click(within(preview).getByRole('button', { name: '关闭大图' }));
    await waitFor(() => expect(previewTrigger).toHaveFocus());
    expect(screen.getByRole('region', { name: '当前批次单词' })).toHaveTextContent('book');
  });

  it('shows clear empty, failed, and missing-asset states', async () => {
    const failed = {
      ...detail('run-failed', ['book'], 'FAILED'),
      errorMessage: '图片 Provider 调用失败',
      steps: [{ ...detail('run-failed', ['book']).steps[0], status: 'FAILED', rawOutput: null, errorMessage: 'Agent output invalid' }],
      assets: [],
      shots: [{ ...detail('run-failed', ['book']).shots[0], status: 'FAILED' }],
    };
    apiMocks.getImageRuns.mockResolvedValue([summary('run-failed', '2026-08-15T03:00:00Z', ['book'], 'FAILED')]);
    apiMocks.getImageRun.mockResolvedValue(failed);
    render(<AntApp><ImageRunHistory open onClose={vi.fn()} /></AntApp>);

    expect(await screen.findByText('Agent output invalid')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '完整输出' })).toHaveTextContent('没有原始输出');
    expect(screen.getByText('分镜生成失败')).toBeInTheDocument();
    expect(screen.getByText('缺少最终图片资产')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('tab', { name: '参考设定图' }));
    expect(screen.getByText(/参考设定图未生成.*图片 Provider 调用失败/)).toBeInTheDocument();
  });

  it('polls active states and stops after a terminal detail arrives', async () => {
    vi.useFakeTimers();
    apiMocks.getImageRuns.mockResolvedValue([summary('run-live', '2026-08-15T03:00:00Z', ['book'], 'PLANNING')]);
    apiMocks.getImageRun
      .mockResolvedValueOnce({ ...detail('run-live', ['book'], 'PLANNING'), steps: [], assets: [], shots: [] })
      .mockResolvedValueOnce(detail('run-live', ['book'], 'COMPLETED'));
    render(<AntApp><ImageRunHistory open onClose={vi.fn()} /></AntApp>);

    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(apiMocks.getImageRun).toHaveBeenCalledTimes(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
    expect(apiMocks.getImageRun).toHaveBeenCalledTimes(2);
    await act(async () => { await vi.advanceTimersByTimeAsync(6000); });
    expect(apiMocks.getImageRun).toHaveBeenCalledTimes(2);
  });

  it('keeps polling an active batch after one transient refresh failure', async () => {
    vi.useFakeTimers();
    apiMocks.getImageRuns.mockResolvedValue([summary('run-live', '2026-08-15T03:00:00Z', ['book'], 'GENERATING_SHOTS')]);
    apiMocks.getImageRun
      .mockResolvedValueOnce({ ...detail('run-live', ['book'], 'GENERATING_SHOTS'), assets: [], shots: [] })
      .mockRejectedValueOnce(new Error('临时网络错误'))
      .mockResolvedValueOnce(detail('run-live', ['book'], 'COMPLETED'));
    render(<AntApp><ImageRunHistory open onClose={vi.fn()} /></AntApp>);

    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
    expect(apiMocks.getImageRun).toHaveBeenCalledTimes(2);
    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
    expect(apiMocks.getImageRun).toHaveBeenCalledTimes(3);
    expect(screen.queryByText('临时网络错误')).not.toBeInTheDocument();
    await act(async () => { await vi.advanceTimersByTimeAsync(4000); });
    expect(apiMocks.getImageRun).toHaveBeenCalledTimes(3);
  });

  it('ignores a late response from the previous run after switching batches', async () => {
    const user = userEvent.setup();
    let resolveOld!: (value: ImageRunDetail) => void;
    const pendingOld = new Promise<ImageRunDetail>((resolve) => { resolveOld = resolve; });
    apiMocks.getImageRuns.mockResolvedValue([
      summary('run-new', '2026-08-15T03:00:00Z', ['book']),
      summary('run-old', '2026-08-13T03:00:00Z', ['friend']),
    ]);
    apiMocks.getImageRun.mockImplementation((runId: string) => runId === 'run-old'
      ? Promise.resolve(detail('run-old', ['friend']))
      : pendingOld);
    render(<AntApp><ImageRunHistory open initialRunId="run-new" onClose={vi.fn()} /></AntApp>);

    const batches = await screen.findByRole('list', { name: '图片批次列表' });
    await user.click(within(batches).getByRole('button', { name: /run-old/ }));
    expect(await screen.findByRole('region', { name: '当前批次单词' })).toHaveTextContent('friend');
    resolveOld(detail('run-new', ['book']));
    await act(async () => Promise.resolve());
    expect(screen.getByRole('region', { name: '当前批次单词' })).toHaveTextContent('friend');
    expect(screen.getByRole('region', { name: '当前批次单词' })).not.toHaveTextContent('book');
  });

  it('closes from the compact header button and Escape', async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    const view = render(<AntApp><ImageRunHistory open initialRunId="run-new" onClose={onClose} /></AntApp>);
    await user.click(await screen.findByRole('button', { name: '关闭图片记录' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    view.rerender(<AntApp><ImageRunHistory open initialRunId="run-new" onClose={onClose} /></AntApp>);
    const dialog = screen.getByRole('dialog', { name: '图片运行记录' });
    fireEvent.keyDown(dialog.parentElement as HTMLElement, { key: 'Escape', keyCode: 27 });
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('uses a new initial run only on the next open and preserves a user switch during one open session', async () => {
    const user = userEvent.setup();
    apiMocks.getImageRuns.mockResolvedValue([
      summary('run-1', '2026-08-15T03:00:00Z', ['book']),
      summary('run-2', '2026-08-14T03:00:00Z', ['friend']),
      summary('run-3', '2026-08-13T03:00:00Z', ['cake']),
    ]);
    apiMocks.getImageRun.mockImplementation(async (runId: string) => detail(runId, [
      runId === 'run-1' ? 'book' : runId === 'run-2' ? 'friend' : 'cake',
    ]));
    const view = render(<AntApp><ImageRunHistory open initialRunId="run-1" onClose={vi.fn()} /></AntApp>);
    expect(await screen.findByRole('region', { name: '当前批次单词' })).toHaveTextContent('book');
    await user.click(screen.getByRole('button', { name: /run-2/ }));
    expect(await screen.findByRole('region', { name: '当前批次单词' })).toHaveTextContent('friend');

    view.rerender(<AntApp><ImageRunHistory open initialRunId="run-3" onClose={vi.fn()} /></AntApp>);
    await act(async () => Promise.resolve());
    expect(screen.getByRole('region', { name: '当前批次单词' })).toHaveTextContent('friend');
    view.rerender(<AntApp><ImageRunHistory open={false} initialRunId="run-3" onClose={vi.fn()} /></AntApp>);
    view.rerender(<AntApp><ImageRunHistory open initialRunId="run-3" onClose={vi.fn()} /></AntApp>);
    await waitFor(() => expect(screen.getByRole('region', { name: '当前批次单词' })).toHaveTextContent('cake'));
  });

  it('shows a bounded run failure reason in the upper audit even when no steps or assets exist', async () => {
    const longReason = `<script>alert("x")</script>${'x'.repeat(1_200)}`;
    apiMocks.getImageRuns.mockResolvedValue([summary('run-empty-failed', '2026-08-15T03:00:00Z', ['book'], 'FAILED')]);
    apiMocks.getImageRun.mockResolvedValue({
      ...detail('run-empty-failed', ['book'], 'FAILED'), errorMessage: longReason, steps: [], shots: [], assets: [],
    });
    render(<AntApp><ImageRunHistory open onClose={vi.fn()} /></AntApp>);

    const audit = await screen.findByRole('region', { name: '批次执行错误' });
    expect(audit).toHaveTextContent('<script>alert("x")</script>');
    expect(audit.textContent?.length).toBeLessThanOrEqual(1_020);
    expect(audit.querySelector('script')).toBeNull();
    const emptySteps = screen.getByRole('list', { name: '已执行步骤' });
    expect(Array.from(emptySteps.children).every((child) => child.getAttribute('role') === 'listitem')).toBe(true);
    expect(screen.getByRole('tab', { name: '最终分镜图' })).toHaveAttribute('aria-selected', 'true');
  });

  it('derives thumbnail and preview URLs only from the controlled asset id', async () => {
    const user = userEvent.setup();
    const unsafe = detail('run-new', ['book']);
    unsafe.assets = unsafe.assets.map((asset) => ({ ...asset, contentUrl: 'https://evil.example/steal.png' }));
    apiMocks.getImageRuns.mockResolvedValue([summary('run-new', '2026-08-15T03:00:00Z', ['book'])]);
    apiMocks.getImageRun.mockResolvedValue(unsafe);
    apiMocks.imageAssetUrl.mockImplementation((assetId: number | string) => `https://trusted.example/api/image-assets/${assetId}/content`);
    render(<AntApp><ImageRunHistory open onClose={vi.fn()} /></AntApp>);

    const thumbnail = await screen.findByRole('img', { name: 'Scene 2 Shot 3 最终分镜图' });
    expect(thumbnail).toHaveAttribute('src', 'https://trusted.example/api/image-assets/42/content');
    expect(thumbnail).not.toHaveAttribute('src', expect.stringContaining('evil.example'));
    await user.click(screen.getByRole('button', { name: '查看 Scene 2 Shot 3 最终分镜图大图' }));
    expect(await screen.findByRole('img', { name: 'Scene 2 Shot 3 最终分镜图大图' })).toHaveAttribute('src', 'https://trusted.example/api/image-assets/42/content');
    expect(apiMocks.imageAssetUrl).toHaveBeenCalledWith(42);
  });

  it('uses modal focus containment and restores history and preview triggers', async () => {
    const Harness = () => {
      const [open, setOpen] = useState(false); const opener = useRef<HTMLButtonElement>(null);
      return <AntApp><button ref={opener} type="button" onClick={() => setOpen(true)}>打开图片记录</button><ImageRunHistory open={open} initialRunId="run-new" onClose={() => setOpen(false)} afterClose={() => opener.current?.focus()} /></AntApp>;
    };
    const user = userEvent.setup(); render(<Harness />);
    const opener = screen.getByRole('button', { name: '打开图片记录' }); await user.click(opener);
    const dialog = await screen.findByRole('dialog', { name: '图片运行记录' });
    const historyClose = await screen.findByRole('button', { name: '关闭图片记录' });
    await waitFor(() => expect(historyClose).toHaveFocus());
    for (let index = 0; index < 12; index += 1) await user.tab();
    expect(dialog).toContainElement(document.activeElement as HTMLElement);

    const previewTrigger = screen.getByRole('button', { name: '查看 Scene 2 Shot 3 最终分镜图大图' });
    await user.click(previewTrigger);
    const previewClose = await screen.findByRole('button', { name: '关闭大图' });
    const preview = previewClose.closest('[role="dialog"]') as HTMLElement;
    expect(preview).toContainElement(document.activeElement as HTMLElement);
    await user.click(previewClose);
    await waitFor(() => expect(previewTrigger).toHaveFocus());
    await user.click(historyClose);
    await waitFor(() => expect(opener).toHaveFocus());
  });

  it('uses listitem selection semantics, linked tabpanels, keyboard tabs, and a preview image error state', async () => {
    render(<AntApp><ImageRunHistory open initialRunId="run-new" onClose={vi.fn()} /></AntApp>);
    const batches = await screen.findByRole('list', { name: '图片批次列表' });
    expect(within(batches).getAllByRole('listitem')).toHaveLength(2);
    expect(within(batches).getByRole('button', { name: /run-new/ })).toHaveAttribute('aria-current', 'true');
    const steps = screen.getByRole('list', { name: '已执行步骤' });
    expect(within(steps).getAllByRole('listitem')).toHaveLength(2);
    expect(within(steps).getByRole('button', { name: /故事结构分析/ })).toHaveAttribute('aria-pressed', 'true');

    const finalTab = screen.getByRole('tab', { name: '最终分镜图' });
    const finalPanel = screen.getByRole('tabpanel', { name: '最终分镜图' });
    expect(finalTab).toHaveAttribute('aria-controls', finalPanel.id);
    expect(finalPanel).toHaveClass('image-story-history-gallery-scroll');
    fireEvent.keyDown(finalTab, { key: 'ArrowRight' });
    const referenceTab = screen.getByRole('tab', { name: '参考设定图' });
    expect(referenceTab).toHaveFocus();
    expect(referenceTab).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel', { name: '参考设定图' })).toHaveAttribute('aria-labelledby', referenceTab.id);

    fireEvent.click(screen.getByRole('button', { name: '查看 toby-character 参考设定图大图' }));
    const largeImage = await screen.findByRole('img', { name: 'toby-character 参考设定图大图' });
    fireEvent.error(largeImage);
    expect(await screen.findByText('大图文件加载失败')).toBeInTheDocument();
  });
});
