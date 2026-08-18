export { ApiClient } from './transport.js';
export type { ClientOptions, RequestOptions, ApiResponse, CursorPage } from './transport.js';
export { ApiError, isProblem } from './problem.js';
export type { Problem, Violation } from './problem.js';

/**
 * The generated contract.
 *
 * `paths` is every endpoint with its parameters, request body and responses;
 * `components` is every schema. Both come from `docs/api/openapi.json`, which
 * comes from the running application, and CI fails on any drift between the
 * three.
 *
 * Screens should not reach into these shapes by hand. Use the helpers below,
 * which turn a path and a method into the response type, so that a change to
 * the backend surfaces as a type error at the call site rather than as
 * `unknown` three layers away.
 */
export type { paths, components, operations } from './schema.js';

import type { paths } from './schema.js';

/** The 200/201 response body of `GET path`. */
export type GetResponse<P extends keyof paths> = paths[P] extends {
  get: { responses: infer R };
}
  ? SuccessBody<R>
  : never;

/** The success response body of `POST path`. */
export type PostResponse<P extends keyof paths> = paths[P] extends {
  post: { responses: infer R };
}
  ? SuccessBody<R>
  : never;

/** The request body of `POST path`. */
export type PostBody<P extends keyof paths> = paths[P] extends {
  post: { requestBody?: { content: infer C } };
}
  ? OnlyContent<C>
  : never;

/** The request body of `PUT path`. */
export type PutBody<P extends keyof paths> = paths[P] extends {
  put: { requestBody?: { content: infer C } };
}
  ? OnlyContent<C>
  : never;

/**
 * The payload out of a content map, whatever the media type is keyed as.
 *
 * springdoc declares every response under `*` + `/` + `*` rather than
 * `application/json`, because a handler returning `ResponseEntity<Object>`
 * tells it nothing about what it produces. Matching on `application/json`
 * therefore resolved every one of these to `never`, and two screen authors
 * worked around it independently by reaching into `components['schemas']` by
 * hand — which is the generated contract with the endpoint association thrown
 * away.
 *
 * Reading whichever key is there is also the honest fix: some endpoints really
 * do return bytes, and forcing the spec to claim `application/json` everywhere
 * would make the contract lie about those.
 */
type OnlyContent<C> = C[keyof C];

/**
 * Picks the body out of whichever 2xx an operation declares.
 *
 * Written as a chain rather than a mapped type over every status because the
 * interesting cases are exactly three — 200, 201, 204 — and a clever version
 * of this produces error messages nobody can read.
 */
type SuccessBody<R> = R extends { 200: { content: infer C } }
  ? OnlyContent<C>
  : R extends { 201: { content: infer C } }
    ? OnlyContent<C>
    : R extends { 204: unknown }
      ? void
      : never;
