import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../core/api.service';
import { ChatService, type ChatEvent } from '../core/chat.service';
import { ChatPanel } from './chat-panel';

async function* events(list: ChatEvent[]): AsyncGenerator<ChatEvent> {
  for (const e of list) {
    await Promise.resolve();
    yield e;
  }
}

async function settle(fixture: { whenStable(): Promise<unknown>; detectChanges(): void }): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await fixture.whenStable();
    fixture.detectChanges();
    await new Promise((r) => setTimeout(r));
  }
}

describe('ChatPanel', () => {
  const turn = vi.fn();
  const assistant = vi.fn();

  beforeEach(() => {
    turn.mockReset();
    assistant.mockReset();
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiService, useValue: { assistant } },
        { provide: ChatService, useValue: { turn } },
      ],
    });
  });

  it('explains why the assistant is unavailable instead of showing a chat box', async () => {
    assistant.mockResolvedValue({ available: false, agentSlug: null, provider: 'offline', note: 'The assistant is not available right now.' });
    const fixture = TestBed.createComponent(ChatPanel);
    await settle(fixture);

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('The assistant is not available right now.');
    expect(el.querySelector('textarea')).toBeNull();
  });

  it('streams the answer into a bubble, renders markdown as elements and continues the conversation', async () => {
    assistant.mockResolvedValue({ available: true, agentSlug: 'rule-assistant-1', provider: 'offline', note: null });
    turn.mockImplementation(() => events([
      { type: 'start', conversationId: 'c-1', turnId: 't' },
      { type: 'text', text: 'I found 1 rule:\n- **ADULT** — ' },
      { type: 'text', text: '`customer.age >= 18` <b>x</b>' },
      { type: 'end' },
    ]));
    const fixture = TestBed.createComponent(ChatPanel);
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;

    const input = el.querySelector('textarea') as HTMLTextAreaElement;
    input.value = 'list the rules';
    input.dispatchEvent(new Event('input'));
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await settle(fixture);

    const bubbles = [...el.querySelectorAll('.bubble')].map((b) => b.textContent ?? '');
    expect(bubbles[0]).toContain('list the rules');
    expect(bubbles[1]).toContain('I found 1 rule:');
    expect(el.querySelector('.bubble-assistant strong')?.textContent).toBe('ADULT');
    expect(el.querySelector('.bubble-assistant code')?.textContent).toBe('customer.age >= 18');
    // markup in an answer stays text
    expect(el.querySelector('.bubble-assistant b')).toBeNull();
    expect(bubbles[1]).toContain('<b>x</b>');
    expect(turn).toHaveBeenCalledWith('rule-assistant-1', 'list the rules', null, expect.anything());

    // the next message continues the same conversation
    (el.querySelector('.chips .chip') as HTMLButtonElement | null)?.click();
    input.value = 'and the groups';
    input.dispatchEvent(new Event('input'));
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await settle(fixture);
    expect(turn).toHaveBeenLastCalledWith('rule-assistant-1', 'and the groups', 'c-1', expect.anything());
  });

  it('shows a failed turn as an alert in the bubble', async () => {
    assistant.mockResolvedValue({ available: true, agentSlug: 's', provider: 'offline', note: null });
    turn.mockImplementation(() => events([{ type: 'error', code: 'model-timeout', title: 'The model did not respond in time.' }]));
    const fixture = TestBed.createComponent(ChatPanel);
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('.chips .chip') as HTMLButtonElement).click();
    await settle(fixture);

    expect(el.querySelector('.bubble-assistant [role="alert"]')?.textContent).toContain('The model did not respond in time.');
  });
});
