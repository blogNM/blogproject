#!/usr/bin/env python3
"""
테스트 계정들이 3단계(글쓰기·읽기) 기능을 실제로 써 본다. 서버 API로만 요청하므로 화면에서 누른 것과 같다.
사용법: python3 scripts/test-data/activity-3.py [서버 주소, 기본 http://localhost:8080]
테스트 데이터(load.sh)를 넣은 뒤에 한 번 돌린다. 다시 돌리면 같은 글이 한 번 더 생긴다.
"""
import http.cookiejar
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")
IMAGES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "images")
PASSWORD = "Test1234!"
results = []


class Session:
    def __init__(self, account):
        self.account = account
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.request("GET", "/api/csrf")
        self.request("POST", "/api/auth/login",
                     {"email": account + "@example.com", "password": PASSWORD, "rememberMe": False})

    def xsrf(self):
        return next((c.value for c in self.jar if c.name == "XSRF-TOKEN"), "")

    def request(self, method, path, body=None, files=None):
        headers = {"Accept": "application/json"}
        data = None
        if method != "GET":
            headers["X-XSRF-TOKEN"] = self.xsrf()
        if files is not None:
            boundary = uuid.uuid4().hex
            name, filename, mime, content = files
            data = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; filename=\"{filename}\"\r\n"
                    f"Content-Type: {mime}\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
            headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        elif body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req) as res:
                text = res.read().decode() or "{}"
                return res.status, json.loads(text)
        except urllib.error.HTTPError as e:
            text = e.read().decode() or "{}"
            try:
                return e.code, json.loads(text)
            except ValueError:
                return e.code, {"message": text[:200]}

    def upload(self, slug, image_file, filename):
        with open(os.path.join(IMAGES, image_file), "rb") as f:
            content = f.read()
        mime = mimetypes.guess_type(filename)[0]
        status, res = self.request("POST", f"/api/blogs/{slug}/images", files=("file", filename, mime, content))
        if status != 200:
            raise SystemExit(f"{self.account} 사진 올리기 실패: {status} {res}")
        return res["url"]

    def category_id(self, slug, name):
        _, cats = self.request("GET", f"/api/blogs/{slug}/categories")
        return next((c["id"] for c in cats if c["name"] == name), None)

    def write(self, slug, title, content, category=None, tags=(), notice=False):
        body = {"title": title, "content": content, "categoryId": self.category_id(slug, category) if category else None,
                "tags": list(tags), "notice": notice}
        status, res = self.request("POST", f"/api/blogs/{slug}/posts", body)
        return status, res


def log(account, action, status, res, expect=200):
    ok = status == expect
    detail = "" if status == 200 else f" → {res.get('message', '')}"
    results.append(f"{'✓' if ok else '✗'} {account}: {action} ({status}){detail}")
    return res


def find_post(session, slug, title):
    _, page = session.request("GET", f"/api/blogs/{slug}/posts?size=30")
    for p in page.get("notices", []) + page.get("items", []):
        if p["title"] == title:
            return p["id"]
    return None


# 1. 민수(서울 맛집 탐방 블로그장): 카테고리 추가, 사진 넣은 글, 공지
minsu = Session("minsu.park")
log("minsu.park", "카테고리 [분식] 추가", *minsu.request("POST", "/api/blogs/foodie-seoul/categories", {"name": "분식"}))
img = minsu.upload("foodie-seoul", "00000000-0000-0000-0000-000000000002", "을지로-골목.jpg")
log("minsu.park", "사진 넣은 글 [을지로 골목 지도] (한식)", *minsu.write("foodie-seoul", "을지로 골목 지도",
    f"을지로 3가역에서 걸어서 갈 수 있는 곳만 모았다.\n\n1. 국밥집\n2. 노가리 골목\n3. 평양냉면\n\n![을지로 골목]({img})",
    category="한식", tags=["을지로", "서울", "지도"]))
log("minsu.park", "공지 [10월 맛집 모임 안내]", *minsu.write("foodie-seoul", "10월 맛집 모임 안내",
    "10월 19일 토요일 낮 12시, 을지로 3가역 1번 출구에서 만나요.\n\n**참석하실 분은 댓글 남겨 주세요.** (댓글은 4단계)",
    notice=True))

# 2. 동현(서울 맛집 탐방 멤버): 분식 글 두 개
donghyun = Session("donghyun.kang")
log("donghyun.kang", "글 [망원동 떡볶이 3곳] (분식)", *donghyun.write("foodie-seoul", "망원동 떡볶이 3곳",
    "| 가게 | 맛 | 가격 |\n|---|---|---|\n| 할머니 떡볶이 | 달달 | 4,000원 |\n| 망원 즉떡 | 매움 | 6,000원 |\n| 시장 떡볶이 | 보통 | 3,500원 |",
    category="분식", tags=["떡볶이", "망원동"]))
log("donghyun.kang", "글 [할인 쿠폰 받아 가세요] (광고 글)", *donghyun.write("foodie-seoul", "할인 쿠폰 받아 가세요",
    "[여기를 누르세요](https://example.com/coupon)"))
log("donghyun.kang", "공지 쓰기 시도 (멤버라 막혀야 함)", *donghyun.write("foodie-seoul", "몰래 공지", "공지", notice=True),
    expect=403)

# 3. 소연(서울 맛집 탐방 부블로그장, 글 관리 권한): 광고 글 삭제, 민수 글 수정 시도
soyeon = Session("soyeon.jung")
ad = find_post(soyeon, "foodie-seoul", "할인 쿠폰 받아 가세요")
log("soyeon.jung", "동현의 광고 글 삭제 (동현에게 알림 저장)", *soyeon.request("DELETE", f"/api/posts/{ad}"))
map_post = find_post(soyeon, "foodie-seoul", "을지로 골목 지도")
log("soyeon.jung", "민수 글 수정 시도 (작성자가 아니라 막혀야 함)",
    *soyeon.request("PUT", f"/api/posts/{map_post}", {"title": "바꿈", "content": "바꿈", "categoryId": None, "tags": [], "notice": False}), expect=403)

# 4. 채림(멤버): 내 글 수정, 자바 스터디(일부 공개)에 코드 글
chaerim = Session("chaerim.dev")
cafe = find_post(chaerim, "foodie-seoul", "성수동 카페 세 곳")
if cafe:
    _, old = chaerim.request("GET", f"/api/posts/{cafe}")
    log("chaerim.dev", "내 글 [성수동 카페 세 곳]에 내용 더하기", *chaerim.request("PUT", f"/api/posts/{cafe}", {
        "title": old["title"], "content": old["contentMarkdown"] + "\n\n> 세 곳 중 두 번째 카페의 라떼가 제일 좋았다.",
        "categoryId": old["categoryId"], "tags": old["tags"] + ["라떼"], "notice": old["notice"]}))
log("chaerim.dev", "자바 스터디에 코드 글 [Spring Security 필터 순서]", *chaerim.write("java-study",
    "Spring Security 필터 순서", "JWT 필터는 `UsernamePasswordAuthenticationFilter` 앞에 둔다.\n\n```java\nhttp.addFilterBefore(jwtFilter,\n    UsernamePasswordAuthenticationFilter.class);\n```",
    category="스프링", tags=["spring", "security", "JWT"]))
log("chaerim.dev", "민수 글 삭제 시도 (멤버라 막혀야 함)", *chaerim.request("DELETE", f"/api/posts/{map_post}"), expect=403)

# 5. 현우(자바 스터디 블로그장): 카테고리 추가, 글
hyunwoo = Session("hyunwoo.choi")
log("hyunwoo.choi", "카테고리 [JPA] 추가", *hyunwoo.request("POST", "/api/blogs/java-study/categories", {"name": "JPA"}))
log("hyunwoo.choi", "글 [영속성 컨텍스트 한 장 정리] (JPA)", *hyunwoo.write("java-study", "영속성 컨텍스트 한 장 정리",
    "- 1차 캐시\n- 변경 감지(dirty checking)\n- 쓰기 지연\n\n~~merge는 되도록 쓰지 않는다~~ 상황에 따라 다르다.",
    category="JPA", tags=["jpa", "자바"]))

# 6. 지영(주말 여행 클럽 블로그장): 카테고리, 사진 글
jiyoung = Session("jiyoung.lee")
log("jiyoung.lee", "카테고리 [해외] 추가", *jiyoung.request("POST", "/api/blogs/weekend-trip/categories", {"name": "해외"}))
img = jiyoung.upload("weekend-trip", "00000000-0000-0000-0000-000000000004", "후쿠오카.png")
log("jiyoung.lee", "사진 넣은 글 [후쿠오카 1박 2일] (해외)", *jiyoung.write("weekend-trip", "후쿠오카 1박 2일",
    f"금요일 저녁 출발, 토요일 밤 도착.\n\n![후쿠오카]({img})\n\n## 먹은 것\n\n- 라멘\n- 명란", category="해외",
    tags=["후쿠오카", "일본", "여행"]))

# 7. 하은(고양이 일기 블로그장): 움직이는 사진(gif) 글
haeun = Session("haeun.oh")
img = haeun.upload("cat-diary", "00000000-0000-0000-0000-000000000005", "보리-낮잠.gif")
log("haeun.oh", "gif 사진 글 [보리 낮잠 시간] (일상)", *haeun.write("cat-diary", "보리 낮잠 시간",
    f"햇빛 드는 창가에서만 잔다.\n\n![보리 낮잠]({img})", category="일상", tags=["고양이", "보리"]))

# 8. 유나(유나의 책방 블로그장, 비공개·폐쇄 예정): 폐쇄 예정이어도 글쓰기
yuna = Session("yuna.yoon")
log("yuna.yoon", "폐쇄 예정 블로그에 글 [마지막 책 모임 후기] (소설)", *yuna.write("book-club", "마지막 책 모임 후기",
    "블로그가 13일에 닫힙니다. 그동안 함께 읽어 주셔서 고마워요.", category="소설", tags=["책", "후기"]))

# 9. 막혀야 하는 사람들
seojun = Session("seojun.han")
log("seojun.han", "정지 중인 서울 맛집 탐방에 글쓰기 시도 (막혀야 함)",
    *seojun.write("foodie-seoul", "정지 중 글", "본문"), expect=403)
jaewon = Session("jaewon.shin")
log("jaewon.shin", "강퇴된 서울 맛집 탐방에 글쓰기 시도 (막혀야 함)",
    *jaewon.write("foodie-seoul", "강퇴 뒤 글", "본문"), expect=403)
log("jaewon.shin", "확장자만 jpg로 바꾼 파일 올리기 (막혀야 함)", *jaewon.request(
    "POST", "/api/blogs/java-study/images", files=("file", "virus.jpg", "image/jpeg", b"MZ" + b"\0" * 64)), expect=400)

print("\n".join(results))
failed = [r for r in results if r.startswith("✗")]
print(f"\n{len(results) - len(failed)}개 성공, {len(failed)}개 예상과 다름")
sys.exit(1 if failed else 0)
