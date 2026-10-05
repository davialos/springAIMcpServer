import { expect, test, type Page } from '@playwright/test';

/**
 * The whole ecosystem through a browser: sign in over protobuf, read the setup, author a rule and a rule group, evaluate it,
 * and as an administrator find it in the logs. Needs the seeded dev users and the sample loan rules (scripts/rule-engine).
 * Tests run in order and share one rule/group created with a unique suffix, so repeated runs do not collide.
 */
test.describe.configure({ mode: 'serial' });

const run = Date.now().toString(36);
const RULE = `e2e-age-${run}`;
const GROUP = `e2e-group-${run}`;
const GROUP_NAME = `E2E loan screening ${run}`;
const SHOTS = process.env.E2E_SCREENSHOTS;

async function shot(page: Page, name: string) {
  if (SHOTS) {
    await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true });
  }
}

/** The sidebar and the log tabs: several pages also link to the same places from their content. */
const sidebar = (page: Page) => page.getByRole('navigation', { name: 'Main' });
const logTabs = (page: Page) => page.getByRole('navigation', { name: 'Log sections' });

/** Submits the login form and does not wait: for attempts that are expected to fail. */
async function attemptSignIn(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

/** Signs in and waits until the console is showing, so a following navigation cannot race the login. */
async function signIn(page: Page, username: string, password: string) {
  await attemptSignIn(page, username, password);
  await expect(sidebar(page)).toBeVisible();
}

test('the login page lists the local users and rejects a wrong password without saying which part was wrong', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: 'Rule engine console' })).toBeVisible();
  await shot(page, '01-login');

  await attemptSignIn(page, 'admin', 'definitely-wrong');
  await expect(page.getByRole('alert')).toHaveText('Wrong username or password.');
  await attemptSignIn(page, `nobody-${run}`, 'x'); // a name no one has: unique per run, because repeated failures lock a name out
  await expect(page.getByRole('alert')).toHaveText('Wrong username or password.');
});

test('an administrator signs in over protobuf and lands in their tenant and organization', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Username').fill('admin');
  await page.getByLabel('Password').fill('admin123');
  const requested = page.waitForRequest('**/auth/login');
  const answered = page.waitForResponse('**/auth/login');
  await page.getByRole('button', { name: 'Sign in' }).click();

  const request = await requested;
  const response = await answered;
  expect(request.headers()['content-type']).toContain('application/x-protobuf');
  expect(request.postDataBuffer()?.length).toBeGreaterThan(0);
  expect(request.postData() ?? '').not.toContain('{'); // binary, not JSON
  expect(response.headers()['content-type']).toContain('application/x-protobuf');
  expect(response.headers()['cache-control']).toContain('no-store');

  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible();
  await expect(page.getByText('Ada Admin')).toBeVisible();
  await expect(page.getByText('Acme Bank · Retail')).toBeVisible();
  await expect(page.getByText('Administrator')).toBeVisible();
  await expect(sidebar(page).getByRole('link', { name: 'Logs & dashboard' })).toBeVisible();
  // the seeded loan tenant: 4 groups, 5 rules
  await expect(page.getByLabel('Setup summary')).toContainText('Rule groups');
  await shot(page, '02-overview');
});

test('the setup pages show the library, the rules and the groups of the tenant', async ({ page }) => {
  await signIn(page, 'admin', 'admin123');
  await sidebar(page).getByRole('link', { name: 'Parameter library' }).click();
  await expect(page.getByText('customer.creditScore')).toBeVisible();
  await expect(page.getByRole('row', { name: /customer\.age/ })).toContainText('INT');
  await shot(page, '03-library');

  await sidebar(page).getByRole('link', { name: 'Rules', exact: true }).click();
  await expect(page.getByRole('row', { name: /Customer is an adult/ })).toContainText('customer.age >= 18');
  await page.getByRole('button', { name: 'Customer is an adult' }).click();
  await expect(page.getByText('Customer must be at least 18 years old.')).toBeVisible();
  await shot(page, '04-rules');

  await sidebar(page).getByRole('link', { name: 'Rule groups' }).click();
  await expect(page.getByRole('row', { name: /Loan eligibility \(composite\)/ })).toBeVisible();
  await sidebar(page).getByRole('link', { name: 'Triggers & channels' }).click();
  await expect(page.getByRole('row', { name: /loan-portal/ }).first()).toBeVisible();
  await page.getByRole('tab', { name: 'API endpoints' }).click();
  await expect(page.getByText('loan-decision-webhook-prod')).toBeVisible();
});

test('a rule is checked against the parameter library when it is saved', async ({ page }) => {
  await signIn(page, 'admin', 'admin123');
  await page.goto('/rules/new');
  await page.getByLabel('Code').fill(RULE);
  await page.getByLabel('Name', { exact: true }).fill('E2E minimum age 21');
  await page.getByLabel('CEL expression').fill('customer.agee >= 21');
  await page.getByRole('button', { name: 'Save rule' }).click();
  await expect(page.getByText(/customer\.agee/)).toBeVisible(); // the compiler's message, on the expression field

  await page.getByLabel('CEL expression').fill('');
  await page.getByRole('button', { name: 'customer.age', exact: true }).click();
  await page.getByLabel('CEL expression').press('End');
  await page.getByLabel('CEL expression').pressSequentially(' >= 21');
  await page.getByLabel('Message when false: text (en)').fill('You must be at least 21.');
  await shot(page, '05-rule-form');
  await page.getByRole('button', { name: 'Save rule' }).click();

  await expect(page).toHaveURL(/\/rules$/);
  await expect(page.getByRole('row', { name: new RegExp(RULE) })).toContainText('customer.age >= 21');
});

test('a rule group is created from rules, a policy and a trigger, then tested', async ({ page }) => {
  await signIn(page, 'admin', 'admin123');
  await page.goto('/groups/new');
  await page.getByLabel('Code').fill(GROUP);
  await page.getByLabel('Name', { exact: true }).fill(GROUP_NAME);
  await page.getByLabel(/Composite/).check();
  const available = page.getByText(/Available in/).locator('..');
  await available.locator('li', { hasText: 'Customer is an adult' }).getByRole('button', { name: 'Add' }).click();
  await available.locator('li', { hasText: RULE }).getByRole('button', { name: 'Add' }).click();
  await page.getByLabel('Message when every rule is true: text (en)').fill('Screening passed.');
  await page.getByLabel('Message when any rule is false: text (en)').fill('Screening failed.');
  await page.getByRole('button', { name: '+ Add a trigger point' }).click();
  await page.getByLabel('Application', { exact: true }).fill('e2e-portal');
  await page.getByLabel('Form', { exact: true }).fill('SCREENING');
  await page.getByLabel('Action', { exact: true }).fill('SUBMIT');
  await shot(page, '06-group-form');
  await page.getByRole('button', { name: 'Create rule group' }).click();

  await expect(page.getByText(`Saved “${GROUP_NAME}”.`)).toBeVisible();
  const row = page.getByRole('row', { name: new RegExp(GROUP) });
  await expect(row).toContainText('composite');
  await expect(row).toContainText('ACTIVE');
  await expect(page.getByText('e2e-portal')).toBeVisible();
  await shot(page, '07-group-saved');

  // test it: a 19-year-old fails the 21+ rule, a 30-year-old passes both
  await page.getByRole('link', { name: 'Test', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Test bench' })).toBeVisible();
  await page.getByLabel('customer.age').fill('19');
  await page.getByRole('button', { name: 'Evaluate' }).click();
  await expect(page.getByText('Screening failed.').first()).toBeVisible();
  await expect(page.getByRole('row', { name: new RegExp(RULE) })).toContainText('FALSE');
  await shot(page, '08-test-bench-block');
  await page.getByLabel('customer.age').fill('30');
  await page.getByRole('button', { name: 'Evaluate' }).click();
  await expect(page.getByText('Screening passed.').first()).toBeVisible();
  await page.getByLabel('customer.age').fill('');
  await page.getByRole('button', { name: 'Evaluate' }).click();
  await expect(page.getByText('MISSING_PARAMETER').first()).toBeVisible(); // a missing value fails closed
});

test('the administrator finds the evaluations, the changes and the chat in the logs', async ({ page }) => {
  await signIn(page, 'admin', 'admin123');
  await sidebar(page).getByRole('link', { name: 'Logs & dashboard' }).click();
  await expect(page.getByRole('heading', { name: 'Logs & dashboard' })).toBeVisible();
  await expect(page.getByLabel('Key numbers')).toContainText('Evaluations');
  await expect(page.getByRole('img', { name: /Evaluations per/ })).toBeVisible();
  await expect(page.getByText('RULE_GROUP_CREATED')).toBeVisible();
  await shot(page, '09-dashboard');

  await logTabs(page).getByRole('link', { name: 'Evaluations' }).click();
  await page.getByLabel('Group code').fill(GROUP);
  const first = page.getByRole('row', { name: new RegExp(GROUP) }).first();
  await expect(first).toBeVisible();
  await first.getByRole('button').click();
  await expect(page.getByRole('row', { name: new RegExp(RULE) })).toBeVisible();
  await expect(page.getByText('Input values are not stored')).toBeVisible();
  await shot(page, '10-evaluations');
  await page.getByLabel('Group code').fill('');
  await page.getByLabel('Only evaluations with errors').check();
  await expect(page.getByRole('row', { name: new RegExp(GROUP) }).first()).toBeVisible(); // the missing-value run

  await logTabs(page).getByRole('link', { name: 'Audit trail' }).click();
  await page.getByLabel('What happened').selectOption('RULE_GROUP_CREATED');
  // one specific row (kind + group), so the check does not depend on when the filtered list has reloaded
  await expect(page.getByRole('row', { name: new RegExp(`RULE_GROUP_CREATED.*${GROUP}`) })).toContainText('Ada Admin');
  await shot(page, '11-audit');

  await logTabs(page).getByRole('link', { name: 'Chat' }).click();
  await page.getByRole('button', { name: /Why was my loan blocked/ }).click();
  await expect(page.getByLabel('Conversation transcript')).toContainText('Your credit score was below the required 650');
  await shot(page, '12-chat');
});

test('a plain user can author but never sees the logs, and the API refuses them too', async ({ page }) => {
  await signIn(page, 'user', 'user123');
  await expect(page.getByText('Uma User')).toBeVisible();
  await expect(page.getByRole('link', { name: /Logs/ })).toHaveCount(0); // neither sidebar nor overview offers it
  await expect(page.getByRole('link', { name: 'New rule group' }).first()).toBeVisible();

  await page.goto('/logs');
  await expect(page.getByText('Administrators only')).toBeVisible();
  const status = await page.evaluate(async () => {
    const stored = JSON.parse(window.sessionStorage.getItem('re.session') ?? '{}') as { accessToken?: string };
    const r = await fetch('/api/v1/admin/logs/summary', { headers: { Authorization: `Bearer ${stored.accessToken}` } });
    return r.status;
  });
  expect(status).toBe(403);
  await shot(page, '13-user-no-logs');
});

test('an organization user sees the tenant-wide rules, and the first organization sees their own', async ({ page }) => {
  await signIn(page, 'corp.user', 'user123');
  await expect(page.getByText('Acme Bank · Corporate')).toBeVisible();
  await sidebar(page).getByRole('link', { name: 'Rules', exact: true }).click();
  await expect(page.getByRole('row', { name: /Customer is an adult/ })).toBeVisible(); // tenant-wide
  await expect(page.getByRole('row', { name: new RegExp(RULE) })).toHaveCount(0); // Retail's own rule
});

test('another tenant sees nothing of Acme', async ({ page }) => {
  await signIn(page, 'globex.admin', 'admin123');
  await expect(page.getByText('Globex Corporation · all organizations')).toBeVisible();
  await sidebar(page).getByRole('link', { name: 'Rule groups' }).click();
  await expect(page.getByText('No rule group yet.')).toBeVisible();
  await sidebar(page).getByRole('link', { name: 'Logs & dashboard' }).click();
  await logTabs(page).getByRole('link', { name: 'Evaluations' }).click();
  await expect(page.getByText('No evaluation matches.')).toBeVisible();
  await shot(page, '14-other-tenant');
});

test('signing out ends the session', async ({ page }) => {
  await signIn(page, 'user', 'user123');
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect(await page.evaluate(() => window.sessionStorage.getItem('re.session'))).toBeNull();
  await page.goto('/groups');
  await expect(page).toHaveURL(/\/login$/);
});
