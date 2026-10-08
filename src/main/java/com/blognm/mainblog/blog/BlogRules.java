package com.blognm.mainblog.blog;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.blognm.mainblog.common.ApiException;

/** 블로그 입력 규칙 (002 입력 규칙, 6.5). 화면도 같은 규칙으로 먼저 확인한다. */
public final class BlogRules {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]{3,30}$");
    private static final Pattern TAG = Pattern.compile("^[가-힣a-z0-9_]{1,20}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 사이트 주소와 겹치거나 헷갈리는 주소는 쓸 수 없다. */
    static final Set<String> RESERVED_SLUGS = Set.of(
            "main", "admin", "api", "login", "logout", "signup", "search", "blog", "blogs", "new", "me", "my",
            "manage", "settings", "help", "terms", "privacy", "notice", "notices", "feed", "static", "css", "js",
            "images", "image", "uploads", "upload", "www", "mail", "root", "system", "support", "official");

    public static final int NAME_MAX = 30;
    public static final int DESCRIPTION_MAX = 200;
    public static final int TAGS_MAX = 5;

    private BlogRules() {
    }

    public static String normalizeSlug(String raw) {
        String slug = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(slug).matches()) {
            throw ApiException.badRequest("INVALID_SLUG", "블로그 주소는 영문 소문자·숫자·-로 3~30자입니다.");
        }
        if (RESERVED_SLUGS.contains(slug)) {
            throw ApiException.badRequest("RESERVED_SLUG", "쓸 수 없는 주소입니다. 다른 주소를 정해 주세요.");
        }
        return slug;
    }

    /** 이름은 중복을 허용하고 1~30자 (추천안 #7). */
    public static String normalizeName(String raw) {
        String name = raw == null ? "" : raw.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > NAME_MAX || hasControlChars(name)) {
            throw ApiException.badRequest("INVALID_BLOG_NAME", "블로그 이름은 1~%d자로 써 주세요.".formatted(NAME_MAX));
        }
        return name;
    }

    /** 소개는 비워 둘 수 있고 200자까지 (추천안 #7). 줄바꿈은 허용한다. */
    public static String normalizeDescription(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        if (text.codePointCount(0, text.length()) > DESCRIPTION_MAX
                || text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r')) {
            throw ApiException.badRequest("INVALID_BLOG_DESCRIPTION",
                    "블로그 소개는 %d자까지 쓸 수 있습니다.".formatted(DESCRIPTION_MAX));
        }
        return text;
    }

    /** 블로그 태그는 5개까지, 한 개에 한글·영문·숫자·_ 1~20자, 소문자로 저장 (추천안 #3, 6.4). */
    public static List<String> normalizeTags(List<String> raw) {
        Set<String> tags = new LinkedHashSet<>();
        if (raw != null) {
            for (String t : raw) {
                if (t == null || t.isBlank()) {
                    continue;
                }
                String tag = normalizeTag(t);
                if (tag == null) {
                    throw ApiException.badRequest("INVALID_TAG", "태그는 한글·영문·숫자·_로 1~20자입니다.");
                }
                tags.add(tag);
            }
        }
        if (tags.size() > TAGS_MAX) {
            throw ApiException.badRequest("TOO_MANY_TAGS", "태그는 %d개까지 달 수 있습니다.".formatted(TAGS_MAX));
        }
        return List.copyOf(tags);
    }

    /** 맞지 않으면 null. 검색어에서 태그를 고를 때도 쓴다. */
    public static String normalizeTag(String raw) {
        String tag = raw.strip();
        if (tag.startsWith("#")) {
            tag = tag.substring(1);
        }
        tag = tag.toLowerCase(Locale.ROOT);
        return TAG.matcher(tag).matches() ? tag : null;
    }

    /** 일부 공개 링크 값. 블로그 주소와 별개인 추측할 수 없는 값 (BLG-01). */
    static String newShareToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static boolean hasControlChars(String s) {
        return s.chars().anyMatch(Character::isISOControl);
    }
}
