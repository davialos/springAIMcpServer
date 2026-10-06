import { ChangeDetectionStrategy, Component, ElementRef, effect, inject, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, toApiError } from '../core/api.service';
import { ChatService } from '../core/chat.service';
import type { AssistantInfo } from '../core/types';
import { lines, type Line } from '../shared/markdown';
import { InlineText } from './inline-text';

interface Message {
  role: 'user' | 'assistant';
  text: string;
  lines: Line[];
  pending: boolean;
  failed: string | null;
}

const SUGGESTIONS = [
  'List the active rules',
  'Which parameters are in the library?',
  'List the rule groups',
  'Check `customer.age >= 18`',
];

/**
 * The assistant, docked at the right edge. It answers about the signed-in user's own rule setup through the library's agent
 * runtime: the tools run as the user, so it can never show another tenant's rules. It reads and checks; it changes nothing.
 */
@Component({
  selector: 'app-chat-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, InlineText],
  template: `
    <aside class="chat" aria-label="AI assistant">
      <header class="chat-head">
        <div>
          <strong>Assistant</strong>
          @if (info(); as i) {
            <span class="hint">{{ i.provider === 'offline' ? 'offline templates' : i.provider }}</span>
          }
        </div>
        <div class="actions">
          <button type="button" class="btn btn-small" (click)="reset()" [disabled]="messages().length === 0 && !busy()">New chat</button>
          <button type="button" class="btn btn-small" (click)="closed.emit()" aria-label="Close the assistant">Close</button>
        </div>
      </header>

      @if (loading()) {
        <p role="status" class="chat-note"><span class="spinner" aria-hidden="true"></span> Getting the assistant ready…</p>
      } @else if (info()?.available !== true) {
        <div class="notice notice-warn" role="status">{{ info()?.note ?? 'The assistant is not available.' }}</div>
      } @else {
        <div class="chat-log" #log role="log" aria-live="polite" aria-label="Conversation">
          @if (messages().length === 0) {
            <p class="chat-note">Ask about your rules, rule groups or the parameter library, or have me check a CEL expression. I can read and check; I never change anything.</p>
            <div class="chips" role="group" aria-label="Suggestions">
              @for (s of suggestions; track s) {
                <button type="button" class="chip" (click)="send(s)">{{ s }}</button>
              }
            </div>
          }
          @for (m of messages(); track $index) {
            <div class="bubble" [class.bubble-user]="m.role === 'user'" [class.bubble-assistant]="m.role === 'assistant'">
              <span class="visually-hidden">{{ m.role === 'user' ? 'You' : 'Assistant' }}:</span>
              @for (l of m.lines; track $index) {
                @switch (l.kind) {
                  @case ('blank') { <div class="gap"></div> }
                  @case ('bullet') { <div class="li"><span aria-hidden="true">•</span><span><app-inline [pieces]="l.inline" /></span></div> }
                  @case ('cont') { <div class="cont"><app-inline [pieces]="l.inline" /></div> }
                  @default { <div><app-inline [pieces]="l.inline" /></div> }
                }
              }
              @if (m.pending && m.text === '') {
                <span class="spinner" aria-hidden="true"></span><span class="visually-hidden">Thinking…</span>
              }
              @if (m.failed) {
                <div class="notice notice-error" role="alert">{{ m.failed }}</div>
              }
            </div>
          }
        </div>
        <form class="chat-form" (ngSubmit)="send(draft())" novalidate>
          <label class="visually-hidden" for="chat-input">Message to the assistant</label>
          <textarea id="chat-input" name="draft" rows="2" maxlength="2000" placeholder="Ask about your rules…" [(ngModel)]="draft"
                    (keydown.enter)="enter($event)" [disabled]="busy()"></textarea>
          @if (busy()) {
            <button type="button" class="btn" (click)="stop()">Stop</button>
          } @else {
            <button type="submit" class="btn btn-primary" [disabled]="!draft().trim()">Send</button>
          }
        </form>
      }
    </aside>

  `,
})
export class ChatPanel {
  private readonly api = inject(ApiService);
  private readonly chat = inject(ChatService);
  private readonly log = viewChild<ElementRef<HTMLElement>>('log');

  readonly closed = output<void>();

  protected readonly suggestions = SUGGESTIONS;
  protected readonly loading = signal(true);
  protected readonly info = signal<AssistantInfo | null>(null);
  protected readonly messages = signal<Message[]>([]);
  protected readonly draft = signal('');
  protected readonly busy = signal(false);
  private conversationId: string | null = null;
  private abort: AbortController | null = null;

  constructor() {
    this.api.assistant().then(
      (i) => this.info.set(i),
      (e: unknown) => this.info.set({ available: false, agentSlug: null, provider: null, note: toApiError(e).message }),
    ).finally(() => this.loading.set(false));
    // keep the newest text in view as the answer streams in
    effect(() => {
      this.messages();
      queueMicrotask(() => {
        const el = this.log()?.nativeElement;
        if (el) {
          el.scrollTop = el.scrollHeight;
        }
      });
    });
  }

  protected enter(e: Event): void {
    if (!(e as KeyboardEvent).shiftKey) {
      e.preventDefault();
      void this.send(this.draft());
    }
  }

  protected stop(): void {
    this.abort?.abort();
  }

  protected reset(): void {
    this.abort?.abort();
    this.conversationId = null;
    this.messages.set([]);
  }

  /** Sends a message and streams the answer into a new assistant bubble. */
  protected async send(text: string): Promise<void> {
    const message = text.trim();
    const slug = this.info()?.agentSlug;
    if (!message || this.busy() || !slug) {
      return;
    }
    this.draft.set('');
    this.busy.set(true);
    const index = this.messages().length + 1; // the assistant bubble comes right after the user's
    this.messages.update((m) => [
      ...m,
      { role: 'user', text: message, lines: lines(message), pending: false, failed: null },
      { role: 'assistant', text: '', lines: [], pending: true, failed: null },
    ]);
    const update = (patch: Partial<Message>) =>
      this.messages.update((all) => all.map((x, i) => (i === index ? { ...x, ...patch } : x)));
    let answer = '';
    this.abort = new AbortController();
    try {
      for await (const e of this.chat.turn(slug, message, this.conversationId, this.abort.signal)) {
        if (e.type === 'start') {
          this.conversationId = e.conversationId || this.conversationId;
        } else if (e.type === 'text') {
          answer += e.text;
          update({ text: answer, lines: lines(answer) });
        } else if (e.type === 'error') {
          update({ failed: e.title, pending: false });
        }
      }
      update({ pending: false });
    } catch (e) {
      update({ failed: toApiError(e).message, pending: false });
    } finally {
      this.busy.set(false);
      this.abort = null;
    }
  }
}
