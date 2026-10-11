# Creat_ 소스코드

메인블로그 서버(Spring Boot + MySQL)입니다. 문서는 저장소 위쪽의 `docs/`·`specs/`에 따로 있습니다 ([문서 안내](../docs/README.md)).

## 폴더

| 위치 | 내용 |
|---|---|
| `src/main/java` | 서버 코드 |
| `src/main/resources` | 설정(`application.yml`), DB 변경(`db/migration`), 화면(`static/`) |
| `src/test` | 자동 테스트 (MySQL `blog_test` 필요) |
| `scripts/test-data` | 테스트 데이터 넣기 (운영 서버에서는 쓰지 않음) |
| `Dockerfile`, `deploy/deploy.sh` | 도커 이미지, 서버에서 이미지를 띄우는 스크립트 |

`.env`, 사진 폴더 `data/uploads`, 백업 `backups/`도 이 폴더 안에 둡니다. 모두 git에 올라가지 않습니다.

## 실행·빌드

```bash
./gradlew bootRun --args='--spring.profiles.active=local'   # 내 컴퓨터에서 실행
./gradlew test                                              # 자동 테스트
./gradlew bootJar -x test                                   # 배포용 파일 build/libs/mainblog-0.1.0.jar
```

## 서버에 배포할 때

`main`에 들어가면 GitHub Actions가 도커 이미지를 만들어 서버에 띄웁니다: [도커 배포 안내](../docs/05-guides/도커-배포.md). 직접 올릴 때는 아래처럼 합니다.


이 폴더만 서버에 올리거나, 위 jar 파일 하나만 올려 `java -jar mainblog-0.1.0.jar`로 띄웁니다. 운영 환경변수는 [로컬 실행 안내](../docs/05-guides/로컬-실행.md)의 "운영 서버에 꼭 넣을 환경변수" 표를 따르고, 사진 폴더는 `UPLOAD_DIR`로 정합니다. 자세한 계획은 [배포 계획](../docs/05-guides/배포-계획.md).
