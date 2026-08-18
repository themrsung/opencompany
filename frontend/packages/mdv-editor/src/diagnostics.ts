/**
 * Live diagnostics for the mdv editor.
 *
 * The implementation is `@mdv/lsp` running in a web worker — it declares that
 * its transport is host-supplied and that it imports no `node:*`, so it runs in
 * the browser with no server round trip (ADR 0008).
 *
 * This module is the interface rather than the implementation, for two reasons:
 * the editor should not know whether diagnostics come from a worker or a server,
 * and a deployment where workers are unavailable needs a fallback that changes
 * nothing else.
 */

import type { Diagnostic } from '@codemirror/lint';
import type { EditorView } from '@codemirror/view';

/** Severity as mdv reports it. */
export type MdvSeverity = 'error' | 'warning' | 'info' | 'hint';

/** One diagnostic, in mdv's own terms rather than CodeMirror's. */
export interface MdvDiagnostic {
  /** Appendix C code, e.g. `MDV5100` for missing glyphs. */
  readonly code: string;
  readonly severity: MdvSeverity;
  readonly message: string;
  /** Zero-based character offsets into the source. */
  readonly from: number;
  readonly to: number;
  /** An offered fix, where mdv provides one. */
  readonly fix?: { readonly title: string; readonly from: number; readonly to: number; readonly insert: string };
}

/**
 * Where diagnostics come from.
 *
 * Async because the worker path is, and because a server fallback would be.
 */
export interface DiagnosticsSource {
  readonly name: string;
  diagnose(source: string, signal: AbortSignal): Promise<readonly MdvDiagnostic[]>;
  dispose?(): void;
}

/**
 * Whether a document may be submitted to 결재.
 *
 * Errors block; warnings do not. A document that fails to render is not a
 * document anyone should be asked to approve — and conversely, blocking on
 * warnings would train drafters to route around the editor entirely.
 */
export function submissionBlockers(
  diagnostics: readonly MdvDiagnostic[],
): readonly MdvDiagnostic[] {
  return diagnostics.filter((d) => d.severity === 'error');
}

export function canSubmit(diagnostics: readonly MdvDiagnostic[]): boolean {
  return submissionBlockers(diagnostics).length === 0;
}

/**
 * A user-facing explanation of why submission is blocked.
 *
 * Names every blocking problem at once. Reporting them one at a time turns a
 * document with three mistakes into three round trips.
 */
export function describeBlockers(diagnostics: readonly MdvDiagnostic[], locale: string): string {
  const blockers = submissionBlockers(diagnostics);
  if (blockers.length === 0) {
    return '';
  }
  const lines = blockers.map((d) => `  · [${d.code}] ${d.message}`);
  return locale === 'ko'
    ? `이 문서는 오류가 있어 상신할 수 없습니다. 아래 항목을 수정해 주십시오.\n${lines.join('\n')}`
    : `This document cannot be submitted for approval until these are fixed:\n${lines.join('\n')}`;
}

/** Maps mdv severities onto CodeMirror's, which has no `hint`. */
function toCodeMirrorSeverity(severity: MdvSeverity): Diagnostic['severity'] {
  switch (severity) {
    case 'error':
      return 'error';
    case 'warning':
      return 'warning';
    default:
      return 'info';
  }
}

/** Adapts mdv diagnostics into CodeMirror's lint gutter. */
export function toCodeMirrorDiagnostics(
  view: EditorView,
  diagnostics: readonly MdvDiagnostic[],
): Diagnostic[] {
  const length = view.state.doc.length;
  return diagnostics.map((d) => {
    // Clamp: the document can change between the request and the response, and
    // an out-of-range position throws inside CodeMirror rather than degrading.
    const from = Math.max(0, Math.min(d.from, length));
    const to = Math.max(from, Math.min(d.to, length));
    const diagnostic: Diagnostic = {
      from,
      to,
      severity: toCodeMirrorSeverity(d.severity),
      message: `[${d.code}] ${d.message}`,
      source: 'mdv',
    };
    if (d.fix) {
      const fix = d.fix;
      return {
        ...diagnostic,
        actions: [
          {
            name: fix.title,
            apply(target: EditorView) {
              const max = target.state.doc.length;
              target.dispatch({
                changes: {
                  from: Math.max(0, Math.min(fix.from, max)),
                  to: Math.max(0, Math.min(fix.to, max)),
                  insert: fix.insert,
                },
              });
            },
          },
        ],
      };
    }
    return diagnostic;
  });
}

/**
 * A source backed by `@mdv/lsp` in a web worker.
 *
 * The worker is created by the caller so this package does not need a bundler
 * plugin, and so a host that cannot spawn workers can supply a different
 * `DiagnosticsSource` without this file changing.
 */
export function workerDiagnosticsSource(worker: Worker): DiagnosticsSource {
  let nextId = 1;
  const pending = new Map<number, (value: readonly MdvDiagnostic[]) => void>();

  worker.addEventListener('message', (event: MessageEvent) => {
    const data = event.data as { id?: number; diagnostics?: readonly MdvDiagnostic[] };
    if (typeof data.id !== 'number') return;
    const resolve = pending.get(data.id);
    if (resolve) {
      pending.delete(data.id);
      resolve(data.diagnostics ?? []);
    }
  });

  return {
    name: 'mdv-lsp-worker',
    diagnose(source, signal) {
      return new Promise((resolve) => {
        const id = nextId++;
        pending.set(id, resolve);
        // An aborted request resolves empty rather than rejecting: a superseded
        // keystroke is not an error, and rejecting would surface as one.
        signal.addEventListener('abort', () => {
          if (pending.delete(id)) resolve([]);
        });
        worker.postMessage({ id, source });
      });
    },
    dispose() {
      worker.terminate();
      pending.clear();
    },
  };
}
