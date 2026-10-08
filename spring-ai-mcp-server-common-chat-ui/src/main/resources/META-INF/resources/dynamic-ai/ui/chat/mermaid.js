// Lazy Mermaid support for <saimcp-chat>. Mermaid (~3 MB) is loaded only when the first diagram appears, from the URL
// in the component's `mermaid-src` attribute (default: jsDelivr ESM build; point it at your own copy for intranets or a
// strict CSP). Diagrams are rendered with securityLevel "strict" (no scripts, no click handlers, labels sanitised by
// Mermaid). When Mermaid cannot be loaded or a diagram does not parse, the source stays visible.

/** Default ESM build. */
export const DEFAULT_MERMAID_SRC = 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs';

const loading = new Map();
let counter = 0;

/**
 * Loads Mermaid once per URL.
 *
 * @param {string} src module URL
 * @returns {Promise<object>} the mermaid API
 */
export function loadMermaid(src = DEFAULT_MERMAID_SRC) {
  if (globalThis.mermaid?.render) {
    return Promise.resolve(globalThis.mermaid); // the host already loaded it
  }
  if (!loading.has(src)) {
    loading.set(src, import(/* @vite-ignore */ src).then((m) => m.default || m).catch((e) => {
      loading.delete(src); // allow a retry later
      throw e;
    }));
  }
  return loading.get(src);
}

/**
 * Renders every closed, not yet rendered Mermaid block inside a root element.
 *
 * @param {ParentNode} root element containing `.mermaid-block` nodes
 * @param {{src?: string, dark?: boolean}} opts
 */
export async function renderMermaidBlocks(root, opts = {}) {
  const blocks = [...root.querySelectorAll('.mermaid-block[data-closed="true"]:not([data-rendered])')];
  if (!blocks.length) return;
  let mermaid;
  try {
    mermaid = await loadMermaid(opts.src || DEFAULT_MERMAID_SRC);
    mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: opts.dark ? 'dark' : 'default' });
  } catch {
    blocks.forEach((b) => markFailed(b, 'Diagram renderer unavailable; showing the source.'));
    return;
  }
  for (const block of blocks) {
    block.setAttribute('data-rendered', 'pending');
    const source = block.querySelector('code')?.textContent || '';
    try {
      const { svg } = await mermaid.render(`saimcp-mermaid-${++counter}`, source);
      const view = document.createElement('div');
      view.className = 'mermaid-view';
      view.innerHTML = svg; // produced by Mermaid with securityLevel "strict"
      block.prepend(view);
      block.setAttribute('data-rendered', 'true');
    } catch {
      markFailed(block, 'This diagram could not be drawn; showing the source.');
    }
  }
}

function markFailed(block, message) {
  block.setAttribute('data-rendered', 'failed');
  const note = document.createElement('div');
  note.className = 'mermaid-note';
  note.textContent = message;
  block.prepend(note);
}
