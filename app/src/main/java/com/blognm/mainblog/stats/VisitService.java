package com.blognm.mainblog.stats;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.blognm.mainblog.blog.Blog;
import com.blognm.mainblog.blog.BlogService;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.common.Hashing;
import com.blognm.mainblog.config.AppProperties;

/**
 * 블로그 홈·글 화면을 열 때 방문 기록을 남긴다 (D-112).
 * 서버 메모리에 아무것도 두지 않고 DB에만 쓴다. 서버 두 대 어느 쪽이 받아도 같다 (SCL-01).
 */
@Service
public class VisitService {

    /** 검색 로봇·미리보기 수집기·명령줄 도구. 사람이 아닌 방문은 세지 않는다. */
    private static final Pattern BOT = Pattern.compile(
            "bot|crawl|spider|slurp|facebookexternalhit|kakaotalk-scrap|preview|headless|python|curl|wget|java/|"
                    + "httpclient|okhttp|go-http|scrapy|phantomjs|lighthouse",
            Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;
    private final BlogService blogs;
    private final AppProperties app;
    private final StatsProperties props;
    private final Clock clock;

    public VisitService(JdbcTemplate jdbc, BlogService blogs, AppProperties app, StatsProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.blogs = blogs;
        this.app = app;
        this.props = props;
        this.clock = clock;
    }

    /** 화면이 보내는 값. from·q는 링크에 붙은 표시, referrer는 이전 화면 주소, key는 일부 공개 블로그의 공유 링크 값. */
    public record VisitRequest(Long postId, String from, String q, String referrer, String key) {
    }

    /**
     * 트랜잭션으로 묶지 않는다. 볼 수 없는 블로그 확인(BlogService)이 예외로 끝나면 묶은 트랜잭션 전체가 롤백 표시되기 때문이다.
     * 쓰는 것은 INSERT 한 줄뿐이다.
     *
     * @return 기록했으면 true. 블로그 멤버·로봇·볼 수 없는 블로그·짧은 시간 안에 다시 연 화면은 false
     */
    public boolean record(String slug, VisitRequest body, Long memberId, String ip, String userAgent,
            String serverName) {
        if (userAgent == null || userAgent.isBlank() || BOT.matcher(userAgent).find()) {
            return false;
        }
        Blog blog;
        try {
            blog = blogs.visibleBlog(slug, body.key(), memberId);
        } catch (ApiException e) {
            return false;
        }
        // 블로그를 함께 쓰는 사람(블로그장·부블로그장·멤버)이 자기 블로그를 본 것은 세지 않는다
        if (memberId != null && isMember(blog.getId(), memberId)) {
            return false;
        }
        Long postId = body.postId();
        if (postId != null && !postInBlog(postId, blog.getId())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String visitor = Hashing.hmacSha256Hex(app.auth().codeHashSecret(),
                "VISIT:" + now.toLocalDate() + ":" + ip + ":" + userAgent);
        Integer recent = jdbc.queryForObject("""
                SELECT COUNT(*) FROM blog_visits
                WHERE visitor_hash = ? AND blog_id = ? AND post_id <=> ? AND visited_at > ?
                """, Integer.class, visitor, blog.getId(), postId, now.minus(props.repeatWindow()));
        if (recent != null && recent > 0) {
            return false;
        }
        VisitSource.Result src = VisitSource.classify(body.from(), body.q(), body.referrer(), blog.getSlug(),
                ourHosts(serverName), props.keywordMax());
        jdbc.update("""
                INSERT INTO blog_visits (blog_id, post_id, visited_at, visitor_hash, source, ref_host, keyword)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, blog.getId(), postId, now, visitor, src.source(), src.refHost(), src.keyword());
        return true;
    }

    private boolean isMember(long blogId, long memberId) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT 1 FROM blog_memberships WHERE blog_id = ? AND member_id = ? AND status = 'ACTIVE'",
                Integer.class, blogId, memberId);
        return !rows.isEmpty();
    }

    private boolean postInBlog(long postId, long blogId) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT 1 FROM posts WHERE id = ? AND blog_id = ? AND status = 'NORMAL'", Integer.class, postId,
                blogId);
        return !rows.isEmpty();
    }

    /** 요청을 받은 호스트와 설정의 사이트 주소. 둘 중 하나와 같으면 우리 사이트 안에서 온 것이다. */
    private Set<String> ourHosts(String serverName) {
        Set<String> hosts = new HashSet<>();
        if (serverName != null && !serverName.isBlank()) {
            hosts.add(serverName.toLowerCase(Locale.ROOT));
        }
        try {
            String host = URI.create(app.baseUrl()).getHost();
            if (host != null) {
                hosts.add(host.toLowerCase(Locale.ROOT));
            }
        } catch (IllegalArgumentException e) {
            // 사이트 주소 설정이 잘못돼도 방문 기록은 남긴다
        }
        return hosts;
    }
}
