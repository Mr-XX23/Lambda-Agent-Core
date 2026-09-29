---
name: commit-message
description: Writes a git commit message from a description of a code change. Use when the user asks for a commit message or describes changes they are about to commit.
---

# Commit messages

Write the message in the Conventional Commits style.

1. First line: `<type>: <summary>`, at most 72 characters, imperative mood
   ("add", not "added"), no full stop.
   - Types: `feat` (new behavior), `fix` (bug fix), `docs`, `test`, `refactor`
     (no behavior change), `perf`, `build`, `chore`.
2. Leave one blank line.
3. Body: explain *why* the change was made and anything a reviewer should know.
   Wrap lines at 72 characters. Skip the body for trivial changes.
4. If the change breaks existing callers, end with a `BREAKING CHANGE:` line
   that says what callers must do.

Reply with only the commit message in a code block, nothing before or after it.
