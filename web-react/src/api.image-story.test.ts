import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  bootstrapAntigravityImageProvider,
  createImageRun,
  createImageStylePreset,
  getImageAgentFlow,
  getImageAgentVersions,
  getImageRun,
  getImageRuns,
  getImageSourceStories,
  getImageStylePresets,
  imageAssetUrl,
  restoreImageAgentVersion,
  updateImageAgent,
  updateImageFlowConfig,
  updateImageStylePreset,
} from './api';
import type { ImageFlowConfig, ImagePromptVersion } from './image-story-types';

const requestMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  defaults: { baseURL: 'https://api.example.test/root/' },
}));

vi.mock('axios', () => ({
  default: {
    create: vi.fn(() => requestMock),
  },
}));

const apiResult = <T,>(data: T) => Promise.resolve({
  data: { code: 0, data, msg: '' },
});

describe('image story API contracts', () => {
  beforeEach(() => {
    requestMock.get.mockReset();
    requestMock.post.mockReset();
    requestMock.put.mockReset();
    requestMock.defaults.baseURL = 'https://api.example.test/root/';
  });

  it('keeps nullable provider IDs present in flow and version contracts', () => {
    const flowConfig: ImageFlowConfig = {
      imageProviderId: null,
      width: 1536,
      height: 864,
      maxShotsPerScene: 5,
      maxShotsPerStory: 20,
      updatedAt: null,
    };
    const promptVersion: ImagePromptVersion = {
      version: 1,
      systemPrompt: 'Plan the illustration.',
      aiProviderId: null,
      temperature: 0.2,
      enabled: true,
      createdAt: '2026-08-15T12:00:00+08:00',
    };

    expect(flowConfig.imageProviderId).toBeNull();
    expect(promptVersion.aiProviderId).toBeNull();
  });

  it('gets the image Agent flow', async () => {
    requestMock.get.mockReturnValue(apiResult({ stages: [] }));
    await getImageAgentFlow();
    expect(requestMock.get).toHaveBeenCalledWith('/image-agents/flow');
  });

  it('bootstraps an Antigravity image provider from a source ID only', async () => {
    requestMock.post.mockReturnValue(apiResult({ active: 'text', providers: [] }));

    await bootstrapAntigravityImageProvider('antigravity-gemini-3-1-pro');

    expect(requestMock.post).toHaveBeenCalledWith('/ai/config/image/bootstrap', {
      sourceProviderId: 'antigravity-gemini-3-1-pro',
    });
  });

  it('updates an encoded image Agent key with its body', async () => {
    const body = { systemPrompt: 'prompt', aiProviderId: 'text', temperature: 0.2, enabled: true, updatedAt: null };
    requestMock.put.mockReturnValue(apiResult({ key: 'agent/a b' }));
    await updateImageAgent('agent/a b', body);
    expect(requestMock.put).toHaveBeenCalledWith('/image-agents/agent%2Fa%20b', body);
  });

  it('gets versions for an encoded image Agent key', async () => {
    requestMock.get.mockReturnValue(apiResult([]));
    await getImageAgentVersions('agent/a b');
    expect(requestMock.get).toHaveBeenCalledWith('/image-agents/agent%2Fa%20b/versions');
  });

  it('restores an encoded Agent version with its body', async () => {
    const body = { updatedAt: null };
    requestMock.post.mockReturnValue(apiResult({ key: 'agent/a b' }));
    await restoreImageAgentVersion('agent/a b', 7, body);
    expect(requestMock.post).toHaveBeenCalledWith('/image-agents/agent%2Fa%20b/versions/7/restore', body);
  });

  it('updates the image flow with its body', async () => {
    const body = { imageProviderId: 'image', width: 1536, height: 864, maxShotsPerScene: 5, maxShotsPerStory: 20, updatedAt: null };
    requestMock.put.mockReturnValue(apiResult(body));
    await updateImageFlowConfig(body);
    expect(requestMock.put).toHaveBeenCalledWith('/image-agents/flow/config', body);
  });

  it('gets image style presets', async () => {
    requestMock.get.mockReturnValue(apiResult([]));
    await getImageStylePresets();
    expect(requestMock.get).toHaveBeenCalledWith('/image-style-presets');
  });

  it('creates an image style preset with its body', async () => {
    const body = { name: 'Watercolor', positivePrompt: 'soft paint', negativePrompt: 'text', description: 'warm', enabled: true };
    requestMock.post.mockReturnValue(apiResult({ id: 1 }));
    await createImageStylePreset(body);
    expect(requestMock.post).toHaveBeenCalledWith('/image-style-presets', body);
  });

  it('updates an encoded image style ID with its body', async () => {
    const body = { name: 'Watercolor', positivePrompt: 'soft paint', negativePrompt: 'text', description: 'warm', enabled: false, updatedAt: null };
    requestMock.put.mockReturnValue(apiResult({ id: 17 }));
    await updateImageStylePreset(17, body);
    expect(requestMock.put).toHaveBeenCalledWith('/image-style-presets/17', body);
  });

  it('gets source stories', async () => {
    requestMock.get.mockReturnValue(apiResult([]));
    await getImageSourceStories();
    expect(requestMock.get).toHaveBeenCalledWith('/image-runs/source-stories');
  });

  it('gets image runs', async () => {
    requestMock.get.mockReturnValue(apiResult([]));
    await getImageRuns();
    expect(requestMock.get).toHaveBeenCalledWith('/image-runs');
  });

  it('gets an encoded image run ID', async () => {
    requestMock.get.mockReturnValue(apiResult({ runId: 'run/a b' }));
    await getImageRun('run/a b');
    expect(requestMock.get).toHaveBeenCalledWith('/image-runs/run%2Fa%20b');
  });

  it('creates an image run with its body', async () => {
    const body = { storyRunId: 'story-1', stylePresetId: 3 };
    requestMock.post.mockReturnValue(apiResult({ runId: 'image-1' }));
    await createImageRun(body);
    expect(requestMock.post).toHaveBeenCalledWith('/image-runs', body);
  });

  it.each([
    ['https://api.example.test/root', 'https://api.example.test/root/image-assets/asset%20%2F12%3F%23/content'],
    ['https://api.example.test/root/', 'https://api.example.test/root/image-assets/asset%20%2F12%3F%23/content'],
    ['/api', '/api/image-assets/asset%20%2F12%3F%23/content'],
    ['/api/', '/api/image-assets/asset%20%2F12%3F%23/content'],
  ])('builds an encoded asset URL from Axios base %s', (baseUrl, expected) => {
    requestMock.defaults.baseURL = baseUrl;
    expect(imageAssetUrl('asset /12?#')).toBe(expected);
  });
});
