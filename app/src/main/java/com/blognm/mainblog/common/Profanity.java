package com.blognm.mainblog.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 욕설 거르기 (D-125). 목록은 resources/profanity/words.txt, 욕이 아닌 말은 allow.txt.
 * 글자(한글·자모·영문) 사이에 낀 기호·숫자는 건너뛰고 찾지만(시1발, 시.발) 띄어쓰기를 넘어서는 찾지 않는다.
 * 목록은 처음 한 번 읽어 두고 바뀌지 않는다 (서버 메모리에 바뀌는 상태를 두지 않음, SCL-01).
 */
public final class Profanity {

    private static final List<String> WORDS = load("profanity/words.txt");
    private static final List<String> ALLOWED = load("profanity/allow.txt");

    /** 링크 주소는 거르지 않는다. 주소가 바뀌면 링크·사진이 깨진다. */
    private static final Pattern LINK = Pattern.compile("https?://[^\\s)\\]]+|\\]\\([^)\\n]*\\)");

    private Profanity() {
    }

    public static boolean contains(String text) {
        return !find(text).isEmpty();
    }

    /** 찾은 욕설을 글자 수만큼 *로 가린다. 닉네임·댓글처럼 그대로 보여 주는 글에 쓴다. */
    public static String mask(String text) {
        return mask(text, "*");
    }

    /** 마크다운 본문용. *가 굵게·기울임 표시로 읽히지 않게 \*로 넣는다. */
    public static String maskMarkdown(String text) {
        return mask(text, "\\*");
    }

    private static String mask(String text, String star) {
        List<int[]> spans = find(text);
        if (spans.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        int from = 0;
        for (int[] span : spans) {
            out.append(text, from, span[0]);
            out.append(star.repeat(text.codePointCount(span[0], span[1])));
            from = span[1];
        }
        return out.append(text, from, text.length()).toString();
    }

    /** 원문에서 가릴 구간 [시작, 끝)을 앞에서부터, 겹치지 않게 돌려준다. */
    private static List<int[]> find(String text) {
        if (text == null || text.isEmpty() || WORDS.isEmpty()) {
            return List.of();
        }
        boolean[] skip = new boolean[text.length()];
        Matcher link = LINK.matcher(text);
        while (link.find()) {
            for (int i = link.start(); i < link.end(); i++) {
                skip[i] = true;
            }
        }
        // 글자만 이어 붙인 문자열과, 각 글자의 원문 위치·앞에 띄어쓰기(또는 링크)가 있었는지
        StringBuilder letters = new StringBuilder();
        List<Integer> at = new ArrayList<>();
        List<Boolean> breakBefore = new ArrayList<>();
        boolean gap = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (skip[i] || Character.isWhitespace(c)) {
                gap = true;
            } else if (Character.isLetter(c)) {
                letters.append(Character.toLowerCase(c));
                at.add(i);
                breakBefore.add(gap);
                gap = false;
            }
        }
        String plain = letters.toString();
        boolean[] allowed = new boolean[plain.length()];
        for (String word : ALLOWED) {
            for (int s = plain.indexOf(word); s >= 0; s = plain.indexOf(word, s + 1)) {
                for (int k = s; k < s + word.length(); k++) {
                    allowed[k] = true;
                }
            }
        }
        boolean[] hit = new boolean[plain.length()];
        for (String word : WORDS) {
            for (int s = plain.indexOf(word); s >= 0; s = plain.indexOf(word, s + 1)) {
                int e = s + word.length();
                if (crossesGap(breakBefore, s, e) || allAllowed(allowed, s, e)) {
                    continue;
                }
                for (int k = s; k < e; k++) {
                    hit[k] = true;
                }
            }
        }
        // 이어진 글자끼리 묶어 원문 구간으로. 사이에 낀 기호(시1발의 1)까지 함께 가린다.
        List<int[]> spans = new ArrayList<>();
        for (int k = 0; k < plain.length(); k++) {
            if (!hit[k]) {
                continue;
            }
            int end = k;
            while (end + 1 < plain.length() && hit[end + 1] && !breakBefore.get(end + 1)) {
                end++;
            }
            spans.add(new int[] { at.get(k), at.get(end) + 1 });
            k = end;
        }
        return spans;
    }

    private static boolean crossesGap(List<Boolean> breakBefore, int start, int end) {
        for (int k = start + 1; k < end; k++) {
            if (breakBefore.get(k)) {
                return true;
            }
        }
        return false;
    }

    private static boolean allAllowed(boolean[] allowed, int start, int end) {
        for (int k = start; k < end; k++) {
            if (!allowed[k]) {
                return false;
            }
        }
        return true;
    }

    private static List<String> load(String path) {
        try (InputStream in = Profanity.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(path + " 파일이 없습니다.");
            }
            List<String> words = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    String word = line.strip().toLowerCase(Locale.ROOT);
                    if (!word.isEmpty() && !word.startsWith("#")) {
                        words.add(word);
                    }
                }
            }
            return List.copyOf(words);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
