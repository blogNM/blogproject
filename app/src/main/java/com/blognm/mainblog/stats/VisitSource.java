package com.blognm.mainblog.stats;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 블로그에 어디서 들어왔는지 (D-112).
 * 우리 사이트 안의 링크는 주소에 {@code ?from=}을 붙여 두고(화면의 visit.js가 읽고 지움), 표시가 없으면 이전 화면 주소
 * (document.referrer)로 가른다.
 */
public final class VisitSource {

    /** 저장하는 값 → 화면에 보이는 이름. 순서는 통계 화면의 설명 순서. */
    public static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("search", "Creat_ 검색");
        LABELS.put("feed", "피드 최신 글");
        LABELS.put("popular", "피드 인기 글");
        LABELS.put("following", "팔로우 피드");
        LABELS.put("subscribed", "구독 피드");
        LABELS.put("explore", "블로그 둘러보기");
        LABELS.put("new", "새로 생긴 블로그");
        LABELS.put("comment", "댓글 보고 회원 눌러서");
        LABELS.put("profile", "회원 프로필·활동");
        LABELS.put("notification", "알림");
        LABELS.put("site", "Creat_ 다른 화면");
        LABELS.put("external", "다른 사이트");
        LABELS.put("direct", "직접 들어옴");
        LABELS.put("inside", "블로그 안에서 이동");
    }

    /** 화면 링크에 붙이는 표시(from=) 중 받는 값. 이 밖의 값은 무시하고 이전 화면 주소로 가른다. */
    static final Set<String> MARKERS = Set.of("search", "feed", "popular", "following", "subscribed", "explore", "new",
            "comment", "profile", "notification");

    /** 다른 사이트 주소에서 검색어로 읽는 칸 (구글 q, 네이버 query, 바이두 wd, 유튜브 search_query 등). */
    private static final String[] KEYWORD_PARAMS = {"q", "query", "wd", "search_query", "keyword"};

    private VisitSource() {
    }

    public record Result(String source, String refHost, String keyword) {
    }

    /**
     * @param from 링크에 붙은 표시
     * @param q 검색해서 왔을 때 검색어 (from=search)
     * @param referrer 이전 화면 주소
     * @param slug 이 블로그 주소 (블로그 안에서 옮겨 다닌 것을 가른다)
     * @param ourHosts 우리 사이트 호스트 이름들
     */
    public static Result classify(String from, String q, String referrer, String slug, Set<String> ourHosts,
            int keywordMax) {
        if (from != null && MARKERS.contains(from)) {
            return new Result(from, null, "search".equals(from) ? keyword(q, keywordMax) : null);
        }
        URI ref = parse(referrer);
        if (ref == null || ref.getHost() == null) {
            return new Result("direct", null, null);
        }
        String host = ref.getHost().toLowerCase(Locale.ROOT);
        if (ourHosts.contains(host)) {
            String path = ref.getRawPath() == null ? "" : ref.getRawPath();
            String blogPath = "/blog/" + slug.toLowerCase(Locale.ROOT);
            String lowerPath = path.toLowerCase(Locale.ROOT);
            if (lowerPath.equals(blogPath) || lowerPath.startsWith(blogPath + "/")) {
                return new Result("inside", null, null);
            }
            if (path.equals("/search.html")) {
                return new Result("search", null, keyword(param(ref, "q"), keywordMax));
            }
            if (path.startsWith("/members/")) {
                return new Result("profile", null, null);
            }
            return new Result("site", null, null);
        }
        String shortHost = host.startsWith("www.") ? host.substring(4) : host;
        if (shortHost.length() > 100) {
            shortHost = shortHost.substring(0, 100);
        }
        String word = null;
        for (String name : KEYWORD_PARAMS) {
            word = keyword(param(ref, name), keywordMax);
            if (word != null) {
                break;
            }
        }
        return new Result("external", shortHost, word);
    }

    /** 앞뒤 공백을 지우고 공백을 하나로, 영문은 소문자로, 정한 글자 수까지. 글자·숫자가 없으면 버린다. */
    static String keyword(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("\\p{Cntrl}", " ").trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (!s.codePoints().anyMatch(Character::isLetterOrDigit)) {
            return null;
        }
        if (s.codePointCount(0, s.length()) > max) {
            s = s.substring(0, s.offsetByCodePoints(0, max)).trim();
        }
        return s;
    }

    private static URI parse(String referrer) {
        if (referrer == null || referrer.isBlank() || referrer.length() > 2000) {
            return null;
        }
        try {
            URI uri = URI.create(referrer.trim());
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) ? uri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String param(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name) && eq >= 0) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
