# NoteCore development

- Read README.md, docs/开发执行计划.md and docs/进度.md before continuing.
- The current phase is C0/C1 architectural validation. Common-code prototypes are not frozen public APIs or production implementations.
- Preserve jnotesv2cn, jnotesv2 and drawpanellibv2; this is an independent new project.
- Do not introduce Android/UIKit, database, network, vendor SDK or app business dependencies into canvas-core/commonMain.
- Use one canonical scene model and one authority per document. Render/session tokens are not content revisions.
- Make failure and ownership contracts concrete before implementing platform features. Do not silently discard real samples or accepted commands.
- Run meaningful common tests and the dependency check. Passing JVM tests does not validate Android, Apple compilation or device performance.
- Update progress with actual verification and remaining gates; never mark an entire requirement complete from a single prototype test.
- The user authorized verified milestone commits and pushes: NoteCore to Margnus/ANote-core and blueprint to Margnus/AI-Note. Check diffs and verification before each milestone; never force-push or publish apps without authorization.
