# Implementation Plan: 블로그 관리 (생성·참여)

**Branch**: `claude/project-thread-q5t8xs` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: [spec.md](spec.md), [헌법](../../.specify/memory/constitution.md), [ERD](../../docs/04-design/ERD.md), [구현 단계 계획](../구현-단계-계획.md)

## Summary

**2단계**에서 002의 User Story 1(블로그 만들기)·2(찾아서 참여하기)·3(내 블로그와 블로그 탈퇴)을 만든다. 같은 단계에서 001의 남은 User Story 3~5(비밀번호 찾기, 이메일 찾기, 정보 수정·탈퇴)도 만든다 ([001 tasks.md](../001-member-account/tasks.md) Phase 5~7).

User Story 4(위임·폐쇄)와 5(멤버 제재·블랙리스트)는 글·신고가 생긴 뒤 6단계(블로그 운영 제재 + 메인 관리자)에서 만든다. 다만 블로그별 역할 확인(`@PreAuthorize`)과 정지 여부 확인은 지금부터 깔아 둔다.

## Technical Context

1단계([001 plan.md](../001-member-account/plan.md))와 같다. 새로 쓰는 것:

| 항목 | 내용 |
|---|---|
| 메서드 보안 | `@EnableMethodSecurity` + `@PreAuthorize("@blogAuth.canManageMembers(#slug)")` |
| 검색 | MySQL FULLTEXT `ft_blogs_name_description` (ngram) + 태그 이름 일치 |
| 스키마 변경 | Flyway `V2__member_credentials_changed_at.sql`: `members.credentials_changed_at` 한 칸 추가 (001 T031) |

## Constitution Check

| 원칙 | 2단계에서 지키는 방법 | 결과 |
|---|---|---|
| I. 문서가 먼저 | 남은 질문 #3·#6·#7·#8은 추천안을 기본값으로 쓰고 spec에 "확인 대기"로 적음 | ✅ |
| II. 매 요청 서버가 확인 | `BlogAuth`가 요청마다 DB에서 역할·정지·블로그 상태를 읽음. 다른 블로그의 신청 번호를 넣어도 처리하지 않음(SEC-07). 탈퇴·비밀번호 변경 전에 발급된 Access Token은 DB 시각과 비교해 거절 | ✅ |
| III. XSS·CSRF 한 곳에서 | 블로그 이름·소개·태그·닉네임은 모두 `textContent`. 요청은 모두 `api.js` | ✅ |
| IV. 개인정보 | 이메일 찾기 결과·멤버 목록은 이메일·전화번호를 가림(4.4). 이메일 찾기에서 재설정으로 넘어갈 때 회원 번호 대신 10분 임시 토큰(D-26) | ✅ |
| V. 서버 메모리에 상태 없음 | 개수 제한은 회원 행 잠금(`SELECT … FOR UPDATE`, D-68). 이메일 찾기 IP 제한만 1단계와 같은 메모리 방식 | ⚠️ 1단계와 같은 예외 |
| VI. 숫자는 설정으로 | `blog.limit.public=${BLOG_LIMIT_PUBLIC:3}`(5 넘으면 서버가 안 뜸), 비공개 5, 거절 뒤 7일, 재설정 30분, 이메일 찾기 10분 5번 | ✅ |
| VII. 업로드 세 겹 검사 | 대표 이미지·프로필 사진은 업로드가 생기는 3단계로 미룸 | 해당 없음 |
| VIII. 1차는 단순하게 | 목록·검색은 SQL 한 번 + 태그 한 번. 캐시 없음 | ✅ |

## 설계 결정

### 블로그별 역할 (헌법 II, SEC-07, D-71)

- `BlogAuth`(빈 이름 `blogAuth`): `isMember`, `isOwner`, `canManageMembers`, `canEditInfo`. 블로그 주소와 로그인한 회원 번호로 `blog_memberships`를 매번 읽는다.
- 폐쇄된 블로그, 정지 기간 중인 멤버는 어떤 권한도 없다. 정지 멤버가 블로그를 열면 `SUSPENDED`와 정지 끝 시각을 준다(BLG-13).
- 공개 범위·참여 방식은 블로그장만, 이름·소개·태그는 정보 수정 권한이 있는 부블로그장도 바꾼다.

### 공개 범위 (D-49, D-50)

- 공개: 목록·검색에 보임. 일부 공개: 목록·검색에 안 나오고 `?key=공유값`이 맞을 때만 보임(비회원 포함). 비공개: 멤버만.
- 볼 수 없는 블로그는 403이 아니라 404로 둬서 있는지조차 드러내지 않는다.
- 공유 값은 32자 무작위(16바이트 hex). 새로 만들면 이전 값은 무효. 일부 공개가 아니게 바꾸면 값을 지운다.
- 비공개 블로그는 참여 신청 경로가 없다(남은 질문으로 올림).

### 생성 개수 제한 (BLG-10, D-52, D-68)

- 만들 때와 비공개 → 공개로 바꿀 때 회원 행을 `SELECT … FOR UPDATE`로 잠근 뒤 운영 중·폐쇄 예정 블로그 수를 센다. 동시에 4번 눌러도 1개만 만들어지는 것을 테스트로 확인.
- 주소 중복은 DB 고유 키로 한 번 더 막는다. 폐쇄된 블로그 행은 30일 뒤 지울 때까지 남아 그 주소도 그동안 못 쓴다.

### 참여 (BLG-04, BLG-05, BLG-07)

- 한 블로그에 한 회원은 `blog_memberships` 한 행. 나갔다가 다시 들어오면 그 행을 다시 쓴다.
- 신청자 행을 잠가 두 번 눌러도 신청이 하나만 생긴다. 신청 처리는 신청 행을 잠가 블로그장·부블로그장이 동시에 눌러도 한 번만 처리된다.
- 승인·거절·신청 알림은 `notifications`에 저장해 둔다. 알림을 보여주는 화면은 5단계(소셜·알림)에서 만든다.
- 블랙리스트(D-34)·차단(D-36) 확인은 강퇴·차단 기능이 생기는 단계에서 신청 확인에 붙인다.

### 계정 (001 US3~5)

- 비밀번호 재설정: 가입 여부와 관계없이 같은 문구. 가입 안 된 이메일에는 메일을 보내지 않고 맞출 수 없는 행만 만든다. 바뀌면 모든 Refresh Token 폐기, 로그인 잠금 해제.
- **남은 Access Token 무효화(T031)**: `members.credentials_changed_at`보다 먼저 발급된 Access Token은 받지 않는다. 확인은 `/api/` 요청에서만 해 정적 파일마다 DB를 읽지 않는다. 탈퇴한 회원의 토큰도 같은 곳에서 막힌다.
- 비밀번호 변경은 모든 기기 로그아웃 뒤 지금 기기만 새 토큰을 받는다(로그인 유지 여부는 그대로).
- 탈퇴: 블로그장인 블로그(폐쇄 예정 포함)가 있으면 그 목록을 돌려주고 막는다. 탈퇴하면 이메일을 바로 비우고, 참여한 블로그에서는 나간 것으로, 대기 중 신청은 지운다. 이름·전화번호·비밀번호 해시는 30일 뒤 04:00 배치가 지운다.

### 시각 저장

- 모든 시각은 한국 시간 그대로 저장한다. `hibernate.type.java_time_use_direct_jdbc=true`로 JPA와 JdbcTemplate이 같은 값을 읽고 쓰게 했다. 전에는 서버 JVM 시간대가 UTC면 JPA가 쓴 시각이 9시간 밀려 저장될 수 있었다.

## API

| 메서드 | 주소 | 권한 | 내용 |
|---|---|---|---|
| POST | `/api/auth/password/code` | 누구나 | `{email}` 또는 `{findToken}`. 같은 문구 + 가린 이메일 |
| POST | `/api/auth/password/reset` | 누구나 | `{email 또는 findToken, code, newPassword}` |
| POST | `/api/auth/find-email` | 누구나 | `{name, phone}` → 가린 이메일·가입일·임시 토큰 |
| GET·PUT | `/api/me/profile` | 회원 | 내 정보, 닉네임·전화번호 수정 |
| POST | `/api/me/password` | 회원 | `{currentPassword, newPassword}` |
| POST | `/api/me/withdraw` | 회원 | `{password}`. 블로그장이면 409 `OWNS_BLOGS` + 블로그 목록 |
| GET | `/api/blogs?sort=latest\|popular&q=&page=` | 누구나 | 공개 블로그 목록·검색, 12개씩 |
| POST | `/api/blogs` | 회원 | 만들기 |
| GET | `/api/blog-slug-available?slug=` | 회원 | 주소 확인 |
| GET | `/api/blogs/{slug}?key=` | 누구나 | 블로그 정보, 내 역할·신청 상태 |
| PUT | `/api/blogs/{slug}` | 정보 수정 권한 | 이름·소개·태그, 블로그장은 공개 범위·참여 방식도 |
| POST | `/api/blogs/{slug}/share-link` | 블로그장 | 공유 링크 새로 만들기 |
| POST·DELETE | `/api/blogs/{slug}/join` | 회원 | 참여 신청 / 대기 중 신청 취소 |
| POST | `/api/blogs/{slug}/leave` | 회원 | 블로그 탈퇴. 블로그장은 409 |
| GET | `/api/blogs/{slug}/join-requests` | 멤버 관리 권한 | 대기 중 신청 |
| POST | `/api/blogs/{slug}/join-requests/{id}/approve\|reject` | 멤버 관리 권한 | 승인·거절 |
| GET | `/api/blogs/{slug}/members` | 멤버 관리 권한 | 멤버 목록(이메일·전화번호 가림) |
| GET | `/api/me/blogs` | 회원 | 만든 블로그, 참여한 블로그, 대기 중 신청 |

화면: `/blog/{주소}`(블로그), `/blog/{주소}/manage`(관리), `/blog-new.html`, `/my-blogs.html`, `/me.html`, `/find-email.html`, `/forgot-password.html`, 메인(목록·검색).

## Complexity Tracking

| 예외 | 왜 필요한가 | 더 단순한 대안을 안 쓴 이유 |
|---|---|---|
| 이메일 찾기 IP 제한을 서버 메모리에서 셈 | 1단계 로그인·메일 제한과 같은 방식 | D-83 결정 전까지 1단계와 같게 둠 |
