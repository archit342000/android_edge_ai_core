# CLAUDE.md

Read `AGENTS.md` first. It is the source of truth for all agents; this file only adds Claude Code specifics.

- **Edits:** prefer the `Edit` tool over `Write` for existing files, and target the smallest string that achieves the change.
- **Sub-agents:** for deep codebase exploration or multi-step research, delegate to the `Agent` tool to preserve the main context.
- **Plans:** write plan files to `plans/` (gitignored) with descriptive, dated names such as `refactor-token-storage-2026-11-09.md`.
  Cover the approach, architecture decisions, and file changes, not just a todo list.
