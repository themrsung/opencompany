# Writing a client module

Two supported ways to extend CoreIntra. Read this far enough to pick the right
one, because the choice is hard to reverse later.

## 1. External service — recommended

Talk to the REST or MCP API with a scoped service account. Fully isolated:
however badly written, it cannot crash the intranet or corrupt core data.

```
POST /api/v1/... with Authorization: Bearer ci_<prefix>_<secret>
```

Key scopes **narrow** the account's permissions and never widen them. A key
scoped to reads cannot write even if its account may — which is what makes
handing an MCP client a read-only key meaningful.

Choose this unless you have a specific reason not to.

## 2. In-process plugin — at your own risk

A jar implementing `IntranetModule`, dropped into `modules/` and discovered by
`ServiceLoader`.

**What you are accepting:** it runs *inside the application process* with the
permissions its manifest declares. It is not sandboxed. A fault in it can affect
the whole intranet. Installing one is master-only, audited, and voids support
guarantees for that installation. The UI says all of this before accepting the
install, and so does `ModuleManifest.installationWarning`.

### What the platform enforces

- **Your own schema namespace.** Core tables are not readable or writable
  directly — `ModuleContext` exposes no `DataSource` onto them. You get
  `moduleDataSource()`, scoped to `mod_<your_id>`.
- **The same permission evaluator as everything else.** A module cannot grant
  itself anything, and its service account's grants appear in the
  effective-permissions explainer like anyone else's.
- **Java 8.** The SPI is compiled for it, like the rest of the backend. A module
  built for a newer release will not load on a client site running an old JRE,
  which is the entire reason the platform targets 8.

### A worked example

`examples/module-hello-assets` is a complete, compiling module: manifest, own
schema, permission-checked reads, an event subscription, clean shutdown. It is
built against the real SPI in CI, so it cannot drift into being pseudocode.

```bash
cd examples/module-hello-assets && mvn package
cp target/module-hello-assets-1.0.0.jar /path/to/coreintra/modules/
```

Then install it from the master settings screen, where the permission list and
the risk warning are shown before it is enabled.

### Rules worth internalising

- **Declare the narrowest permission set that works.** The list is shown to the
  installing master as one screen; a long one invites a refusal.
- **Never throw from an event listener for ordinary failure.** An exception
  there is logged, and repeated failures disable your module — but it never
  rolls back the platform operation that raised the event.
- **Throwing from `start()` disables your module**, records why, and leaves the
  rest of the application running. A client module failing to start must never
  stop everyone else working.
- **`stop()` may be followed by another `start()`** in the same process: a
  module can be disabled and re-enabled without being uninstalled.
