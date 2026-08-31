---
name: project-map
description: Route internal-admin-template tasks to the minimum authoritative documents, modules, consumers, and synchronization points before implementation or review. Use for planning, changing, delegating, or reviewing repository work; do not use as a substitute for reading task-specific requirements or code.
---

# Project Map

Use this skill to reduce missed documents and stale indexes without loading the whole repository.

## Start

1. Read `docs/PROJECT_MAP.md` completely; it is intentionally short.
2. Match the request to one or more rows in “按任务进入”.
3. Read only the listed authoritative entry, the confirmed requirement, the target module capability, and directly related code/tests required by the task risk.
4. Use `rg` to verify current callers, consumers, migrations, generated contracts, and tests. The map is navigation, not proof that code still matches it.

State the selected route and minimum reading set. Do not turn optional or unrelated entries into mandatory reading.

## Decide authority

- Current user instruction and confirmed requirements outrank the map.
- Architecture and engineering policies constrain implementation.
- Status indexes describe current delivery state but do not replace requirements.
- Drafts and historical evidence cannot authorize production behavior.
- If the map conflicts with an authoritative file or code, report the conflict and use the authoritative source; update the map only when the current task authorizes documentation maintenance.

## Finish

After inspecting the actual diff, use “常见同步点” to check only affected contracts. Verify rather than assume:

- public API consumers;
- Liquibase formal and test masters;
- OpenAPI/generated frontend types;
- module capability ownership;
- the single delivery status index;
- README only when its current-stage statement became false.

Do not expand scope merely to update adjacent documentation. Report a missing sync point separately unless it is a necessary part of the requested change.

## Boundaries

- Do not copy requirements, source details, secrets, or historical reports into the map.
- Do not inventory every class, method, route, table field, or test.
- Do not read `.env.local` or expose configuration values unless the task explicitly authorizes and requires it.
- Do not treat the map as approval, a new review gate, or a reason to create extra roles and reports.
- Keep the map concise; add only stable entry points or repeated synchronization relationships.

For a mechanical health check, run `python3 .agents/skills/project-map/scripts/check_map.py`.
