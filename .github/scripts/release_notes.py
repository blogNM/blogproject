"""릴리스 노트를 만든다 (D-97). release.yml 이 부른다.

    python3 release_notes.py <버전 태그> <커밋 SHA> <owner/repo> <출력 폴더>

1) docs/releases/<버전>.md 가 있으면 그 글을 쓴다. 첫 줄이 "# 제목"이면 제목으로 쓴다.
   캡처 사진은 app/src/main/resources/static/img/releases/<버전>/ 에 넣고 글에서 /img/releases/<버전>/파일.png 로 적는다.
   <!-- github-only --> 와 <!-- /github-only --> 사이 글은 깃허브 릴리스에만 넣고 사이트 공지에서는 뺀다 (관리자용 내용 등).
2) 없으면 CHANGELOG.md 에서 지난 버전 뒤에 새로 적힌 줄만 뽑는다 (날짜·묶음 제목은 따라 붙인다).

출력: title.txt, github.md (깃허브 릴리스용, 사진은 깃허브 주소), payload.json (사이트 공지용, 사진은 사이트 주소)
"""
import json
import os
import re
import subprocess
import sys

NOTICE_MAX = 5000  # NoticeService.CONTENT_MAX
TITLE_MAX = 100
# 깃허브 릴리스에만 넣을 글 (사이트 공지에서는 뺀다)
GITHUB_ONLY = re.compile(r"<!--\s*github-only\s*-->.*?<!--\s*/github-only\s*-->", re.S)
GITHUB_ONLY_MARK = re.compile(r"<!--\s*/?github-only\s*-->")


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def previous_tag(tag, sha):
    tags = [t for t in git("tag", "--merged", sha, "--sort=-creatordate").split() if t != tag and t.startswith("v")]
    return tags[0] if tags else None


def from_changelog(tag, sha):
    lines = git("show", f"{sha}:CHANGELOG.md").splitlines()
    start = next((i for i, l in enumerate(lines) if l.startswith("## ")), len(lines))
    prev = previous_tag(tag, sha)
    if prev is None:
        wanted = set(range(start, len(lines)))
    else:
        wanted = set()
        for m in re.finditer(r"^@@ -\S+ \+(\d+)(?:,(\d+))? @@", git("diff", "-U0", prev, sha, "--", "CHANGELOG.md"), re.M):
            first, count = int(m.group(1)), int(m.group(2) or "1")
            wanted.update(range(first - 1, first - 1 + count))
    out, day, group, shown = [], None, None, set()
    for i in range(start, len(lines)):
        line = lines[i]
        if line.startswith("## "):
            day, group = i, None
        elif line.startswith("### "):
            group = i
        if i not in wanted or not line.strip():
            continue
        for h in (day, group):
            if h is not None and h not in shown and h != i:
                out.append(("\n" if out else "") + lines[h] + "\n")
                shown.add(h)
        if line.startswith("#"):
            shown.add(i)
            line = ("\n" if out else "") + line + "\n"
        out.append(line)
    body = re.sub(r"\n{3,}", "\n\n", "\n".join(out)).strip()
    return body or "이번 버전은 작은 정리만 있었습니다."


def main():
    tag, sha, repo, out_dir = sys.argv[1:5]
    title = f"Creat_ {tag} 업데이트 소식"
    path = f"docs/releases/{tag}.md"
    if os.path.exists(path):
        body = open(path, encoding="utf-8").read().strip()
        if body.startswith("# "):
            first, _, body = body.partition("\n")
            title = first[2:].strip()
            body = body.strip()
    else:
        body = from_changelog(tag, sha)
    title = title[:TITLE_MAX]

    blob = f"https://github.com/{repo}/blob/{sha}/"
    raw = f"https://raw.githubusercontent.com/{repo}/{sha}/app/src/main/resources/static"
    release_url = f"https://github.com/{repo}/releases/tag/{tag}"
    # 저장소 안 상대 주소(docs/...)는 깃허브 주소로 바꾼다. 사이트·깃허브 모두에서 열리게
    body = re.sub(r"\]\((?!https?:|mailto:|/|#)([^)]+)\)", lambda m: f"]({blob}{m.group(1)})", body)

    github = re.sub(r"\]\(/img/releases/", f"]({raw}/img/releases/", GITHUB_ONLY_MARK.sub("", body))
    github = re.sub(r"\n{3,}", "\n\n", github).strip()
    site = re.sub(r"\n{3,}", "\n\n", GITHUB_ONLY.sub("", body)).strip()
    more = f"\n\n[전체 변경 내용 보기]({release_url})"
    if len(site) + len(more) > NOTICE_MAX:
        cut = site[: NOTICE_MAX - len(more) - 20]
        site = cut[: cut.rfind("\n")] + "\n\n…(이하 생략)"
    site += more

    os.makedirs(out_dir, exist_ok=True)
    open(os.path.join(out_dir, "title.txt"), "w", encoding="utf-8").write(title)
    open(os.path.join(out_dir, "github.md"), "w", encoding="utf-8").write(github + "\n")
    json.dump({"title": title, "content": site}, open(os.path.join(out_dir, "payload.json"), "w", encoding="utf-8"),
              ensure_ascii=False)
    # 사이트에 사진이 올라가 있는지 서버에서 확인할 주소 목록
    images = sorted(set(re.findall(r"\]\((/img/releases/[^)\s]+)\)", site)))
    open(os.path.join(out_dir, "images.txt"), "w", encoding="utf-8").write(" ".join(images))
    print(title)
    print(f"사이트 공지 {len(site)}자, 사진 {len(images)}장")


if __name__ == "__main__":
    main()
