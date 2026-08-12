import { App as AntApp } from 'antd';
import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';

const apiMocks = vi.hoisted(() => ({
  getConnections: vi.fn(),
  getAIConfig: vi.fn(),
  getLocalCliConfig: vi.fn(),
}));

vi.mock('./api', async (importOriginal) => ({
  ...await importOriginal<typeof import('./api')>(),
  ...apiMocks,
}));

describe('App primary navigation', () => {
  beforeEach(() => {
    apiMocks.getConnections.mockReset().mockResolvedValue([]);
    apiMocks.getAIConfig.mockReset().mockResolvedValue({ active: '', providers: [] });
    apiMocks.getLocalCliConfig.mockReset().mockResolvedValue({ active: '', configs: [] });
  });

  it('only exposes configuration and word browsing workspaces', async () => {
    render(<AntApp><App /></AntApp>);

    const navigation = await screen.findByRole('menu', { name: '主导航' });
    expect(within(navigation).getByRole('menuitem', { name: /配置管理/ })).toBeInTheDocument();
    expect(within(navigation).getByRole('menuitem', { name: /去重单词表/ })).toBeInTheDocument();
    expect(within(navigation).getAllByRole('menuitem')).toHaveLength(2);
  });
});
