import { get, patch, post, put, query } from './http';
import type {
  ApiEndpointView,
  AuditEntry,
  ChannelView,
  ConversationDetail,
  ConversationLog,
  CreateRuleRequest,
  Decision,
  EmailTemplateView,
  EvaluationDetail,
  EvaluationLog,
  GroupRequest,
  GroupView,
  LibraryObject,
  LogSummary,
  Me,
  Module,
  Page,
  RuleView,
  SetupSummary,
  TriggerView,
  Written,
} from './types';

/** Every call of the rule-engine service. The tenant and organization are the token's: none of these takes one. */
export const engine = {
  me: () => get<Me>('/api/v1/me'),
  setup: () => get<SetupSummary>('/api/v1/setup'),
  modules: () => get<Module[]>('/api/v1/modules'),
  library: () => get<LibraryObject[]>('/api/v1/library'),
  rules: (module?: string, status?: string) => get<RuleView[]>('/api/v1/rules' + query({ module, status })),
  groups: (module?: string, status?: string) => get<GroupView[]>('/api/v1/rule-groups' + query({ module, status })),
  group: (id: string) => get<GroupView>(`/api/v1/rule-groups/${id}`),
  triggers: () => get<TriggerView[]>('/api/v1/triggers'),
  channels: () => get<ChannelView[]>('/api/v1/channels'),
  emailTemplates: () => get<EmailTemplateView[]>('/api/v1/email-templates'),
  apiEndpoints: () => get<ApiEndpointView[]>('/api/v1/api-endpoints'),

  createRule: (r: CreateRuleRequest) => post<Written<RuleView>>('/api/v1/rules', r),
  setRuleStatus: (id: string, status: string) => patch<RuleView>(`/api/v1/rules/${id}/status`, { status }),
  createGroup: (g: GroupRequest) => post<Written<GroupView>>('/api/v1/rule-groups', g),
  replaceGroup: (id: string, g: GroupRequest) => put<Written<GroupView>>(`/api/v1/rule-groups/${id}`, g),
  setGroupStatus: (id: string, status: string) => patch<GroupView>(`/api/v1/rule-groups/${id}/status`, { status }),

  evaluate: (moduleCode: string, groupCode: string, facts: Record<string, unknown>, languages: string[]) =>
    post<Decision>('/api/v1/evaluations', { moduleCode, groupCode, facts, languages }),

  logs: {
    summary: (hours: number) => get<LogSummary>('/api/v1/admin/logs/summary' + query({ hours })),
    evaluations: (f: {
      decision?: string;
      module?: string;
      group?: string;
      errorsOnly?: boolean;
      page?: number;
      size?: number;
    }) => get<Page<EvaluationLog>>('/api/v1/admin/logs/evaluations' + query(f)),
    evaluation: (id: string) => get<EvaluationDetail>(`/api/v1/admin/logs/evaluations/${id}`),
    audit: (f: { action?: string; entityType?: string; actor?: string; page?: number; size?: number }) =>
      get<Page<AuditEntry>>('/api/v1/admin/logs/audit' + query(f)),
    conversations: (page = 0, size = 25) =>
      get<Page<ConversationLog>>('/api/v1/admin/logs/conversations' + query({ page, size })),
    conversation: (id: string) => get<ConversationDetail>(`/api/v1/admin/logs/conversations/${id}`),
  },
};
