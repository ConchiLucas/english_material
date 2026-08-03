import axios from 'axios';

export interface ApiResponse<T> { code: number; data: T; msg: string; }
export interface ConnectionConfig { ID?: number; connectionName: string; connectionType: string; connectionUrl: string; databaseName: string; port: number; dbLoginName: string; dbLoginPassword?: string; envName?: string; }
export interface AIProviderConfigItem { id: string; label: string; type: 'openai-compatible' | 'anthropic-compatible' | 'mimo-tts'; base_url: string; api_key: string; model: string; max_tokens: number; enabled?: boolean; active?: boolean; }
export interface AIConfig { active: string; providers: AIProviderConfigItem[]; }
export interface LocalCliConfigItem { enabled: boolean; id: string; label: string; command: string; defaultArgs: string[]; model?: string; reasoningEffort?: string; workingDirectory: string; timeoutSeconds: number; active?: boolean; }
export interface LocalCliConfig { active: string; configs: LocalCliConfigItem[]; }

const request = axios.create({ baseURL: import.meta.env.VITE_API_BASE || 'http://127.0.0.1:18744/api', timeout: 120000 });
const unwrap = <T>(response: { data: ApiResponse<T> }) => response.data.data;

export const getConnections = () => request.get<ApiResponse<{ list: ConnectionConfig[] }>>('/connection/getTbConnectionList').then(unwrap).then((page) => page.list);
export const createConnection = (value: ConnectionConfig) => request.post('/connection/createTbConnection', value).then(unwrap);
export const updateConnection = (value: ConnectionConfig) => request.put('/connection/updateTbConnection', value).then(unwrap);
export const deleteConnection = (ID: number) => request.delete('/connection/deleteTbConnection', { data: { id: ID } }).then(unwrap);
export const testConnectionPayload = (value: ConnectionConfig) => request.post('/connection/testConnectionPayload', value).then(unwrap);
export const getAIConfig = () => request.get<ApiResponse<AIConfig>>('/ai/config').then(unwrap);
export const saveAIConfig = (value: AIConfig) => request.post('/ai/config', value).then(unwrap);
export const getLocalCliConfig = () => request.get<ApiResponse<LocalCliConfig>>('/ai/cli/config').then(unwrap);
export const saveLocalCliConfig = (value: LocalCliConfig) => request.post('/ai/cli/config', value).then(unwrap);
