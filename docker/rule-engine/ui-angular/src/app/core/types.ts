// Mirror of the JSON the rule-engine service speaks (Dtos.java). No fact value ever appears in these types.

export type Action = 'ALLOW' | 'WARN' | 'BLOCK';
export type Policy = 'FIRST_MATCH' | 'ALL_MATCH' | 'EVALUATE_ALL' | 'COMPOSITE';
export type Status = 'DRAFT' | 'ACTIVE' | 'RETIRED';
export type Scope = 'TENANT' | 'ORGANIZATION';
/** Language tag → text. */
export type Texts = Record<string, string>;

export interface Me {
  userId: string;
  username: string;
  displayName: string;
  role: 'USER' | 'ADMIN';
  tenantId: string;
  tenantName: string;
  organizationId: string | null;
  organizationName: string | null;
}

export interface SetupSummary {
  tenant: string;
  organization: string | null;
  role: string;
  modules: number;
  objects: number;
  parameters: number;
  rules: number;
  activeRules: number;
  groups: number;
  activeGroups: number;
  triggers: number;
  channels: number;
  emailTemplates: number;
  apiEndpoints: number;
  messages: number;
  policies: Policy[];
  actions: Action[];
  languages: string[];
}

export interface Module {
  code: string;
  name: string;
  description: string | null;
}

export interface Attribute {
  code: string;
  name: string;
  dataType: string;
  required: boolean;
  sampleValue: string | null;
  celName: string;
  usedByRules: number;
}

export interface LibraryObject {
  code: string;
  name: string;
  moduleCode: string | null;
  attributes: Attribute[];
}

export interface RuleView {
  id: string;
  moduleCode: string;
  code: string;
  name: string;
  description: string | null;
  expression: string;
  status: Status;
  scope: Scope;
  trueAction: Action;
  falseAction: Action;
  trueMessage: Texts;
  falseMessage: Texts;
  parameters: string[];
  groups: string[];
  rowVersion: number;
  updatedAt: string;
}

export interface GroupRuleView {
  ruleCode: string;
  ruleName: string;
  ruleStatus: Status;
  sequence: number;
  enabled: boolean;
}

export interface TriggerView {
  id: string;
  application: string;
  type: 'FORM_ACTION' | 'FORM_FIELD';
  formCode: string;
  actionCode: string;
  fieldCode: string | null;
  moduleCode: string;
  groupCode: string;
  sequence: number;
  enabled: boolean;
  scope: Scope;
}

export interface GroupView {
  id: string;
  moduleCode: string;
  code: string;
  name: string;
  description: string | null;
  status: Status;
  scope: Scope;
  policy: Policy;
  matchOn: 'TRUE' | 'FALSE';
  onError: Action;
  compositeTrueAction: Action;
  compositeFalseAction: Action;
  compositeTrueMessage: Texts;
  compositeFalseMessage: Texts;
  rules: GroupRuleView[];
  triggers: TriggerView[];
  channelCount: number;
  rowVersion: number;
  updatedAt: string;
}

export interface ChannelView {
  id: string;
  ownerType: string;
  ownerCode: string | null;
  onResult: string;
  channelType: string;
  sequence: number;
  enabled: boolean;
  emailTemplate: string | null;
  apiEndpoint: string | null;
  hasRecipientExpression: boolean;
}

export interface EmailTemplateView {
  id: string;
  templateRef: string;
  name: string;
  active: boolean;
}

export interface ApiEndpointView {
  id: string;
  name: string;
  method: string;
  url: string;
  environment: string;
  timeoutMs: number;
  externalConfirmed: boolean;
  active: boolean;
}

// ── requests ──
export interface CreateRuleRequest {
  moduleCode: string;
  code: string;
  name: string;
  description?: string;
  expression: string;
  trueMessage?: Texts;
  falseMessage?: Texts;
  trueAction: Action;
  falseAction: Action;
  status: 'DRAFT' | 'ACTIVE';
  scope?: Scope;
}

export interface RuleRef {
  ruleCode: string;
  sequence?: number;
  enabled?: boolean;
}

export interface TriggerSpec {
  application: string;
  type: 'FORM_ACTION' | 'FORM_FIELD';
  formCode: string;
  actionCode: string;
  fieldCode?: string;
}

export interface GroupRequest {
  moduleCode: string;
  code: string;
  name: string;
  description?: string;
  policy: Policy;
  matchOn?: 'TRUE' | 'FALSE';
  onError?: Action;
  compositeTrueMessage?: Texts;
  compositeFalseMessage?: Texts;
  compositeTrueAction?: Action;
  compositeFalseAction?: Action;
  rules?: RuleRef[];
  triggers?: TriggerSpec[];
  status?: 'DRAFT' | 'ACTIVE';
  scope?: Scope;
  expectedRowVersion?: number;
}

export interface Written<T> {
  value: T;
  warnings: string[];
}

// ── evaluation ──
export interface RuleOutcome {
  ruleCode: string;
  ruleName: string;
  sequence: number;
  outcome: 'TRUE' | 'FALSE' | 'ERROR';
  action: Action;
  errorCode: string | null;
  errorDetail: string | null;
}

export interface MessageView {
  source: string;
  ruleCode: string | null;
  outcome: string;
  action: Action;
  language: string;
  text: string;
}

export interface PlannedChannelView {
  type: string;
  onResult: string;
  ruleCode: string | null;
  recipientResolved: boolean;
  target: string | null;
}

export interface Decision {
  moduleCode: string;
  groupCode: string;
  policy: Policy;
  decision: Action;
  matched: boolean;
  primaryMessage: MessageView | null;
  messages: MessageView[];
  results: RuleOutcome[];
  channels: PlannedChannelView[];
  durationMicros: number;
}

// ── admin logs ──
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface EvaluationLog {
  id: string;
  at: string;
  moduleCode: string | null;
  groupCode: string | null;
  groupName: string | null;
  policy: Policy;
  decision: Action;
  language: string | null;
  durationMicros: number;
  rulesTrue: number;
  rulesFalse: number;
  rulesError: number;
  viaTrigger: boolean;
  organizationId: string | null;
}

export interface EvaluationDetail {
  evaluation: EvaluationLog;
  results: RuleOutcome[];
}

export interface AuditEntry {
  id: string;
  at: string;
  actorName: string | null;
  actorRole: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  entityCode: string | null;
  summary: string | null;
  details: string | null;
  organizationId: string | null;
}

export interface ConversationLog {
  id: string;
  title: string | null;
  channel: string;
  status: string;
  startedAt: string;
  lastActivityAt: string;
  messages: number;
}

export interface ChatMessage {
  seq: number;
  role: string;
  content: string;
  redacted: boolean;
  tokens: number | null;
  at: string;
}

export interface ConversationDetail {
  conversation: ConversationLog;
  messages: ChatMessage[];
}

export interface Bucket {
  at: string;
  total: number;
  allow: number;
  warn: number;
  block: number;
}

export interface GroupLoad {
  moduleCode: string | null;
  groupCode: string;
  evaluations: number;
  blocked: number;
  errors: number;
  avgMillis: number;
}

export interface LogSummary {
  hours: number;
  evaluations: number;
  allow: number;
  warn: number;
  block: number;
  withErrors: number;
  p50Millis: number;
  p95Millis: number;
  auditEvents: number;
  authoringChanges: number;
  conversations: number;
  series: Bucket[];
  topGroups: GroupLoad[];
  auditByAction: Record<string, number>;
}

// ── AI assistant ──
export interface AssistantInfo {
  available: boolean;
  agentSlug: string | null;
  provider: string | null;
  note: string | null;
}

export interface ExpressionCheck {
  valid: boolean;
  error: string | null;
  parameters: string[];
}
