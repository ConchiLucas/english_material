import type { AIProviderConfigItem } from './api';

export const hasProviderCapability = (provider: AIProviderConfigItem, capability: string) =>
  (provider.capabilities ?? []).some((item) => item.trim().toUpperCase() === capability);

export const isImageProviderConfig = (provider: AIProviderConfigItem) =>
  hasProviderCapability(provider, 'IMAGE_GENERATION')
  || hasProviderCapability(provider, 'IMAGE_REFERENCE');

const validImageProviderUrl = (value: string) => {
  const baseUrl = value.trim();
  if (!/^https?:\/\/[^/]+(?:\/|$)/i.test(baseUrl) || baseUrl.includes('?') || baseUrl.includes('#')) return false;
  try {
    const url = new URL(baseUrl);
    const path = url.pathname.replace(/\/+$/, '').toLowerCase();
    return (url.protocol === 'http:' || url.protocol === 'https:')
      && !!url.hostname
      && !url.username
      && !url.password
      && !url.search
      && !url.hash
      && !path.endsWith('/images/generations')
      && !path.endsWith('/images/edits');
  } catch {
    return false;
  }
};

const validImageProviderOptions = (options?: Record<string, unknown>) => {
  if (!options) return true;
  const allowed = new Set(['responseFormat', 'quality', 'size']);
  const qualities = new Set(['auto', 'low', 'medium', 'high', 'standard', 'hd']);
  return Object.entries(options).every(([key, raw]) => {
    if (!allowed.has(key) || typeof raw !== 'string') return false;
    const value = raw.trim();
    if (!value || value.length > 64) return false;
    if (key === 'responseFormat') return value.toLowerCase() === 'b64_json';
    if (key === 'quality') return qualities.has(value.toLowerCase());
    return value === '1536x864';
  });
};

export const isExecutableImageProvider = (provider: AIProviderConfigItem) =>
  !!provider.id?.trim()
  && !!provider.model?.trim()
  && !!provider.base_url?.trim()
  && provider.type?.trim().toLowerCase() === 'openai-compatible'
  && provider.enabled !== false
  && hasProviderCapability(provider, 'IMAGE_GENERATION')
  && hasProviderCapability(provider, 'IMAGE_REFERENCE')
  && validImageProviderUrl(provider.base_url)
  && validImageProviderOptions(provider.options);
