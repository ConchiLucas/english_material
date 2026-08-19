export type StoryNodeKind = 'AGENT' | 'PROGRAM' | 'HUMAN';

export interface StoryAgentNode {
  key: string;
  name: string;
  nodeKind: StoryNodeKind;
  roleType: string;
  stageKey: string;
  order: number;
  parallelGroup?: string | null;
  description: string;
  variables: string[];
  upstream: string[];
  downstream: string[];
  systemPrompt?: string | null;
  aiProviderId?: string | null;
  temperature?: number | null;
  enabled?: boolean | null;
  promptVersion?: number | null;
  updatedAt?: string | null;
  editable: boolean;
}

export interface StoryFlowStage {
  key: string;
  name: string;
  note: string;
  order: number;
  nodes: StoryAgentNode[];
}

export interface StoryFlowBudget {
  maxQualityRounds: number;
  maxLocalRevisions: number;
  maxWriterRewrites: number;
  maxDirectorReturns: number;
  maxPitchReturns: number;
  maxPlanReturns: number;
  maxTotalTokens: number;
  updatedAt?: string | null;
}

export interface StoryAgentFlow {
  stages: StoryFlowStage[];
  budget: StoryFlowBudget;
}

export interface StoryPromptVersion {
  version: number;
  systemPrompt: string;
  aiProviderId: string;
  temperature: number;
  enabled: boolean;
  createdAt: string;
}

export interface StoryAgentUpdate {
  systemPrompt: string;
  aiProviderId: string;
  temperature: number;
  enabled: boolean;
  updatedAt?: string | null;
}

export interface StoryAgentRestoreRequest {
  updatedAt: string | null;
}

export interface StoryWord { word: string; meaning: string; }
export interface StoryWordLibrary { id: number; name: string; meaning: string; wordCount: number; }
export interface StoryRunSummary {
  runId: string;
  words: StoryWord[];
  targetGrade: string;
  status: string;
  totalTokens: number;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}
export interface StoryRunStep {
  id: number;
  sequence: number;
  qualityRound: number;
  agentKey: string;
  agentName: string;
  promptVersion: number;
  providerId: string;
  providerModel: string;
  inputJson: string;
  outputText: string;
  status: string;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  durationMs: number;
  createdAt: string;
}
export interface StoryRunDetail extends StoryRunSummary {
  finalStory: string | null;
  errorMessage: string | null;
  steps: StoryRunStep[];
}
export type StoryResultPageSize = 10 | 20 | 100;
export interface StoryResultItem {
  runId: string;
  title: string;
  targetGrade: string;
  wordCount: number;
  finalStory: string;
  createdAt: string;
}
export interface StoryResultPage {
  items: StoryResultItem[];
  page: number;
  pageSize: StoryResultPageSize;
  totalItems: number;
  totalPages: number;
}
