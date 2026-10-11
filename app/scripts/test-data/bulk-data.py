#!/usr/bin/env python3
"""
대량 데이터로 큰 블로그 사이트처럼 버티는지 본다. 회원·블로그·글·댓글·좋아요를 SQL로 한 번에 넣고, 주요 화면 API의 속도를 잰다.

사용법:
  python3 scripts/test-data/bulk-data.py make  [회원 수, 기본 1000] [글 수, 기본 30000]
  python3 scripts/test-data/bulk-data.py bench [서버 주소, 기본 http://localhost:8080]
  python3 scripts/test-data/bulk-data.py clean

- 넣는 데이터는 이메일 `@bulk.example.com`, 블로그 주소 `bulk-` 로 시작해서 다른 데이터와 섞이지 않는다.
  직접 가입한 계정과 테스트 계정은 바꾸지 않는다. 테스트 계정(@example.com)만 대량 회원 몇 명을 팔로우하고
  대량 블로그 몇 개를 구독해서 피드의 팔로우·구독 탭에 글이 찬다.
- clean은 대량 데이터와 그것에 딸린 행(대량 글에 남이 단 댓글·좋아요, 알림 등)을 모두 지운다. 다시 make 하면 된다.
- 비밀번호는 모두 `Test1234!`. DB 접속은 환경변수 DB_NAME(blog), DB_USERNAME(blog), DB_PASSWORD(blog), DB_HOST(127.0.0.1).
"""
import json
import os
import random
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

BCRYPT = "$2a$10$fMbOdhlmxkas/E3fgYOsXe.VAPCtQI7.PLEr..rSuq5qailHZlzTy"  # Test1234! (seed.sql과 같음)
DOMAIN = "@bulk.example.com"
THEMES = {"ODYSSEY": "#7C0CB4", "POSTER": "#1E1D33", "WHATEVER": "#9C7A54", "LETTER": "#2FA58C",
          "BOOK_CLUB": "#39385B", "MAGAZINE": "#D93A49", "PORTFOLIO": "#FF4F7B"}

TOPICS = {
    "맛집": {"blog": ["{p} 맛집 지도", "{p} 먹방 기록", "오늘 뭐 먹지 {p}"], "cats": ["한식", "카페", "분식", "양식", "술집"],
             "title": ["{p} {f} 맛집 후기", "{p}에서 찾은 {f}", "줄 서서 먹은 {f}", "{f} 맛집 세 곳", "{p} 골목 {f}"],
             "words": ["국밥", "떡볶이", "라멘", "파스타", "커피", "빵집", "냉면", "삼겹살", "디저트", "브런치"],
             "tags": ["맛집", "카페", "서울", "디저트", "혼밥"]},
    "여행": {"blog": ["{p} 여행 일기", "주말엔 {p}", "{p} 한 바퀴"], "cats": ["국내", "해외", "캠핑", "기차"],
             "title": ["{p} {f} 여행 후기", "{p} 1박 2일", "{f} 따라 걷기", "{p}에서 본 {f}", "비 오는 날 {p}"],
             "words": ["바다", "산책", "온천", "야경", "시장", "섬", "캠핑", "기차", "골목", "축제"],
             "tags": ["여행", "제주", "부산", "일본", "캠핑"]},
    "개발": {"blog": ["{p} 개발 노트", "{p} 스터디", "오늘의 {p} 공부"], "cats": ["자바", "스프링", "DB", "프론트"],
             "title": ["{f} 정리", "{f}에서 막힌 점", "{p} 프로젝트 {f}", "{f} 처음 써 보기", "{f} 성능 고치기"],
             "words": ["트랜잭션", "인덱스", "JPA", "스프링 시큐리티", "도커", "리액트", "쿼리", "테스트", "캐시", "JWT"],
             "tags": ["스프링", "자바", "mysql", "개발", "공부"]},
    "책": {"blog": ["{p} 책방", "한 달에 두 권 {p}", "{p} 독서 모임"], "cats": ["소설", "에세이", "인문", "만화"],
            "title": ["{f} 읽고 나서", "이번 달 {f}", "{p} 모임 {f}", "{f} 한 줄 감상", "다시 읽은 {f}"],
            "words": ["장편 소설", "에세이", "시집", "역사책", "그림책", "추리 소설", "자기계발서", "만화책", "여행기", "과학책"],
            "tags": ["책", "독서", "소설", "에세이", "북클럽"]},
    "반려동물": {"blog": ["{p} 고양이 일기", "{p} 댕댕이", "우리 집 {p}"], "cats": ["일상", "간식", "병원", "장난감"],
              "title": ["{f} 하는 {p}", "{p}의 {f}", "오늘의 {f}", "{f} 추천", "{p} {f} 기록"],
              "words": ["낮잠", "산책", "간식", "목욕", "창밖 구경", "장난감", "털갈이", "미용", "병원", "생일"],
              "tags": ["고양이", "강아지", "반려동물", "일상", "간식"]},
    "일상": {"blog": ["{p} 하루", "{p}의 기록", "소소한 {p}"], "cats": ["일기", "운동", "취미", "생각"],
             "title": ["{p} {f}", "오늘의 {f}", "{f} 30일째", "{f} 하다 생긴 일", "주말 {f}"],
             "words": ["운동", "러닝", "요리", "청소", "그림", "사진", "글쓰기", "산책", "음악", "영화"],
             "tags": ["일상", "운동", "기록", "취미", "주말"]},
}
PLACES = ["서울", "을지로", "성수", "망원", "홍대", "부산", "제주", "강릉", "전주", "대구", "광주", "인천", "수원", "경주", "여수"]
NAMES = ["하늘", "바다", "구름", "별빛", "달님", "소나무", "민트", "라떼", "모카", "보리", "콩이", "치즈", "호두", "단풍", "봄비"]


def mysql(sql_path=None, sql=None):
    env = dict(os.environ, MYSQL_PWD=os.environ.get("DB_PASSWORD", "blog"))
    cmd = ["mysql", "--default-character-set=utf8mb4", "-h", os.environ.get("DB_HOST", "127.0.0.1"),
           "-u", os.environ.get("DB_USERNAME", "blog"), "-N", os.environ.get("DB_NAME", "blog")]
    data = open(sql_path, "rb").read() if sql_path else sql.encode()
    res = subprocess.run(cmd, input=data, capture_output=True, env=env)
    if res.returncode != 0:
        raise SystemExit("MySQL 오류: " + res.stderr.decode()[-800:])
    return res.stdout.decode()


def q(s):
    return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'"


class Writer:
    """여러 행을 1000개씩 묶어 INSERT 한다."""

    def __init__(self, f):
        self.f = f

    def insert(self, table, cols, rows, ignore=False):
        for i in range(0, len(rows), 1000):
            chunk = rows[i:i + 1000]
            self.f.write(f"INSERT {'IGNORE ' if ignore else ''}INTO {table} ({', '.join(cols)}) VALUES\n")
            self.f.write(",\n".join("(" + ", ".join(r) + ")" for r in chunk) + ";\n")


def ago(minutes):
    return f"NOW() - INTERVAL {minutes} MINUTE"


def make(n_members, n_posts):
    if mysql(sql=f"SELECT COUNT(*) FROM members WHERE email LIKE '%{DOMAIN}';").strip() != "0":
        raise SystemExit("대량 데이터가 이미 있습니다. 먼저 clean 하세요.")
    rnd = random.Random(42)
    n_blogs = max(10, n_members // 12)
    out = tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False, encoding="utf-8")
    w = Writer(out)
    out.write("SET NAMES utf8mb4;\nSET autocommit = 0;\n")
    out.write("SELECT COALESCE(MAX(id), 0) INTO @m FROM members;\nSELECT COALESCE(MAX(id), 0) INTO @b FROM blogs;\n"
              "SELECT COALESCE(MAX(id), 0) INTO @c FROM categories;\nSELECT COALESCE(MAX(id), 0) INTO @p FROM posts;\n"
              "SELECT COALESCE(MAX(id), 0) INTO @cm FROM comments;\n")

    # 회원
    rows = []
    for i in range(1, n_members + 1):
        joined = rnd.randint(60 * 24 * 30, 60 * 24 * 180)
        rows.append([f"@m + {i}", q(f"bulk{i:04d}{DOMAIN}"), q(BCRYPT), q(f"대량{i:04d}"), q(f"{rnd.choice(NAMES)}{i:04d}"),
                     q("010" + f"{rnd.randint(0, 99999999):08d}"), ago(joined), ago(joined), ago(joined), ago(joined)])
    w.insert("members", ["id", "email", "password_hash", "name", "nickname", "phone", "terms_agreed_at",
                         "privacy_agreed_at", "age14_confirmed_at", "created_at"], rows)

    # 블로그: 앞쪽 회원이 하나씩 만든다. 대부분 공개
    blogs = []
    rows = []
    for i in range(1, n_blogs + 1):
        topic = rnd.choice(list(TOPICS))
        t = TOPICS[topic]
        name = rnd.choice(t["blog"]).format(p=rnd.choice(PLACES + NAMES))[:30]
        vis = "PUBLIC" if i % 16 else ("LINK_ONLY" if i % 32 else "PRIVATE")
        theme = rnd.choice(list(THEMES))
        token = q(f"{rnd.getrandbits(128):032x}") if vis == "LINK_ONLY" else "NULL"
        created = rnd.randint(60 * 24 * 20, 60 * 24 * 150)
        blogs.append({"no": i, "topic": topic, "owner": i, "vis": vis})
        rows.append([f"@b + {i}", q(f"bulk-{i:03d}"), q(name), q(f"{topic} 이야기를 함께 쓰는 블로그"), f"@m + {i}",
                     q(vis), q(rnd.choice(["FREE", "APPROVAL"])), token, q(theme), q(THEMES[theme]),
                     q(rnd.choice(["SANS", "SANS", "SERIF", "MONO"])), ago(created)])
    w.insert("blogs", ["id", "slug", "name", "description", "owner_id", "visibility", "join_policy", "share_token",
                       "theme", "theme_accent", "theme_font", "created_at"], rows)

    # 멤버십: 블로그장 + 멤버 5~40명(부블로그장 1명)
    rows = []
    members_of = {}
    for b in blogs:
        team = {b["owner"]} | set(rnd.sample(range(1, n_members + 1), rnd.randint(5, 40)))
        members_of[b["no"]] = sorted(team)
        sub = rnd.choice([m for m in team if m != b["owner"]])
        for m in team:
            role = "OWNER" if m == b["owner"] else ("SUB_OWNER" if m == sub else "MEMBER")
            perm = "TRUE" if role == "SUB_OWNER" else "FALSE"
            rows.append([f"@b + {b['no']}", f"@m + {m}", q(role), perm, perm, perm,
                         ago(60 * 24 * 10) if role == "SUB_OWNER" else "NULL", ago(rnd.randint(60 * 24 * 15, 60 * 24 * 120))])
    w.insert("blog_memberships", ["blog_id", "member_id", "role", "perm_edit_info", "perm_manage_members",
                                  "perm_manage_posts", "sub_owner_since", "joined_at"], rows)

    # 카테고리
    rows = []
    cats_of = {}
    cid = 0
    for b in blogs:
        names = TOPICS[b["topic"]]["cats"][:rnd.randint(2, 4)]
        cats_of[b["no"]] = []
        for k, name in enumerate(names):
            cid += 1
            cats_of[b["no"]].append(cid)
            rows.append([f"@c + {cid}", f"@b + {b['no']}", q(name), str(k)])
    w.insert("categories", ["id", "blog_id", "name", "sort_order"], rows)

    # 글: 블로그마다 많이 쓰는 곳과 적게 쓰는 곳이 섞이게
    weights = [rnd.paretovariate(1.2) for _ in blogs]
    rows, post_tags, post_info = [], [], []
    for pid in range(1, n_posts + 1):
        b = rnd.choices(blogs, weights)[0]
        t = TOPICS[b["topic"]]
        place, word = rnd.choice(PLACES), rnd.choice(t["words"])
        title = rnd.choice(t["title"]).format(p=place, f=word)[:30]
        body = (f"## {title}\n\n{place}에서 {word} 이야기를 남깁니다. "
                + " ".join(f"{rnd.choice(t['words'])}도 좋았고 {rnd.choice(PLACES)}에도 다시 가 보고 싶다." for _ in range(rnd.randint(2, 8)))
                + f"\n\n- {rnd.choice(t['words'])}\n- {rnd.choice(t['words'])}\n")[:900]
        author = rnd.choice(members_of[b["no"]])
        created = int(rnd.triangular(0, 60 * 24 * 90, 0))
        notice = "TRUE" if rnd.random() < 0.004 else "FALSE"
        cat = rnd.choice(cats_of[b["no"]] + [None])
        rows.append([f"@p + {pid}", f"@b + {b['no']}", f"@m + {author}", f"@c + {cat}" if cat else "NULL", q(title), q(body),
                     notice, str(rnd.randint(0, 40) + int(800 * rnd.random() ** 6)), ago(created), ago(created)])
        for tag in rnd.sample(t["tags"] + [place.lower(), word.replace(" ", "_")], rnd.randint(1, 4)):
            post_tags.append([f"@p + {pid}", q(tag)])
        post_info.append((pid, b["no"], author, created))
    w.insert("posts", ["id", "blog_id", "author_id", "category_id", "title", "content_md", "is_notice", "view_count",
                       "created_at", "updated_at"], rows)
    w.insert("tags", ["name"], [[q(n)] for n in sorted({r[1] for r in post_tags})], ignore=True)
    out.write("CREATE TEMPORARY TABLE bulk_pt (post_id BIGINT, tag_name VARCHAR(20));\n")
    w.insert("bulk_pt", ["post_id", "tag_name"], post_tags)
    out.write("INSERT IGNORE INTO post_tags (post_id, tag_id) SELECT x.post_id, t.id FROM bulk_pt x JOIN tags t ON t.name = x.tag_name;\n")

    # 좋아요: 인기 글에 몰리게
    likes = set()
    pop = [rnd.paretovariate(1.1) for _ in post_info]
    target = n_posts * 3
    for pid in rnd.choices(range(1, n_posts + 1), pop, k=target):
        likes.add((rnd.randint(1, n_members), pid))
    w.insert("post_likes", ["member_id", "post_id", "created_at"],
             [[f"@m + {m}", f"@p + {p}", ago(max(0, post_info[p - 1][3] - rnd.randint(0, 600)))] for m, p in likes])

    # 댓글과 답글
    rows = []
    cmid = 0
    phrases = ["좋은 글 감사해요", "저도 가 봤어요!", "정리가 깔끔하네요", "다음 글도 기대할게요", "사진이 예뻐요",
               "어디인지 궁금해요", "저장해 두고 볼게요", "공감합니다", "이거 진짜 꿀팁이네요", "덕분에 해결했어요"]
    for pid in rnd.choices(range(1, n_posts + 1), pop, k=int(n_posts * 1.2)):
        cmid += 1
        root = cmid
        author = rnd.randint(1, n_members)
        created = max(0, post_info[pid - 1][3] - rnd.randint(1, 900))
        rows.append([f"@cm + {root}", f"@p + {pid}", f"@m + {author}", "NULL", "NULL", q(rnd.choice(phrases)), ago(created)])
        if rnd.random() < 0.35:
            cmid += 1
            rows.append([f"@cm + {cmid}", f"@p + {pid}", f"@m + {post_info[pid - 1][2]}", f"@cm + {root}", f"@m + {author}",
                         q("감사합니다 :)"), ago(max(0, created - 30))])
    w.insert("comments", ["id", "post_id", "author_id", "parent_id", "reply_to_member_id", "content", "created_at"], rows)

    # 팔로우·구독 (대량 회원끼리, 그리고 테스트 계정 → 대량)
    follows = {(rnd.randint(1, n_members), rnd.randint(1, n_members)) for _ in range(n_members * 8)}
    w.insert("follows", ["follower_id", "followee_id"], [[f"@m + {a}", f"@m + {b}"] for a, b in follows if a != b])
    subs = {(rnd.randint(1, n_members), rnd.choice(blogs)["no"]) for _ in range(n_members * 5)}
    w.insert("subscriptions", ["member_id", "blog_id"], [[f"@m + {m}", f"@b + {b}"] for m, b in subs])
    out.write(f"""
INSERT IGNORE INTO follows (follower_id, followee_id)
  SELECT t.id, @m + n.k FROM members t JOIN (SELECT 1 k UNION SELECT 2 UNION SELECT 3 UNION SELECT 5 UNION SELECT 8
    UNION SELECT 13 UNION SELECT 21 UNION SELECT 34 UNION SELECT 55 UNION SELECT 89) n
  WHERE t.email LIKE '%@example.com' AND t.email NOT LIKE '%{DOMAIN}' AND @m + n.k <= @m + {n_members};
INSERT IGNORE INTO subscriptions (member_id, blog_id)
  SELECT t.id, @b + n.k FROM members t JOIN (SELECT 1 k UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 6) n
  WHERE t.email LIKE '%@example.com' AND t.email NOT LIKE '%{DOMAIN}';
""")

    # 숫자 맞추기
    out.write(f"""
UPDATE posts p JOIN (SELECT post_id, COUNT(*) n FROM post_likes WHERE post_id > @p GROUP BY post_id) x ON x.post_id = p.id
  SET p.like_count = x.n, p.view_count = p.view_count + x.n * 6;
UPDATE posts p JOIN (SELECT post_id, COUNT(*) n FROM comments WHERE post_id > @p GROUP BY post_id) x ON x.post_id = p.id
  SET p.comment_count = x.n;
COMMIT;
ANALYZE TABLE members, blogs, posts, comments, post_likes, tags, post_tags, follows, subscriptions;
""")
    out.close()
    size = os.path.getsize(out.name) // 1024
    print(f"SQL {size}KB를 넣는 중... (회원 {n_members}, 블로그 {n_blogs}, 글 {n_posts}, 좋아요 {len(likes)}, 댓글 {cmid})")
    start = time.time()
    mysql(out.name)
    os.unlink(out.name)
    print(f"넣기 끝: {time.time() - start:.1f}초")


def clean():
    sql = f"""
SET NAMES utf8mb4;
DROP TABLE IF EXISTS bulk_m, bulk_b, bulk_p, bulk_c;
CREATE TABLE bulk_m (id BIGINT PRIMARY KEY) AS SELECT id FROM members WHERE email LIKE '%{DOMAIN}';
CREATE TABLE bulk_b (id BIGINT PRIMARY KEY) AS SELECT id FROM blogs WHERE slug LIKE 'bulk-%';
CREATE TABLE bulk_p (id BIGINT PRIMARY KEY) AS SELECT id FROM posts
  WHERE blog_id IN (SELECT id FROM bulk_b) OR author_id IN (SELECT id FROM bulk_m);
CREATE TABLE bulk_c (id BIGINT PRIMARY KEY) AS SELECT id FROM comments
  WHERE post_id IN (SELECT id FROM bulk_p) OR author_id IN (SELECT id FROM bulk_m);
-- 남의 글에 대량 회원이 단 댓글에 붙은 답글도 함께 (답글은 한 단계뿐)
INSERT IGNORE INTO bulk_c SELECT c.id FROM comments c JOIN (SELECT id FROM bulk_c) x ON c.parent_id = x.id;
SELECT COUNT(*) FROM bulk_m; SELECT COUNT(*) FROM bulk_b; SELECT COUNT(*) FROM bulk_p;
"""
    fks = mysql(sql=f"""SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE
        WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IN ('members', 'blogs', 'posts', 'comments');""")
    sets = {"members": "bulk_m", "blogs": "bulk_b", "posts": "bulk_p", "comments": "bulk_c"}
    sql += "SET FOREIGN_KEY_CHECKS = 0;\n"
    for line in fks.strip().splitlines():
        table, col, ref = line.split("\t")
        if table in sets:  # 본 행은 마지막에 지운다
            continue
        sql += f"DELETE FROM {table} WHERE {col} IN (SELECT id FROM {sets[ref]});\n"
    # 대량 글·댓글에 딸린 알림 (대상 번호로만 이어져 있음)
    sql += """DELETE FROM notifications WHERE target_type = 'POST' AND target_id IN (SELECT id FROM bulk_p);
DELETE FROM notifications WHERE target_type = 'COMMENT' AND target_id IN (SELECT id FROM bulk_c);
DELETE FROM comments WHERE id IN (SELECT id FROM bulk_c);
DELETE FROM posts WHERE id IN (SELECT id FROM bulk_p);
DELETE FROM categories WHERE blog_id IN (SELECT id FROM bulk_b);
DELETE FROM blogs WHERE id IN (SELECT id FROM bulk_b);
DELETE FROM members WHERE id IN (SELECT id FROM bulk_m);
SET FOREIGN_KEY_CHECKS = 1;
DROP TABLE bulk_m, bulk_b, bulk_p, bulk_c;
UPDATE posts p SET comment_count = (SELECT COUNT(*) FROM comments c WHERE c.post_id = p.id AND c.status = 'NORMAL'),
  like_count = (SELECT COUNT(*) FROM post_likes l WHERE l.post_id = p.id);
DELETE FROM tags WHERE id NOT IN (SELECT tag_id FROM post_tags) AND id NOT IN (SELECT tag_id FROM blog_tags);
"""
    counts = mysql(sql=sql).split()
    print(f"지움: 회원 {counts[0]}, 블로그 {counts[1]}, 글 {counts[2]}")


def bench(base):
    import http.cookiejar
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

    def call(method, path, body=None):
        headers = {"Accept": "application/json"}
        if method != "GET":
            headers["X-XSRF-TOKEN"] = next((c.value for c in jar if c.name == "XSRF-TOKEN"), "")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body else None, headers=headers, method=method)
        start = time.perf_counter()
        try:
            with opener.open(req) as res:
                data = res.read()
                status = res.status
        except urllib.error.HTTPError as e:
            data, status = e.read(), e.code
        return status, (time.perf_counter() - start) * 1000, data

    anon = [
        ("메인 피드 최신", "/api/feed?tab=LATEST"), ("메인 피드 인기", "/api/feed?tab=POPULAR"),
        ("메인 피드 10쪽", "/api/feed?tab=LATEST&page=10"), ("블로그 목록 인기순", "/api/blogs?sort=popular"),
        ("검색 '서울' 글", "/api/search?q=" + urllib.parse.quote("서울")),
        ("검색 '맛집' 인기순", "/api/search?q=" + urllib.parse.quote("맛집") + "&sort=POPULAR"),
        ("검색 '스프링' 최신순", "/api/search?q=" + urllib.parse.quote("스프링") + "&sort=LATEST"),
        ("검색 '고양이' 태그", "/api/search?q=" + urllib.parse.quote("고양이") + "&type=TAG"),
        ("검색 '하늘' 닉네임", "/api/search?q=" + urllib.parse.quote("하늘") + "&type=NICKNAME"),
        ("검색 '여행' 블로그", "/api/search?q=" + urllib.parse.quote("여행") + "&type=BLOG"),
        ("큰 블로그 글 목록 1쪽", "/api/blogs/{big}/posts"), ("큰 블로그 글 목록 마지막 쪽", "/api/blogs/{big}/posts?page={last}"),
        ("인기 글 상세", "/api/posts/{hot}"), ("인기 글 댓글", "/api/posts/{hot}/comments"),
    ]
    _, _, data = call("GET", "/api/csrf")
    biggest = mysql(sql="""SELECT b.slug, COUNT(*) n FROM posts p JOIN blogs b ON b.id = p.blog_id
        WHERE b.slug LIKE 'bulk-%' AND b.visibility = 'PUBLIC' GROUP BY b.id ORDER BY n DESC LIMIT 1;""").split()
    hot = mysql(sql="""SELECT p.id FROM posts p JOIN blogs b ON b.id = p.blog_id
        WHERE b.visibility = 'PUBLIC' AND p.status = 'NORMAL' ORDER BY p.comment_count DESC LIMIT 1;""").strip()
    big, n = (biggest[0], int(biggest[1])) if biggest else ("foodie-seoul", 10)
    last = max(1, (n + 9) // 10)
    print(f"가장 큰 블로그: {big} (글 {n}개), 댓글 가장 많은 글: {hot}\n")
    print(f"{'화면':<22} {'가운데값':>8} {'가장 느림':>8}  상태")

    def run(label, path, rounds=7):
        path = path.format(big=big, last=last, hot=hot)
        times, statuses = [], set()
        call("GET", path)  # 한 번 데우기
        for _ in range(rounds):
            status, ms, _ = call("GET", path)
            times.append(ms)
            statuses.add(status)
        flag = "  ← 1초 넘음" if statistics.median(times) > 1000 else ""
        print(f"{label:<22} {statistics.median(times):7.0f}ms {max(times):7.0f}ms  {','.join(map(str, sorted(statuses)))}{flag}")

    for label, path in anon:
        run(label, path)
    status, _, _ = call("POST", "/api/auth/login", {"email": "minsu.park@example.com", "password": "Test1234!", "rememberMe": False})
    if status == 200:
        print("\n(minsu.park 로그인)")
        run("메인 피드 팔로우", "/api/feed?tab=FOLLOWING")
        run("메인 피드 구독", "/api/feed?tab=SUBSCRIBED")
        run("검색 '서울' (로그인)", "/api/search?q=" + urllib.parse.quote("서울"))


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "make":
        make(int(sys.argv[2]) if len(sys.argv) > 2 else 1000, int(sys.argv[3]) if len(sys.argv) > 3 else 30000)
    elif mode == "clean":
        clean()
    elif mode == "bench":
        bench((sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8080").rstrip("/"))
    else:
        raise SystemExit(__doc__)
