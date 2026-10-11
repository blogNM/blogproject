# D-82 문서를 GitHub Spec Kit 형식으로 정리

- 날짜: 2026-10-07
- 관련 요구사항: 전체 (USR, BLG, SOC, BRD, ADM, SEC, SCL)
- 선택지:
  1. PDF 두 편만 기준으로 두고 계속 PDF를 고친다
  2. GitHub Spec Kit 형식으로 옮겨 마크다운을 작업 기준으로 삼고, PDF는 원본으로 보관한다
- 결정과 이유: 2번. 요구사항이 81건의 결정을 거치며 자주 바뀌었고, PDF는 AI 에이전트와 사람이 고치거나 차이를 비교하기 어렵다. Spec Kit은 헌법(constitution) → 기능 명세(spec) → 구현 계획(plan) → 작업 목록(tasks) 순서를 정해 두어, 요구사항이 정리된 뒤 DB 설계를 하기로 한 결정(D-47)과도 맞는다.
  - 전체 원칙과 기술 스택: `.specify/memory/constitution.md`
  - 기능 명세: `specs/001-member-account` ~ `specs/005-admin-moderation`
  - 요구사항 ID(USR-01 등)와 D-번호는 그대로 쓴다
  - 2026-10-02 PDF는 원본으로 남기고, 이후 바뀌는 내용은 spec과 결정 기록에만 반영한다
  - Claude Code에서는 `/speckit-specify`, `/speckit-clarify`, `/speckit-plan`, `/speckit-tasks`, `/speckit-implement` 명령을 쓴다 (`.claude/skills/`)
