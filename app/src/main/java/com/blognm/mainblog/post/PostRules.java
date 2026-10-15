package com.blognm.mainblog.post;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.blognm.mainblog.blog.BlogRules;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.common.Profanity;

/** 글·카테고리 입력 규칙 (004 6.6, 6.4). 서버가 최종으로 확인한다. */
public final class PostRules {

    public static final int TITLE_MAX = 30;
    /** 본문 최대 글자 수, 공백 포함 (D-121). 블로그 글 권장 분량(공백 포함 2,500~3,000자)이 들어간다. */
    public static final int CONTENT_MAX = 3000;
    public static final int TAGS_MAX = 10;
    public static final int CATEGORY_NAME_MAX = 20;

    /** 이미지 문법 ![설명](주소). 본문 글자 수에서 뺀다 (추천안 #14). */
    private static final Pattern IMAGE_SYNTAX = Pattern.compile("!\\[[^\\]\\n]*\\]\\([^)\\n]*\\)");

    /** 글자 색·크기·형광펜 표시 <span class="tc-…|ts-…|hl-…">·</span>도 글자 수에 넣지 않는다 (D-93, D-106). */
    private static final Pattern COLOR_TAG = Pattern.compile("<span class=\"(?:tc|ts|hl)-[a-z]+\">|</span>");

    /**
     * 체크박스 목록 표시 "[ ] "·"[x] "도 글자 수에 넣지 않는다 (D-110). 화면에는 네모 칸으로 보이므로
     * 글머리 목록과 같게 "- "까지만 센다. 목록 줄 맨 앞(- * + 또는 1.) 바로 뒤의 것만 뺀다.
     */
    private static final Pattern TASK_MARKER = Pattern.compile("(?m)^([ \\t]*(?:[-*+]|\\d{1,9}[.)])[ \\t]+)\\[[ xX]\\](?:[ \\t]|$)");

    private PostRules() {
    }

    public static String normalizeTitle(String raw) {
        String title = raw == null ? "" : raw.strip();
        int length = title.codePointCount(0, title.length());
        if (length < 1 || length > TITLE_MAX || title.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("INVALID_TITLE", "제목은 1~%d자로 써 주세요.".formatted(TITLE_MAX));
        }
        return Profanity.mask(title);
    }

    /**
     * 본문은 마크다운 원문 그대로 저장한다. 글자 수는 이미지 문법을 뺀 원문으로 센다 (추천안 #14).
     * 욕설은 저장할 때 *로 가린다 (D-125). 글자 수는 가리기 전 원문으로 센다.
     */
    public static String checkContent(String raw) {
        String content = raw == null ? "" : raw.replace("\r\n", "\n");
        if (content.isBlank()) {
            throw ApiException.badRequest("INVALID_CONTENT", "본문을 써 주세요.");
        }
        if (contentLength(content) > CONTENT_MAX) {
            throw ApiException.badRequest("CONTENT_TOO_LONG",
                    "본문은 공백 포함 %,d자까지 쓸 수 있습니다. (사진은 글자 수에 넣지 않습니다.)".formatted(CONTENT_MAX));
        }
        if (content.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw ApiException.badRequest("INVALID_CONTENT", "본문에 쓸 수 없는 문자가 있습니다.");
        }
        return Profanity.maskMarkdown(content);
    }

    public static int contentLength(String content) {
        String text = COLOR_TAG.matcher(IMAGE_SYNTAX.matcher(content).replaceAll("")).replaceAll("");
        text = TASK_MARKER.matcher(text).replaceAll("$1");
        return text.codePointCount(0, text.length());
    }

    /** 글 태그는 10개까지. 앞의 #·공백 정리, 소문자, 한 글 안의 중복은 하나로 (6.4). 욕설 태그는 받지 않는다 (D-125). */
    public static List<String> normalizeTags(List<String> raw) {
        Set<String> tags = new LinkedHashSet<>();
        if (raw != null) {
            for (String t : raw) {
                if (t == null || t.isBlank()) {
                    continue;
                }
                String tag = BlogRules.normalizeTag(t);
                if (tag == null) {
                    throw ApiException.badRequest("INVALID_TAG", "태그는 한글·영문·숫자·_로 1~20자입니다.");
                }
                if (Profanity.contains(tag)) {
                    throw ApiException.badRequest("PROFANE_TAG", "태그에 쓸 수 없는 말이 있습니다: #" + tag);
                }
                tags.add(tag);
            }
        }
        if (tags.size() > TAGS_MAX) {
            throw ApiException.badRequest("TOO_MANY_TAGS", "태그는 %d개까지 달 수 있습니다.".formatted(TAGS_MAX));
        }
        return List.copyOf(tags);
    }

    public static String normalizeCategoryName(String raw) {
        String name = raw == null ? "" : raw.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > CATEGORY_NAME_MAX || name.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("INVALID_CATEGORY",
                    "카테고리 이름은 1~%d자로 써 주세요.".formatted(CATEGORY_NAME_MAX));
        }
        return name;
    }
}
