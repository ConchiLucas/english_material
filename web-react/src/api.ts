import axios from 'axios';

export interface ApiResponse<T> { code: number; data: T; msg: string; }
export interface ConnectionConfig { ID?: number; connectionName: string; connectionType: string; connectionUrl: string; databaseName: string; port: number; dbLoginName: string; dbLoginPassword?: string; envName?: string; }
export interface AIProviderConfigItem { id: string; label: string; type: 'openai-compatible' | 'anthropic-compatible' | 'mimo-tts'; base_url: string; api_key: string; model: string; max_tokens: number; capabilities?: string[]; enabled?: boolean; active?: boolean; }
export interface AIConfig { active: string; providers: AIProviderConfigItem[]; }
export interface LocalCliConfigItem { enabled: boolean; id: string; label: string; command: string; defaultArgs: string[]; model?: string; reasoningEffort?: string; workingDirectory: string; timeoutSeconds: number; active?: boolean; }
export interface LocalCliConfig { active: string; configs: LocalCliConfigItem[]; }
export interface WordCleanItem {
  id: number;
  word: string;
  meaning: string;
  difficulty: number;
  frequency: number;
  sentence: string;
  pepDifficulty?: number;
  pepDifficultyLabel: string;
  sourceDifficulty?: number;
  sourceLabel: string;
  bestSentenceId?: number;
  bestSourceSentenceId?: number;
  bestSourceModelName: string;
  bestSentence: string;
  bestSentenceTranslation: string;
  bestSentenceScore?: number;
  bestSentenceScoreReason: string;
  bestSentenceScoreModelName: string;
  bestSentenceScoredAt: string;
  bestSentenceTtsStatus: string;
  bestSentenceTtsObjectUrl: string;
  wordTtsStatus: string;
  wordTtsObjectUrl: string;
}
export interface WordCleanSentenceItem {
  id: number;
  wordCleanId: number;
  word: string;
  modelName: string;
  sentence: string;
  sentenceTranslation: string;
  score?: number;
  scoreReason: string;
  scoreModelName: string;
  scoredAt: string;
}
export interface WordCleanFacetItem { value: string; label: string; count: number; }
export interface WordCleanFacets {
  pepDifficulties: WordCleanFacetItem[];
  sourceDifficulties: WordCleanFacetItem[];
  difficultyRanges: WordCleanFacetItem[];
}
export interface WordCleanQuery {
  connectionId: number;
  keyword?: string;
  pepDifficulty?: number;
  sourceDifficulty?: number;
  difficultyMin?: number;
  difficultyMax?: number;
  sortBy?: 'difficulty' | 'frequency' | 'pepDifficulty' | 'sourceDifficulty';
  sortOrder?: 'asc' | 'desc';
  page?: number;
  pageSize?: number;
}

export type AgentCategory = 'planning' | 'creation' | 'review' | 'visual' | 'learning';
export interface AgentDefinition {
  ID?: number;
  CreatedAt?: string;
  UpdatedAt?: string;
  agentKey: string;
  name: string;
  category: AgentCategory;
  description: string;
  aiProviderId: string;
  systemPrompt: string;
  promptTemplate: string;
  inputSchema: string;
  outputSchema: string;
  hardRules: string;
  evaluationRubric: string;
  temperature: number;
  maxTokens: number;
  retryLimit: number;
  sortOrder: number;
}
export interface AgentTestResult {
  runId: number;
  agentId: number;
  agentKey: string;
  agentName: string;
  aiProviderId: string;
  status: 'PASSED' | 'NEEDS_REVISION' | 'NEEDS_REVIEW' | 'FAILED' | 'RUNNING';
  inputJson: string;
  outputText: string;
  schemaValid: boolean;
  overallScore?: number;
  dimensionScores: Record<string, number>;
  issues: string[];
  durationMs: number;
  errorMessage: string;
  createdAt?: string;
}

const request = axios.create({ baseURL: import.meta.env.VITE_API_BASE || 'http://127.0.0.1:18744/api', timeout: 120000 });
const unwrap = <T>(response: { data: ApiResponse<T> }) => {
  if (response.data.code !== 0) throw new Error(response.data.msg || '请求失败');
  return response.data.data;
};

export const getConnections = () => request.get<ApiResponse<{ list: ConnectionConfig[] }>>('/connection/getTbConnectionList').then(unwrap).then((page) => page.list);
export const createConnection = (value: ConnectionConfig) => request.post('/connection/createTbConnection', value).then(unwrap);
export const updateConnection = (value: ConnectionConfig) => request.put('/connection/updateTbConnection', value).then(unwrap);
export const deleteConnection = (ID: number) => request.delete('/connection/deleteTbConnection', { data: { id: ID } }).then(unwrap);
export const testConnectionPayload = (value: ConnectionConfig) => request.post('/connection/testConnectionPayload', value).then(unwrap);
export const getAIConfig = () => request.get<ApiResponse<AIConfig>>('/ai/config').then(unwrap);
export const saveAIConfig = (value: AIConfig) => request.post('/ai/config', value).then(unwrap);
export const getLocalCliConfig = () => request.get<ApiResponse<LocalCliConfig>>('/ai/cli/config').then(unwrap);
export const saveLocalCliConfig = (value: LocalCliConfig) => request.post('/ai/cli/config', value).then(unwrap);
export const getWordCleanWords = (params: WordCleanQuery) =>
  request.get<ApiResponse<{ list: WordCleanItem[]; total: number; page: number; pageSize: number }>>('/word-clean', { params }).then(unwrap);
export const getWordCleanFacets = (connectionId: number) =>
  request.get<ApiResponse<WordCleanFacets>>('/word-clean/facets', { params: { connectionId } }).then(unwrap);
export const getWordCleanSentences = (connectionId: number, wordCleanId: number) =>
  request.get<ApiResponse<WordCleanSentenceItem[]>>(`/word-clean/${wordCleanId}/sentences`, { params: { connectionId } }).then(unwrap);
export const getAgents = () => request.get<ApiResponse<AgentDefinition[]>>('/agents').then(unwrap);
export const createAgent = (value: AgentDefinition) => request.post<ApiResponse<AgentDefinition>>('/agents', value).then(unwrap);
export const updateAgent = (value: AgentDefinition) => request.put<ApiResponse<AgentDefinition>>(`/agents/${value.ID}`, value).then(unwrap);
export const testAgent = (id: number, inputJson: string) => request.post<ApiResponse<AgentTestResult>>(`/agents/${id}/test`, { inputJson }).then(unwrap);
export const getAgentRuns = () => request.get<ApiResponse<AgentTestResult[]>>('/agents/runs').then(unwrap);
