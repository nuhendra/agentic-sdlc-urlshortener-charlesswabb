package com.schwab.urlshortener;

import com.schwab.urlshortener.util.Base62;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Base62Test {

    @Test
    void encodesZeroToFirstAlphabetChar() {
        assertEquals("0", Base62.encode(0));
    }

    @Test
    void roundTripsAcrossAWideRange() {
        long[] samples = {1, 61, 62, 63, 3843, 1_000_000_000L, Long.MAX_VALUE};
        for (long v : samples) {
            assertEquals(v, Base62.decode(Base62.encode(v)), "round-trip failed for " + v);
        }
    }

    @Test
    void encodingIsStrictlyOrderPreservingInLength() {
        // 62 is the first value needing two chars.
        assertEquals(1, Base62.encode(61).length());
        assertEquals(2, Base62.encode(62).length());
    }

    @Test
    void rejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> Base62.encode(-1));
    }

    @Test
    void rejectsIllegalCharacters() {
        assertThrows(IllegalArgumentException.class, () -> Base62.decode("abc$def"));
    }
}
