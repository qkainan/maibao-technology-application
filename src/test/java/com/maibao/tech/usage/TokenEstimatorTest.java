package com.maibao.tech.usage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void should_count_each_chinese_char_as_one_token() {
        assertThat(estimator.estimate("你好")).isEqualTo(2);
    }

    @Test
    void should_count_about_four_latin_chars_per_token() {
        assertThat(estimator.estimate("hello world")).isEqualTo(3); // 11 / 4 向上取整
    }

    @Test
    void should_mix_chinese_and_latin() {
        assertThat(estimator.estimate("你好hello")).isEqualTo(4); // 2 + ceil(5/4)
    }

    @Test
    void should_return_zero_for_empty_text() {
        assertThat(estimator.estimate(null)).isZero();
        assertThat(estimator.estimate("")).isZero();
    }

    @Test
    void should_never_return_zero_for_non_empty_text() {
        assertThat(estimator.estimate("a")).isEqualTo(1);
        assertThat(estimator.estimate(" ")).isEqualTo(1);
    }
}
