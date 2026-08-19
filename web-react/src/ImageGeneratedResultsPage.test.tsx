import { App as AntApp } from 'antd';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getImageResults, imageAssetUrl } from './api';
import ImageGeneratedResultsPage from './ImageGeneratedResultsPage';
import type { ImageResultPage } from './image-story-types';

vi.mock('./api', () => ({ getImageResults: vi.fn(), imageAssetUrl: vi.fn() }));

const mockedGetImageResults = vi.mocked(getImageResults);
const mockedImageAssetUrl = vi.mocked(imageAssetUrl);
const resultPage = (overrides: Partial<ImageResultPage> = {}): ImageResultPage => ({
  items: [{
    runId: 'image-run-1',
    title: 'The Moon Picnic',
    stylePresetName: '水彩绘本',
    targetGrade: '三年级上册',
    imageCount: 2,
    completedAt: '2026-08-16T08:08:00+08:00',
    shots: [
      { assetId: 42, shotKey: 'scene-1-shot-2', sceneIndex: 1, shotIndex: 2, sequence: 2, sourceExcerpt: 'The moon is bright.', dialogue: null, caption: 'They share a cake.' },
      { assetId: 41, shotKey: 'scene-1-shot-1', sceneIndex: 1, shotIndex: 1, sequence: 1, sourceExcerpt: 'Mimi opens a book.', dialogue: 'Look at the moon!', caption: null },
    ],
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

const renderPage = () => render(<AntApp><ImageGeneratedResultsPage /></AntApp>);

describe('ImageGeneratedResultsPage', () => {
  beforeEach(() => {
    mockedGetImageResults.mockReset();
    mockedImageAssetUrl.mockReset().mockImplementation((id) => `/controlled/image-assets/${id}/content`);
  });

  it('renders each completed batch as a full-width ordered final image gallery', async () => {
    mockedGetImageResults.mockResolvedValue(resultPage());
    renderPage();

    const region = await screen.findByRole('region', { name: '图片生成结果' });
    expect(mockedGetImageResults).toHaveBeenCalledWith(1, 10);
    const batch = within(region).getByRole('article', { name: /The Moon Picnic/ });
    expect(within(batch).getByText(/The Moon Picnic · 水彩绘本 · 2 张 · 三年级上册/)).toBeInTheDocument();
    expect(within(batch).getAllByRole('img').map((image) => image.getAttribute('alt'))).toEqual([
      'Scene 1 · Shot 1',
      'Scene 1 · Shot 2',
    ]);
    expect(within(batch).getByRole('img', { name: 'Scene 1 · Shot 1' }))
      .toHaveAttribute('src', '/controlled/image-assets/41/content');
    expect(mockedImageAssetUrl).toHaveBeenCalledWith(41);
    expect(within(batch).getByText('“Look at the moon!”')).toBeInTheDocument();
    expect(within(batch).getByText('They share a cake.')).toBeInTheDocument();
  });

  it('switches between ten, twenty and one hundred batches per page', async () => {
    const user = userEvent.setup();
    mockedGetImageResults.mockResolvedValue(resultPage());
    renderPage();
    await screen.findByText(/The Moon Picnic ·/);

    await user.click(screen.getByRole('combobox', { name: '每页批次数量' }));
    await user.click(await screen.findByText('20 批 / 页'));
    await waitFor(() => expect(mockedGetImageResults).toHaveBeenLastCalledWith(1, 20));
    await user.click(screen.getByRole('combobox', { name: '每页批次数量' }));
    await user.click(await screen.findByText('100 批 / 页'));
    await waitFor(() => expect(mockedGetImageResults).toHaveBeenLastCalledWith(1, 100));
  });

  it('opens the controlled final asset in the built-in image preview', async () => {
    const user = userEvent.setup();
    mockedGetImageResults.mockResolvedValue(resultPage());
    renderPage();

    await user.click(await screen.findByRole('img', { name: 'Scene 1 · Shot 1' }));

    await waitFor(() => expect(document.querySelector('.ant-image-preview-root')).toBeInTheDocument());
    const previewImage = document.querySelector('.ant-image-preview-img');
    expect(previewImage).toHaveAttribute('src', '/controlled/image-assets/41/content');
  });

  it('keeps current galleries on page failure and discards an older late response', async () => {
    const user = userEvent.setup();
    const second = deferred<ImageResultPage>();
    const third = deferred<ImageResultPage>();
    mockedGetImageResults
      .mockResolvedValueOnce(resultPage())
      .mockReturnValueOnce(second.promise)
      .mockReturnValueOnce(third.promise);
    renderPage();
    await screen.findByText(/The Moon Picnic ·/);

    await user.click(screen.getByTitle('2'));
    await user.click(screen.getByTitle('3'));
    await act(async () => third.reject(new Error('第三页读取失败')));
    expect(await screen.findByText('第三页读取失败')).toBeInTheDocument();
    expect(screen.getByText(/The Moon Picnic ·/)).toBeInTheDocument();
    await act(async () => second.resolve(resultPage({ page: 2, items: [{ ...resultPage().items[0], title: '迟到批次' }] })));
    expect(screen.queryByText(/迟到批次 ·/)).not.toBeInTheDocument();
  });

  it('shows initial failure, empty state, and a local broken-image state', async () => {
    const user = userEvent.setup();
    mockedGetImageResults
      .mockRejectedValueOnce(new Error('图片结果读取失败'))
      .mockResolvedValueOnce(resultPage());
    renderPage();

    expect(await screen.findByText('图片结果读取失败')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '重新加载' }));
    const image = await screen.findByRole('img', { name: 'Scene 1 · Shot 1' });
    fireEvent.error(image);
    expect(await screen.findByText('图片读取失败')).toBeInTheDocument();

    mockedGetImageResults.mockResolvedValueOnce(resultPage({ items: [], totalItems: 0, totalPages: 0 }));
    await user.click(screen.getByRole('combobox', { name: '每页批次数量' }));
    await user.click(await screen.findByText('20 批 / 页'));
    expect(await screen.findByText('还没有可展示的图片生成结果')).toBeInTheDocument();
  });
});
