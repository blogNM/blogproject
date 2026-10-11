# Implementation Plan: 블로그 운영 제재 + 메인 관리자 — 6단계

**Branch**: `claude/project-thread-q5t8xs` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md), [002 spec](../002-blog-management/spec.md) US4·US5

**Input**: [spec.md](spec.md), [헌법](../../.specify/memory/constitution.md), [ERD](../../docs/04-design/ERD.md), [구현 단계 계획](../구현-단계-계획.md)

## Summary

블로그장·부블로그장의 멤버 관리(역할·권한, 경고·정지·강제 퇴장, 블랙리스트와 해제 문의, 신고 처리)와 블로그장 위임·폐쇄(002 US4·US5), 그리고 회원과 분리된 메인 관리자 로그인과 관리자 페이지(005 ADM-01~07)를 만든다.

## Technical Context

1~5단계와 같다. 새로 쓰는 것:

| 항목 | 내용 |
|---|---|
| 스키마 변경 | V7: `admins.must_change_password·password_changed_at·created_by`, 활동 기록 종류 추가(UNHIDE·REPORT·CREATE_ADMIN), `notifications.detail`, `blacklist_entries.member_id·lifted_at`, `blogs.close_requested_by`에 OWNER_KICKED, `blogs.purged_at`, 인덱스 3개 |
| 관리자 로그인 | `ADMIN_TOKEN` 쿠키(세션 쿠키, 2시간). `/api/admin/**`·`/admin/**`에서만 읽고 회원 쿠키는 보지 않음. 비밀번호를 바꾸면 그 전 토큰 무효 |
| 설정 | `admin.initial-login-id`, `admin.initial-password`(환경변수로만), `admin.session-ttl`(2h), `admin.login-per-minute`(10) |
| 배치 | `BlogLifecycleJob` 매일 04:00 (ShedLock): 폐쇄 예정 닫기, 3일·1일 전 알림, 7일 지난 위임 요청 정리, 닫힌 지 30일 지난 블로그 지우기, 1년 지난 제재·활동 기록 지우기 |

## Constitution Check

| 원칙 | 6단계에서 지키는 방법 | 결과 |
|---|---|---|
| I. 문서가 먼저 | #16·#17은 추천안을 기본값으로 쓰고 spec에 "확인 대기"로 적음. 강제 폐쇄는 추천안이 아니라 D-86 | ✅ |
| II. 매 요청 서버가 확인 | 블로그 권한은 `@blogAuth`가 요청마다 DB에서. 관리자 토큰도 요청마다 계정·비밀번호 변경 시각 확인 | ✅ |
| III. XSS·CSRF 한 곳에서 | 관리자 페이지도 `api.js`, 모든 글자는 `textContent` | ✅ |
| IV. 개인정보 | 멤버 목록·관리자 회원 상세는 가려서, "전체 보기"는 관리자만 하고 활동 기록에 남김. 블랙리스트는 해시만 | ✅ |
| V. 서버 메모리에 상태 없음 | 관리자 로그인도 JWT 쿠키 | ✅ |
| VI. 숫자는 설정으로 | 위 `admin.*` | ✅ |
| VIII. 1차는 단순하게 | 관리자 페이지는 한 화면에 탭 | ✅ |

## 설계 결정

- 제재는 블로그 안에서만: 관리자는 회원 정지·강퇴를 하지 않고 블로그장 경고·강퇴, 블로그 숨김·폐쇄, 글·댓글 숨김·삭제만 한다 (ADM-01).
- 부블로그장은 "멤버 관리" 권한이 있으면 제재·신고 처리·블랙리스트를 하고, 다른 부블로그장과 블로그장은 제재하지 못한다. 역할과 권한은 블로그장만 바꾼다 (D-71).
- 블로그장 경고 3번(1년)이면 강퇴. 강퇴되면 가장 먼저 부블로그장이 된 사람에게 넘어가고, 없으면 7일 뒤 04:00 폐쇄 (ADM-07, D-87).
- 위임을 받아들이면 전 블로그장은 일반 멤버 (확인 대기).
