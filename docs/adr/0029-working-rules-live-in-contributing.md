# 0029. Working rules live in CONTRIBUTING.md, which CLAUDE.md imports

- Status: Accepted, supersedes the CLAUDE.md home of commands and conventions in
  [0001](0001-record-architecture-decisions.md)
- Date: 2026-10-04

## Context

[0001](0001-record-architecture-decisions.md) gives each kind of text one home, and puts the build and test commands,
the code conventions and the map of which document describes what in CLAUDE.md, beside the architecture summary,
the invariants and the framework traps. These are working rules for anyone who changes the repository, and a reader
looks for them in a root CONTRIBUTING.md, which GitHub links for contributors.

Only two sets of files that change together were written down: the topology diagram in README.md and ARCHITECTURE.md,
and a business rule with `docs/business-rules.md`. The repository's history shows that the documentation defect
that recurs most is a second copy of a fact left behind. The documentation audit in `ee31cf1` corrected about twenty
stale claims across 22 files. Of the 34 commits that added or removed a test after the test count first appeared in
`docs/development.md`, four left it stale. Four outbox variables sat in `.env.example` while `application.yml` never
read them (`67fabe2`). The contract evolution rule also had three wordings, and two of them said a field is never
removed while describing how to drop one once every consumer has moved.

## Decision

- `CONTRIBUTING.md` in the repository root holds the working rules: the build and test commands, the table of where
  each kind of text lives, the change map, the code and test conventions, a summary of the ADR format, the commit
  rules and the definition of done.
- The change map lists, for each kind of change, everything that changes with it in the same commit.
- CLAUDE.md imports the file with `@CONTRIBUTING.md` and keeps the architecture summary, the invariants, the
  framework traps and the local working files.
- [0001](0001-record-architecture-decisions.md) stays the source for the ADR format and the rule on code comments.
  CONTRIBUTING.md summarizes them and links to it.

## Alternatives considered

- Keeping the rules in CLAUDE.md: contributors do not look there, and GitHub does not surface it.
- A copy in both files: the two drift, as the contract rule already had.
- `docs/contributing.md`: GitHub finds it there too, but the root is where a reader looks first.
- Tests instead of a written list: some coupled facts can be pinned by a test, such as the configuration keys in
  `application.yml` against `.env.example`, but a sentence in `ARCHITECTURE.md` that restates a behaviour cannot.

## Consequences

- An agent that reads CLAUDE.md gets the change map with it, so a change and its companions are planned together.
- The change map is maintained like code. A new kind of fact kept in two places adds a row.
- Coupled facts that a test can pin, such as the two topology diagrams or the ADR index against the ADR files, can
  move from the change map into tests later.
