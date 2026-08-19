import { App as AntApp } from 'antd';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getStoryResults } from './api';
import AgentGeneratedResultsPage from './AgentGeneratedResultsPage';
import type { StoryResultPage } from './story-flow-types';

vi.mock('./api', () => ({ getStoryResults: vi.fn() }));

const mockedGetStoryResults = vi.mocked(getStoryResults);
const page = (overrides: Partial<StoryResultPage> = {}): StoryResultPage => ({
  items: [{
    runId: 'run-1',
    title: 'The Wrong Recipe',
    targetGrade: '三年级上册',
    wordCount: 20,
    finalStory: 'Scene 1: The Wrong Recipe\n\nMimi opens a book.\n\nScene 2: The Shaking Desk\n\nThe desk shakes.',
    createdAt: '2026-08-16T08:08:00+08:00',
  }],
  page: 1,
  pageSize: 10,
  totalItems: 21,
  totalPages: 3,
  ...overrides,
});

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};

const renderPage = () => render(<AntApp><AgentGeneratedResultsPage /></AntApp>);

describe('AgentGeneratedResultsPage', () => {
  beforeEach(() => {
    mockedGetStoryResults.mockReset();
  });

  it('loads ten latest results and renders each complete story in a full article', async () => {
    mockedGetStoryResults.mockResolvedValue(page());

    renderPage();

    expect(await screen.findByRole('region', { name: 'Agent 生成结果' })).toBeInTheDocument();
    expect(mockedGetStoryResults).toHaveBeenCalledWith(1, 10);
    const article = screen.getByRole('article', { name: /The Wrong Recipe/ });
    expect(within(article).getByText(/The Wrong Recipe · 三年级上册 · 20 个单词/)).toBeInTheDocument();
    expect(within(article).getByText(/Scene 2: The Shaking Desk/)).toBeInTheDocument();
    expect(screen.getByText('共 21 条')).toBeInTheDocument();
  });

  it('changes page size to twenty or one hundred and returns to page one', async () => {
    const user = userEvent.setup();
    mockedGetStoryResults.mockResolvedValue(page());
    renderPage();
    await screen.findByText(/The Wrong Recipe ·/);

    await user.click(screen.getByRole('combobox', { name: '每页数量' }));
    await user.click(await screen.findByText('20 条 / 页'));
    await waitFor(() => expect(mockedGetStoryResults).toHaveBeenLastCalledWith(1, 20));

    await user.click(screen.getByRole('combobox', { name: '每页数量' }));
    await user.click(await screen.findByText('100 条 / 页'));
    await waitFor(() => expect(mockedGetStoryResults).toHaveBeenLastCalledWith(1, 100));
  });

  it('keeps current stories when a page request fails and can retry', async () => {
    const user = userEvent.setup();
    mockedGetStoryResults.mockResolvedValueOnce(page()).mockRejectedValueOnce(new Error('分页读取失败')).mockResolvedValueOnce(page({ page: 2 }));
    renderPage();
    await screen.findByText(/The Wrong Recipe ·/);

    await user.click(screen.getByTitle('2'));

    expect(await screen.findByText('分页读取失败')).toBeInTheDocument();
    expect(screen.getByText(/The Wrong Recipe ·/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '重试当前页' }));
    await waitFor(() => expect(mockedGetStoryResults).toHaveBeenLastCalledWith(2, 10));
  });

  it('shows an empty state and retries an initial failure', async () => {
    const user = userEvent.setup();
    mockedGetStoryResults.mockRejectedValueOnce(new Error('结果读取失败')).mockResolvedValueOnce(page({ items: [], totalItems: 0, totalPages: 0 }));
    renderPage();

    expect(await screen.findByText('结果读取失败')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '重新加载' }));
    expect(await screen.findByText('还没有可展示的 Agent 生成结果')).toBeInTheDocument();
  });

  it('discards a late response from an older page request', async () => {
    const user = userEvent.setup();
    const second = deferred<StoryResultPage>();
    const third = deferred<StoryResultPage>();
    mockedGetStoryResults.mockResolvedValueOnce(page()).mockReturnValueOnce(second.promise).mockReturnValueOnce(third.promise);
    renderPage();
    await screen.findByText(/The Wrong Recipe ·/);

    await user.click(screen.getByTitle('2'));
    await user.click(screen.getByTitle('3'));
    await act(async () => third.resolve(page({ page: 3, items: [{ ...page().items[0], runId: 'run-3', title: 'Newest Page' }] })));
    expect(await screen.findByText(/Newest Page ·/)).toBeInTheDocument();
    await act(async () => second.resolve(page({ page: 2, items: [{ ...page().items[0], runId: 'run-2', title: 'Late Page' }] })));
    expect(screen.queryByText(/Late Page ·/)).not.toBeInTheDocument();
  });
});
