package com.finguard.transactionapi.finance.importing;

import org.apache.commons.csv.CSVException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Syntax-only, bounded UTF-8 CSV parsing. No bank, date, or amount interpretation. */
public final class CsvParser {
    public static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024;
    public static final int DEFAULT_MAX_ROWS = 5_000;

    private static final CSVFormat FORMAT = CSVFormat.RFC4180.builder()
            .setIgnoreEmptyLines(true)
            .setLenientEof(false)
            .setTrailingData(false)
            .get();

    private final long maxBytes;
    private final int maxRows;

    public CsvParser() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_ROWS);
    }

    public CsvParser(long maxBytes, int maxRows) {
        if (maxBytes <= 0 || maxRows <= 0) {
            throw new IllegalArgumentException("CSV limits must be positive");
        }
        this.maxBytes = maxBytes;
        this.maxRows = maxRows;
    }

    /**
     * Consumes and closes the supplied stream, including on failure. Byte limits include a BOM.
     * The first nonblank record is the header (logical record 1); empty physical lines are ignored.
     * Quoted multiline fields count as one record. Data records, including all-empty delimited
     * records, retain their cells for profile validation. Commons CSV treats a quote inside an
     * unquoted field as a literal; unclosed quoted fields and trailing nonwhitespace are rejected.
     */
    public CsvParseResult parse(InputStream input) {
        Objects.requireNonNull(input, "input");
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        try (var reader = new PushbackReader(
                new InputStreamReader(new BoundedInputStream(input, maxBytes), decoder), 1)) {
            int first = reader.read();
            if (first != -1 && first != '\uFEFF') {
                reader.unread(first);
            }
            try (var parser = FORMAT.parse(reader)) {
                List<String> headers = null;
                List<ParsedCsvRow> rows = new ArrayList<>();
                for (CSVRecord record : parser) {
                    if (headers == null) {
                        if (record.size() == 1 && record.get(0).isBlank()) {
                            continue;
                        }
                        headers = record.toList();
                    } else {
                        if (rows.size() >= maxRows) {
                            throw new CsvParseException(ImportIssueCode.TOO_MANY_ROWS);
                        }
                        rows.add(new ParsedCsvRow(rows.size() + 2L, record.toList()));
                    }
                }
                if (headers == null) {
                    throw new CsvParseException(ImportIssueCode.EMPTY_FILE);
                }
                return new CsvParseResult(headers, rows);
            }
        } catch (CsvParseException failure) {
            // Resource-close exceptions can contain private data in suppressed exceptions.
            throw new CsvParseException(failure.code());
        } catch (IOException | UncheckedIOException failure) {
            throw new CsvParseException(issueCode(failure));
        }
    }

    private static ImportIssueCode issueCode(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ByteLimitExceededException) {
                return ImportIssueCode.FILE_TOO_LARGE;
            }
            if (current instanceof CharacterCodingException) {
                return ImportIssueCode.INVALID_ENCODING;
            }
            if (current instanceof CSVException) {
                return ImportIssueCode.INVALID_CSV;
            }
        }
        return ImportIssueCode.IO_ERROR;
    }

    /** Reads at most the configured bytes plus one probe byte; never buffers the whole file. */
    private static final class BoundedInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        private BoundedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value != -1) {
                if (count == limit) {
                    throw new ByteLimitExceededException();
                }
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            long remaining = limit - count;
            if (remaining == 0) {
                return read(); // EOF or a one-byte probe which throws before any value is returned.
            }
            int read = in.read(bytes, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                count += read;
            }
            return read;
        }
    }

    private static final class ByteLimitExceededException extends IOException {
    }
}
