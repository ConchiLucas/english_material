import { describe, expect, it } from 'vitest';
import type { AIProviderConfigItem } from './api';
import { isExecutableImageProvider, isImageProviderConfig } from './image-provider-policy';

const provider = (overrides: Partial<AIProviderConfigItem> = {}): AIProviderConfigItem => ({
  id: 'image',
  label: 'Image',
  type: 'openai-compatible',
  base_url: 'https://provider.example/v1',
  api_key: '',
  model: 'image-model',
  max_tokens: 4096,
  capabilities: ['IMAGE_GENERATION', 'IMAGE_REFERENCE'],
  options: { responseFormat: 'b64_json', quality: 'hd', size: '1536x864' },
  enabled: true,
  ...overrides,
});

describe('image provider policy', () => {
  it('classifies providers with either image capability without mixing text-only providers', () => {
    expect(isImageProviderConfig(provider({ capabilities: ['TEXT_GENERATION'] }))).toBe(false);
    expect(isImageProviderConfig(provider({ capabilities: ['IMAGE_GENERATION'] }))).toBe(true);
    expect(isImageProviderConfig(provider({ capabilities: ['image_reference'] }))).toBe(true);
  });

  it('mirrors the executable image provider contract', () => {
    expect(isExecutableImageProvider(provider())).toBe(true);
    expect(isExecutableImageProvider(provider({ options: { quality: 'ultra' } }))).toBe(false);
    expect(isExecutableImageProvider(provider({ base_url: 'https://provider.example/v1?secret=value' }))).toBe(false);
    expect(isExecutableImageProvider(provider({ capabilities: ['IMAGE_GENERATION'] }))).toBe(false);
  });
});
