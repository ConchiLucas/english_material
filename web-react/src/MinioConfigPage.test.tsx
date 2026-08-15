import { App as AntApp } from 'antd';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import MinioConfigPage from './MinioConfigPage';

const apiMocks = vi.hoisted(() => ({
  getMinioConfig: vi.fn(),
  saveMinioConfig: vi.fn(),
  testMinioConfig: vi.fn(),
}));

vi.mock('./api', async (importOriginal) => ({
  ...await importOriginal<typeof import('./api')>(),
  ...apiMocks,
}));

describe('MinioConfigPage', () => {
  beforeEach(() => {
    apiMocks.getMinioConfig.mockReset().mockResolvedValue({
      enabled: true,
      endpoint: 'minio.internal:9000',
      accessKeyId: 'english-app',
      useSsl: false,
      bucketName: 'english-material',
      basePath: 'image-story',
      secretConfigured: true,
      updatedAt: '2026-08-16T10:00:00+08:00',
    });
    apiMocks.saveMinioConfig.mockReset().mockImplementation(async (value) => ({
      ...value,
      secretConfigured: true,
      secretAccessKey: undefined,
      updatedAt: '2026-08-16T11:00:00+08:00',
    }));
    apiMocks.testMinioConfig.mockReset().mockResolvedValue(undefined);
  });

  it('loads a redacted private bucket configuration with fixed defaults visible', async () => {
    render(<AntApp><MinioConfigPage /></AntApp>);

    expect(await screen.findByRole('heading', { name: 'MinIO 配置' })).toBeInTheDocument();
    expect(screen.getByLabelText('Endpoint')).toHaveValue('minio.internal:9000');
    expect(screen.getByLabelText('Bucket')).toHaveValue('english-material');
    expect(screen.getByLabelText('基础路径')).toHaveValue('image-story');
    expect(screen.getByLabelText('Secret Key')).toHaveValue('');
    expect(screen.getByText('已保存密钥；留空表示继续使用现有密钥。')).toBeInTheDocument();
  });

  it('tests and saves without requiring the saved secret to be re-entered', async () => {
    const user = userEvent.setup();
    render(<AntApp><MinioConfigPage /></AntApp>);
    await screen.findByDisplayValue('minio.internal:9000');

    await user.click(screen.getByRole('button', { name: '测试连接' }));
    expect(apiMocks.testMinioConfig).toHaveBeenCalledWith(expect.objectContaining({
      endpoint: 'minio.internal:9000',
      secretAccessKey: '',
      bucketName: 'english-material',
      basePath: 'image-story',
      updatedAt: '2026-08-16T10:00:00+08:00',
    }));

    await user.click(screen.getByRole('button', { name: '保存配置' }));
    expect(apiMocks.saveMinioConfig).toHaveBeenCalledWith(expect.objectContaining({
      accessKeyId: 'english-app',
      secretAccessKey: '',
      updatedAt: '2026-08-16T10:00:00+08:00',
    }));
  });
});
