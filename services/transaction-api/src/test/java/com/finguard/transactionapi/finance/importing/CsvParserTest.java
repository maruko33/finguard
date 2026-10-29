package com.finguard.transactionapi.finance.importing;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvParserTest {
    private final CsvParser parser = new CsvParser();

    @Test
    void preservesQuotedCommasEscapedQuotesAndEmptyOptionalCells() {
        CsvParseResult result = parse("first,second,third\n\"alpha, beta\",\"a \"\"quote\"\"\",\n");

        assertEquals(List.of("first", "second", "third"), result.headers());
        assertEquals(List.of("alpha, beta", "a \"quote\"", ""), result.rows().getFirst().values());
        assertEquals(2L, result.rows().getFirst().sourceRowNumber());
    }

    @Test
    void removesUtf8BomBeforeQuotedHeaderAndAcceptsCrLf() {
        CsvParseResult result = parse("\uFEFF\"first\",second\r\nvalue,\r\n");

        assertEquals(List.of("first", "second"), result.headers());
        assertEquals(List.of("value", ""), result.rows().getFirst().values());
    }

    @Test
    void numbersLogicalRecordsAcrossBlankLinesAndQuotedMultilineValues() {
        CsvParseResult result = parse("\r\nfirst,second\r\n\r\n\"line one\r\nline two\",x\r\n\r\nlast,y\r\n");

        assertEquals(List.of(2L, 3L), result.rows().stream().map(ParsedCsvRow::sourceRowNumber).toList());
        assertEquals("line one\r\nline two", result.rows().getFirst().values().getFirst());
    }

    @Test
    void preservesDelimitedEmptyAndQuotedEmptyDataRecordsWithoutSilentDrops() {
        CsvParseResult result = parse("a,b\n,\n\"\"\n \t \n");

        assertEquals(3, result.rows().size());
        assertEquals(List.of("", ""), result.rows().get(0).values());
        assertEquals(List.of(""), result.rows().get(1).values());
        assertEquals(List.of(" \t "), result.rows().get(2).values());
        assertEquals(List.of(2L, 3L, 4L), result.rows().stream().map(ParsedCsvRow::sourceRowNumber).toList());
    }

    @Test
    void leavesVariableRecordWidthsAndBankSpecificValuesForProfileValidation() {
        CsvParseResult result = parse("header,other\nnot-a-date\nnot-money,x,extra\n");

        assertEquals(List.of("not-a-date"), result.rows().getFirst().values());
        assertEquals(List.of("not-money", "x", "extra"), result.rows().getLast().values());
    }

    @Test
    void allowsHeaderOnlyCsvButRejectsEmptyAndBlankOnlyInputs() {
        assertTrue(parse("a,b\r\n").rows().isEmpty());
        for (String input : List.of("", "\uFEFF", "\r\n\n", " \t \r\n \n")) {
            assertFailure(ImportIssueCode.EMPTY_FILE, () -> parse(input));
        }
    }

    @Test
    void rejectsMalformedQuotedFieldsWithSafeErrors() {
        for (String input : List.of("a,b\n\"fixture-private-data,x", "a,b\n\"x\"fixture-private-data,y")) {
            CsvParseException error = assertFailure(ImportIssueCode.INVALID_CSV, () -> parse(input));
            assertSafe(error, "fixture-private-data");
        }
    }

    @Test
    void usesCommonsCsvLiteralBareQuoteDialectWithoutClaimingStrictRfc() {
        assertEquals(List.of("a\"b", "x"), parse("first,second\na\"b,x\n").rows().getFirst().values());
    }

    @Test
    void rejectsMalformedUtf8InsteadOfReplacingInvalidBytes() {
        byte[] invalid = new byte[]{'a', '\n', (byte) 0xc3, (byte) 0x28};
        assertFailure(ImportIssueCode.INVALID_ENCODING, () -> parser.parse(new ByteArrayInputStream(invalid)));
    }

    @Test
    void enforcesByteLimitIncludingBomWithoutOverreadingBeyondOneProbe() {
        byte[] bytes = "\uFEFFa\nx\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(1, new CsvParser(bytes.length, 1).parse(new ByteArrayInputStream(bytes)).rows().size());
        assertFailure(ImportIssueCode.FILE_TOO_LARGE,
                () -> new CsvParser(bytes.length - 1, 1).parse(new ByteArrayInputStream(bytes)));

        RepeatingInputStream endless = new RepeatingInputStream(Long.MAX_VALUE, "x");
        assertFailure(ImportIssueCode.FILE_TOO_LARGE, () -> new CsvParser(31, 1).parse(endless));
        assertEquals(32, endless.consumed);
        assertTrue(endless.closed);
    }

    @Test
    void defaultByteLimitAcceptsTwoMiBAndRejectsTheNextByteDuringProcessing() {
        RepeatingInputStream exact = new RepeatingInputStream(2L * 1024 * 1024, "x");
        assertEquals(2 * 1024 * 1024, parser.parse(exact).headers().getFirst().length());
        assertTrue(exact.closed);

        RepeatingInputStream oversized = new RepeatingInputStream(Long.MAX_VALUE, "x");
        assertFailure(ImportIssueCode.FILE_TOO_LARGE, () -> parser.parse(oversized));
        assertEquals(2L * 1024 * 1024 + 1, oversized.consumed);
    }

    @Test
    void defaultRowLimitAllowsFiveThousandDataRecordsAndRejectsNextRecord() {
        CsvParseResult result = parser.parse(new RepeatingInputStream(5_001L * 2, "a\n"));
        assertEquals(5_000, result.rows().size());
        assertEquals(5_001, result.rows().getLast().sourceRowNumber());
        assertFailure(ImportIssueCode.TOO_MANY_ROWS,
                () -> parser.parse(new RepeatingInputStream(5_002L * 2, "a\n")));
    }

    @Test
    void rowLimitStopsStreamingBeforeReadingTheWholeInput() {
        RepeatingInputStream manyRows = new RepeatingInputStream(1_000_000, "a\n");
        assertFailure(ImportIssueCode.TOO_MANY_ROWS, () -> new CsvParser(2_000_000, 3).parse(manyRows));
        assertTrue(manyRows.consumed < 1_000_000, "The row bound must stop processing before EOF");
        assertTrue(manyRows.closed);
    }

    @Test
    void rejectsNonPositiveResourceLimits() {
        assertThrows(IllegalArgumentException.class, () -> new CsvParser(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new CsvParser(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CsvParser(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CsvParser(1, -1));
    }

    @Test
    void convertsIoFailuresWithoutLeakingMessagesCausesOrSuppressedExceptions() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("fixture-private-read");
            }

            @Override
            public void close() throws IOException {
                throw new IOException("fixture-private-close");
            }
        };
        CsvParseException error = assertFailure(ImportIssueCode.IO_ERROR, () -> parser.parse(failing));
        assertSafe(error, "fixture-private");
    }

    @Test
    void sanitizesSuppressedCloseFailuresAfterStructuredRowLimitFailure() {
        InputStream failingClose = new ByteArrayInputStream("a\nx\ny\n".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                throw new IOException("fixture-private-close");
            }
        };
        CsvParseException error = assertFailure(ImportIssueCode.TOO_MANY_ROWS,
                () -> new CsvParser(100, 1).parse(failingClose));
        assertSafe(error, "fixture-private-close");
    }

    private CsvParseResult parse(String input) {
        return parser.parse(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static CsvParseException assertFailure(ImportIssueCode expected, Runnable action) {
        CsvParseException error = assertThrows(CsvParseException.class, action::run);
        assertEquals(expected, error.code());
        assertEquals(expected.message(), error.getMessage());
        return error;
    }

    private static void assertSafe(CsvParseException error, String privateText) {
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        StringWriter stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        assertFalse(stack.toString().contains(privateText));
    }

    /** Generates input on demand so limit tests never allocate the oversized source. */
    private static final class RepeatingInputStream extends InputStream {
        private final long length;
        private final byte[] pattern;
        private long consumed;
        private boolean closed;

        private RepeatingInputStream(long length, String pattern) {
            this.length = length;
            this.pattern = pattern.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int read() {
            if (consumed >= length) {
                return -1;
            }
            int next = pattern[(int) (consumed % pattern.length)] & 0xff;
            consumed++;
            return next;
        }

        @Override
        public int read(byte[] bytes, int offset, int requested) {
            if (requested == 0) {
                return 0;
            }
            if (consumed >= length) {
                return -1;
            }
            int actual = (int) Math.min(requested, length - consumed);
            for (int index = 0; index < actual; index++) {
                bytes[offset + index] = (byte) read();
            }
            return actual;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
