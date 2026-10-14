package com.blognm.mainblog.stats;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.blog.BlogRepository;
import com.blognm.mainblog.common.ApiException;

/**
 * 블로그 통계 (D-112). 시간별·일별·월별 방문자와 조회수, 들어온 경로, 검색어, 많이 본 글.
 * 모두 DB에서 바로 모아 센다. 시간은 한국 시간(app.time-zone)으로 저장돼 있다.
 *
 * <p>
 * 방문자: 하루에 한 사람을 한 번으로 센 수 (방문자 값이 날마다 바뀐다). 월별·기간 합계는 날마다 센 방문자의 합이다.
 * 조회수: 블로그 홈·글 화면을 연 수. 같은 사람이 같은 화면을 짧은 시간(blog.stats.repeat-window) 안에 다시 연 것은 한 번.
 */
@Service
public class StatsService {

    private final JdbcTemplate jdbc;
    private final BlogRepository blogs;
    private final StatsProperties props;
    private final Clock clock;

    public StatsService(JdbcTemplate jdbc, BlogRepository blogs, StatsProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.blogs = blogs;
        this.props = props;
        this.clock = clock;
    }

    public record Count(long visitors, long views) {
    }

    public record Point(String key, String label, long visitors, long views) {
    }

    public record Summary(Count today, Count yesterday, Count last30Days, Count total, int keptDays) {
    }

    public record SourceItem(String source, String label, long count, double percent) {
    }

    public record Sources(long total, long inside, List<SourceItem> items) {
    }

    public record Named(String name, long count) {
    }

    public record TopPost(long id, String title, long views, long visitors) {
    }

    public record Stats(String unit, String from, String to, Summary summary, List<Point> series, Count period,
            Sources sources, List<Named> hosts, List<Named> keywords, List<TopPost> posts) {
    }

    /**
     * @param unit hour(하루 24칸), day(기간의 날마다), month(최근 몇 달)
     * @param date 시간별로 볼 날 (없으면 오늘)
     * @param from 일별 시작일 (없으면 to의 29일 전)
     * @param to 일별 끝 날 (없으면 오늘)
     * @param months 월별로 볼 달 수 (1~24, 기본 12)
     */
    @Transactional(readOnly = true)
    public Stats stats(String slug, String unit, String date, String from, String to, Integer months) {
        long blogId = blogs.findBySlug(slug.toLowerCase())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BLOG_NOT_FOUND", "블로그를 찾을 수 없습니다."))
                .getId();
        LocalDate today = LocalDate.now(clock);
        LocalDate start;
        LocalDate end;
        List<Point> series;
        switch (unit == null ? "day" : unit) {
            case "hour" -> {
                start = date == null || date.isBlank() ? today : parse(date);
                end = start;
                series = hourly(blogId, start);
            }
            case "month" -> {
                int n = months == null ? 12 : months;
                if (n < 1 || n > 24) {
                    throw ApiException.badRequest("INVALID_RANGE", "월별 통계는 1~24개월까지 볼 수 있어요.");
                }
                YearMonth last = YearMonth.from(today);
                YearMonth first = last.minusMonths(n - 1L);
                start = first.atDay(1);
                end = today;
                series = monthly(blogId, first, last);
            }
            case "day" -> {
                end = to == null || to.isBlank() ? today : parse(to);
                start = from == null || from.isBlank() ? end.minusDays(29) : parse(from);
                if (start.isAfter(end)) {
                    throw ApiException.badRequest("INVALID_RANGE", "시작일이 끝 날보다 늦어요.");
                }
                if (start.plusDays(props.maxDayRange()).isBefore(end.plusDays(1))) {
                    throw ApiException.badRequest("INVALID_RANGE",
                            "일별 통계는 한 번에 " + props.maxDayRange() + "일까지 볼 수 있어요.");
                }
                series = daily(blogId, start, end);
            }
            default -> throw ApiException.badRequest("INVALID_UNIT", "시간별·일별·월별 중에서 골라 주세요.");
        }
        LocalDateTime rangeFrom = start.atStartOfDay();
        LocalDateTime rangeTo = end.plusDays(1).atStartOfDay();
        return new Stats(unit == null ? "day" : unit, start.toString(), end.toString(), summary(blogId, today),
                series, count(blogId, rangeFrom, rangeTo), sources(blogId, rangeFrom, rangeTo),
                hosts(blogId, rangeFrom, rangeTo), keywords(blogId, rangeFrom, rangeTo),
                posts(blogId, rangeFrom, rangeTo));
    }

    private static LocalDate parse(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_DATE", "날짜는 2026-10-14처럼 넣어 주세요.");
        }
    }

    private Summary summary(long blogId, LocalDate today) {
        LocalDateTime t = today.atStartOfDay();
        return new Summary(count(blogId, t, t.plusDays(1)), count(blogId, t.minusDays(1), t),
                count(blogId, t.minusDays(29), t.plusDays(1)), count(blogId, null, null), props.retentionDays());
    }

    private Count count(long blogId, LocalDateTime from, LocalDateTime to) {
        if (from == null) {
            return jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT visitor_hash) v, COUNT(*) c FROM blog_visits WHERE blog_id = ?",
                    (rs, i) -> new Count(rs.getLong("v"), rs.getLong("c")), blogId);
        }
        return jdbc.queryForObject("""
                SELECT COUNT(DISTINCT visitor_hash) v, COUNT(*) c FROM blog_visits
                WHERE blog_id = ? AND visited_at >= ? AND visited_at < ?
                """, (rs, i) -> new Count(rs.getLong("v"), rs.getLong("c")), blogId, from, to);
    }

    private List<Point> hourly(long blogId, LocalDate day) {
        Map<Integer, Count> rows = new HashMap<>();
        jdbc.query("""
                SELECT HOUR(visited_at) h, COUNT(DISTINCT visitor_hash) v, COUNT(*) c FROM blog_visits
                WHERE blog_id = ? AND visited_at >= ? AND visited_at < ? GROUP BY h
                """, rs -> {
            rows.put(rs.getInt("h"), new Count(rs.getLong("v"), rs.getLong("c")));
        }, blogId, day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        List<Point> points = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Count c = rows.getOrDefault(h, new Count(0, 0));
            points.add(new Point(String.valueOf(h), h + "시", c.visitors(), c.views()));
        }
        return points;
    }

    private List<Point> daily(long blogId, LocalDate from, LocalDate to) {
        Map<String, Count> rows = new HashMap<>();
        jdbc.query("""
                SELECT DATE_FORMAT(visited_at, '%Y-%m-%d') d, COUNT(DISTINCT visitor_hash) v, COUNT(*) c
                FROM blog_visits WHERE blog_id = ? AND visited_at >= ? AND visited_at < ? GROUP BY d
                """, rs -> {
            rows.put(rs.getString("d"), new Count(rs.getLong("v"), rs.getLong("c")));
        }, blogId, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        List<Point> points = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Count c = rows.getOrDefault(d.toString(), new Count(0, 0));
            points.add(new Point(d.toString(), d.getMonthValue() + "." + d.getDayOfMonth(), c.visitors(), c.views()));
        }
        return points;
    }

    private List<Point> monthly(long blogId, YearMonth first, YearMonth last) {
        Map<String, Count> rows = new HashMap<>();
        jdbc.query("""
                SELECT DATE_FORMAT(visited_at, '%Y-%m') m, COUNT(DISTINCT visitor_hash) v, COUNT(*) c
                FROM blog_visits WHERE blog_id = ? AND visited_at >= ? AND visited_at < ? GROUP BY m
                """, rs -> {
            rows.put(rs.getString("m"), new Count(rs.getLong("v"), rs.getLong("c")));
        }, blogId, first.atDay(1).atStartOfDay(), last.plusMonths(1).atDay(1).atStartOfDay());
        List<Point> points = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            Count c = rows.getOrDefault(m.toString(), new Count(0, 0));
            String label = m.getMonthValue() == 1 || m.equals(first) ? m.getYear() % 100 + "년 " + m.getMonthValue() + "월"
                    : m.getMonthValue() + "월";
            points.add(new Point(m.toString(), label, c.visitors(), c.views()));
        }
        return points;
    }

    /** 들어온 경로. 블로그 안에서 옮겨 다닌 것(홈 → 글)은 들어온 경로가 아니라서 따로 센다. */
    private Sources sources(long blogId, LocalDateTime from, LocalDateTime to) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("""
                SELECT source, COUNT(*) c FROM blog_visits
                WHERE blog_id = ? AND visited_at >= ? AND visited_at < ? GROUP BY source
                """, rs -> {
            counts.put(rs.getString("source"), rs.getLong("c"));
        }, blogId, from, to);
        long inside = counts.getOrDefault("inside", 0L);
        long total = counts.entrySet().stream().filter(e -> !e.getKey().equals("inside"))
                .mapToLong(Map.Entry::getValue).sum();
        List<SourceItem> items = counts.entrySet().stream().filter(e -> !e.getKey().equals("inside"))
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new SourceItem(e.getKey(), VisitSource.LABELS.getOrDefault(e.getKey(), e.getKey()),
                        e.getValue(), Math.round(e.getValue() * 1000.0 / total) / 10.0))
                .toList();
        return new Sources(total, inside, items);
    }

    private List<Named> hosts(long blogId, LocalDateTime from, LocalDateTime to) {
        return jdbc.query("""
                SELECT ref_host n, COUNT(*) c FROM blog_visits
                WHERE blog_id = ? AND visited_at >= ? AND visited_at < ? AND source = 'external' AND ref_host IS NOT NULL
                GROUP BY ref_host ORDER BY c DESC, n LIMIT ?
                """, (rs, i) -> new Named(rs.getString("n"), rs.getLong("c")), blogId, from, to, props.topCount());
    }

    /** 검색어: 우리 사이트 검색과 다른 사이트 주소에 실려 온 검색어를 합쳐 센다. */
    private List<Named> keywords(long blogId, LocalDateTime from, LocalDateTime to) {
        return jdbc.query("""
                SELECT keyword n, COUNT(*) c FROM blog_visits
                WHERE blog_id = ? AND visited_at >= ? AND visited_at < ? AND keyword IS NOT NULL
                GROUP BY keyword ORDER BY c DESC, n LIMIT ?
                """, (rs, i) -> new Named(rs.getString("n"), rs.getLong("c")), blogId, from, to, props.topCount());
    }

    /** 이 기간에 많이 본 글. 지워졌거나 숨겨진 글은 뺀다. */
    private List<TopPost> posts(long blogId, LocalDateTime from, LocalDateTime to) {
        return jdbc.query("""
                SELECT p.id, p.title, COUNT(*) c, COUNT(DISTINCT v.visitor_hash) u
                FROM blog_visits v JOIN posts p ON p.id = v.post_id AND p.status = 'NORMAL'
                WHERE v.blog_id = ? AND v.visited_at >= ? AND v.visited_at < ?
                GROUP BY p.id, p.title ORDER BY c DESC, p.id DESC LIMIT ?
                """, (rs, i) -> new TopPost(rs.getLong("id"), rs.getString("title"), rs.getLong("c"), rs.getLong("u")),
                blogId, from, to, props.topCount());
    }
}
