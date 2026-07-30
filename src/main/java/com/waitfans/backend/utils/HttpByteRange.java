package com.waitfans.backend.utils;

public class HttpByteRange {
    private final long start;
    private final long end;
    private final long total;
    private final boolean partial;

    private HttpByteRange(long start, long end, long total, boolean partial) {
        this.start = start;
        this.end = end;
        this.total = total;
        this.partial = partial;
    }

    public static HttpByteRange parse(String rangeHeader, long total) {
        if (total <= 0) {
            throw new IllegalArgumentException("Stored object is empty");
        }
        if (rangeHeader == null || rangeHeader.trim().isEmpty()) {
            return new HttpByteRange(0, total - 1, total, false);
        }
        if (!rangeHeader.startsWith("bytes=") || rangeHeader.indexOf(',') >= 0) {
            throw new IllegalArgumentException("Only a single HTTP byte range is supported");
        }
        String requested = rangeHeader.substring("bytes=".length()).trim();
        int separator = requested.indexOf('-');
        if (separator < 0) {
            throw new IllegalArgumentException("Malformed HTTP byte range");
        }
        String startText = requested.substring(0, separator).trim();
        String endText = requested.substring(separator + 1).trim();
        try {
            long start;
            long end;
            if (startText.isEmpty()) {
                long suffixLength = Long.parseLong(endText);
                if (suffixLength <= 0) {
                    throw new IllegalArgumentException("Invalid suffix byte range");
                }
                start = Math.max(0, total - suffixLength);
                end = total - 1;
            } else {
                start = Long.parseLong(startText);
                end = endText.isEmpty() ? total - 1 : Long.parseLong(endText);
                if (start < 0 || start >= total || end < start) {
                    throw new IllegalArgumentException("HTTP byte range is outside the object");
                }
                end = Math.min(end, total - 1);
            }
            return new HttpByteRange(start, end, total, true);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed HTTP byte range", e);
        }
    }

    public long getStart() {
        return start;
    }

    public long getEnd() {
        return end;
    }

    public long getLength() {
        return end - start + 1;
    }

    public long getTotal() {
        return total;
    }

    public boolean isPartial() {
        return partial;
    }
}
