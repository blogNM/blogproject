package com.blognm.mainblog.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import com.blognm.mainblog.common.ApiException;

class MemberRulesTest {

    @Test
    void emailIsLowercased() {
        assertThat(MemberRules.normalizeEmail("  AbC@Example.COM ")).isEqualTo("abc@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Abcd123!", "abc123!@#", "A1!aaaaaaaaaaaa"})
    void validPasswords(String pw) {
        MemberRules.checkPassword(pw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Abc12!", "Abcd1234", "abcd!!!!", "12345678!", "Abcd 123!", "A1!aaaaaaaaaaaaa", "가나다라123!"})
    void invalidPasswords(String pw) {
        assertThatThrownBy(() -> MemberRules.checkPassword(pw)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"관리자", "Admin", "운영자123", "탈퇴한회원", "a", "닉네임이열세글자를넘어갑니다", "nick name", "nick!"})
    void invalidNicknames(String nickname) {
        assertThatThrownBy(() -> MemberRules.checkNickname(nickname)).isInstanceOf(ApiException.class);
    }

    @Test
    void phoneKeepsDigitsOnly() {
        assertThat(MemberRules.normalizePhone("010-1234-5678")).isEqualTo("01012345678");
        assertThat(MemberRules.normalizePhone("02 1234 5678")).isEqualTo("0212345678");
        assertThatThrownBy(() -> MemberRules.normalizePhone("1012345678")).isInstanceOf(ApiException.class);
    }

    @Test
    void nameIsTrimmed() {
        assertThat(MemberRules.normalizeName("  홍  길동 ")).isEqualTo("홍 길동");
        assertThatThrownBy(() -> MemberRules.normalizeName("홍길동1")).isInstanceOf(ApiException.class);
    }
}
