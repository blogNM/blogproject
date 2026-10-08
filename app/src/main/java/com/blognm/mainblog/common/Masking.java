package com.blognm.mainblog.common;

/** 개인정보 가림 (4.4). */
public final class Masking {

    private Masking() {
    }

    /** ab***@gmail.com 처럼 아이디 앞 두 글자만 보여준다. 두 글자 이하면 한 글자만. */
    public static String email(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        int keep = local.length() <= 2 ? 1 : 2;
        return local.substring(0, keep) + "***" + email.substring(at);
    }

    /** 010****5678 처럼 가운데를 가린다. */
    public static String phone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone == null ? null : "***";
        }
        return phone.substring(0, 3) + "*".repeat(phone.length() - 7) + phone.substring(phone.length() - 4);
    }
}
