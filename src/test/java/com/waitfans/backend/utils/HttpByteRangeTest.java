package com.waitfans.backend.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpByteRangeTest {
    @Test
    void returnsWholeObjectWhenRangeIsMissing() {
        HttpByteRange range = HttpByteRange.parse(null, 1000);

        assertEquals(0, range.getStart());
        assertEquals(999, range.getEnd());
        assertEquals(1000, range.getLength());
        assertFalse(range.isPartial());
    }

    @Test
    void parsesClosedOpenEndedAndSuffixRanges() {
        HttpByteRange closed = HttpByteRange.parse("bytes=100-199", 1000);
        HttpByteRange openEnded = HttpByteRange.parse("bytes=900-", 1000);
        HttpByteRange suffix = HttpByteRange.parse("bytes=-50", 1000);

        assertEquals(100, closed.getLength());
        assertEquals(900, openEnded.getStart());
        assertEquals(950, suffix.getStart());
        assertTrue(closed.isPartial());
    }

    @Test
    void rejectsMultipleAndOutOfBoundsRanges() {
        assertThrows(IllegalArgumentException.class, () -> HttpByteRange.parse("bytes=0-1,5-6", 1000));
        assertThrows(IllegalArgumentException.class, () -> HttpByteRange.parse("bytes=1000-", 1000));
        assertThrows(IllegalArgumentException.class, () -> HttpByteRange.parse("items=0-1", 1000));
    }
}
