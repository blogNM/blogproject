package com.blognm.mainblog.linkpreview;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.blognm.mainblog.common.Hashing;
import com.blognm.mainblog.image.Image;
import com.blognm.mainblog.image.ImageService;
import com.blognm.mainblog.image.UploadProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 글 속 링크 미리보기 카드 (D-92). 한 줄에 링크 하나만 있으면 썸네일·제목·설명 카드로 보인다.
 * 미리보기는 글을 저장할 때 한 번 받아 두고(collect), 글을 읽을 때는 저장해 둔 것만 쓴다(decorate).
 * 썸네일은 서버가 받아 /uploads에 둔 사진만 쓴다 (CSP img-src 'self'). 제목·설명은 글자로만 넣는다 (헌법 III).
 */
@Service
public class LinkPreviewService {

    private static final Logger log = LoggerFactory.getLogger(LinkPreviewService.class);
    private static final int HTML_MAX_BYTES = 512 * 1024;
    private static final int TITLE_MAX = 200;
    private static final int DESCRIPTION_MAX = 300;
    private static final Pattern CHARSET = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> YOUTUBE = Set.of("youtube.com", "www.youtube.com", "m.youtube.com",
            "music.youtube.com", "youtu.be");

    private final NamedParameterJdbcTemplate jdbc;
    private final SafeFetcher fetcher;
    private final ImageService images;
    private final UploadProperties uploadProps;
    private final LinkPreviewProperties props;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public LinkPreviewService(NamedParameterJdbcTemplate jdbc, SafeFetcher fetcher, ImageService images,
            UploadProperties uploadProps, LinkPreviewProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.fetcher = fetcher;
        this.images = images;
        this.uploadProps = uploadProps;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 글을 저장한 뒤 부른다. 본문에서 혼자 있는 링크를 찾아, 받아 둔 적 없거나 오래된 것만 받는다.
     * 바깥 사이트가 늦거나 막혀도 글 저장에는 영향이 없다 (DB 트랜잭션 밖에서 부름).
     */
    public void collect(String safeHtml, long uploaderId) {
        if (!props.enabled()) {
            return;
        }
        List<String> urls = standaloneLinks(Jsoup.parseBodyFragment(safeHtml)).stream()
                .map(a -> a.attr("href")).distinct().limit(props.maxLinksPerPost()).toList();
        LocalDateTime now = LocalDateTime.now(clock);
        // 링크마다 시간 제한이 있어도 여러 개면 길어진다. 모두 합쳐 정해 둔 시간을 넘으면 남은 링크는 다음 저장 때 받는다
        long deadline = System.nanoTime() + props.collectBudget().toNanos();
        for (String url : urls) {
            if (fresh(url, now)) {
                continue;
            }
            if (System.nanoTime() > deadline) {
                log.info("링크 미리보기: 시간 상한을 넘어 남은 링크는 다음에 받음");
                return;
            }
            try {
                save(url, fetch(url, uploaderId), now);
            } catch (RuntimeException e) {
                log.warn("링크 미리보기를 받지 못함: {}", e.toString());
                save(url, Optional.empty(), now);
            }
        }
    }

    /** 걸러낸 본문 HTML에서 혼자 있는 링크를 카드로 바꾼다. 받아 둔 미리보기가 없으면 그대로 둔다. */
    public String decorate(String safeHtml) {
        if (!props.enabled() || !safeHtml.contains("<a ")) {
            return safeHtml;
        }
        Document doc = Jsoup.parseBodyFragment(safeHtml);
        List<Element> links = standaloneLinks(doc);
        if (links.isEmpty()) {
            return safeHtml;
        }
        Map<String, Preview> found = find(links.stream().map(a -> a.attr("href")).toList());
        for (Element a : links) {
            Preview p = found.get(hash(a.attr("href")));
            if (p != null) {
                a.parent().replaceWith(card(a, p));
            }
        }
        doc.outputSettings().prettyPrint(false);
        return doc.body().html();
    }

    /** 문단에 링크 하나만 있는 것. 문장 안의 링크는 글자 링크로 둔다. */
    private static List<Element> standaloneLinks(Document doc) {
        List<Element> result = new ArrayList<>();
        for (Element p : doc.select("p")) {
            List<Node> nodes = p.childNodes().stream()
                    .filter(n -> !(n instanceof TextNode t && t.isBlank())).toList();
            if (nodes.size() == 1 && nodes.get(0) instanceof Element a && a.tagName().equals("a")
                    && a.attr("href").matches("(?i)https?://.+")) {
                result.add(a);
            }
        }
        return result;
    }

    private static Element card(Element link, Preview p) {
        String href = link.attr("href");
        Element card = new Element("a").addClass("link-card").attr("href", href)
                .attr("rel", "nofollow noopener noreferrer").attr("target", "_blank");
        if (p.imageName() != null) {
            card.appendElement("span").addClass("lc-thumb").appendElement("img").attr("src", "/uploads/" + p.imageName())
                    .attr("alt", "").attr("loading", "lazy");
        }
        Element body = card.appendElement("span").addClass("lc-body");
        body.appendElement("strong").addClass("lc-title").text(p.title() != null ? p.title() : link.text());
        if (p.description() != null) {
            body.appendElement("span").addClass("lc-desc").text(p.description());
        }
        body.appendElement("span").addClass("lc-site").text(p.siteName() != null ? p.siteName() : host(href));
        return card;
    }

    // ----- 받기 -----

    private Optional<Fetched> fetch(String url, long uploaderId) {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        Optional<Fetched> meta = YOUTUBE.contains(host) ? youtube(url) : Optional.empty();
        if (meta.isEmpty()) {
            meta = openGraph(url);
        }
        return meta.map(m -> m.withImage(m.imageUrl() == null ? null : thumbnail(m.imageUrl(), uploaderId)));
    }

    /** 유튜브는 페이지 대신 oEmbed가 잘 맞는다. */
    private Optional<Fetched> youtube(String url) {
        String api = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, StandardCharsets.UTF_8);
        return fetcher.get(api, 64 * 1024).filter(f -> !f.truncated()).flatMap(f -> {
            try {
                JsonNode node = json.readTree(new String(f.body(), StandardCharsets.UTF_8));
                String title = text(node, "title");
                if (title == null) {
                    return Optional.empty();
                }
                String author = text(node, "author_name");
                return Optional.of(new Fetched(title, author, "YouTube", text(node, "thumbnail_url"), null));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private Optional<Fetched> openGraph(String url) {
        return fetcher.get(url, HTML_MAX_BYTES).flatMap(f -> {
            if (!f.contentType().toLowerCase(Locale.ROOT).contains("html")) {
                return Optional.empty();
            }
            Document doc = Jsoup.parse(new String(f.body(), charset(f.contentType())), f.finalUrl());
            String title = first(meta(doc, "og:title"), meta(doc, "twitter:title"), doc.title());
            if (title == null) {
                return Optional.empty();
            }
            String description = first(meta(doc, "og:description"), meta(doc, "twitter:description"),
                    meta(doc, "description"));
            String site = first(meta(doc, "og:site_name"), host(f.finalUrl()));
            String image = first(meta(doc, "og:image:secure_url"), meta(doc, "og:image"), meta(doc, "twitter:image"));
            if (image != null) {
                image = URI.create(f.finalUrl()).resolve(image.strip()).toString();
            }
            return Optional.of(new Fetched(title, description, site, image, null));
        });
    }

    private static String meta(Document doc, String key) {
        Element e = doc.selectFirst("meta[property=" + key + "], meta[name=" + key + "]");
        return e == null ? null : e.attr("content");
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    private static Charset charset(String contentType) {
        Matcher m = CHARSET.matcher(contentType);
        try {
            return m.find() ? Charset.forName(m.group(1)) : StandardCharsets.UTF_8;
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** 썸네일을 받아 이 사이트에 저장한다. 사진 형식은 파일 앞부분(시그니처)으로 다시 본다 (SEC-08). */
    private String thumbnail(String imageUrl, long uploaderId) {
        try {
            return fetcher.get(imageUrl, (int) uploadProps.maxFileSize().toBytes()).filter(f -> !f.truncated())
                    .flatMap(f -> images.storeFetched(uploaderId, f.body())).map(Image::getStoredName).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ----- 저장 -----

    private boolean fresh(String url, LocalDateTime now) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, fetched_at FROM link_previews WHERE url_hash = :h", Map.of("h", hash(url)));
        if (rows.isEmpty()) {
            return false;
        }
        LocalDateTime fetched = (LocalDateTime) rows.get(0).get("fetched_at");
        boolean ok = "OK".equals(rows.get(0).get("status"));
        // 실패한 주소는 하루 뒤에 다시 받아 본다
        return fetched.isAfter(now.minus(ok ? props.refreshAfter() : java.time.Duration.ofDays(1)));
    }

    private void save(String url, Optional<Fetched> f, LocalDateTime now) {
        Long imageId = f.map(Fetched::imageName).flatMap(name -> jdbc.queryForList(
                "SELECT id FROM images WHERE stored_name = :n", Map.of("n", name), Long.class).stream().findFirst())
                .orElse(null);
        jdbc.update("""
                INSERT INTO link_previews (url_hash, url, status, title, description, site_name, image_id, fetched_at)
                VALUES (:h, :url, :status, :title, :description, :site, :image, :now)
                ON DUPLICATE KEY UPDATE status = VALUES(status), title = VALUES(title), description = VALUES(description),
                  site_name = VALUES(site_name), image_id = VALUES(image_id), fetched_at = VALUES(fetched_at)
                """, new MapSqlParameterSource("h", hash(url)).addValue("url", cut(url, 2048))
                .addValue("status", f.isPresent() ? "OK" : "FAILED")
                .addValue("title", f.map(x -> cut(x.title(), TITLE_MAX)).orElse(null))
                .addValue("description", f.map(x -> cut(x.description(), DESCRIPTION_MAX)).orElse(null))
                .addValue("site", f.map(x -> cut(x.siteName(), 100)).orElse(null))
                .addValue("image", imageId).addValue("now", now));
    }

    private Map<String, Preview> find(List<String> urls) {
        Set<String> hashes = new LinkedHashSet<>();
        urls.forEach(u -> hashes.add(hash(u)));
        Map<String, Preview> result = new HashMap<>();
        jdbc.query("""
                SELECT lp.url_hash, lp.title, lp.description, lp.site_name, i.stored_name
                FROM link_previews lp LEFT JOIN images i ON i.id = lp.image_id
                WHERE lp.url_hash IN (:hashes) AND lp.status = 'OK'
                """, Map.of("hashes", hashes), rs -> {
                    result.put(rs.getString("url_hash"), new Preview(rs.getString("title"), rs.getString("description"),
                            rs.getString("site_name"), rs.getString("stored_name")));
                });
        return result;
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").strip();
        return t.codePointCount(0, t.length()) <= max ? t : t.substring(0, t.offsetByCodePoints(0, max - 1)) + "…";
    }

    private static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? url : h.startsWith("www.") ? h.substring(4) : h;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    static String hash(String url) {
        return Hashing.sha256Hex(url);
    }

    private record Fetched(String title, String description, String siteName, String imageUrl, String imageName) {

        Fetched withImage(String name) {
            return new Fetched(title, description, siteName, imageUrl, name);
        }
    }

    private record Preview(String title, String description, String siteName, String imageName) {
    }
}
