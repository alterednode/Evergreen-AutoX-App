# Server-enforced result review

For ordinary signed-in callers, direct run and acceleration creation requires
source=trackaddict. This is a self-reported import label, not verification and
not proof that the measurements passed through the parser. Other sources and
manual/photo results must use the existing evidence-submission queue.

Ordinary PATCH requests cannot change provenance. Owners must submit new evidence
to change a photo result. Admin creation and corrections remain available.
Approval requires the evidence file, explicitly assigns source=photo, and checks
pending status inside an immediate SQLite transaction to prevent double approval.

Existing photo labels are unchanged. There is no retrospective migration or schema
change. Historical labels can still reflect the former client-controlled behavior.

Regression tests cover both ordinary runs and acceleration: source assertion,
source edits, protected reviewed entries, missing evidence, and repeated approval.
Existing posting fixtures explicitly use the supported self-reported import path.
Run the complete suite with `python -m pytest -q`.

This branch contains finding 2 only; it has no iOS credential, parser, storage,
admin-session, dependency, or container changes.
