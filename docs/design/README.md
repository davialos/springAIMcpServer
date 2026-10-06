# Console design

The rule-engine console (`docker/rule-engine/ui`) is styled **only** through design tokens in `src/styles/tokens.css`
(colours, type scale, spacing, radii, shadows) and the component rules in `src/styles/app.css`. No component hard-codes a colour.

**Status: not a copy of the Figma design.** The requested file
(<https://www.figma.com/design/Bu0ocbPypdNsLD0z7PIABK/Rule-Builder--Copy-?node-id=1-2>) could not be read from the build environment
(no Figma access), so the layout and the look are an original, token-driven design with the same functional scope
(setup, rule groups, test bench, admin logs). OQ-70 tracks this.

## How to bring the real design in
1. Export the frames (PNG/SVG) and, if available, the variables/tokens as JSON, or provide a Figma personal access token to the session.
2. Map the Figma variables to the CSS custom properties in `tokens.css` (same names where possible).
3. Compare page by page with the Playwright screenshots (`E2E_SCREENSHOTS=dir npx playwright test`) and adjust `app.css`/components.
4. Keep the CSP intact: no inline styles or scripts, fonts and images from the same origin.
