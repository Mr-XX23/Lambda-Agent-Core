---
name: release-notes
description: Turns a list of merged changes into user-facing release notes. Use when asked for release notes, a changelog, or a summary of what changed in a version.
---

# Release notes

1. Read `template.md` in this skill with `read_skill_file` and follow its layout exactly.
2. Sort each change into one section of the template. Leave out sections with no entries.
3. Write for users of the library, not its developers: describe the effect of each
   change, not how it was implemented. Keep each entry to one line.
4. Put breaking changes first, each with the action users must take.
5. If the user did not give a version number, use `Unreleased`.
