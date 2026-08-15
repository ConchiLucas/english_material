import { App as AntApp } from 'antd';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { AIConfig, AIProviderConfigItem } from './api';
import ImageModelConfigPage from './ImageModelConfigPage';

const textProvider = (): AIProviderConfigItem => ({
  id: 'antigravity-gemini-3-1-pro',
  label: 'Antigravity Gemini 3.1 Pro',
  type: 'openai-compatible',
  base_url: 'https://antigravity.example/v1',
  api_key: '',
  model: 'gemini-3.1-pro',
  max_tokens: 4096,
  capabilities: ['TEXT_GENERATION'],
  enabled: true,
});

const imageProvider = (id = 'image-one'): AIProviderConfigItem => ({
  id,
  label: id === 'image-one' ? 'Image One' : 'Image Two',
  type: 'openai-compatible',
  base_url: 'https://images.example/v1',
  api_key: '',
  model: `${id}-model`,
  max_tokens: 4096,
  capabilities: ['IMAGE_GENERATION', 'IMAGE_REFERENCE'],
  options: { responseFormat: 'b64_json', quality: 'hd', size: '1536x864' },
  enabled: true,
});

const renderPage = (initial: AIConfig, overrides: {
  onSave?: (next: AIConfig) => Promise<void>;
  onBootstrap?: (sourceProviderId: string) => Promise<void>;
} = {}) => {
  const onSave = vi.fn(overrides.onSave ?? (async () => undefined));
  const onBootstrap = vi.fn(overrides.onBootstrap ?? (async () => undefined));
  const changes: AIConfig[] = [];
  function Harness() {
    const [config, setConfig] = useState(initial);
    return (
      <ImageModelConfigPage
        config={config}
        saving={false}
        onChange={(next) => { changes.push(next); setConfig(next); }}
        onSave={onSave}
        onBootstrap={onBootstrap}
      />
    );
  }
  render(<AntApp><Harness /></AntApp>);
  return { onSave, onBootstrap, changes };
};

describe('ImageModelConfigPage', () => {
  it('shows only image-capability providers while preserving text providers outside the list', () => {
    renderPage({ active: 'antigravity-gemini-3-1-pro', providers: [textProvider(), imageProvider(), imageProvider('image-two')] });

    const list = screen.getByRole('list', { name: '图片模型配置列表' });
    expect(within(list).getByRole('button', { name: /Image One/ })).toBeInTheDocument();
    expect(within(list).getByRole('button', { name: /Image Two/ })).toBeInTheDocument();
    expect(within(list).queryByText('Antigravity Gemini 3.1 Pro')).not.toBeInTheDocument();
  });

  it('adds a fixed Antigravity Gemini image draft and keeps the text provider', async () => {
    const user = userEvent.setup();
    const view = renderPage({ active: 'antigravity-gemini-3-1-pro', providers: [textProvider()] });

    await user.click(screen.getAllByRole('button', { name: '添加图片模型' })[0]);

    expect(screen.getByRole('textbox', { name: '配置 ID' })).toHaveValue('antigravity-gemini-image');
    expect(screen.getByRole('textbox', { name: '模型名称' })).toHaveValue('gemini-3-pro-image');
    expect(screen.getByText('IMAGE_GENERATION')).toBeInTheDocument();
    expect(screen.getByText('IMAGE_REFERENCE')).toBeInTheDocument();
    expect(screen.getByText('b64_json')).toBeInTheDocument();
    expect(screen.getByText('1536 × 864')).toBeInTheDocument();
    expect(view.changes.at(-1)?.providers.some((item) => item.id === 'antigravity-gemini-3-1-pro')).toBe(true);
  });

  it('uses a non-conflicting ID when the default image ID already exists', async () => {
    const user = userEvent.setup();
    renderPage({
      active: 'antigravity-gemini-3-1-pro',
      providers: [textProvider(), imageProvider('antigravity-gemini-image')],
    });

    await user.click(screen.getByRole('button', { name: '添加图片模型' }));

    expect(screen.getByRole('textbox', { name: '配置 ID' })).toHaveValue('antigravity-gemini-image-2');
  });

  it('saves edited model settings with fixed capabilities and options without losing text providers', async () => {
    const user = userEvent.setup();
    const view = renderPage({ active: 'antigravity-gemini-3-1-pro', providers: [textProvider(), imageProvider()] });

    const model = screen.getByRole('textbox', { name: '模型名称' });
    await user.clear(model);
    await user.type(model, 'gemini-3.1-flash-image');
    await user.click(screen.getByRole('combobox', { name: '质量' }));
    await user.click(screen.getAllByText('medium').at(-1)!);
    await user.click(screen.getByRole('button', { name: '保存配置' }));

    expect(view.onSave).toHaveBeenCalledTimes(1);
    const saved = view.onSave.mock.calls[0][0];
    expect(saved.providers.some((item: AIProviderConfigItem) => item.id === 'antigravity-gemini-3-1-pro')).toBe(true);
    expect(saved.providers.find((item: AIProviderConfigItem) => item.id === 'image-one')).toEqual(expect.objectContaining({
      model: 'gemini-3.1-flash-image',
      capabilities: ['IMAGE_GENERATION', 'IMAGE_REFERENCE'],
      options: { responseFormat: 'b64_json', quality: 'medium', size: '1536x864' },
    }));
  });

  it('keeps the dirty draft visible when save fails', async () => {
    const user = userEvent.setup();
    renderPage(
      { active: 'antigravity-gemini-3-1-pro', providers: [textProvider(), imageProvider()] },
      { onSave: async () => { throw new Error('save failed'); } },
    );

    await user.type(screen.getByRole('textbox', { name: '显示名称' }), ' Updated');
    await user.click(screen.getByRole('button', { name: '保存配置' }));

    expect(await screen.findByText('有未保存更改')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '显示名称' })).toHaveValue('Image OneUpdated');
  });

  it('bootstraps from an existing Antigravity provider without displaying its API key', async () => {
    const user = userEvent.setup();
    const source = { ...textProvider(), api_key: 'must-never-render' };
    const view = renderPage({ active: source.id, providers: [source] });

    expect(screen.queryByDisplayValue('must-never-render')).not.toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: '凭据来源' }));
    await user.click(screen.getByText('Antigravity Gemini 3.1 Pro · gemini-3.1-pro'));
    await user.click(screen.getByRole('button', { name: '从现有 Antigravity 配置添加' }));

    expect(view.onBootstrap).toHaveBeenCalledWith('antigravity-gemini-3-1-pro');
    expect(JSON.stringify(view.onBootstrap.mock.calls)).not.toContain('must-never-render');
  });
});
