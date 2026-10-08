#!/usr/bin/env python3
"""
테스트 계정들이 5단계 기능(프로필 꾸미기, 팔로우·구독, 내 활동 공개 설정, 차단, 신고, 알림 설정)을 써 본다.
서버 API로만 요청하므로 화면에서 누른 것과 같다. 있는 데이터를 지우지 않고 더하기만 한다.

사용법: python3 scripts/test-data/activity-5.py [서버 주소, 기본 http://localhost:8080] [사진 폴더, 기본 ./data/theme-photos]

activity-4.py를 돌린 뒤에 한 번 돌린다. 다시 돌려도 결과는 같다 (팔로우·구독·차단은 켜져 있는지 보고 누른다).
프로필 사진은 activity-4.py와 같은 사진 폴더의 사진을 쓰고, 없으면 테스트 그림으로 대신한다.
"""
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
base = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
photos = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.getcwd(), "data", "theme-photos")

# activity-4.py의 로그인·사진 도우미를 그대로 쓴다 (앞부분만 읽어 실행).
spec = importlib.util.spec_from_file_location("activity4", os.path.join(HERE, "activity-4.py"))
source = open(spec.origin, encoding="utf-8").read().split("\ndef theme(")[0]
module = {"__name__": "activity4", "__file__": spec.origin}
sys.argv = [sys.argv[0], base, photos]
exec(compile(source, spec.origin, "exec"), module)
Session, log, results, send_file = module["Session"], module["log"], module["results"], module["send_file"]

ACCOUNTS = ["chaerim.dev", "minsu.park", "jiyoung.lee", "hyunwoo.choi", "soyeon.jung", "donghyun.kang",
            "yuna.yoon", "seojun.han", "haeun.oh", "jaewon.shin"]
s = {a: Session(a) for a in ACCOUNTS}
ids = {a: s[a].request("GET", "/api/me")[1]["id"] for a in ACCOUNTS}

# 1. 프로필 꾸미기: 소개와 사진
bios = {
    "soyeon.jung": "카페 다니며 사진 남기는 사람",
    "seojun.han": "주말엔 떡볶이",
    "jaewon.shin": "책 읽고 짧게 기록해요",
}
for account, bio in bios.items():
    log(account, f"소개 '{bio}'", *s[account].request("PUT", "/api/me/bio", {"bio": bio}))
avatars = {"minsu.park": "food-gukbap", "jiyoung.lee": "cover-weekendtrip", "hyunwoo.choi": "cover-javastudy",
           "yuna.yoon": "cover-bookclub", "haeun.oh": "cover-catdiary"}
for account, name in avatars.items():
    log(account, f"프로필 사진 {name}", *send_file(s[account], "/api/me/profile-image", name))


def ensure(session, path, label, want=True, key="on"):
    """켜져 있지 않을 때만 눌러서, 몇 번 돌려도 같은 상태가 되게 한다."""
    status, res = session.request("POST", path)
    if status == 200 and res.get(key) != want:
        status, res = session.request("POST", path)
    log(session.account, label, status, res)


# 2. 팔로우 (받는 사람에게 하루 한 번 알림)
follows = [
    ("soyeon.jung", "minsu.park"), ("soyeon.jung", "haeun.oh"), ("seojun.han", "minsu.park"),
    ("jaewon.shin", "yuna.yoon"), ("jaewon.shin", "haeun.oh"), ("haeun.oh", "jiyoung.lee"),
    ("yuna.yoon", "jaewon.shin"), ("donghyun.kang", "hyunwoo.choi"), ("hyunwoo.choi", "donghyun.kang"),
]
for a, b in follows:
    ensure(s[a], f"/api/members/{ids[b]}/follow", f"{b} 팔로우")

# 3. 블로그 구독 (블로그장에게 알림)
subs = [
    ("soyeon.jung", "foodie-seoul"), ("seojun.han", "cat-diary"), ("jaewon.shin", "weekend-trip"),
    ("soyeon.jung", "cat-diary"), ("hyunwoo.choi", "weekend-trip"), ("donghyun.kang", "foodie-seoul"),
]
for a, slug in subs:
    ensure(s[a], f"/api/blogs/{slug}/subscribe", f"{slug} 구독")

# 4. 내 활동 공개 설정: 재원은 좋아요 한 글을, 서준은 구독 목록을 나만 보기로 (기본은 모두 공개)
log("jaewon.shin", "좋아요 한 글 나만 보기", *s["jaewon.shin"].request(
    "PUT", "/api/me/activity-visibility", {"posts": True, "likes": False, "following": True, "subscriptions": True}))
log("seojun.han", "구독한 블로그 나만 보기", *s["seojun.han"].request(
    "PUT", "/api/me/activity-visibility", {"posts": True, "likes": True, "following": True, "subscriptions": False}))

# 5. 차단: 재원이 서준을 차단 (서로의 글·댓글이 숨겨지고 팔로우가 풀림)
ensure(s["jaewon.shin"], f"/api/members/{ids['seojun.han']}/block", "서준 차단", key="blocked")

# 6. 신고 (같은 대상은 2주에 한 번이라 다시 돌리면 '이미 신고함'으로 남는다)
_, feed = s["soyeon.jung"].request("GET", "/api/blogs/foodie-seoul/posts?size=30")
post = next((p for p in feed.get("items", []) if p["authorName"] != "민수네밥상"), None)
if post:
    status, res = s["soyeon.jung"].request("POST", "/api/reports",
                                           {"targetType": "POST", "targetId": post["id"], "reason": "SPAM"})
    log("soyeon.jung", f"글 '{post['title']}' 신고 → {res.get('handler', res.get('code'))}",
        200 if status in (200, 409) else status, res)
status, res = s["haeun.oh"].request("POST", "/api/reports", {"targetType": "MEMBER", "targetId": ids["seojun.han"],
                                                             "reason": "ETC", "detail": "테스트 신고입니다."})
log("haeun.oh", f"서준 프로필 신고 → {res.get('handler', res.get('code'))}", 200 if status in (200, 409) else status, res)

# 7. 알림 설정: 동현은 좋아요 알림을 끄고 보관을 7일로
log("donghyun.kang", "좋아요 알림 끄기, 보관 7일", *s["donghyun.kang"].request(
    "PUT", "/api/me/notification-settings", {"enabled": {"LIKE": False}, "retentionDays": 7}))

print("\n".join(results))
failed = sum(1 for r in results if r.startswith("✗"))
print(f"\n{len(results) - failed}개 성공, {failed}개 실패")
