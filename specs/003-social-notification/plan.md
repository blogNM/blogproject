# Implementation Plan: 소셜·알림 — 5단계

**Branch**: `claude/project-thread-q5t8xs` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: [spec.md](spec.md), [헌법](../../.specify/memory/constitution.md), [ERD](../../docs/04-design/ERD.md), [구현 단계 계획](../구현-단계-계획.md)

## Summary

003의 모든 User Story(팔로우·구독, 프로필, 알림, 차단, 신고)와 [내 활동] 화면(SOC-07, D-91)을 만든다. 신고는 접수까지 하고, 처리 화면(블로그장·관리자)은 6단계에서 만든다.

## Technical Context

1~4단계와 같다. 새로 쓰는 것:

| 항목 | 내용 |
|---|---|
| 스키마 변경 | V4: `members.show_posts·show_likes·show_following·show_subscriptions`(기본 TRUE), `reports.reason_code` CHECK, 알림 탭·팔로워·좋아요 목록 인덱스 |
| 설정 | `social.actions-per-minute`(30), `report-cooldown`(14d), `notification-retention-days`(30), `bio-max`(150). 모두 환경변수로 바꿈 |
| 알림 전달 | 30초 폴링 (D-72). 화면이 보이지 않을 때는 묻지 않음 |
| 배치 | `NotificationCleanupJob` 매일 04:00 (ShedLock) |

## Constitution Check

| 원칙 | 5단계에서 지키는 방법 | 결과 |
|---|---|---|
| I. 문서가 먼저 | #9·#10·#11은 추천안을 기본값으로 쓰고 spec에 "확인 대기"로 적음 | ✅ |
| II. 매 요청 서버가 확인 | 차단·멤버 여부·목록 공개 설정을 요청마다 DB에서 다시 읽음. 블로그 알림 보관은 `@blogAuth.isOwner` | ✅ |
| III. XSS·CSRF 한 곳에서 | 알림 문구는 서버가 만들고 화면은 `textContent`. 닉네임·소개·블로그 이름도 `textContent`. 모든 요청은 `api.js` | ✅ |
| IV. 개인정보 | 프로필·팔로워 목록 응답에 이메일·이름·전화번호를 넣지 않음 (SC-003) | ✅ |
| V. 서버 메모리에 상태 없음 | 알림은 DB, 폴링은 화면에서. 요청 제한만 1차는 메모리(1~4단계와 같음) | ✅ |
| VI. 숫자는 설정으로 | 위 `social.*` | ✅ |
| VII. 업로드 세 겹 검사 | 프로필 사진도 같은 `ImageService.upload` | ✅ |
| VIII. 1차는 단순하게 | SSE·WebSocket 없이 폴링. 목록은 쪽 번호 | ✅ |

## 설계 결정

### 팔로우·구독 (SOC-01)

- 누르면 하고, 한 번 더 누르면 취소. 결과로 켜짐 여부와 지금 수를 돌려준다.
- 받는 사람 알림(새 팔로워, 내 블로그 구독)은 같은 사람에게서 하루 한 번만. 껐다 켜서 알림을 쏟아내지 못하게.
- 구독은 볼 수 있는 블로그만, 폐쇄 예정인 블로그는 새로 구독하지 못한다(이미 한 구독은 풀 수 있음).

### 프로필과 내 활동 (SOC-03, SOC-07, D-91)

- `/members/{번호}` 한 화면. 본인이 보면 [내 활동]이 되고 목록마다 "다른 사람에게 보여주기" 체크가 나온다.
- 탭: 쓴 글 · 좋아요 한 글 · 팔로잉 · 팔로워 · 구독한 블로그. 팔로워는 늘 공개(SOC-02).
- 남이 볼 때는 그 사람이 볼 수 있는 글·블로그만: 공개 블로그, 또는 보는 사람이 멤버인 블로그. 서로 차단한 사이의 글은 뺀다. 블로그를 나간 뒤의 글(`author_left`)은 쓴 글에서 뺀다.
- 공개를 끈 목록은 403 `PRIVATE_LIST`. 본인은 언제나 모두 본다.

### 차단 (SOC-05, D-36, 추천안 #10)

- 양방향: 한쪽이 차단해도 서로의 글(피드·검색·프로필)과 댓글이 숨겨지고, 서로 팔로우가 풀리며 다시 팔로우·댓글·답글·좋아요를 못 한다.
- 같은 블로그의 멤버끼리는 그 블로그 안에서 보이고 댓글·좋아요도 된다 (D-36).
- 블로그장과 서로 차단한 사이면 그 블로그에 참여 신청을 못 한다 (`JOIN_BLOCKED`).
- 숨긴 댓글에 답글이 남아 있으면 "삭제된 댓글입니다"처럼 보인다.

### 신고 (SOC-06, D-45, D-85, 추천안 #9)

- 대상: 회원·블로그·글·댓글. 접수처는 신고할 때 정해 `reports.handler`에 둔다.
  - 블로그 안의 글·댓글, 블로그 안에서 신고한 멤버 → 블로그장(`BLOG_OWNER`)
  - 블로그 자체, 블로그장 본인과 그 글·댓글, 메인 프로필에서 한 회원 신고 → 메인 관리자(`ADMIN`)
- 같은 사람이 같은 대상을 14일 안에 다시 신고하면 409. 신고자 행을 잠가 두 번 눌러도 하나만 생긴다.
- 자기 자신·내 글·내 댓글·내 블로그는 신고하지 못한다. 기타 사유는 내용 필수, 200자.

### 알림 (SOC-04, D-39, D-56, D-72)

- 사이드바 탭: 전체(맨 앞), 댓글, 좋아요, 팔로우·구독, 블로그, 운영. 읽은 알림은 강조 색만 빠진다.
- 문구와 누르면 갈 주소는 서버가 만든다. 대상이 지워졌으면 주소 없이 문구만.
- 종 아이콘은 30초마다 `GET /api/notifications/unread`. 이 요청은 30분 무활동 시간을 늘리지 않는다 (D-62).
- 내 정보에서 끌 수 있는 종류만 켜고 끈다. 끌 수 없는 알림(내 글 삭제됨 등)은 설정 요청을 거절한다 (SC-002). 끈 동안 생긴 일은 만들지 않는다.
- 보관: 받는 사람이 30일·7일, 블로그 탭 알림은 그 블로그의 설정(블로그장). 매일 04:00 정리.

## API

| 요청 | 설명 |
|---|---|
| `GET /api/members/{id}` | 프로필 (비회원도) |
| `GET /api/members/{id}/posts·likes·following·followers·subscriptions?page=` | 내 활동 목록 |
| `POST /api/members/{id}/follow`, `POST /api/blogs/{slug}/subscribe`, `POST /api/members/{id}/block` | 켜기·끄기 |
| `PUT /api/me/bio`, `POST·DELETE /api/me/profile-image`, `PUT /api/me/activity-visibility`, `GET /api/me/blocks` | 내 정보 |
| `POST /api/reports` | 신고 `{targetType, targetId, slug, key, reason, detail}` |
| `GET /api/notifications?tab=&page=`, `GET /api/notifications/unread`, `POST /api/notifications/{id}/read`, `POST /api/notifications/read-all?tab=`, `DELETE /api/notifications?tab=` | 알림 |
| `GET·PUT /api/me/notification-settings`, `GET·PUT /api/blogs/{slug}/notification-retention` | 알림 설정 |
