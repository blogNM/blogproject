package com.blognm.mainblog.member;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.common.Profanity;

/** 회원 입력 규칙 (001 입력 규칙, 6.5, 6.7, SEC-02). 화면에서도 같은 규칙을 안내하지만 서버가 최종 확인한다. */
public final class MemberRules {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern NICKNAME = Pattern.compile("^[가-힣a-zA-Z0-9]{2,12}$");
    private static final List<String> RESERVED_NICKNAMES = List.of("관리자", "admin", "운영자", "탈퇴한회원");
    private static final Pattern PASSWORD_CHARS = Pattern.compile("^[\\x21-\\x7E]{8,15}$");
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern HAS_DIGIT = Pattern.compile("[0-9]");
    private static final Pattern HAS_SPECIAL = Pattern.compile("[^A-Za-z0-9]");
    // 추천안 #5 (확인 대기): 이름 1~20자, 한글·영문·공백
    private static final Pattern NAME = Pattern.compile("^[가-힣a-zA-Z ]{1,20}$");
    // 추천안 #5 (확인 대기): 숫자만 10~11자리, 0으로 시작
    private static final Pattern PHONE = Pattern.compile("^0[0-9]{9,10}$");

    private MemberRules() {
    }

    /** 이메일은 소문자로 바꿔 저장한다. 대소문자만 다른 이메일은 같은 이메일이다. */
    public static String normalizeEmail(String raw) {
        String email = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 255 || !EMAIL.matcher(email).matches()) {
            throw ApiException.badRequest("INVALID_EMAIL", "이메일 형식이 올바르지 않습니다.");
        }
        return email;
    }

    public static void checkPassword(String password) {
        if (password == null || !PASSWORD_CHARS.matcher(password).matches()
                || !HAS_LETTER.matcher(password).find()
                || !HAS_DIGIT.matcher(password).find()
                || !HAS_SPECIAL.matcher(password).find()) {
            throw ApiException.badRequest("INVALID_PASSWORD",
                    "비밀번호는 8~15자로 영문·숫자·특수문자를 모두 넣어 주세요.");
        }
    }

    public static String checkNickname(String raw) {
        String nickname = raw == null ? "" : raw.trim();
        if (!NICKNAME.matcher(nickname).matches()) {
            throw ApiException.badRequest("INVALID_NICKNAME", "닉네임은 2~12자의 한글·영문·숫자로 입력해 주세요.");
        }
        String lower = nickname.toLowerCase(Locale.ROOT);
        for (String reserved : RESERVED_NICKNAMES) {
            if (lower.contains(reserved)) {
                throw ApiException.badRequest("RESERVED_NICKNAME", "사용할 수 없는 닉네임입니다.");
            }
        }
        // 닉네임은 가리면 한글·영문·숫자 규칙에 어긋나므로 받지 않는다 (D-125)
        if (Profanity.contains(nickname)) {
            throw ApiException.badRequest("PROFANE_NICKNAME", "욕설이 든 닉네임은 쓸 수 없습니다.");
        }
        return nickname;
    }

    /** 이름은 앞뒤 공백을 지우고, 가운데 여러 칸은 한 칸으로 줄인다. */
    public static String normalizeName(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (!NAME.matcher(name).matches()) {
            throw ApiException.badRequest("INVALID_NAME", "이름은 1~20자의 한글·영문으로 입력해 주세요.");
        }
        return name;
    }

    /** 전화번호는 숫자만 남겨 저장한다 (USR-01). */
    public static String normalizePhone(String raw) {
        String phone = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (!PHONE.matcher(phone).matches()) {
            throw ApiException.badRequest("INVALID_PHONE", "전화번호는 0으로 시작하는 숫자 10~11자리로 입력해 주세요.");
        }
        return phone;
    }
}
