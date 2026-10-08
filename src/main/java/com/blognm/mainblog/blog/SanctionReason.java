package com.blognm.mainblog.blog;

import com.blognm.mainblog.common.ApiException;

/**
 * 제재 사유 항목 (D-95). 신고 사유 6가지에 도배·사칭을 더했다. 신고를 처리하거나 멤버를 제재할 때 하나를 고르고,
 * 신고 처리 목록과 제재 기록을 이 항목으로 나눠 본다.
 */
public enum SanctionReason {
    SPAM("스팸·광고"),
    ABUSE("욕설·괴롭힘"),
    OBSCENE("음란·불쾌한 내용"),
    PRIVACY("개인정보 노출"),
    COPYRIGHT("저작권 침해"),
    FLOOD("도배"),
    IMPERSONATION("사칭"),
    ETC("기타");

    public final String label;

    SanctionReason(String label) {
        this.label = label;
    }

    /** 제재할 때는 꼭 골라야 한다. */
    public static SanctionReason require(SanctionReason code) {
        if (code == null) {
            throw ApiException.badRequest("INVALID_SANCTION_REASON", "제재 사유를 골라 주세요.");
        }
        return code;
    }

    public static String labelOf(String code) {
        return code == null ? null : valueOf(code).label;
    }
}
