#!/usr/bin/env python3
"""
테스트 계정 몇 명이 지정한 블로그에 참여 신청을 한다. 화면에서 [참여 신청하기]를 누른 것과 같다.
사용법: python3 scripts/test-data/join-blog.py <블로그 주소> [일부 공개 블로그의 공유 키] [서버 주소, 기본 http://localhost:8080]
승인제 블로그면 블로그장의 [블로그 관리]에 신청이 쌓이고, 자유 참여면 바로 멤버가 된다.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import importlib.util

if len(sys.argv) < 2:
    raise SystemExit(__doc__)
slug = sys.argv[1]
key = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] else None
base = sys.argv[3] if len(sys.argv) > 3 else "http://localhost:8080"

# activity-3.py의 로그인 처리를 그대로 쓴다 (모듈 이름에 '-'가 있어 파일로 읽음).
spec = importlib.util.spec_from_file_location("activity", os.path.join(os.path.dirname(os.path.abspath(__file__)), "activity-3.py"))
source = open(spec.origin, encoding="utf-8").read().split("# 1. 민수")[0]
module = {"__name__": "activity", "__file__": spec.origin}
sys.argv = [sys.argv[0], base]
exec(compile(source, spec.origin, "exec"), module)
Session = module["Session"]

ACCOUNTS = ["donghyun.kang", "haeun.oh", "hyunwoo.choi", "jiyoung.lee", "yuna.yoon"]
for account in ACCOUNTS:
    s = Session(account)
    status, res = s.request("POST", f"/api/blogs/{slug}/join", {"key": key})
    if status == 200:
        print(f"✓ {account}: {'바로 멤버가 됨' if res.get('joined') else '참여 신청함 (승인 기다림)'}")
    else:
        print(f"✗ {account}: ({status}) {res.get('message', '')}")
