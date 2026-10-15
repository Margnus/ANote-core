# NoteCore development

- Follow docs/AI开发实施规范.md: plan globally, deliver verifiable milestones, and execute implementation/testing/fixes/review through completion within the authorized scope. Define acceptance before coding; close PoCs with an adoption/discard/integration decision. Test counts and unintegrated experiments are not product acceptance. This rule does not authorize spawning sub-agents.

- Start with docs/当前开发状态.md and git status/fetch. Read only the relevant design/plan sections; README.md, docs/开发执行计划.md and docs/进度.md retain historical context. Update the current-state entry after each verified milestone, including evidence scope, remaining gates and the next concrete step.
- The user now requests an initial packaged runnable preview. Build a narrow D4 offline handwriting vertical slice alongside C0/C1 validation. Keep prototypes and preview APIs explicitly unfrozen; packaging is not production architecture approval.
- Preserve jnotesv2cn, jnotesv2 and drawpanellibv2; this is an independent new project.
- Do not introduce Android/UIKit, database, network, vendor SDK or app business dependencies into canvas-core/commonMain.
- Use one canonical scene model and one authority per document. Render/session tokens are not content revisions.
- Make failure and ownership contracts concrete before implementing platform features. Do not silently discard real samples or accepted commands.
- Run meaningful common tests and the dependency check. Passing JVM tests does not validate Android, Apple compilation or device performance.
- Update progress with actual verification and remaining gates; never mark an entire requirement complete from a single prototype test.
- The user authorized verified milestone commits and pushes: NoteCore to Margnus/ANote-core and blueprint to Margnus/AI-Note. Check diffs and verification before each milestone; never force-push or publish apps without authorization.
- Follow blueprint revision e066cfb / ADR-0020: close the retired single-page editor/writer stack, then prioritize C0 physical baselines, shared brush geometry and dense-page index PoCs before freezing architecture or expanding preview features.
- Mac-lock-dependent UI verification was deferred; the user subsequently confirmed unlocked and UI-001 verified selected flows through Device Hub (com.apple.dt.Devices). Preserve report-specific package scopes and remaining physical/multitouch/rotation gates. If the Mac locks again, defer affected checks without repeatedly asking or blocking independent work.
- Keep historical legacy-source evidence pinned to its recorded commits; rehash results are machine-specific and do not prove source/AAR equivalence.
