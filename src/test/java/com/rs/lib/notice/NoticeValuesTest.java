package com.rs.lib.notice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;

class NoticeValuesTest {

    @Test
    void valuesSurviveTheTripWithTheirLiteralFlag() {
        List<Ph> values = List.of(Ph.of("price", 4500), Ph.literal("item", "a=b\\n\nc\r"), Ph.of("k=ey", ""),
                Ph.literal("empty", ""));
        assertEquals(values, NoticeValues.decode(NoticeValues.encode(values)));
    }

    @Test
    void nothingAndBrokenLinesDecodeToWhatCanBeRead() {
        assertEquals(List.of(), NoticeValues.decode(""));
        assertEquals(List.of(), NoticeValues.decode(null));
        assertEquals(List.of(Ph.of("a", "1")), NoticeValues.decode("x\nfnoequals\nfa=1\nl=2"));
    }
}
