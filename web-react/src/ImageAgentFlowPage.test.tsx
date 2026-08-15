import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ImageAgentFlowPage from './ImageAgentFlowPage';
import { imageAssetUrl } from './api';

const apiMocks = vi.hoisted(() => ({
  getImageAgentFlow: vi.fn(),
}));

vi.mock('./api', async (importOriginal) => ({
  ...await importOriginal<typeof import('./api')>(),
  ...apiMocks,
}));

describe('ImageAgentFlowPage shell', () => {
  beforeEach(() => {
    apiMocks.getImageAgentFlow.mockReset();
  });

  it('identifies the image workbench while loading its independent flow', () => {
    apiMocks.getImageAgentFlow.mockReturnValue(new Promise(() => undefined));

    render(<ImageAgentFlowPage providers={[]} onDirtyChange={vi.fn()} />);

    expect(screen.getByRole('region', { name: '图片 Agent 工作台' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '图片工作台' })).toBeInTheDocument();
    expect(screen.getByLabelText('正在加载图片 Agent 流程')).toBeInTheDocument();
  });

  it('shows a bounded page-local error when the image flow cannot load', async () => {
    apiMocks.getImageAgentFlow.mockRejectedValue(new Error('image flow unavailable'));

    render(<ImageAgentFlowPage providers={[]} onDirtyChange={vi.fn()} />);

    expect(await screen.findByText('图片流程加载失败')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '图片 Agent 工作台' })).toBeInTheDocument();
  });

  it('builds encoded image asset URLs from the configured API base', () => {
    expect(imageAssetUrl('12/34')).toBe('http://127.0.0.1:18744/api/image-assets/12%2F34/content');
  });

  it('does not reload the flow when a parent replaces the dirty callback', () => {
    apiMocks.getImageAgentFlow.mockReturnValue(new Promise(() => undefined));
    const firstCallback = vi.fn();
    const latestCallback = vi.fn();
    const view = render(<ImageAgentFlowPage providers={[]} onDirtyChange={firstCallback} />);

    view.rerender(<ImageAgentFlowPage providers={[]} onDirtyChange={latestCallback} />);

    expect(apiMocks.getImageAgentFlow).toHaveBeenCalledTimes(1);
    expect(firstCallback).not.toHaveBeenCalled();
    view.unmount();
    expect(latestCallback).toHaveBeenCalledWith(false);
  });
});
