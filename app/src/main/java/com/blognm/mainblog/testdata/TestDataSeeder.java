package com.blognm.mainblog.testdata;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.blognm.mainblog.blog.Blog;
import com.blognm.mainblog.blog.BlogLifecycleService;
import com.blognm.mainblog.blog.BlogMembership;
import com.blognm.mainblog.blog.BlogService;
import com.blognm.mainblog.blog.SanctionReason;
import com.blognm.mainblog.blog.SanctionService;
import com.blognm.mainblog.comment.CommentService;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.image.Image;
import com.blognm.mainblog.image.ImageService;
import com.blognm.mainblog.linkpreview.LinkPreviewService;
import com.blognm.mainblog.member.MemberRules;
import com.blognm.mainblog.notification.NotificationService;
import com.blognm.mainblog.post.CategoryService;
import com.blognm.mainblog.post.MarkdownRenderer;
import com.blognm.mainblog.post.PostRules;
import com.blognm.mainblog.post.PostService;
import com.blognm.mainblog.post.ReactionService;
import com.blognm.mainblog.report.ReportService;
import com.blognm.mainblog.social.BlockService;
import com.blognm.mainblog.social.FollowService;
import com.blognm.mainblog.social.ProfileService;

/**
 * 실제 사이트에 테스트 회원 8명과 그 활동을 넣는다 (D-113).
 *
 * 회원 만들기만 SQL로 한다(가입은 메일 인증번호가 필요해서). 블로그·글·사진·댓글·좋아요·팔로우·구독·신고·제재는
 * 화면이 부르는 것과 같은 서비스를 불러서 만든다. 그래서 글자 수·사진 검사(SEC-08)·권한·알림·숫자 세기가 실제와 같다.
 * 사진은 실제 올리기와 같은 저장소(UPLOAD_DIR, 서버에서는 data/uploads)에 들어간다.
 * 날짜를 몇 주에 걸쳐 흩어 놓는 일과 블로그 통계용 방문 기록(D-112)만 SQL로 넣는다.
 *
 * 두 번 돌려도 더 생기지 않는다: 테스트 회원 1번이 이미 있으면 비밀번호만 새 값으로 바꾸고 끝낸다.
 * 넣는 중에 실패하면 DB는 모두 되돌린다(한 트랜잭션).
 */
@Service
public class TestDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(TestDataSeeder.class);

    /** 메일이 실제로 가지 않는 주소. 테스트 회원 이메일은 모두 test번호@test.creat */
    public static final String EMAIL_DOMAIN = "test.creat";

    /** 블로그 통계에 남길 방문 기록 기간(일) */
    static final int VISIT_DAYS = 42;

    public record Account(int no, String nickname, String name, String bio, String photo) {

        public String email() {
            return "test" + no + "@" + EMAIL_DOMAIN;
        }

        String phone() {
            return "0100000000" + no;
        }
    }

    public static final List<Account> ACCOUNTS = List.of(
            new Account(1, "테스트채림", "테스트일", "카페 노트 블로그장. 라떼와 창가 자리를 좋아해요.", "profile-1.png"),
            new Account(2, "테스트민수", "테스트이", "주말마다 어디든 가는 여행 기록 블로그장.", "profile-2.png"),
            new Account(3, "테스트지영", "테스트삼", "개발 공부방(일부 공개)을 운영해요.", "profile-3.png"),
            new Account(4, "테스트현우", "테스트사", "밤에만 쓰는 비공개 일기장.", "profile-4.png"),
            new Account(5, "테스트소연", "테스트오", "고양이와 책 블로그장, 카페 노트 부블로그장.", "profile-5.png"),
            new Account(6, "테스트동현", "테스트육", "여행 기록 부블로그장(멤버 관리). 좋아요 알림은 꺼 둠.", "profile-6.png"),
            new Account(7, "테스트유나", "테스트칠", "개발 공부방 부블로그장. 블로그장 위임을 받는 중.", "profile-7.png"),
            new Account(8, "테스트하은", "테스트팔", "여기저기 참여 신청하는 회원. 좋아요 한 글은 나만 봐요.", "profile-8.png"));

    public static final String CAFE = "test-cafe-note";
    public static final String TRIP = "test-weekend-trip";
    public static final String DEV = "test-dev-room";
    public static final String DIARY = "test-night-diary";
    public static final String BOOKS = "test-cat-books";
    public static final List<String> SLUGS = List.of(CAFE, TRIP, DEV, DIARY, BOOKS);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final BlogService blogs;
    private final BlogLifecycleService lifecycle;
    private final SanctionService sanctions;
    private final CategoryService categories;
    private final PostService posts;
    private final CommentService comments;
    private final ReactionService reactions;
    private final FollowService follows;
    private final BlockService blocks;
    private final ProfileService profiles;
    private final NotificationService notificationSettings;
    private final ReportService reports;
    private final ImageService images;
    private final LinkPreviewService linkPreviews;
    private final MarkdownRenderer markdown;
    private final Clock clock;

    /** 역할을 SQL로 바꾸는 서비스가 있어, 한 트랜잭션 안에서 앞서 읽어 둔 멤버십이 낡지 않게 비운다. */
    @PersistenceContext
    private EntityManager entityManager;

    public TestDataSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, PlatformTransactionManager txManager,
            BlogService blogs, BlogLifecycleService lifecycle, SanctionService sanctions, CategoryService categories,
            PostService posts, CommentService comments, ReactionService reactions, FollowService follows,
            BlockService blocks, ProfileService profiles, NotificationService notificationSettings,
            ReportService reports, ImageService images, LinkPreviewService linkPreviews, MarkdownRenderer markdown,
            Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.tx = new TransactionTemplate(txManager);
        this.blogs = blogs;
        this.lifecycle = lifecycle;
        this.sanctions = sanctions;
        this.categories = categories;
        this.posts = posts;
        this.comments = comments;
        this.reactions = reactions;
        this.follows = follows;
        this.blocks = blocks;
        this.profiles = profiles;
        this.notificationSettings = notificationSettings;
        this.reports = reports;
        this.images = images;
        this.linkPreviews = linkPreviews;
        this.markdown = markdown;
        this.clock = clock;
    }

    /** created가 false면 이미 넣어 둔 상태라 비밀번호만 바꿨다는 뜻. */
    public record Result(boolean created, int members, int blogs, int posts, int comments, int visits) {
    }

    public Result load(String password, boolean collectLinkPreviews) {
        try {
            MemberRules.checkPassword(password);
        } catch (ApiException e) {
            throw new IllegalStateException("TEST_MEMBER_PASSWORD가 비었거나 비밀번호 규칙(8~15자, 영문·숫자·특수문자)에 맞지 않습니다.");
        }
        checkNoRealConflicts();
        String hash = passwordEncoder.encode(password);
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE email = ? AND is_test = TRUE",
                Integer.class, ACCOUNTS.get(0).email());
        if (existing != null && existing > 0) {
            int updated = 0;
            for (Account a : ACCOUNTS) {
                updated += jdbc.update("UPDATE members SET password_hash = ? WHERE email = ? AND is_test = TRUE",
                        hash, a.email());
            }
            log.info("테스트 데이터가 이미 있습니다. 테스트 회원 {}명의 비밀번호만 새 값으로 바꿨습니다.", updated);
            return new Result(false, updated, 0, 0, 0, 0);
        }
        Scenario s = tx.execute(status -> new Scenario(hash).run());
        if (collectLinkPreviews) {
            // 글 저장 화면처럼 트랜잭션 밖에서 링크 미리보기를 받는다. 바깥 사이트가 늦어도 넣은 데이터에는 영향이 없다
            s.written.forEach(w -> linkPreviews.collect(markdown.toSafeHtml(w.content()), w.authorId()));
        }
        Result r = new Result(true, ACCOUNTS.size(), SLUGS.size(), s.written.size(), s.commentCount, s.visitCount);
        log.info("테스트 데이터를 넣었습니다: {}", r);
        return r;
    }

    /** 실제 회원이 이미 쓰는 이메일·닉네임이나, 실제 회원의 블로그 주소와 겹치면 아무것도 하지 않고 멈춘다. */
    private void checkNoRealConflicts() {
        for (Account a : ACCOUNTS) {
            Integer real = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM members WHERE (email = ? OR nickname = ?) AND is_test = FALSE",
                    Integer.class, a.email(), a.nickname());
            if (real != null && real > 0) {
                throw new IllegalStateException("실제 회원이 이미 쓰는 이메일 또는 닉네임입니다: " + a.email() + " / " + a.nickname());
            }
        }
        for (String slug : SLUGS) {
            Integer real = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM blogs b JOIN members m ON m.id = b.owner_id WHERE b.slug = ? AND m.is_test = FALSE
                    """, Integer.class, slug);
            if (real != null && real > 0) {
                throw new IllegalStateException("실제 회원의 블로그가 이미 쓰는 주소입니다: /blog/" + slug);
            }
        }
    }

    // ----- 넣는 순서 -----

    private record Written(long postId, long authorId, String content, int daysAgo) {
    }

    private final class Scenario {

        private final String hash;
        private final LocalDateTime now = LocalDateTime.now(clock);
        private final Map<Integer, Long> member = new LinkedHashMap<>();
        private final Map<String, Long> post = new LinkedHashMap<>();
        private final List<Written> written = new ArrayList<>();
        private final Map<Long, Integer> commentDays = new LinkedHashMap<>();
        private String devKey;
        private int commentCount;
        private int visitCount;

        Scenario(String hash) {
            this.hash = hash;
        }

        long m(int no) {
            return member.get(no);
        }

        Scenario run() {
            createMembers();
            createBlogs();
            joinBlogs();
            entityManager.flush();
            entityManager.clear();
            writePosts();
            socialize();
            reportAndSanction();
            finishSettings();
            backdate();
            visitCount = insertVisits();
            return this;
        }

        // 1. 회원. 가입은 메일 인증번호가 필요해 SQL로 만들고 is_test를 켠다. 프로필 사진은 실제 올리기로
        void createMembers() {
            int i = 0;
            for (Account a : ACCOUNTS) {
                LocalDateTime joined = now.minusDays(56 - i++);
                jdbc.update("""
                        INSERT INTO members (email, password_hash, name, nickname, phone, status, is_test,
                                             terms_agreed_at, privacy_agreed_at, age14_confirmed_at, created_at)
                        VALUES (?, ?, ?, ?, ?, 'ACTIVE', TRUE, ?, ?, ?, ?)
                        """, a.email(), hash, a.name(), a.nickname(), a.phone(), joined, joined, joined, joined);
                long id = jdbc.queryForObject("SELECT id FROM members WHERE email = ?", Long.class, a.email());
                member.put(a.no(), id);
                profiles.updateBio(id, a.bio());
                if (a.no() != 8) {
                    profiles.changeImage(id, upload(id, a.photo()).getId());
                }
            }
        }

        // 2. 블로그 다섯 개. 테마 틀·글꼴·강조 색과 공개 범위·참여 방식을 서로 다르게 (D-89, D-49)
        void createBlogs() {
            blogs.create(m(1), new BlogService.CreateRequest(CAFE, "카페 노트", "테스트 회원들이 다녀온 카페와 홈카페 기록입니다.",
                    Blog.Visibility.PUBLIC, Blog.JoinPolicy.FREE, List.of("카페", "커피", "테스트"),
                    new BlogService.ThemeRequest(Blog.Theme.MAGAZINE, "#A0522D", Blog.Font.SERIF, "오늘의 한 잔",
                            "테스트 계정이 쓰는 카페 매거진")));
            blogs.create(m(2), new BlogService.CreateRequest(TRIP, "주말 여행 기록", "주말마다 떠난 곳. 참여는 승인제예요.",
                    Blog.Visibility.PUBLIC, Blog.JoinPolicy.APPROVAL, List.of("여행", "주말", "테스트"),
                    new BlogService.ThemeRequest(Blog.Theme.WHATEVER, "#1F7A8C", Blog.Font.SANS, "어디든 좋아",
                            "가까운 바다부터 먼 산까지")));
            blogs.create(m(3), new BlogService.CreateRequest(DEV, "개발 공부방", "공유 링크를 받은 사람만 보는 스터디 블로그.",
                    Blog.Visibility.LINK_ONLY, Blog.JoinPolicy.APPROVAL, List.of("자바", "스프링", "테스트"),
                    new BlogService.ThemeRequest(Blog.Theme.LETTER, "#39385B", Blog.Font.MONO, null, null)));
            // 일기장은 공개로 만들어 멤버·구독자를 받은 뒤 비공개로 바꾼다 (비공개 전환 알림, D-37)
            blogs.create(m(4), new BlogService.CreateRequest(DIARY, "밤의 일기", "멤버만 보는 일기장.",
                    Blog.Visibility.PUBLIC, Blog.JoinPolicy.FREE, List.of("일기", "테스트"),
                    new BlogService.ThemeRequest(Blog.Theme.BOOK_CLUB, "#4B3F72", Blog.Font.SERIF, "밤의 일기",
                            null)));
            blogs.create(m(5), new BlogService.CreateRequest(BOOKS, "고양이와 책", "고양이가 고른 책 이야기.",
                    Blog.Visibility.PUBLIC, Blog.JoinPolicy.FREE, List.of("책", "고양이", "테스트"),
                    new BlogService.ThemeRequest(Blog.Theme.POSTER, "#C2304A", Blog.Font.SANS, "책 읽는 고양이",
                            "이번 달에 고른 책")));
            devKey = jdbc.queryForObject("SELECT share_token FROM blogs WHERE slug = ?", String.class, DEV);
            blogs.changeCover(CAFE, upload(m(1), "cafe-window.png").getId());
            blogs.changeCover(TRIP, upload(m(2), "trip-mountain.webp").getId());
            blogs.changeCover(DIARY, upload(m(4), "diary-moon.jpg").getId());
            for (String name : List.of("카페 리뷰", "홈카페", "안내")) {
                categories.add(CAFE, name);
            }
            for (String name : List.of("국내", "산", "준비물")) {
                categories.add(TRIP, name);
            }
            for (String name : List.of("자바", "스프링", "리뷰")) {
                categories.add(DEV, name);
            }
            categories.add(BOOKS, "이번 달 책");
            categories.add(BOOKS, "책장");
        }

        // 3. 참여: 자유 참여는 바로, 승인제는 신청 → 승인·거절·대기. 부블로그장은 권한을 서로 다르게 (D-71)
        void joinBlogs() {
            blogs.join(CAFE, null, m(5));
            blogs.join(CAFE, null, m(6));
            blogs.join(CAFE, null, m(8));
            sanctions.setRole(CAFE, m(1), m(5), BlogMembership.Role.SUB_OWNER,
                    new SanctionService.Permissions(true, true, true));

            blogs.join(TRIP, null, m(6));
            blogs.join(TRIP, null, m(5));
            decide(TRIP, 6, m(2), true);
            decide(TRIP, 5, m(2), true);
            sanctions.setRole(TRIP, m(2), m(6), BlogMembership.Role.SUB_OWNER,
                    new SanctionService.Permissions(false, true, false));
            blogs.join(TRIP, null, m(8));
            decide(TRIP, 8, m(6), false);
            blogs.join(TRIP, null, m(7));

            blogs.join(DEV, devKey, m(7));
            decide(DEV, 7, m(3), true);
            sanctions.setRole(DEV, m(3), m(7), BlogMembership.Role.SUB_OWNER,
                    new SanctionService.Permissions(true, false, true));
            blogs.join(DEV, devKey, m(8));

            blogs.join(DIARY, null, m(8));
            blogs.join(BOOKS, null, m(1));
            blogs.join(BOOKS, null, m(2));
        }

        void decide(String slug, int applicant, long actorId, boolean approve) {
            long requestId = jdbc.queryForObject("""
                    SELECT r.id FROM join_requests r JOIN blogs b ON b.id = r.blog_id
                    WHERE b.slug = ? AND r.member_id = ? AND r.status = 'PENDING'
                    """, Long.class, slug, m(applicant));
            blogs.decide(slug, requestId, actorId, approve);
        }

        // 4. 글. 사진·링크 카드·영상 링크·체크박스·글자 크기·색·형광펜·표·코드·공지·태그·카테고리, 본문 최대 글자 수
        void writePosts() {
            long cafeReview = category(CAFE, "카페 리뷰");
            long homeCafe = category(CAFE, "홈카페");
            long cafeNotice = category(CAFE, "안내");
            write("cafe-notice", CAFE, 1, "카페 노트 이용 안내", """
                    카페 노트는 **테스트 계정**이 기능을 확인하려고 쓰는 블로그예요.

                    - [x] 카페에 다녀오면 사진과 함께 글 쓰기
                    - [x] 카테고리 고르기 (카페 리뷰·홈카페)
                    - [ ] 태그는 다섯 개까지만

                    <span class="ts-large">궁금한 점은 댓글로 남겨 주세요.</span>""", cafeNotice, List.of("공지", "테스트"), true, 20);
            Image latte = upload(m(1), "cafe-latte.jpg");
            Image window = upload(m(1), "cafe-window.png");
            write("cafe-latte", CAFE, 1, "성수동 라떼 맛집 세 곳", """
                    ## 첫 번째 카페
                    ![라떼 아트](%s)
                    우유 거품이 <span class="tc-mauve">아주 부드러운</span> 곳. <span class="hl-yellow">창가 자리 추천!</span>

                    ## 두 번째 카페
                    ![창가 자리](%s)
                    <span class="ts-small">평일 오후에는 조용해요.</span>

                    | 카페 | 라떼 | 자리 |
                    |---|---|---|
                    | 첫 번째 | 5,500원 | 넓음 |
                    | 두 번째 | 6,000원 | 창가 |

                    ~~세 번째 카페는 문을 닫았어요.~~""".formatted(latte.url(), window.url()), cafeReview,
                    List.of("카페", "라떼", "성수동"), false, 3);
            write("cafe-drip", CAFE, 5, "홈카페 드립 기록", """
                    집에서 내린 드립 커피 기록이에요. 참고한 영상:

                    https://www.youtube.com/watch?v=jNQXAC9IVRw

                    1. 원두 15g
                    2. 물 250ml, 92도
                    3. 뜸 들이기 30초

                    <span class="ts-xlarge">맛있어요!</span>""", homeCafe, List.of("홈카페", "드립"), false, 2);
            write("cafe-rain", CAFE, 8, "비 오는 날의 창가 자리", """
                    비가 와서 <span class="tc-blue">창밖이 파랗게</span> 보였어요.
                    오늘은 따뜻한 라떼 한 잔.""", cafeReview, List.of("카페", "비"), false, 1);
            write("cafe-long", CAFE, 6, "본문 3000자 꽉 채운 글", longBody(), homeCafe, List.of("최대글자", "테스트"),
                    false, 9);

            long domestic = category(TRIP, "국내");
            long mountain = category(TRIP, "산");
            long packing = category(TRIP, "준비물");
            Image sea = upload(m(2), "trip-sea.jpg");
            write("trip-sea", TRIP, 2, "제주 동쪽 바다 한 바퀴", """
                    ![제주 바다](%s)

                    성산에서 시작해 해안 도로를 따라 걸었어요. 같이 본 영상:

                    https://vimeo.com/1084537

                    | 시간 | 장소 |
                    |---|---|
                    | 09:00 | 성산 |
                    | 13:00 | 세화 해변 |""".formatted(sea.url()), domestic, List.of("제주", "바다", "여행"), false, 6);
            Image peak = upload(m(2), "trip-mountain.webp");
            write("trip-mountain", TRIP, 2, "설악산 단풍 산행", """
                    ![설악산](%s)

                    - [x] 등산화
                    - [x] 물 1리터
                    - [ ] 보조 배터리 (깜빡함)

                    <span class="hl-green">단풍이 절정이었어요.</span>""".formatted(peak.url()), mountain,
                    List.of("등산", "단풍"), false, 12);
            write("trip-pack", TRIP, 6, "여행 짐 싸기 체크리스트", """
                    1박 2일 기준이에요.

                    - [ ] 칫솔·치약
                    - [ ] 충전기
                    - [x] 신분증
                    - [x] 우산""", packing, List.of("준비물", "여행"), false, 4);
            write("trip-busan", TRIP, 5, "부산 1박 2일 코스", """
                    부산에서 간 곳을 정리했어요. 미리 읽어 본 글:

                    https://en.wikipedia.org/wiki/Busan

                    https://www.visitbusan.net/index.do?menuCd=DOM_000000000000000000

                    둘째 날은 <span class="tc-orange">해운대</span>에서 쉬었어요.""", domestic, List.of("부산", "여행"),
                    false, 0);

            long java = category(DEV, "자바");
            long spring = category(DEV, "스프링");
            long review = category(DEV, "리뷰");
            Image code = upload(m(3), "dev-code.png");
            write("dev-record", DEV, 3, "자바 레코드 정리", """
                    ![코드 화면](%s)

                    ```java
                    public record Point(int x, int y) {
                    }
                    ```

                    https://openjdk.org/jeps/395""".formatted(code.url()), java, List.of("자바", "레코드"), false, 15);
            write("dev-rules", DEV, 7, "스터디 규칙", """
                    - [x] 매주 글 하나
                    - [x] 서로 리뷰 댓글 달기
                    - [ ] 빠지면 다음 주에 두 개""", review, List.of("공지"), true, 14);
            write("dev-tx", DEV, 7, "스프링 트랜잭션 메모", """
                    `@Transactional`은 프록시로 동작해요. 같은 클래스 안에서 부르면 적용되지 않아요.

                    https://github.com/spring-projects/spring-framework

                    https://www.youtube.com/watch?v=aqz-KE-bpKQ""", spring, List.of("스프링", "트랜잭션"), false, 5);
            write("dev-review", DEV, 3, "코드 리뷰 체크리스트", """
                    - [ ] 이름이 하는 일을 말하는가
                    - [ ] 테스트가 있는가
                    - [x] 로그에 개인정보가 없는가""", review, List.of("리뷰"), false, 1);

            Image moon = upload(m(4), "diary-moon.jpg");
            write("diary-moon", DIARY, 4, "달 보며 쓴 일기", """
                    ![밤하늘](%s)

                    오늘은 달이 <span class="ts-large">아주 컸다</span>.""".formatted(moon.url()), null, List.of("일기"),
                    false, 8);
            write("diary-three", DIARY, 8, "오늘의 세 줄", """
                    1. 일찍 일어났다
                    2. 산책했다
                    3. 일기를 썼다""", null, List.of("일기", "세줄"), false, 2);

            long monthly = category(BOOKS, "이번 달 책");
            long shelf = category(BOOKS, "책장");
            Image cat = upload(m(5), "loading.gif");
            write("books-month", BOOKS, 5, "고양이가 고른 이번 달 책", """
                    ![돌아가는 그림](%s)

                    무료로 읽을 수 있는 고전이 많은 곳:

                    https://www.gutenberg.org/""".formatted(cat.url()), monthly, List.of("책", "고양이"), false, 7);
            write("books-shelf", BOOKS, 1, "책장 정리하는 법", """
                    **크기별로** 꽂으면 보기 좋고, *주제별로* 꽂으면 찾기 쉬워요.

                    > 다 읽은 책은 아래 칸으로.""", shelf, List.of("책장", "정리"), false, 3);
            write("books-prince", BOOKS, 5, "읽는 중: 어린 왕자", """
                    다시 읽어도 좋은 책.

                    https://www.youtube.com/watch?v=jNQXAC9IVRw""", monthly, List.of("책", "어린왕자"), false, 0);
        }

        long category(String slug, String name) {
            return jdbc.queryForObject(
                    "SELECT c.id FROM categories c JOIN blogs b ON b.id = c.blog_id WHERE b.slug = ? AND c.name = ?",
                    Long.class, slug, name);
        }

        void write(String key, String slug, int author, String title, String content, Long categoryId,
                List<String> tags, boolean notice, int daysAgo) {
            long id = posts.write(slug, m(author),
                    new PostService.WriteRequest(title, content, categoryId, tags, notice));
            post.put(key, id);
            written.add(new Written(id, m(author), content, daysAgo));
        }

        /** 글자 수를 세는 규칙(PostRules.contentLength)으로 딱 최대 글자 수(공백 포함 3000자, D-121)인 본문. */
        String longBody() {
            String unit = "테스트 계정이 본문 글자 수 끝까지 써 보는 글입니다. 글자 수는 사진을 빼고 셉니다. ";
            StringBuilder sb = new StringBuilder("## 최대 글자 수 확인\n");
            while (PostRules.contentLength(sb.toString()) < PostRules.CONTENT_MAX) {
                sb.append(unit);
            }
            String text = sb.toString();
            int cut = text.offsetByCodePoints(0, PostRules.CONTENT_MAX);
            String body = text.substring(0, cut).stripTrailing();
            while (PostRules.contentLength(body) < PostRules.CONTENT_MAX) {
                body = body + ".";
            }
            if (PostRules.contentLength(body) != PostRules.CONTENT_MAX) {
                throw new IllegalStateException("최대 글자 수 글을 만들지 못했습니다.");
            }
            return body;
        }

        // 5. 댓글·답글, 좋아요, 조회, 팔로우, 구독
        void socialize() {
            long c1 = comment("cafe-latte", 5, "여기 저도 가 봤어요! 창가 자리 최고", null, 2);
            comment("cafe-latte", 1, "다음에 같이 가요.", c1, 2);
            long abusive = comment("cafe-latte", 8, "라떼보다 아메리카노가 낫던데요ㅋㅋ 별로예요", null, 1);
            comment("cafe-latte", 6, "@테스트하은 말이 좀 심해요.", abusive, 1);
            comment("cafe-drip", 1, "물 온도 참고할게요.", null, 1);
            comment("cafe-long", 2, "정말 3000자 꽉 찼네요.", null, 8);
            long t1 = comment("trip-sea", 6, "세화 해변 정말 예쁘죠.", null, 5);
            long t2 = comment("trip-sea", 2, "다음엔 우도도 가 보려고요.", t1, 5);
            comment("trip-sea", 5, "우도 땅콩 아이스크림 꼭 드세요.", t2, 4);
            comment("trip-busan", 2, "해운대 좋죠!", null, 0);
            long d1 = comment("dev-record", 7, "compact 생성자도 같이 정리하면 좋겠어요.", null, 14);
            comment("dev-record", 3, "다음 글에 쓸게요.", d1, 13);
            comment("dev-tx", 3, "self-invocation 예시 좋아요.", null, 4);
            long b1 = comment("books-month", 1, "고양이 그림 귀여워요.", null, 6);
            comment("books-month", 5, "고마워요!", b1, 6);
            comment("books-shelf", 2, "주제별로 꽂는 편이에요.", null, 2);
            comment("diary-moon", 8, "저도 어제 달 봤어요.", null, 7);

            like(2, "cafe-latte", "cafe-drip", "trip-sea", "books-month", "books-shelf");
            like(3, "cafe-latte", "dev-tx", "trip-mountain");
            like(5, "cafe-latte", "trip-sea", "trip-mountain", "cafe-rain");
            like(6, "cafe-latte", "trip-sea", "cafe-drip", "trip-busan");
            like(7, "dev-record", "cafe-latte", "books-prince");
            like(8, "cafe-drip", "trip-sea", "books-month", "diary-moon");
            like(1, "trip-sea", "books-month", "cafe-long");
            like(4, "books-month", "trip-busan");

            int ip = 1;
            for (String key : post.keySet()) {
                for (int who = 1; who <= 8; who++) {
                    if ((key.hashCode() + who) % 3 != 0) {
                        view(key, who);
                    }
                }
                for (int n = 0; n < 3 + Math.abs(key.hashCode()) % 9; n++) {
                    reactions.recordView(post.get(key), null, "198.51.100." + (ip++ % 250 + 1));
                }
            }

            follow(1, 2, 5);
            follow(2, 1, 5);
            follow(3, 7);
            follow(4, 5);
            follow(5, 1);
            follow(6, 2, 8);
            follow(7, 3, 1);
            follow(8, 1, 6);
            subscribe(1, TRIP, null);
            subscribe(2, CAFE, null);
            subscribe(2, DIARY, null);
            subscribe(3, CAFE, null);
            subscribe(5, DEV, devKey);
            subscribe(6, BOOKS, null);
            subscribe(7, CAFE, null);
            subscribe(8, TRIP, null);
            subscribe(8, BOOKS, null);
        }

        long comment(String postKey, int author, String content, Long parentId, int daysAgo) {
            String key = postKey.startsWith("dev-") ? devKey : null;
            long id = comments.write(post.get(postKey), key, m(author), new CommentService.WriteRequest(content, parentId));
            commentDays.put(id, daysAgo);
            commentCount++;
            return id;
        }

        void like(int who, String... keys) {
            for (String key : keys) {
                reactions.toggleLike(post.get(key), key.startsWith("dev-") ? devKey : null, m(who));
            }
        }

        /** 글을 연 것처럼 조회수를 센다. 볼 수 없는 글(비공개 일기장의 남, 공유 링크 없는 공부방)은 건너뛴다. */
        void view(String key, int who) {
            try {
                posts.readable(post.get(key), key.startsWith("dev-") ? devKey : null, m(who));
            } catch (ApiException e) {
                return;
            }
            reactions.recordView(post.get(key), m(who), null);
        }

        void follow(int who, int... targets) {
            for (int t : targets) {
                follows.follow(m(who), m(t));
            }
        }

        void subscribe(int who, String slug, String key) {
            follows.subscribe(m(who), slug, key);
        }

        // 6. 신고: 블로그장에게 가는 것과 메인 관리자에게 가는 것 (D-85), 사유는 여러 가지 (D-95). 경고 하나
        void reportAndSanction() {
            long abusiveComment = jdbc.queryForObject("""
                    SELECT id FROM comments WHERE post_id = ? AND author_id = ? AND parent_id IS NULL
                    """, Long.class, post.get("cafe-latte"), m(8));
            report(8, ReportService.Target.POST, post.get("cafe-drip"), null, ReportService.Reason.SPAM, "광고 같아요.");
            report(6, ReportService.Target.COMMENT, abusiveComment, null, ReportService.Reason.ABUSE, null);
            report(5, ReportService.Target.MEMBER, m(8), CAFE, ReportService.Reason.ABUSE, "댓글 말투가 거칠어요.");
            report(2, ReportService.Target.POST, post.get("cafe-latte"), null, ReportService.Reason.COPYRIGHT,
                    "사진 출처가 궁금해요.");
            report(7, ReportService.Target.MEMBER, m(4), null, ReportService.Reason.PRIVACY, "프로필에 연락처가 보여요.");
            report(1, ReportService.Target.BLOG, null, TRIP, ReportService.Reason.ETC, "블로그 소개가 다른 블로그와 똑같아요.");
            report(3, ReportService.Target.POST, post.get("books-shelf"), null, ReportService.Reason.OBSCENE, null);
            Long reportId = jdbc.queryForObject("""
                    SELECT id FROM reports WHERE target_type = 'COMMENT' AND target_id = ?
                    """, Long.class, abusiveComment);
            sanctions.sanction(CAFE, m(1), m(8), SanctionService.Action.WARNING, null, SanctionReason.ABUSE,
                    "댓글은 서로 존중해 주세요.", reportId);
        }

        void report(int who, ReportService.Target type, Long targetId, String slug, ReportService.Reason reason,
                String detail) {
            reports.report(m(who), new ReportService.ReportRequest(type, targetId, slug, null, reason, detail));
        }

        // 7. 위임 요청, 비공개 전환, 차단, 알림·공개 설정
        void finishSettings() {
            lifecycle.requestTransfer(DEV, m(3), m(7));
            blogs.update(DIARY, m(4), new BlogService.UpdateRequest("밤의 일기", "멤버만 보는 일기장.",
                    Blog.Visibility.PRIVATE, Blog.JoinPolicy.APPROVAL, List.of("일기", "테스트")));
            blocks.toggle(m(8), m(6));
            notificationSettings.updateSettings(m(6), Map.of("LIKE", false), 7);
            profiles.updateVisibility(m(8), new ProfileService.Visibility(true, true, false, true, true));
        }

        // 8. 날짜를 몇 주에 걸쳐 흩는다. 인기 글(최근 7일)에도, 몇 주 전 글에도 나오게
        void backdate() {
            int i = 0;
            for (String slug : SLUGS) {
                LocalDateTime made = now.minusDays(50 - i * 2L);
                jdbc.update("UPDATE blogs SET created_at = ? WHERE slug = ?", made, slug);
                jdbc.update("""
                        UPDATE blog_memberships bm JOIN blogs b ON b.id = bm.blog_id
                        SET bm.joined_at = GREATEST(?, bm.joined_at - INTERVAL 40 DAY) WHERE b.slug = ?
                        """, made, slug);
                i++;
            }
            for (Written w : written) {
                LocalDateTime at = now.minusDays(w.daysAgo()).minusHours(2 + w.postId() % 9);
                jdbc.update("UPDATE posts SET created_at = ?, updated_at = ? WHERE id = ?", at, at, w.postId());
            }
            for (Map.Entry<Long, Integer> c : commentDays.entrySet()) {
                LocalDateTime at = now.minusDays(c.getValue()).minusMinutes(30 + c.getKey() % 50);
                jdbc.update("UPDATE comments SET created_at = ?, updated_at = ? WHERE id = ?", at, at, c.getKey());
            }
            String ids = String.join(",", member.values().stream().map(String::valueOf).toList());
            jdbc.update("UPDATE follows SET created_at = created_at - INTERVAL (follower_id % 20) DAY WHERE follower_id IN (" + ids + ")");
            jdbc.update("UPDATE subscriptions SET created_at = created_at - INTERVAL (member_id % 15) DAY WHERE member_id IN (" + ids + ")");
            jdbc.update("UPDATE post_likes SET created_at = created_at - INTERVAL (post_id % 6) DAY WHERE member_id IN (" + ids + ")");
        }

        /**
         * 블로그 통계(D-112)가 보여 줄 방문 기록 6주치. 요일·시간에 따라 수가 달라지고, 들어온 경로·검색어·바깥 사이트가 섞인다.
         * 방문자 값은 하루마다 다른 해시라 사람을 알 수 없다(실제 기록과 같은 모양).
         */
        int insertVisits() {
            Random random = new Random(113);
            String[] sources = {"search", "feed", "popular", "following", "subscribed", "explore", "new", "comment",
                "profile", "notification", "site", "external", "direct", "inside"};
            int[] weights = {14, 12, 6, 4, 6, 8, 3, 3, 4, 3, 5, 12, 14, 6};
            String[][] keywords = {{"라떼", "성수동 카페", "홈카페", "드립 커피"}, {"제주 여행", "설악산 단풍", "부산 1박 2일", "여행 준비물"},
                {"자바 레코드", "스프링 트랜잭션", "코드 리뷰"}, {"일기"}, {"이번 달 책", "어린 왕자", "책장 정리"}};
            String[] hosts = {"www.google.com", "search.naver.com", "m.search.naver.com", "www.daum.net", "www.instagram.com",
                "t.co", "www.youtube.com"};
            List<Object[]> rows = new ArrayList<>();
            LocalDate today = now.toLocalDate();
            for (int b = 0; b < SLUGS.size(); b++) {
                String slug = SLUGS.get(b);
                long blogId = jdbc.queryForObject("SELECT id FROM blogs WHERE slug = ?", Long.class, slug);
                List<Long> blogPosts = jdbc.queryForList("SELECT id FROM posts WHERE blog_id = ?", Long.class, blogId);
                int base = slug.equals(DIARY) ? 2 : slug.equals(DEV) ? 4 : 8 + b * 2;
                for (int d = VISIT_DAYS; d >= 0; d--) {
                    LocalDate day = today.minusDays(d);
                    boolean weekend = day.getDayOfWeek().getValue() >= 6;
                    int count = base + random.nextInt(base + 1) + (weekend ? base / 2 : 0) + (VISIT_DAYS - d) / 6;
                    for (int n = 0; n < count; n++) {
                        int hour = pickHour(random);
                        LocalDateTime at = day.atTime(hour, random.nextInt(60), random.nextInt(60));
                        if (at.isAfter(now)) {
                            continue;
                        }
                        String source = pick(sources, weights, random);
                        if (slug.equals(DIARY) || slug.equals(DEV)) {
                            source = random.nextBoolean() ? "direct" : "inside";
                        }
                        String keyword = null;
                        String host = null;
                        if (source.equals("search")) {
                            keyword = keywords[b][random.nextInt(keywords[b].length)];
                        } else if (source.equals("external")) {
                            host = hosts[random.nextInt(hosts.length)];
                            if (host.contains("google") || host.contains("naver") || host.contains("daum")) {
                                keyword = keywords[b][random.nextInt(keywords[b].length)];
                            }
                        }
                        Long postId = blogPosts.isEmpty() || random.nextInt(10) < 4 ? null
                                : blogPosts.get(random.nextInt(blogPosts.size()));
                        String visitor = sha256("test-data|" + slug + "|" + day + "|" + random.nextInt(count * 2 + 1));
                        rows.add(new Object[] {blogId, postId, at, visitor, source, host, keyword});
                    }
                }
            }
            jdbc.batchUpdate("""
                    INSERT INTO blog_visits (blog_id, post_id, visited_at, visitor_hash, source, ref_host, keyword)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, rows);
            return rows.size();
        }
    }

    private static int pickHour(Random random) {
        int[] hours = {0, 1, 7, 8, 9, 10, 11, 12, 12, 13, 14, 15, 17, 18, 19, 20, 20, 21, 21, 22, 22, 23};
        return hours[random.nextInt(hours.length)];
    }

    private static String pick(String[] values, int[] weights, Random random) {
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        int r = random.nextInt(total);
        for (int i = 0; i < values.length; i++) {
            r -= weights[i];
            if (r < 0) {
                return values[i];
            }
        }
        return values[values.length - 1];
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 저장소에 넣어 둔 그림(src/main/resources/test-data/images, 직접 그린 그림)을 이 회원이 올린 것처럼 올린다. */
    private Image upload(long memberId, String fileName) {
        String type = fileName.endsWith(".png") ? "image/png" : fileName.endsWith(".gif") ? "image/gif"
                : fileName.endsWith(".webp") ? "image/webp" : "image/jpeg";
        try (InputStream in = new ClassPathResource("test-data/images/" + fileName).getInputStream()) {
            return images.upload(memberId, new SeedFile(fileName, type, in.readAllBytes()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
