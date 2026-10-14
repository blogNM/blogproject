package com.blognm.mainblog.testdata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.blognm.mainblog.image.FileStorage;

/**
 * 테스트 데이터를 모두 지운다 (D-113). 무엇이 테스트 데이터인지는 이렇게 정한다.
 *
 * - 테스트 회원: members.is_test = TRUE인 회원. 실제 회원은 어떤 경우에도 지우지 않는다.
 * - 테스트 블로그: 블로그장이 테스트 회원인 블로그. 안의 글은 누가 썼든 블로그와 함께 지운다.
 * - 테스트 글·댓글: 테스트 블로그의 글, 테스트 회원이 쓴 글·댓글, 그 글에 달린 댓글과 답글.
 * - 실제 회원이 테스트 쪽에 남긴 것: 테스트 글에 단 댓글·좋아요, 테스트 회원 팔로우, 테스트 블로그 구독·참여 신청,
 *   테스트 쪽을 신고한 것, 테스트 쪽에서 온 알림.
 * - 실제 블로그에서 테스트 회원의 자리(멤버·부블로그장)와 테스트 회원이 올린 사진(파일까지)도 지운다.
 *   실제 블로그의 머리 사진이 테스트 회원이 올린 것이면 머리 사진만 비운다.
 *
 * 실제 블로그와 실제 회원이 실제 블로그에 쓴 글은 남는다. 테스트 쪽 댓글·좋아요가 빠진 실제 글은 숫자를 다시 센다.
 * 관리자 활동 기록(admin_action_logs)은 감사 기록이라 남긴다. 한 트랜잭션으로 지우고, 끝난 뒤 사진 파일을 지운다.
 */
@Service
public class TestDataCleaner {

    private static final Logger log = LoggerFactory.getLogger(TestDataCleaner.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FileStorage storage;

    public TestDataCleaner(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager txManager, FileStorage storage) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.storage = storage;
    }

    /** 지운 행 수를 표 이름별로 돌려준다. 사진 파일 수는 "files". */
    public Map<String, Integer> remove() {
        Removal r = tx.execute(status -> new Removal().run());
        int files = 0;
        for (String name : r.files) {
            try {
                storage.delete(name);
                files++;
            } catch (RuntimeException e) {
                log.warn("사진 파일을 지우지 못함: {} ({})", name, e.toString());
            }
        }
        if (!r.files.isEmpty()) {
            r.counts.put("files", files);
        }
        log.info("테스트 데이터를 지웠습니다: {}", r.counts);
        return r.counts;
    }

    private final class Removal {

        final Map<String, Integer> counts = new LinkedHashMap<>();
        final List<String> files = new ArrayList<>();

        Removal run() {
            List<Long> members = ids("SELECT id FROM members WHERE is_test = TRUE", Map.of());
            if (members.isEmpty()) {
                return this;
            }
            Map<String, Object> m = Map.of("m", members);
            List<Long> blogs = ids("SELECT id FROM blogs WHERE owner_id IN (:m)", m);
            List<Long> posts = ids("SELECT id FROM posts WHERE author_id IN (:m)"
                    + (blogs.isEmpty() ? "" : " OR blog_id IN (:b)"), withBlogs(m, blogs));
            List<Long> comments = commentsToRemove(members, posts);
            List<Long> images = ids("SELECT id FROM images WHERE uploader_id IN (:m)"
                    + (posts.isEmpty() ? "" : " OR post_id IN (:p)"), Map.of("m", members, "p", nonEmpty(posts)));
            List<Long> reports = reportsToRemove(members, blogs, posts, comments);
            List<Long> entries = ids("SELECT id FROM blacklist_entries WHERE member_id IN (:m)"
                    + (blogs.isEmpty() ? "" : " OR blog_id IN (:b)"), withBlogs(m, blogs));

            // 숫자를 다시 셀 실제 글: 테스트 쪽 댓글·좋아요가 달려 있던, 지우지 않는 글
            Set<Long> recount = new LinkedHashSet<>();
            if (!comments.isEmpty()) {
                recount.addAll(ids("SELECT DISTINCT post_id FROM comments WHERE id IN (:c)", Map.of("c", comments)));
            }
            recount.addAll(ids("SELECT DISTINCT post_id FROM post_likes WHERE member_id IN (:m)", m));
            recount.removeAll(posts);

            files.addAll(images.isEmpty() ? List.of()
                    : jdbc.queryForList("SELECT stored_name FROM images WHERE id IN (:i)", Map.of("i", images), String.class));

            MapSqlParameterSource p = new MapSqlParameterSource("m", members).addValue("b", nonEmpty(blogs))
                    .addValue("p", nonEmpty(posts)).addValue("c", nonEmpty(comments)).addValue("i", nonEmpty(images))
                    .addValue("r", nonEmpty(reports)).addValue("e", nonEmpty(entries));

            // 사진을 가리키는 칸부터 비운다
            update("members.profile_image", "UPDATE members SET profile_image_id = NULL WHERE profile_image_id IN (:i)", p);
            update("blogs.cover_image", "UPDATE blogs SET cover_image_id = NULL WHERE cover_image_id IN (:i)", p);
            update("link_previews", "DELETE FROM link_previews WHERE image_id IN (:i)", p);
            removePreviewsOnlyIn(posts);

            update("notifications", """
                    DELETE FROM notifications WHERE recipient_id IN (:m) OR actor_id IN (:m) OR blog_id IN (:b)
                       OR (target_type = 'POST' AND target_id IN (:p)) OR (target_type = 'COMMENT' AND target_id IN (:c))
                       OR (target_type = 'MEMBER' AND target_id IN (:m)) OR (target_type = 'REPORT' AND target_id IN (:r))
                       OR (target_type = 'BLOG' AND target_id IN (:b))
                    """, p);
            update("member_sanctions", """
                    DELETE FROM member_sanctions WHERE blog_id IN (:b) OR member_id IN (:m) OR actor_id IN (:m)
                    """, p);
            update("owner_warnings", "DELETE FROM owner_warnings WHERE blog_id IN (:b) OR member_id IN (:m)", p);
            // 실제 회원끼리의 제재가 테스트 신고에서 시작했으면 그 연결만 끊는다
            update("member_sanctions.report", "UPDATE member_sanctions SET report_id = NULL WHERE report_id IN (:r)", p);
            update("owner_warnings.report", "UPDATE owner_warnings SET report_id = NULL WHERE report_id IN (:r)", p);
            update("reports", "DELETE FROM reports WHERE id IN (:r)", p);
            update("blacklist_appeals", """
                    DELETE FROM blacklist_appeals WHERE blog_id IN (:b) OR member_id IN (:m) OR processed_by IN (:m)
                       OR entry_id IN (:e)
                    """, p);
            update("blacklist_entries", "DELETE FROM blacklist_entries WHERE id IN (:e)", p);
            update("blog_visits", "DELETE FROM blog_visits WHERE blog_id IN (:b) OR post_id IN (:p)", p);
            update("post_views", "DELETE FROM post_views WHERE post_id IN (:p)", p);
            update("post_likes", "DELETE FROM post_likes WHERE post_id IN (:p) OR member_id IN (:m)", p);
            update("post_tags", "DELETE FROM post_tags WHERE post_id IN (:p)", p);
            update("images", "DELETE FROM images WHERE id IN (:i)", p);
            update("comments.parent", "UPDATE comments SET parent_id = NULL WHERE id IN (:c)", p);
            update("comments", "DELETE FROM comments WHERE id IN (:c)", p);
            update("posts", "DELETE FROM posts WHERE id IN (:p)", p);
            update("categories", "DELETE FROM categories WHERE blog_id IN (:b)", p);
            update("blog_tags", "DELETE FROM blog_tags WHERE blog_id IN (:b)", p);
            update("blog_closing_notices", "DELETE FROM blog_closing_notices WHERE blog_id IN (:b)", p);
            update("ownership_transfers", """
                    DELETE FROM ownership_transfers WHERE blog_id IN (:b) OR from_member_id IN (:m) OR to_member_id IN (:m)
                    """, p);
            update("join_requests", """
                    DELETE FROM join_requests WHERE blog_id IN (:b) OR member_id IN (:m) OR processed_by IN (:m)
                    """, p);
            update("blog_memberships", "DELETE FROM blog_memberships WHERE blog_id IN (:b) OR member_id IN (:m)", p);
            update("subscriptions", "DELETE FROM subscriptions WHERE blog_id IN (:b) OR member_id IN (:m)", p);
            update("blogs", "DELETE FROM blogs WHERE id IN (:b)", p);
            update("follows", "DELETE FROM follows WHERE follower_id IN (:m) OR followee_id IN (:m)", p);
            update("blocks", "DELETE FROM blocks WHERE blocker_id IN (:m) OR blocked_id IN (:m)", p);
            update("recent_searches", "DELETE FROM recent_searches WHERE member_id IN (:m)", p);
            update("notification_settings", "DELETE FROM notification_settings WHERE member_id IN (:m)", p);
            update("social_accounts", "DELETE FROM social_accounts WHERE member_id IN (:m)", p);
            update("email_find_tokens", "DELETE FROM email_find_tokens WHERE member_id IN (:m)", p);
            update("refresh_tokens", "DELETE FROM refresh_tokens WHERE member_id IN (:m)", p);
            update("login_failures", """
                    DELETE FROM login_failures WHERE email IN (SELECT email FROM members WHERE id IN (:m))
                    """, p);
            update("email_verifications", """
                    DELETE FROM email_verifications WHERE email IN (SELECT email FROM members WHERE id IN (:m))
                    """, p);
            // 마지막으로 테스트 회원. 여기서도 is_test를 다시 확인해 실제 회원은 절대 지우지 않는다
            update("members", "DELETE FROM members WHERE id IN (:m) AND is_test = TRUE", p);

            if (!recount.isEmpty()) {
                MapSqlParameterSource q = new MapSqlParameterSource("ids", recount);
                jdbc.update("""
                        UPDATE posts p SET
                          p.like_count = (SELECT COUNT(*) FROM post_likes l WHERE l.post_id = p.id),
                          p.comment_count = (SELECT COUNT(*) FROM comments c WHERE c.post_id = p.id AND c.status = 'NORMAL')
                        WHERE p.id IN (:ids)
                        """, q);
                counts.put("posts.recounted", recount.size());
            }
            return this;
        }

        /** 테스트 글에만 있던 링크의 미리보기 카드(D-92). 남는 글에도 같은 링크가 있으면 그대로 둔다. */
        void removePreviewsOnlyIn(List<Long> posts) {
            if (posts.isEmpty()) {
                return;
            }
            List<String> bodies = jdbc.queryForList("SELECT content_md FROM posts WHERE id IN (:p)", Map.of("p", posts),
                    String.class);
            List<Long> gone = new ArrayList<>();
            jdbc.query("SELECT id, url FROM link_previews", Map.of(), rs -> {
                String url = rs.getString("url");
                if (bodies.stream().anyMatch(b -> b.contains(url))) {
                    Integer kept = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM posts WHERE id NOT IN (:p) AND INSTR(content_md, :url) > 0
                            """, new MapSqlParameterSource("p", posts).addValue("url", url), Integer.class);
                    if (kept == null || kept == 0) {
                        gone.add(rs.getLong("id"));
                    }
                }
            });
            if (!gone.isEmpty()) {
                update("link_previews", "DELETE FROM link_previews WHERE id IN (:g)", new MapSqlParameterSource("g", gone));
            }
        }

        /** 테스트 회원이 쓴 댓글, 테스트 글에 달린 댓글, 테스트 회원에게 단 답글, 그리고 그 댓글들의 답글. */
        List<Long> commentsToRemove(List<Long> members, List<Long> posts) {
            Set<Long> found = new LinkedHashSet<>(ids("""
                    SELECT id FROM comments WHERE author_id IN (:m) OR reply_to_member_id IN (:m)
                    """ + (posts.isEmpty() ? "" : " OR post_id IN (:p)"), Map.of("m", members, "p", nonEmpty(posts))));
            while (true) {
                if (found.isEmpty()) {
                    break;
                }
                List<Long> children = ids("SELECT id FROM comments WHERE parent_id IN (:c)", Map.of("c", found));
                if (found.containsAll(children)) {
                    break;
                }
                found.addAll(children);
            }
            return new ArrayList<>(found);
        }

        /** 테스트 회원이 했거나 받은 신고, 테스트 블로그·글·댓글에 대한 신고, 테스트 회원이 처리한 신고. */
        List<Long> reportsToRemove(List<Long> members, List<Long> blogs, List<Long> posts, List<Long> comments) {
            return ids("""
                    SELECT id FROM reports WHERE reporter_id IN (:m) OR processed_by_member_id IN (:m) OR blog_id IN (:b)
                       OR (target_type IN ('MEMBER', 'BLOG_OWNER') AND target_id IN (:m))
                       OR (target_type = 'BLOG' AND target_id IN (:b))
                       OR (target_type = 'POST' AND target_id IN (:p))
                       OR (target_type = 'COMMENT' AND target_id IN (:c))
                    """, Map.of("m", members, "b", nonEmpty(blogs), "p", nonEmpty(posts), "c", nonEmpty(comments)));
        }

        void update(String name, String sql, MapSqlParameterSource p) {
            int n = jdbc.update(sql, p);
            if (n > 0) {
                counts.merge(name, n, Integer::sum);
            }
        }

        List<Long> ids(String sql, Map<String, ?> params) {
            return jdbc.queryForList(sql, params, Long.class);
        }

        Map<String, Object> withBlogs(Map<String, Object> m, List<Long> blogs) {
            Map<String, Object> out = new LinkedHashMap<>(m);
            out.put("b", nonEmpty(blogs));
            return out;
        }
    }

    /** 빈 IN ()은 SQL 오류라, 비어 있으면 어떤 행과도 맞지 않는 번호 하나를 넣는다. */
    private static Collection<Long> nonEmpty(Collection<Long> ids) {
        return ids.isEmpty() ? List.of(-1L) : ids;
    }
}
