import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getStoryResults } from './api';

const requestMock = vi.hoisted(() => ({
  get: vi.fn(),
  defaults: { baseURL: '/api' },
}));

vi.mock('axios', () => ({
  default: { create: vi.fn(() => requestMock) },
}));

describe('story result API contract', () => {
  beforeEach(() => requestMock.get.mockReset());

  it('requests the selected one-based page and allowed page size', async () => {
    requestMock.get.mockResolvedValue({
      data: { code: 0, msg: '', data: { items: [], page: 2, pageSize: 20, totalItems: 0, totalPages: 0 } },
    });

    await getStoryResults(2, 20);

    expect(requestMock.get).toHaveBeenCalledWith('/story-runs/results', {
      params: { page: 2, pageSize: 20 },
    });
  });
});
