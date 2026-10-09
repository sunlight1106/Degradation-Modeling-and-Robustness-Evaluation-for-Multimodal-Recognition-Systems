package com.robustvision.platform.service;

import java.text.Normalizer;
import java.util.Locale;

/** Exact headwords/phrases share progress; different inflections remain distinct. */
public final class VocabularyTermKey {
    private VocabularyTermKey() {}
    public static String of(String term) {
        return Normalizer.normalize(term, Normalizer.Form.NFKC).replace('\u2019', '\'')
                .strip().replaceAll("[\\p{Z}\\s]+", " ").toLowerCase(Locale.ROOT);
    }
}
