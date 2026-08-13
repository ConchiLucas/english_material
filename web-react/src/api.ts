import axios from 'axios';
import type {
  StoryAgentFlow,
  StoryAgentNode,
  StoryAgentRestoreRequest,
  StoryAgentUpdate,
  StoryFlowBudget,
  StoryPromptVersion,
  StoryRunDetail,
  StoryRunSummary,
  StoryWord,
  StoryWordLibrary,
} from './story-flow-types';

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
export const getStoryAgentFlow = () =>
  request.get<ApiResponse<StoryAgentFlow>>('/story-agents/flow').then(unwrap);
export const updateStoryAgent = (key: string, value: StoryAgentUpdate) =>
  request.put<ApiResponse<StoryAgentNode>>(`/story-agents/${encodeURIComponent(key)}`, value).then(unwrap);
export const getStoryAgentVersions = (key: string) =>
  request.get<ApiResponse<StoryPromptVersion[]>>(`/story-agents/${encodeURIComponent(key)}/versions`).then(unwrap);
export const restoreStoryAgentVersion = (key: string, version: number, value: StoryAgentRestoreRequest) =>
  request.post<ApiResponse<StoryAgentNode>>(
    `/story-agents/${encodeURIComponent(key)}/versions/${encodeURIComponent(String(version))}/restore`,
    value,
  ).then(unwrap);
export const updateStoryFlowBudget = (value: StoryFlowBudget) =>
  request.put<ApiResponse<StoryFlowBudget>>('/story-agents/flow/config', value).then(unwrap);
export const getStoryRuns = () => request.get<ApiResponse<StoryRunSummary[]>>('/story-runs').then(unwrap);
export const getStoryRun = (runId: string) => request.get<ApiResponse<StoryRunDetail>>(`/story-runs/${encodeURIComponent(runId)}`).then(unwrap);
export const createStoryRun = (value: { words: StoryWord[]; targetGrade: string }) =>
  request.post<ApiResponse<StoryRunSummary>>('/story-runs', value).then(unwrap);
export const getStoryWordLibraries = (connectionId: number) =>
  request.get<ApiResponse<StoryWordLibrary[]>>('/story-runs/word-libraries', { params: { connectionId } }).then(unwrap);
export const previewRandomStoryWords = (value: { connectionId: number; libraryId: number; count: number }) =>
  request.post<ApiResponse<StoryWord[]>>('/story-runs/random-words', value).then(unwrap);
