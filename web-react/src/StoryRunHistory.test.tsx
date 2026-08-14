import { App as AntApp } from 'antd';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import StoryRunHistory from './StoryRunHistory';

const apiMocks = vi.hoisted(() => ({ getStoryRuns: vi.fn(), getStoryRun: vi.fn() }));
vi.mock('./api', async (importOriginal) => ({
  ...await importOriginal<typeof import('./api')>(),
  ...apiMocks,
}));

const run = (runId: string, createdAt: string, word: string) => ({
  runId,
  words: [{ word, meaning: `${word} meaning` }],
  targetGrade: '三年级上册',
  status: 'COMPLETED',
  totalTokens: 100,
  createdAt,
  startedAt: createdAt,
  finishedAt: createdAt,
});

describe('StoryRunHistory', () => {
  beforeEach(() => {
    apiMocks.getStoryRuns.mockReset().mockResolvedValue([
      run('run-new', '2026-08-13T21:00:00Z', 'book'),
      run('run-old', '2026-08-12T21:00:00Z', 'friend'),
    ]);
    apiMocks.getStoryRun.mockImplementation(async (runId: string) => ({
      ...run(runId, '2026-08-13T21:00:00Z', runId === 'run-new' ? 'book' : 'friend'),
      finalStory: runId === 'run-new' ? 'The newest final story.' : 'The older final story.',
      errorMessage: null,
      steps: [
        { id: 1, sequence: 1, qualityRound: 1, agentKey: 'story-writer', agentName: '故事作家 Agent', promptVersion: 1, providerId: 'p', providerModel: 'm', inputJson: '{"candidateStory":"full writer input"}', outputText: 'full writer output', status: 'COMPLETED', inputTokens: 1, outputTokens: 1, totalTokens: 2, durationMs: 5, createdAt: '2026-08-13T21:00:01Z' },
        { id: 2, sequence: 2, qualityRound: 2, agentKey: 'story-writer', agentName: '故事作家 Agent', promptVersion: 1, providerId: 'p', providerModel: 'm', inputJson: 'second writer input', outputText: 'second writer output', status: 'COMPLETED', inputTokens: 1, outputTokens: 1, totalTokens: 2, durationMs: 5, createdAt: '2026-08-13T21:00:02Z' },
      ],
    }));
  });

  it('links newest-first batches to words, repeated Agent calls, I/O and bottom result', async () => {
    const user = userEvent.setup();
    render(<AntApp><StoryRunHistory open onClose={vi.fn()} /></AntApp>);

    const dialog = await screen.findByRole('dialog', { name: '故事运行记录' });
    expect(dialog).toHaveClass('story-run-history');
    expect(screen.getAllByRole('button', { name: /批次/ })[0]).toHaveAccessibleName(/run-new/);
    expect(await screen.findByText('book')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /故事作家 Agent/ })).toHaveLength(2);
    expect(screen.getByText('Agent 输入').closest('section')?.querySelector('pre')?.textContent)
      .toContain('\n  "candidateStory": "full writer input"\n');
    expect(screen.getByText('full writer output')).toBeInTheDocument();
    expect(screen.getByText('故事作家 Agent · 第 1 轮')).toBeInTheDocument();
    expect(screen.getByText('故事作家 Agent · 第 2 轮')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '最终故事结果' })).toHaveTextContent('The newest final story.');

    await user.click(screen.getByRole('button', { name: /run-old/ }));
    await waitFor(() => expect(apiMocks.getStoryRun).toHaveBeenCalledWith('run-old'));
    expect(await screen.findByText('friend')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '最终故事结果' })).toHaveTextContent('The older final story.');
  });

  it('polls the selected batch while it is still running', async () => {
    apiMocks.getStoryRuns.mockResolvedValue([{
      ...run('run-live', '2026-08-13T21:00:00Z', 'book'), status: 'RUNNING', finishedAt: null,
    }]);
    apiMocks.getStoryRun
      .mockResolvedValueOnce({
        ...run('run-live', '2026-08-13T21:00:00Z', 'book'), status: 'RUNNING', finishedAt: null,
        finalStory: null, errorMessage: null, steps: [],
      })
      .mockResolvedValueOnce({
        ...run('run-live', '2026-08-13T21:00:00Z', 'book'),
        finalStory: 'Polling finished story.', errorMessage: null, steps: [],
      });
    render(<AntApp><StoryRunHistory open onClose={vi.fn()} /></AntApp>);

    await screen.findByText('故事生成中…');
    await waitFor(() => expect(apiMocks.getStoryRun.mock.calls.length).toBeGreaterThanOrEqual(2));
    expect(await screen.findByText('Polling finished story.', {}, { timeout: 3500 })).toBeInTheDocument();
  }, 5000);

  it('ignores an older batch response that arrives after switching batches', async () => {
    const user = userEvent.setup();
    let resolveRefresh!: (value: object) => void;
    let resolveOld!: (value: object) => void;
    const pendingRefresh = new Promise((resolve) => { resolveRefresh = resolve; });
    const pendingOld = new Promise((resolve) => { resolveOld = resolve; });
    apiMocks.getStoryRun
      .mockResolvedValueOnce({ ...run('run-new', '2026-08-13T21:00:00Z', 'book'), finalStory: 'Initial story.', errorMessage: null, steps: [] })
      .mockImplementation((runId: string) => runId === 'run-new' ? pendingRefresh : pendingOld);
    render(<AntApp><StoryRunHistory open onClose={vi.fn()} /></AntApp>);

    await screen.findByText('Initial story.');
    await user.click(screen.getByRole('button', { name: /刷\s*新/ }));
    await waitFor(() => expect(apiMocks.getStoryRun.mock.calls.length).toBeGreaterThanOrEqual(2));
    await user.click(await screen.findByRole('button', { name: /run-old/ }));
    resolveOld({ ...run('run-old', '2026-08-12T21:00:00Z', 'friend'), finalStory: 'Old selected story.', errorMessage: null, steps: [] });
    expect(await screen.findByText('Old selected story.')).toBeInTheDocument();

    resolveRefresh({ ...run('run-new', '2026-08-13T21:00:00Z', 'book'), finalStory: 'Stale newest story.', errorMessage: null, steps: [] });
    await act(async () => Promise.resolve());
    expect(screen.getByRole('region', { name: '最终故事结果' })).toHaveTextContent('Old selected story.');
    expect(screen.queryByText('Stale newest story.')).not.toBeInTheDocument();
  });
});
