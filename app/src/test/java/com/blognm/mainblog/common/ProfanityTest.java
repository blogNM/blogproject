package com.blognm.mainblog.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 욕설 거르기 (D-125) */
class ProfanityTest {

    @Test
    @DisplayName("욕설은 글자 수만큼 *로 가리고 나머지는 그대로 둔다")
    void masksWords() {
        assertThat(Profanity.mask("아 시발 진짜")).isEqualTo("아 ** 진짜");
        assertThat(Profanity.mask("What the FUCK is this")).isEqualTo("What the **** is this");
        assertThat(Profanity.mask("이 개새끼야")).isEqualTo("이 ***야");
    }

    @Test
    @DisplayName("글자 사이에 낀 기호·숫자도 찾고, 그 기호까지 함께 가린다")
    void skipsSymbolsBetweenLetters() {
        assertThat(Profanity.mask("시1발")).isEqualTo("***");
        assertThat(Profanity.mask("씨.발!")).isEqualTo("***!");
        assertThat(Profanity.mask("s.h.i.t")).isEqualTo("*******");
    }

    @ParameterizedTest
    @ValueSource(strings = { "새끼 고양이가 귀엽다", "껌을 씹다", "시바견 산책", "등신대 전시", "시발점부터 다시", "class pass",
            "하시 발표를 듣다", "Scunthorpe United" })
    @DisplayName("다른 뜻으로 쓰는 말, 띄어쓰기를 넘는 말은 가리지 않는다")
    void leavesInnocentText(String text) {
        assertThat(Profanity.contains(text)).isFalse();
        assertThat(Profanity.mask(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("마크다운 본문은 \\*로 가리고, 링크·사진 주소는 건드리지 않는다")
    void markdownKeepsLinks() {
        assertThat(Profanity.maskMarkdown("**시발** 끝")).isEqualTo("**\\*\\*** 끝");
        assertThat(Profanity.maskMarkdown("[shit](https://example.com/shit) https://a.com/fuck"))
                .isEqualTo("[\\*\\*\\*\\*](https://example.com/shit) https://a.com/fuck");
    }

    @Test
    void emptyText() {
        assertThat(Profanity.mask("")).isEmpty();
        assertThat(Profanity.mask(null)).isNull();
        assertThat(Profanity.contains(null)).isFalse();
    }
}
