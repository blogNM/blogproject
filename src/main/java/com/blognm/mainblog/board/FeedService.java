package com.blognm.mainblog.board;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.blognm.mainblog.common.ApiException;

/**
 * 메인 피드 (BRD-09, 004 US7). 최신·인기 공개 글, 팔로우한 회원의 새 글, 구독한 블로그의 새 글을 탭으로 나눈다 (D-65).
 * 볼 때마다 새로 모아 오므로, 지워진 글이나 비공개로 바뀐 블로그의 글은 다음 조회에서 바로 빠진다 (D-73).
 */
@Service
public class FeedService {

    public enum Tab { LATEST, POPULAR, FOLLOWING, SUBSCRIBED }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final PostCards cards;
    private final NamedParameterJdbcTemplate jdbc;
    private final BoardProperties props;
    private final Clock clock;

    public FeedService(PostCards cards, NamedParameterJdbcTemplate jdbc, BoardProperties props, Clock clock) {
        this.cards = cards;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    public PostCards.Page feed(Tab tab, Long viewerId, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = PostCards.publicWhere(viewerId, params);
        String order = "p.created_at DESC, p.id DESC";
        switch (tab) {
            case LATEST -> {
                return cards.latestPage(where, params, page, size);
            }
            case POPULAR -> {
                // 최근 7일 안에 쓴 글 중 좋아요 많은 순, 같으면 조회수 (추천안 #15)
                where += " AND p.created_at >= :since";
                params.addValue("since", LocalDateTime.now(clock).minus(props.popularWindow()));
                order = "p.like_count DESC, p.view_count DESC, p.id DESC";
            }
            case FOLLOWING -> {
                requireLogin(viewerId);
                where += " AND p.author_left = FALSE AND p.author_id IN (SELECT f.followee_id FROM follows f WHERE f.follower_id = :me)";
                params.addValue("me", viewerId);
            }
            case SUBSCRIBED -> {
                requireLogin(viewerId);
                where += " AND p.blog_id IN (SELECT s.blog_id FROM subscriptions s WHERE s.member_id = :me)";
                params.addValue("me", viewerId);
            }
        }
        return cards.page(where, order, params, page, size);
    }

    /** 메인 공지사항 (BRD-10). 관리자가 쓰는 화면은 6단계. */
    public List<Notice> notices() {
        return jdbc.query("""
                SELECT id, title, created_at FROM notices WHERE status = 'NORMAL'
                ORDER BY created_at DESC, id DESC LIMIT :limit
                """, Map.of("limit", props.feedNoticeCount()), (rs, i) -> new Notice(rs.getLong("id"),
                rs.getString("title"), rs.getObject("created_at", LocalDateTime.class).format(DATE)));
    }

    private static void requireLogin(Long viewerId) {
        if (viewerId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED",
                    "로그인하면 팔로우·구독한 글을 볼 수 있습니다.");
        }
    }

    public record Notice(long id, String title, String createdAt) {
    }
}
