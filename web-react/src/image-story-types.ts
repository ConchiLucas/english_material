export type ImageNodeKind = 'AGENT' | 'PROGRAM';

export interface ImageAgentNode {
  key: string;
  name: string;
  nodeKind: ImageNodeKind;
  roleType: string;
  stageKey: string;
  order: number;
  parallelGroup: string | null;
  description: string;
  variables: string[];
  upstream: string[];
  systemPrompt: string | null;
  aiProviderId: string | null;
  temperature: number | null;
  enabled: boolean | null;
  promptVersion: number | null;
  updatedAt: string | null;
  editable: boolean;
}

export interface ImageFlowStage {
  key: string;
  name: string;
  note: string;
  order: number;
  nodes: ImageAgentNode[];
}

export interface ImageFlowConfig {
  imageProviderId: string | null;
  width: number;
  height: number;
  maxShotsPerScene: number;
  maxShotsPerStory: number;
  updatedAt: string | null;
}

export interface ImageStylePreset {
  id: number;
  key: string;
  name: string;
  positivePrompt: string;
  negativePrompt: string;
  description: string;
  enabled: boolean;
  builtIn: boolean;
  updatedAt: string;
}

export interface ImageAgentFlow {
  stages: ImageFlowStage[];
  config: ImageFlowConfig;
  stylePresets: ImageStylePreset[];
}

export interface ImagePromptVersion {
  version: number;
  systemPrompt: string;
  aiProviderId: string | null;
  temperature: number;
  enabled: boolean;
  createdAt: string;
}

export interface ImageAgentUpdate {
  systemPrompt: string;
  aiProviderId: string;
  temperature: number;
  enabled: boolean;
  updatedAt: string | null;
}

export interface ImageAgentRestoreRequest {
  updatedAt: string | null;
}

export interface ImageFlowUpdate {
  imageProviderId: string;
  width: number;
  height: number;
  maxShotsPerScene: number;
  maxShotsPerStory: number;
  updatedAt: string | null;
}

export interface ImageStyleCreate {
  name: string;
  positivePrompt: string;
  negativePrompt: string;
  description: string;
  enabled: boolean;
}

export interface ImageStyleUpdate extends ImageStyleCreate {
  updatedAt: string | null;
}

export interface ImageStoryWord {
  word: string;
  meaning: string;
}

export interface ImageRunStart {
  storyRunId: string;
  stylePresetId: number;
}

export interface ImageRunSummary {
  runId: string;
  storyRunId: string;
  stylePresetId: number | null;
  stylePresetName: string | null;
  targetGrade: string;
  words: ImageStoryWord[];
  wordsError: string | null;
  status: string;
  expectedImageCount: number;
  generatedImageCount: number;
  totalTextTokens: number;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface ImageSourceStory {
  runId: string;
  words: ImageStoryWord[];
  wordsError: string | null;
  targetGrade: string;
  status: string;
  finalStory: string;
  createdAt: string;
  finishedAt: string | null;
}

export interface ImageRunStep {
  id: number;
  sequence: number;
  stageKey: string;
  nodeKey: string;
  nodeName: string;
  nodeKind: ImageNodeKind;
  promptVersion: number | null;
  providerId: string | null;
  providerModel: string | null;
  inputJson: string | null;
  rawOutput: string | null;
  parsedOutputJson: string | null;
  errorMessage: string | null;
  status: string;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  durationMs: number;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
}

export interface ImageShot {
  id: number;
  shotKey: string;
  sceneIndex: number;
  shotIndex: number;
  sequence: number;
  sourceExcerpt: string | null;
  visualGoal: string | null;
  speaker: string | null;
  dialogue: string | null;
  caption: string | null;
  textAnchorJson: string | null;
  prompt: string | null;
  negativePrompt: string | null;
  referenceAssetKeysJson: string | null;
  status: string;
  createdAt: string;
}

export interface ImageAsset {
  id: number;
  assetType: string;
  assetKey: string;
  shotKey: string | null;
  mime: string;
  width: number;
  height: number;
  sha256: string;
  providerId: string | null;
  providerModel: string | null;
  providerRequestId: string | null;
  prompt: string | null;
  negativePrompt: string | null;
  providerMetadataJson: string | null;
  contentUrl: string;
  createdAt: string;
}

export interface ImageAgentSnapshot {
  sequence: number;
  stageKey: string;
  key: string;
  name: string;
  systemPrompt: string;
  promptVersion: number;
  temperature: number;
  providerId: string;
  providerLabel: string;
  providerType: string;
  providerModel: string;
  maxTokens: number | null;
  capabilities: string[];
}

export interface ImageRunDetail {
  runId: string;
  storyRunId: string;
  words: ImageStoryWord[];
  wordsError: string | null;
  targetGrade: string;
  status: string;
  storySnapshot: string;
  stylePresetId: string | null;
  stylePresetName: string | null;
  styleSnapshotJson: string;
  flowSnapshotJson: string;
  agentSnapshotSchemaVersion: number | null;
  agentSnapshots: ImageAgentSnapshot[];
  agentSnapshotError: string | null;
  expectedImageCount: number;
  generatedImageCount: number;
  totalTextTokens: number;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  steps: ImageRunStep[];
  shots: ImageShot[];
  assets: ImageAsset[];
}

export type ImageResultPageSize = 10 | 20 | 100;

export interface ImageResultShot {
  assetId: number;
  shotKey: string;
  sceneIndex: number;
  shotIndex: number;
  sequence: number;
  sourceExcerpt: string | null;
  dialogue: string | null;
  caption: string | null;
}

export interface ImageResultItem {
  runId: string;
  title: string;
  stylePresetName: string | null;
  targetGrade: string;
  imageCount: number;
  completedAt: string;
  shots: ImageResultShot[];
}

export interface ImageResultPage {
  items: ImageResultItem[];
  page: number;
  pageSize: ImageResultPageSize;
  totalItems: number;
  totalPages: number;
}
