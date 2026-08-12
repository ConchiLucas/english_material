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
