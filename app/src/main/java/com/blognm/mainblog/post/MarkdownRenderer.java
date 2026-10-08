package com.blognm.mainblog.post;

import java.util.List;
import java.util.Set;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * 글 본문 마크다운을 HTML로 바꾸고 jsoup으로 거른다 (헌법 III, D-64).
 * 화면은 이 결과만 innerHTML로 넣는다. 스크립트·이벤트 속성·style·iframe은 모두 지워진다.
 */
@Component
public class MarkdownRenderer {

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create(),
            StrikethroughExtension.create(), AutolinkExtension.create());

    /** 링크는 http·https·mailto. 이미지는 이 사이트에 올린 사진(/uploads/...)만 (CSP img-src 'self'). */
    private static final Safelist SAFELIST = Safelist.relaxed()
            .addTags("del", "s", "hr")
            .addAttributes("span", "class")
            .removeProtocols("img", "src", "http", "https")
            .addProtocols("img", "src", "https")
            .addAttributes("a", "rel")
            .preserveRelativeLinks(true);

    /**
     * 글자 색 (사용자 요청 2026-10-08). 에디터가 {@code <span class="tc-red">글자</span>}로 넣는다.
     * CSP가 style 속성을 막으므로 색은 정해 둔 class로만 준다. 다른 class나 style은 지우고 글자만 남긴다.
     */
    public static final Set<String> TEXT_COLORS = Set.of("tc-red", "tc-orange", "tc-green", "tc-blue", "tc-purple",
            "tc-mauve", "tc-gray");

    private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().extensions(EXTENSIONS).build();

    public String toSafeHtml(String markdown) {
        String raw = renderer.render(parser.parse(markdown == null ? "" : markdown));
        // 상대 주소(/uploads/...)를 살리려면 기준 주소가 있어야 한다. 기준 주소는 결과에 남지 않는다.
        Document dirty = Jsoup.parseBodyFragment(raw, "https://mainblog.invalid/");
        Document clean = new Cleaner(SAFELIST).clean(dirty);
        clean.select("a[href]").forEach(a -> a.attr("rel", "nofollow noopener noreferrer"));
        clean.select("span").forEach(span -> {
            if (!TEXT_COLORS.contains(span.attr("class"))) {
                span.unwrap();
            }
        });
        clean.select("img[src]").forEach(img -> {
            if (!isAllowedImage(img.attr("src"))) {
                img.remove();
            }
        });
        clean.outputSettings().prettyPrint(false);
        return clean.body().html();
    }

    /** 미리보기 문구(og:description)용 글자만. */
    public String toPlainText(String markdown, int maxLength) {
        String text = Jsoup.parse(toSafeHtml(markdown)).text().strip();
        if (text.codePointCount(0, text.length()) <= maxLength) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxLength)) + "…";
    }

    private static boolean isAllowedImage(String src) {
        return src.startsWith("/uploads/");
    }
}
