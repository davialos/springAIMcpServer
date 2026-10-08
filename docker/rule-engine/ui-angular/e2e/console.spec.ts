import { expect, test, type Page } from '@playwright/test';

/**
 * The Angular console end to end, against a LIVE stack: protobuf sign-in, rule setup, live CEL validation, a rule group
 * created and tested, the AI assistant answering from the signed-in user's own tenant, and the administrator's logs.
 * Tests run in order and share rule/group names with a unique suffix, so repeated runs do not collide.
 */
test.describe.configure({ mode: 'serial' });

/**
 * Any uncaught error or console error (a CSP violation included) fails the test. Refusals (4xx) are asserted by the tests
 * that provoke them.
 */
const problems: string[] = [];
test.beforeEach(({ page }) => {
  problems.length = 0;
  page.on('pageerror', (e) => problems.push(`uncaught: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && !/Failed to load resource: the server responded with a status of 4\d\d/.test(m.text())) {
      problems.push(`console: ${m.text()}`);
    }
  });
});
test.afterEach(() => {
  expect(problems, 'browser errors / CSP violations').toEqual([]);
});

const run = Date.now().toString(36);
const RULE = `ng-age-${run}`;
const GROUP = `ng-group-${run}`;
const ADMIN_PW = process.env['E2E_ADMIN_PASSWORD'] ?? 'admin123';
const USER_PW = process.env['E2E_USER_PASSWORD'] ?? 'user123';

const sidebar = (page: Page) => page.getByRole('navigation', { name: 'Main' });

async function attemptSignIn(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

async function signIn(page: Page, username: string, password: string) {
  await attemptSignIn(page, username, password);
  await expect(sidebar(page)).toBeVisible();
}

async function openAssistant(page: Page) {
  await sidebar(page).getByRole('button', { name: 'AI assistant' }).click();
  await expect(page.getByRole('complementary', { name: 'AI assistant' })).toBeVisible();
  await expect(page.getByLabel('Message to the assistant')).toBeVisible();
}

async function ask(page: Page, text: string) {
  const before = await page.locator('.bubble-assistant').count();
  await page.getByLabel('Message to the assistant').fill(text);
  await page.getByRole('button', { name: 'Send' }).click();
  await expect(page.locator('.bubble-assistant')).toHaveCount(before + 1);
  // the answer has finished streaming when the Send button is back
  await expect(page.getByRole('button', { name: 'Send' })).toBeVisible();
  return page.locator('.bubble-assistant').last();
}

test('the login page rejects a wrong password without saying which part was wrong', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: 'Rule engine console' })).toBeVisible();

  await attemptSignIn(page, 'admin', 'definitely-wrong');
  await expect(page.getByRole('alert')).toHaveText('Wrong username or password.');
  await attemptSignIn(page, `nobody-${run}`, 'x'); // unique per run: repeated failures lock a name out
  await expect(page.getByRole('alert')).toHaveText('Wrong username or password.');
});

test('an administrator signs in over protobuf and lands in their tenant and organization', async ({ page }) => {
  const login = page.waitForRequest((r) => r.url().endsWith('/auth/login'));
  await signIn(page, 'admin', ADMIN_PW);

  const req = await login;
  expect(req.headers()['content-type']).toContain('application/x-protobuf');
  expect(req.postDataBuffer()?.length).toBeGreaterThan(0);
  await expect(page.locator('.user-chip')).toContainText('Acme Bank · Retail');
  await expect(page.locator('.user-chip')).toContainText('Administrator');
  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible();
  await expect(sidebar(page).getByRole('link', { name: /Logs/ })).toBeVisible();
});

test('the setup pages show the library, the rules, the groups and the channels of the tenant', async ({ page }) => {
  await signIn(page, 'admin', ADMIN_PW);

  await sidebar(page).getByRole('link', { name: 'Parameter library' }).click();
  await expect(page.getByRole('cell', { name: 'customer.age', exact: true })).toBeVisible();
  await page.getByLabel('Find a parameter').fill('credit');
  await expect(page.getByRole('cell', { name: 'customer.creditScore', exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'customer.age', exact: true })).toHaveCount(0);

  await sidebar(page).getByRole('link', { name: 'Rules' }).click();
  await expect(page.getByRole('button', { name: 'Customer is an adult' })).toBeVisible();
  await page.getByRole('button', { name: 'Customer is an adult' }).click();
  await expect(page.getByText('Used in groups')).toBeVisible();

  await sidebar(page).getByRole('link', { name: 'Rule groups' }).click();
  await expect(page.getByRole('cell', { name: /LOAN_ELIGIBILITY/ })).toBeVisible();

  await sidebar(page).getByRole('link', { name: 'Triggers & channels' }).click();
  await expect(page.getByRole('cell', { name: 'loan-portal' }).first()).toBeVisible();
});

test('a rule is checked against the parameter library as it is typed and when it is saved', async ({ page }) => {
  await signIn(page, 'admin', ADMIN_PW);
  await page.goto('/rules/new');
  await page.getByLabel('Code').fill(RULE);
  await page.getByLabel('Name', { exact: true }).fill(`Adult ${run}`);

  const expression = page.getByLabel('CEL expression');
  await expression.fill('customer.shoeSize > 3');
  await expect(page.getByRole('status').filter({ hasText: /undeclared|unknown|shoeSize/i })).toBeVisible();
  await page.getByRole('button', { name: 'Save rule' }).click();
  await expect(page.getByRole('alert').filter({ hasText: /shoeSize|undeclared/i })).toBeVisible();

  await expression.fill('customer.age >= 21');
  await expect(page.getByRole('status').filter({ hasText: 'Valid. It reads: customer.age' })).toBeVisible();
  await page.getByRole('button', { name: 'Save rule' }).click();
  await expect(page).toHaveURL(/\/rules$/);
  await expect(page.getByRole('button', { name: `Adult ${run}` })).toBeVisible();
});

test('a rule group is created from rules, a policy and a trigger, then tested', async ({ page }) => {
  await signIn(page, 'admin', ADMIN_PW);
  await page.goto('/groups/new');
  await page.getByLabel('Code').fill(GROUP);
  await page.getByLabel('Name', { exact: true }).fill(`Screening ${run}`);
  await page.getByRole('radio', { name: /Composite/ }).check();

  const available = page.getByRole('listitem').filter({ hasText: RULE });
  await expect(available).toBeVisible();
  await available.getByRole('button', { name: 'Add' }).click();
  await page.getByRole('listitem').filter({ hasText: 'KYC_VERIFIED' }).getByRole('button', { name: 'Add' }).click();
  await expect(page.getByRole('list', { name: 'Rules in this group' }).getByRole('listitem')).toHaveCount(2);

  await page.getByLabel('Message when every rule is true').first().fill('Welcome');
  await page.getByRole('button', { name: '+ Add a trigger point' }).click();
  await page.getByLabel('Application').fill('loan-portal');
  await page.getByLabel('Form', { exact: true }).fill('NG_FORM');
  await page.getByLabel('Action', { exact: true }).fill('SUBMIT');
  await page.getByRole('button', { name: 'Create rule group' }).click();

  await expect(page).toHaveURL(/\/groups\?open=/);
  await expect(page.getByText(`Saved “Screening ${run}”.`)).toBeVisible();
  await expect(page.getByText('loan-portal', { exact: false }).first()).toBeVisible();

  await page.getByRole('link', { name: 'Test', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Test bench' })).toBeVisible();
  await page.getByRole('button', { name: 'Fill sample values' }).click();
  await page.getByRole('button', { name: 'Evaluate' }).click();
  await expect(page.getByRole('heading', { name: '3. Decision' })).toBeVisible();
  await expect(page.locator('.decision .badge')).toHaveText(/ALLOW|WARN|BLOCK/);
  await expect(page.getByRole('cell', { name: new RegExp(RULE) })).toBeVisible();
});

test('the AI assistant answers from the signed-in user’s own rules, streams, and keeps the conversation', async ({ page }) => {
  await signIn(page, 'admin', ADMIN_PW);
  await openAssistant(page);

  const first = await ask(page, 'list the active rules');
  await expect(first).toContainText('ADULT');
  await expect(first).toContainText('KYC_VERIFIED');
  await expect(first.locator('code').first()).toBeVisible(); // markdown became elements, not text

  const second = await ask(page, 'explain rule ADULT');
  await expect(second).toContainText('customer.age >= 18');
  await expect(second).toContainText('Used in groups');

  const third = await ask(page, 'check `customer.age >=`');
  await expect(third).toContainText('not valid');
  const fourth = await ask(page, 'check `customer.age >= 18 && loan.amount < 5.0`');
  await expect(fourth).toContainText('valid');
  await expect(fourth).toContainText('loan.amount');

  const own = await ask(page, 'list the rules about ' + RULE);
  await expect(own).toContainText(RULE);

  await page.getByRole('button', { name: 'New chat' }).click();
  await expect(page.locator('.bubble')).toHaveCount(0);
  await page.getByRole('button', { name: 'Close the assistant' }).click();
  await expect(page.getByRole('complementary', { name: 'AI assistant' })).toHaveCount(0);
});

test('a user of another tenant gets their own assistant and sees nothing of Acme', async ({ page }) => {
  await signIn(page, 'globex.admin', ADMIN_PW);
  await openAssistant(page);

  const answer = await ask(page, 'list the rules');
  await expect(answer).toContainText('no matching rule');
  await expect(answer).not.toContainText('ADULT');
  await expect(answer).not.toContainText(RULE);
});

test('a plain user can author and chat but never sees administration, and the API refuses them too', async ({ page }) => {
  await signIn(page, 'user', USER_PW);
  await expect(sidebar(page).getByRole('link', { name: /Logs/ })).toHaveCount(0);
  await page.goto('/admin');
  await expect(page).toHaveURL(/\/$/); // the guard sent them home

  await openAssistant(page);
  const answer = await ask(page, 'list the rule groups');
  await expect(answer).toContainText('LOAN_ELIGIBILITY');

  const status = await page.evaluate(async () => {
    const stored = JSON.parse(sessionStorage.getItem('re.session') ?? '{}') as { accessToken?: string };
    const res = await fetch('/api/v1/admin/logs/summary', { headers: { Authorization: `Bearer ${stored.accessToken}` } });
    return res.status;
  });
  expect(status).toBe(403);
});

test('the administrator reads the evaluations, the audit trail and the AI conversations of the tenant', async ({ page }) => {
  await signIn(page, 'admin', ADMIN_PW);
  await sidebar(page).getByRole('link', { name: /Logs/ }).click();
  await expect(page.getByRole('heading', { name: /Logs/ })).toBeVisible();
  await expect(page.getByLabel('Last 24 hours')).toBeVisible();

  await page.getByRole('tab', { name: 'Who changed what' }).click();
  await expect(page.getByRole('cell', { name: 'RULE_CREATED' }).first()).toBeVisible();

  await page.getByRole('tab', { name: 'AI conversations' }).click();
  await expect(page.getByRole('button', { name: 'Read' }).first()).toBeVisible();
  await page.getByRole('button', { name: 'Read' }).first().click();
  await expect(page.getByText(/Transcript:/)).toBeVisible();
  await expect(page.locator('.bubble').first()).toBeVisible();
});

test('signing out ends the session', async ({ page }) => {
  await signIn(page, 'admin', ADMIN_PW);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto('/rules');
  await expect(page).toHaveURL(/\/login$/);
});
