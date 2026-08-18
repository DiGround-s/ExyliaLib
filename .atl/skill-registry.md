# Skill Registry — ExyliaLib

<!-- Auto-generated SDD initialization registry. Read the referenced SKILL.md before applying a matching skill. -->

Last updated: 2026-08-17

## Sources scanned

- `/root/.config/agents/skills` (available)
- `/root/.config/opencode/skills` (available)
- `/root/.claude/skills` (available)
- `/root/.cursor/skills` (available)
- `/root/.copilot/skills` (available)
- `/root/.codex/skills` (available)
- Project skill locations listed by SDD conventions (none found)

## Contract

This registry is an index, not a summary. `SKILL.md` is the source of truth. Load only the skills whose trigger matches the task, using the exact paths below. SDD and shared skills are intentionally excluded because the SDD workflow resolves them by phase.

## Skills

| Skill | Trigger / description | Scope | Path |
| --- | --- | --- | --- |
| `branch-pr` | Create Gentle AI pull requests with issue-first checks. | user | `/root/.config/agents/skills/branch-pr/SKILL.md` |
| `chained-pr` | PRs over 400 lines, stacked PRs, or review slices. | user | `/root/.config/agents/skills/chained-pr/SKILL.md` |
| `codebase-memory` | Structural code queries, call paths, dependencies, and impact analysis. | user | `/root/.claude/skills/codebase-memory/SKILL.md` |
| `cognitive-doc-design` | Guides, READMEs, RFCs, onboarding, architecture, and review-facing docs. | user | `/root/.config/agents/skills/cognitive-doc-design/SKILL.md` |
| `comment-writer` | PR feedback, issue replies, reviews, Slack, or GitHub comments. | user | `/root/.config/agents/skills/comment-writer/SKILL.md` |
| `exylia-style-java` | Java/Minecraft messages, menus, items, GUI flows, or player-facing text. | user | `/root/.claude/skills/exylia-style-java/SKILL.md` |
| `exylia-style-web` | Exylia Node/web interfaces and UX. | user | `/root/.claude/skills/exylia-style-web/SKILL.md` |
| `gentle-ai-bench` | Bench journeys, driven mode, and journey corpus changes. | user | `/root/.config/agents/skills/gentle-ai-bench/SKILL.md` |
| `go-testing` | Go tests, coverage, Bubbletea teatest, or golden files. | user | `/root/.config/agents/skills/go-testing/SKILL.md` |
| `issue-creation` | GitHub issue creation, bug reports, feature requests, or issue approval. | user | `/root/.config/agents/skills/issue-creation/SKILL.md` |
| `judgment-day` | Explicit blind dual or adversarial review. | user | `/root/.config/agents/skills/judgment-day/SKILL.md` |
| `rdd-defect-workflow` | Receipt-driven development, review authority, correction, or delivery gates. | user | `/root/.config/agents/skills/rdd-defect-workflow/SKILL.md` |
| `skill-creator` | New skills, agent instructions, or AI usage patterns. | user | `/root/.config/agents/skills/skill-creator/SKILL.md` |
| `skill-improver` | Skill audits, refactors, or quality improvements. | user | `/root/.config/agents/skills/skill-improver/SKILL.md` |
| `systemic-issue-triage` | Issue triage, backlog, root cause, dead ends, or blocked users. | user | `/root/.config/agents/skills/systemic-issue-triage/SKILL.md` |
| `work-unit-commits` | Implementation work units, commit splitting, or chained PR preparation. | user | `/root/.config/agents/skills/work-unit-commits/SKILL.md` |

## Project conventions

| Source | Scope | Notes |
| --- | --- | --- |
| `/root/Java/Exylia/ExyliaLib/AGENTS.md` | project | Architecture, module contracts, stack, lifecycle, documentation, and verification policy. |
| `/root/Java/CLAUDE.md` | workspace | Exylia Java/Minecraft UX and collaboration conventions inherited by this project. |

## Loading protocol

1. Match task context and target files against the trigger column.
2. Load the exact matching `SKILL.md` paths before task work.
3. Read project conventions before changing product code, APIs, configuration, or documentation.
4. If no skill matches, proceed without skill injection and report `skill_resolution: none`.
