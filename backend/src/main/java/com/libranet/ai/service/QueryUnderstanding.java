package com.libranet.ai.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured-filter extraction for natural-language catalog queries (Phase 10):
 * "beginner Java books after 2023" → { text="beginner java books", publishedAfter=2023 }.
 * Deliberately small and deterministic — the LLM-shaped part lives behind understanding
 * the corpus; filtering must always be exact and explainable.
 */
public final class QueryUnderstanding {

    private QueryUnderstanding() {}

    /** Extracted filters; all bounds are inclusive-exclusive as named. */
    public record ParsedQuery(String text, Integer publishedAfter, Integer publishedBefore,
                              Integer publishedIn) {}

    private static final Pattern AFTER =
            Pattern.compile("\\b(after|since|from|newer than|later than)\\s+((?:19|20)\\d{2})\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern BEFORE =
            Pattern.compile("\\b(before|until|older than|earlier than|prior to)\\s+((?:19|20)\\d{2})\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern EXACT_YEAR =
            Pattern.compile("\\b(?:in|of|from)\\s+((?:19|20)\\d{2})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOSE_YEAR_REMOVAL =
            Pattern.compile("\\b(?:19|20)\\d{2}\\b");

    public static ParsedQuery parse(String raw) {
        String q = raw == null ? "" : raw;
        Integer after = null, before = null, in = null;

        Matcher m = AFTER.matcher(q);
        if (m.find()) {
            after = Integer.parseInt(m.group(2));
            q = m.replaceFirst(" ");
        }
        m = BEFORE.matcher(q);
        if (m.find()) {
            before = Integer.parseInt(m.group(2));
            q = m.replaceFirst(" ");
        }
        if (after == null && before == null) {
            m = EXACT_YEAR.matcher(q);
            if (m.find()) {
                in = Integer.parseInt(m.group(1));
                q = m.replaceFirst(" ");
            }
        }
        // any remaining standalone year still filters the query (e.g. "Java 2023")
        m = LOOSE_YEAR_REMOVAL.matcher(q);
        if (m.find() && after == null && before == null && in == null) {
            in = Integer.parseInt(m.group());
            q = m.replaceFirst(" ");
        }
        String text = q.replaceAll("\\s+", " ").trim();
        return new ParsedQuery(text, after, before, in);
    }

    /** Leading 4-digit year of a free-form publishedDate ("2024", "2024-05", "©2021"). */
    public static Integer yearOf(String publishedDate) {
        if (publishedDate == null) return null;
        Matcher m = Pattern.compile("(19|20)\\d{2}").matcher(publishedDate);
        return m.find() ? Integer.parseInt(m.group()) : null;
    }

    public static boolean matchesYear(String publishedDate, Integer after, Integer before, Integer in) {
        if (after == null && before == null && in == null) return true;
        Integer year = yearOf(publishedDate);
        if (year == null) return false; // a year-filtered query never guesses an unknown year
        if (after != null && year <= after) return false;
        if (before != null && year >= before) return false;
        return in == null || year.equals(in);
    }
}
