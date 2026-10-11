#!/usr/bin/env python3
"""
테스트 계정들이 4단계 기능(블로그 테마 틀·머리 사진, 댓글·답글, 좋아요, 조회수)을 실제로 써 본다.
서버 API로만 요청하므로 화면에서 누른 것과 같다.

사용법: python3 scripts/test-data/activity-4.py [서버 주소, 기본 http://localhost:8080] [사진 폴더, 기본 ./data/theme-photos]

사진 폴더에 저작권 걱정 없는 사진(Unsplash·Pexels 라이선스 등)을 이름별로 넣어 두면 그 사진을 쓴다.
예: cover-foodie.jpg, food-tteokbokki.jpg. 없으면 scripts/test-data/images의 테스트 그림으로 대신한다.
activity-3.py를 돌린 뒤에 한 번 돌린다. 다시 돌리면 글과 댓글이 한 번 더 생긴다 (테마·좋아요는 그대로).
"""
import glob
import importlib.util
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
base = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
PHOTOS = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.getcwd(), "data", "theme-photos")
JAVA_KEY = "9f2c4a7b1e6d3f80a5c2b7e4d1f6a3c9"

# activity-3.py의 로그인·요청 도우미를 그대로 쓴다 (모듈 이름에 '-'가 있어 파일로 읽음).
spec = importlib.util.spec_from_file_location("activity", os.path.join(HERE, "activity-3.py"))
source = open(spec.origin, encoding="utf-8").read().split("# 1. 민수")[0]
module = {"__name__": "activity", "__file__": spec.origin}
sys.argv = [sys.argv[0], base]
exec(compile(source, spec.origin, "exec"), module)
Session, log, results, IMAGES = module["Session"], module["log"], module["results"], module["IMAGES"]
BASE = module["BASE"]

FALLBACK = sorted(os.listdir(IMAGES))
used_fallback = []


def photo(name):
    """사진 폴더의 name.* 파일, 없으면 테스트 그림. (파일 경로, 올릴 때 쓸 파일 이름)"""
    found = sorted(glob.glob(os.path.join(PHOTOS, name + ".*")))
    if found:
        path = found[0]
        return path, os.path.basename(path)
    used_fallback.append(name)
    path = os.path.join(IMAGES, FALLBACK[len(used_fallback) % len(FALLBACK)])
    return path, name + "." + kind(path)


def kind(path):
    """파일 앞부분(시그니처)으로 사진 형식을 알아낸다. 서버도 이것과 확장자를 함께 본다."""
    with open(path, "rb") as f:
        head = f.read(12)
    if head.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if head.startswith(b"\x89PNG"):
        return "png"
    if head.startswith(b"GIF8"):
        return "gif"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "webp"
    raise SystemExit(f"{path}: jpg·png·gif·webp 사진이 아닙니다.")


def send_file(session, path_url, name):
    path, filename = photo(name)
    with open(path, "rb") as f:
        content = f.read()
    ext = kind(path)
    filename = filename.rsplit(".", 1)[0] + "." + ext
    mime = {"jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "gif": "image/gif", "webp": "image/webp"}[ext]
    return session.request("POST", path_url, files=("file", filename, mime, content))


def theme(session, slug, theme, accent, font, title, subtitle, cover):
    log(session.account, f"[{slug}] 테마 {theme} · {accent} · {font}", *session.request(
        "PUT", f"/api/blogs/{slug}/theme",
        {"theme": theme, "accent": accent, "font": font, "heroTitle": title, "heroSubtitle": subtitle}))
    log(session.account, f"[{slug}] 머리 사진 {cover}", *send_file(session, f"/api/blogs/{slug}/cover", cover))


def photo_post(session, slug, title, text, photos, category=None, tags=()):
    body = text
    for name, alt in photos:
        status, res = send_file(session, f"/api/blogs/{slug}/images", name)
        if status != 200:
            log(session.account, f"[{slug}] 사진 {name} 올리기", status, res)
            return None
        body += f"\n\n![{alt}]({res['url']})"
    status, res = session.write(slug, title, body, category=category, tags=tags)
    log(session.account, f"[{slug}] 사진 글 [{title}]", status, res)
    return res.get("id") if status == 200 else None


def post_ids(session, slug, key=None):
    q = f"&key={key}" if key else ""
    _, page = session.request("GET", f"/api/blogs/{slug}/posts?size=30{q}")
    return {p["title"]: p["id"] for p in page.get("items", [])}


def comment(session, post_id, content, parent=None, key=None):
    q = f"?key={key}" if key else ""
    status, res = session.request("POST", f"/api/posts/{post_id}/comments{q}", {"content": content, "parentId": parent})
    log(session.account, f"댓글 \"{content[:16]}\"", status, res)
    return res.get("id") if status == 200 else None


def like(session, post_id, title):
    status, res = session.request("POST", f"/api/posts/{post_id}/like")
    if status == 200 and not res.get("liked"):  # 이미 눌러 둔 글이면 다시 켠다
        status, res = session.request("POST", f"/api/posts/{post_id}/like")
    log(session.account, f"좋아요 [{title}] → {res.get('likes')}", status, res)


def view(session, post_id, key=None):
    q = f"?key={key}" if key else ""
    if session is None:
        urllib.request.urlopen(urllib.request.Request(BASE + f"/api/posts/{post_id}{q}", headers={"Accept": "application/json"})).read()
    else:
        session.request("GET", f"/api/posts/{post_id}{q}")


s = {a: Session(a) for a in ["minsu.park", "jiyoung.lee", "hyunwoo.choi", "yuna.yoon", "haeun.oh",
                              "chaerim.dev", "soyeon.jung", "donghyun.kang", "seojun.han"]}

# 1. 블로그장마다 자기 블로그에 맞는 테마 틀을 고르고 머리 사진을 올린다.
theme(s["minsu.park"], "foodie-seoul", "MAGAZINE", "#D93A49", "SANS",
      "오늘은 뭐 먹지?", "서울 골목 맛집을 멤버들이 직접 다니며 기록해요", "cover-foodie")
theme(s["jiyoung.lee"], "weekend-trip", "WHATEVER", "#9C7A54", "SERIF",
      "주말엔 떠나요", "금요일 저녁 출발, 일요일 밤 도착하는 여행 기록", "cover-weekendtrip")
theme(s["hyunwoo.choi"], "java-study", "LETTER", "#2FA58C", "MONO",
      "", "스프링 부트를 한 장씩 읽고 정리합니다", "cover-javastudy")
theme(s["yuna.yoon"], "book-club", "BOOK_CLUB", "#39385B", "SERIF",
      "한 달에 두 권", "같이 읽고 한 줄씩 남기는 책방", "cover-bookclub")
theme(s["haeun.oh"], "cat-diary", "POSTER", "#925B8F", "SANS",
      "보리와 콩이", "창가에서 자는 두 고양이의 하루", "cover-catdiary")

# 2. 사진 넣은 글
photo_post(s["minsu.park"], "foodie-seoul", "새벽 국밥 한 그릇",
           "추운 날엔 역시 국밥. 깍두기 국물을 조금 넣으면 더 맛있다.", [("food-gukbap", "국밥")], "한식", ["국밥", "을지로"])
photo_post(s["donghyun.kang"], "foodie-seoul", "시장 떡볶이 지도",
           "매운 정도별로 정리했다. 순한 맛부터 차례로 먹어 보자.", [("food-tteokbokki", "떡볶이")], "분식", ["떡볶이"])
photo_post(s["chaerim.dev"], "foodie-seoul", "창가 자리 좋은 카페",
           "오후 세 시쯤 햇빛이 제일 예쁘다.", [("food-cafe", "카페")], "카페", ["카페", "성수"])
photo_post(s["jiyoung.lee"], "weekend-trip", "제주 동쪽 한 바퀴",
           "성산에서 시작해서 세화 바다까지.", [("travel-jeju", "제주 바다")], None, ["제주", "바다"])
photo_post(s["jiyoung.lee"], "weekend-trip", "후쿠오카 골목 산책",
           "라멘 먹고 강가를 따라 걸었다.", [("travel-fukuoka", "후쿠오카")], "해외", ["후쿠오카"])
photo_post(s["hyunwoo.choi"], "java-study", "4주차: 트랜잭션 정리",
           "@Transactional은 프록시로 동작한다. 같은 클래스 안에서 부르면 적용되지 않는다.", [("dev-code", "코드")], None, ["스프링"])
photo_post(s["yuna.yoon"], "book-club", "10월의 책장",
           "이번 달에 고른 두 권.", [("book-shelf", "책장"), ("book-coffee", "책과 커피")], None, ["책"])
photo_post(s["haeun.oh"], "cat-diary", "콩이 낮잠",
           "꼬리까지 말고 잔다.", [("cat-nap", "낮잠")], "일상", ["고양이", "콩이"])
photo_post(s["haeun.oh"], "cat-diary", "창밖 구경",
           "새가 지나갈 때마다 귀가 움직인다.", [("cat-window", "창밖")], "일상", ["고양이", "보리"])

# 3. 댓글과 답글 (답글에 다시 답하면 첫 댓글 아래에 붙고 @닉네임이 붙는다)
foodie = post_ids(s["minsu.park"], "foodie-seoul")
cats = post_ids(s["haeun.oh"], "cat-diary")
trip = post_ids(s["jiyoung.lee"], "weekend-trip")
java = post_ids(s["hyunwoo.choi"], "java-study", JAVA_KEY)

if "시장 떡볶이 지도" in foodie:
    pid = foodie["시장 떡볶이 지도"]
    c1 = comment(s["chaerim.dev"], pid, "순한 맛 집 어디예요? 매운 거 잘 못 먹어요")
    if c1:
        c2 = comment(s["donghyun.kang"], pid, "첫 번째 집이요! 어린이용도 있어요", parent=c1)
        if c2:
            comment(s["chaerim.dev"], pid, "감사해요 이번 주말에 가 볼게요", parent=c2)
    comment(s["soyeon.jung"], pid, "지도 너무 좋네요. 공지에 올려도 될까요?")
if "새벽 국밥 한 그릇" in foodie:
    pid = foodie["새벽 국밥 한 그릇"]
    c1 = comment(s["yuna.yoon"], pid, "깍두기 국물 꿀팁 감사합니다")
    if c1:
        comment(s["minsu.park"], pid, "다음엔 같이 가요!", parent=c1)
if "콩이 낮잠" in cats:
    pid = cats["콩이 낮잠"]
    c1 = comment(s["minsu.park"], pid, "꼬리 말고 자는 거 너무 귀여워요")
    if c1:
        comment(s["haeun.oh"], pid, "깨우면 화내요 ㅎㅎ", parent=c1)
    comment(s["jiyoung.lee"], pid, "콩이 사진 더 올려 주세요")
if "제주 동쪽 한 바퀴" in trip:
    pid = trip["제주 동쪽 한 바퀴"]
    c1 = comment(s["donghyun.kang"], pid, "세화 바다 색 진짜 예쁘죠")
    if c1:
        comment(s["jiyoung.lee"], pid, "다음 모임은 서쪽으로 가요", parent=c1)
if "4주차: 트랜잭션 정리" in java:
    comment(s["chaerim.dev"], java["4주차: 트랜잭션 정리"], "self-invocation 예제 덕분에 이해했어요", key=JAVA_KEY)

# 4. 좋아요 (같은 회원이 같은 글에 여러 번 눌러도 한 번만 센다)
likes = [
    ("chaerim.dev", foodie, "새벽 국밥 한 그릇"), ("soyeon.jung", foodie, "새벽 국밥 한 그릇"),
    ("donghyun.kang", foodie, "새벽 국밥 한 그릇"), ("yuna.yoon", foodie, "시장 떡볶이 지도"),
    ("minsu.park", foodie, "시장 떡볶이 지도"), ("haeun.oh", foodie, "창가 자리 좋은 카페"),
    ("minsu.park", cats, "콩이 낮잠"), ("jiyoung.lee", cats, "콩이 낮잠"), ("yuna.yoon", cats, "콩이 낮잠"),
    ("chaerim.dev", cats, "창밖 구경"), ("donghyun.kang", trip, "제주 동쪽 한 바퀴"),
    ("haeun.oh", trip, "후쿠오카 골목 산책"), ("minsu.park", trip, "제주 동쪽 한 바퀴"),
]
for account, posts, title in likes:
    if title in posts:
        like(s[account], posts[title], title)

# 5. 조회수 (같은 사람은 하루 한 번만 센다. 로그인하지 않은 방문자도 센다)
viewed = 0
for posts in (foodie, cats, trip):
    for title, pid in posts.items():
        for account in ("chaerim.dev", "soyeon.jung", "seojun.han", "yuna.yoon"):
            view(s[account], pid)
        view(None, pid)
        viewed += 1
results.append(f"✓ 글 {viewed}개를 회원 4명과 방문자가 읽음 (조회수 반영)")

print("\n".join(results))
if used_fallback:
    print(f"\n※ 사진 폴더({PHOTOS})에 없어 테스트 그림으로 대신한 사진 {len(used_fallback)}장: {', '.join(used_fallback)}")
failed = sum(1 for r in results if r.startswith("✗"))
print(f"\n{len(results) - failed}개 성공, {failed}개 실패")
