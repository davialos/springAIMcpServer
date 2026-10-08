import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type {
  ApiEndpointView,
  AssistantInfo,
  AuditEntry,
  ChannelView,
  ConversationDetail,
  ConversationLog,
  CreateRuleRequest,
  Decision,
  EmailTemplateView,
  EvaluationDetail,
  EvaluationLog,
  ExpressionCheck,
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

/** A refused request: the stable `code` and a message the service guarantees is safe to show. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** Turns whatever a call threw into an {@link ApiError} (problem documents carry `code` and `detail`). */
export function toApiError(e: unknown): ApiError {
  if (e instanceof ApiError) {
    return e;
  }
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) {
      return new ApiError(0, 'network', 'the server cannot be reached');
    }
    const body = e.error as { code?: string; detail?: string } | null;
    return new ApiError(e.status, body?.code ?? 'http_' + e.status, body?.detail ?? (e.statusText || 'request failed'));
  }
  return new ApiError(0, 'unexpected', e instanceof Error ? e.message : 'something went wrong');
}

/** Every call of the rule-engine service. The tenant and organization are the token's: none of these takes one. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  private async call<T>(request: Promise<T>): Promise<T> {
    try {
      return await request;
    } catch (e) {
      throw toApiError(e);
    }
  }

  private get<T>(path: string, params: Record<string, string | number | undefined> = {}): Promise<T> {
    let p = new HttpParams();
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== '') {
        p = p.set(k, String(v));
      }
    }
    return this.call(firstValueFrom(this.http.get<T>(path, { params: p })));
  }

  me = () => this.get<Me>('/api/v1/me');
  setup = () => this.get<SetupSummary>('/api/v1/setup');
  modules = () => this.get<Module[]>('/api/v1/modules');
  library = () => this.get<LibraryObject[]>('/api/v1/library');
  rules = (module?: string, status?: string) => this.get<RuleView[]>('/api/v1/rules', { module, status });
  groups = (module?: string, status?: string) => this.get<GroupView[]>('/api/v1/rule-groups', { module, status });
  group = (id: string) => this.get<GroupView>(`/api/v1/rule-groups/${id}`);
  triggers = () => this.get<TriggerView[]>('/api/v1/triggers');
  channels = () => this.get<ChannelView[]>('/api/v1/channels');
  emailTemplates = () => this.get<EmailTemplateView[]>('/api/v1/email-templates');
  apiEndpoints = () => this.get<ApiEndpointView[]>('/api/v1/api-endpoints');
  logSummary = (hours: number) => this.get<LogSummary>('/api/v1/admin/logs/summary', { hours });
  evaluationLog = (page: number, size = 15) => this.get<Page<EvaluationLog>>('/api/v1/admin/logs/evaluations', { page, size });
  evaluationDetail = (id: string) => this.get<EvaluationDetail>(`/api/v1/admin/logs/evaluations/${id}`);
  auditLog = (page: number, size = 15) => this.get<Page<AuditEntry>>('/api/v1/admin/logs/audit', { page, size });
  conversations = (page: number, size = 15) => this.get<Page<ConversationLog>>('/api/v1/admin/logs/conversations', { page, size });
  conversation = (id: string) => this.get<ConversationDetail>(`/api/v1/admin/logs/conversations/${id}`);
  assistant = () => this.get<AssistantInfo>('/api/v1/assistant');

  createRule = (r: CreateRuleRequest) => this.call(firstValueFrom(this.http.post<Written<RuleView>>('/api/v1/rules', r)));
  setRuleStatus = (id: string, status: string) =>
    this.call(firstValueFrom(this.http.patch<RuleView>(`/api/v1/rules/${id}/status`, { status })));
  createGroup = (g: GroupRequest) => this.call(firstValueFrom(this.http.post<Written<GroupView>>('/api/v1/rule-groups', g)));
  replaceGroup = (id: string, g: GroupRequest) =>
    this.call(firstValueFrom(this.http.put<Written<GroupView>>(`/api/v1/rule-groups/${id}`, g)));
  setGroupStatus = (id: string, status: string) =>
    this.call(firstValueFrom(this.http.patch<GroupView>(`/api/v1/rule-groups/${id}/status`, { status })));
  checkExpression = (expression: string) =>
    this.call(firstValueFrom(this.http.post<ExpressionCheck>('/api/v1/expressions/check', { expression })));
  evaluate = (moduleCode: string, groupCode: string, facts: Record<string, unknown>, languages: string[]) =>
    this.call(firstValueFrom(this.http.post<Decision>('/api/v1/evaluations', { moduleCode, groupCode, facts, languages })));
}
