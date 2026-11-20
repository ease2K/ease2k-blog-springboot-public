package com.ease2k.blog.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

public final class ArticleDates {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private ArticleDates() {
    }

    public static OffsetDateTime parse(String value) {
        if (value.length() == 10) {
            return LocalDate.parse(value).atStartOfDay(BEIJING).toOffsetDateTime();
        }
        String normalized = value.replace(' ', 'T');
        try {
            return OffsetDateTime.parse(normalized);
        } catch (DateTimeParseException ignored) {
            // 兼容原站按北京时间保存但没有时区偏移的历史数据。
            return LocalDateTime.parse(normalized).atZone(BEIJING).toOffsetDateTime();
        }
    }
}
