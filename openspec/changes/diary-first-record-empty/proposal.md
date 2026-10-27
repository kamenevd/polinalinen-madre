# Diary: first-record empty Journal

Approved scope: SCREENS-7.0.3 §3 (105–106), §19 (198). Version 7.1.19 (72).

Reuse PaperEmpty and PaperButton, with the actual starter name and the existing onFeed callback. Show only on Journal when authoritative history is empty. Pending deletion hides a row but is not an empty history. New empty content uses existing readable ink2 locally; no kit/token change.

Acceptance: real NavHost → F4 form → one real Room save with exact imported JPEG → same Diary entry; pending-only row never shows first-record empty, Undo restores the same id/JPEG, final deletion shows empty. Advice and existing rows remain unchanged. Verify 320dp/fontScale2 long-name text layout, button semantics/focus/keyboard and AA contrast across Paper atmospheres. Preserve existing F4/D3/D2/Book assertions.

Focused debug/release compilation and freshly executed relevant unit suites only. No full-suite, review, push, rendering, APK, device, production, backend, schema, VM, navigation or kit effects. Parent owns final full gate and review.
