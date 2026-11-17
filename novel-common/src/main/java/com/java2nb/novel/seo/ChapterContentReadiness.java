package com.java2nb.novel.seo;

import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Bounded TXT validation using the same visible-text rules as ReaderChapterService. */
public final class ChapterContentReadiness {
    public static final int MAX_TXT_BYTES = 2 * 1024 * 1024;
    private ChapterContentReadiness() {}

    public static boolean hasReadableText(String html) {
        if (html == null) return false;
        String text = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1\\s*>", "")
            .replaceAll("(?i)<br\\s*/?>|</(?:p|div|li|h[1-6])\\s*>", "\n")
            .replaceAll("(?s)<!--.*?-->", "")
            .replaceAll("</?[A-Za-z][^>]*>", "");
        text = HtmlUtils.htmlUnescape(text).replace('\u00a0', ' ').replace("\r", "");
        for (String line : text.split("\n+")) if (!line.strip().isBlank()) return true;
        return false;
    }

    public static boolean fileReady(Path directory, long bookId, long chapterId) {
        if (bookId <= 0 || chapterId <= 0) return false;
        Path root = directory.toAbsolutePath().normalize();
        Path book = root.resolve(Long.toString(bookId));
        Path file = book.resolve(chapterId + ".txt");
        try {
            if (Files.isSymbolicLink(book) || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
            long size = Files.size(file);
            if (size <= 0 || size > MAX_TXT_BYTES) return false;
            // A concurrent writer cannot make validation allocate an unbounded byte array.
            byte[] bytes;
            try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes(MAX_TXT_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_TXT_BYTES) return false;
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            // The existing TXT service concatenates readLine() results before parsing HTML.
            return hasReadableText(text.replace("\r", "").replace("\n", ""));
        } catch (CharacterCodingException e) {
            return false;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
