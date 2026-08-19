import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getImageResults } from './api';

const requestMock = vi.hoisted(() => ({
  get: vi.fn(),
  defaults: { baseURL: '/api' },
}));

vi.mock('axios', () => ({
  default: { create: vi.fn(() => requestMock) },
}));

describe('image result API contract', () => {
  beforeEach(() => requestMock.get.mockReset());

  it('requests the selected one-based page and allowed page size', async () => {
    requestMock.get.mockResolvedValue({
      data: { code: 0, msg: '', data: { items: [], page: 2, pageSize: 20, totalItems: 0, totalPages: 0 } },
    });

    await getImageResults(2, 20);

    expect(requestMock.get).toHaveBeenCalledWith('/image-runs/results', {
      params: { page: 2, pageSize: 20 },
    });
  });
});
