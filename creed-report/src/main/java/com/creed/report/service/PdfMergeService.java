package com.creed.report.service;

import com.lowagie.text.Document;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfImportedPage;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfSmartCopy;
import com.lowagie.text.pdf.PdfStamper;
import com.lowagie.text.pdf.parser.FinalText;
import com.lowagie.text.pdf.parser.ParsedText;
import com.lowagie.text.pdf.parser.ParsedTextImpl;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.lowagie.text.pdf.parser.TextAssembler;
import com.lowagie.text.pdf.parser.Word;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Several finished PDFs into one, with the page counter in the footer <b>corrected</b> for the
 * document they have become.
 *
 * <p>Two renders of a two-page statement both say "1 of 2" and "2 of 2". Concatenating them gives a
 * four-page document that says it twice, which is worse than saying nothing: a reader who takes the
 * counter at face value thinks they are holding two documents, and a reader who does not cannot
 * tell which page is missing. So this does the second half as well — it finds each page's old
 * counter, paints it out, and draws the one the merged document needs.
 *
 * <p><b>Why not concatenate the HTML and render once.</b> That is the cheaper answer when both
 * halves come from our own templates — Flying Saucer would then count the pages itself and no PDF
 * would be touched — but it only works for documents this application renders. This takes
 * {@code byte[]}s, so it merges anything: a template render, a file upload, a PDF from another
 * service.
 *
 * <h2>How the counter is corrected</h2>
 *
 * <ol>
 *   <li><b>Merge</b> with {@link PdfSmartCopy}, which de-duplicates identical embedded resources.
 *       It matters here: every statement render embeds the same Noto faces, and a plain
 *       {@code PdfCopy} of two of them carries two copies of every one.</li>
 *   <li><b>Locate</b> the old counter by parsing each page's text with its position (a recording
 *       {@link TextAssembler}) and looking for the exact string that page used to carry. The merge
 *       knows how many pages each part had, so it knows that string: page 3 of the merged document
 *       is page 1 of part 2 and reads "1 of 2". Searching for a known string beats guessing at
 *       coordinates, and it is why {@link PageNumbering} needs the same separator the template
 *       printed — {@code pdf.page.middle}, which is " of " in English and " จาก " in Thai.</li>
 *   <li><b>Paint it out</b> with a filled rectangle over the text's own bounding box, in the page's
 *       background colour. The counter sits alone on its line in the white footer band, with the
 *       seal floated off to the right, so the cover clears the text and nothing else. A page whose
 *       footer is not white needs {@link PageNumbering#background} set to whatever it is.</li>
 *   <li><b>Redraw</b> centred on the old text's own centre, on its own baseline — so the new
 *       counter lands exactly where the old one was without this class knowing anything about the
 *       template's margins.</li>
 * </ol>
 *
 * <p><b>The font is ours, not the page's.</b> Reusing the face already embedded in the page would
 * be the obvious move and is a trap: Flying Saucer embeds a <i>subset</i>, carrying only the glyphs
 * that were drawn. A two-page part contains "1" and "2" and no "3", so a merged document would
 * renumber into blanks. {@link PageNumbering#font} therefore carries a font registered by us, and
 * {@link PdfExportService#stampingFont} builds it from the same TTFs the render used.
 *
 * <p>A page whose old counter cannot be found is <b>left alone and logged</b>, not stamped
 * speculatively: a document with one honest counter and one guess is harder to trust than one that
 * simply was not renumbered.
 */
@Service
public class PdfMergeService {

    private static final Logger log = LoggerFactory.getLogger(PdfMergeService.class);

    /** Padding around the covered text, so no antialiased edge of the old glyphs survives. */
    private static final float COVER_PADDING = 1.5f;

    /**
     * What to draw over the old page counter, and what the old one looked like.
     *
     * @param separator  the text between the two numbers — the {@code pdf.page.middle} message the
     *                   template printed, e.g. {@code " of "}. Both halves of the job need it: it
     *                   is how the old counter is recognised and how the new one is spelled
     * @param font       the face to draw in, registered by us rather than taken off the page — see
     *                   the class note about subset fonts
     * @param fontSize   in points; mirrors {@code .statement-page-number { font-size }}
     * @param color      the text colour; mirrors that rule's {@code color}
     * @param background what to paint over the old counter — the colour of the footer band behind it
     */
    public record PageNumbering(String separator, BaseFont font, float fontSize, Color color,
                                Color background) {

        public PageNumbering {
            Objects.requireNonNull(separator, "separator");
            Objects.requireNonNull(font, "font");
            if (fontSize <= 0) {
                throw new IllegalArgumentException("A page counter needs a positive font size");
            }
            color = color == null ? Color.BLACK : color;
            background = background == null ? Color.WHITE : background;
        }

        /** The statement chrome's counter: 8pt {@code #414042} on white, like the CSS rule. */
        public static PageNumbering statement(String separator, BaseFont font) {
            return new PageNumbering(separator, font, 8f, new Color(0x41, 0x40, 0x42), Color.WHITE);
        }

        /** How page {@code page} of {@code total} reads. */
        String text(int page, int total) {
            return page + separator + total;
        }
    }

    /**
     * One document to merge, and the separator <b>its own</b> counter was printed with.
     *
     * <p>Only needed when the parts were not all numbered the same way — an English render says
     * "1 of 2" and a Thai one says "1 จาก 2", and the correction finds the old counter by the exact
     * string it must have carried. Merge those two with one separator and half the document is
     * silently left counting for its part: {@link #mergeParts} exists so the caller can say what
     * each part looked like.
     *
     * @param document  the finished PDF
     * @param separator what sat between the two numbers in <i>this</i> part's footer
     */
    public record Part(byte[] document, String separator) {

        public Part {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(separator, "separator");
        }
    }

    /** What a merged part turned out to be: how many pages, and how it numbered them. */
    private record PartPages(int pages, String separator) {
    }

    /**
     * The documents as one, page order preserved, counters untouched.
     *
     * <p>Useful on its own for documents that carry no page counter; anything from the statement
     * chrome wants {@link #mergeAndRenumber} instead.
     */
    public byte[] merge(List<byte[]> documents) {
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("Merging needs at least one document");
        }
        List<Part> parts = new ArrayList<>(documents.size());
        for (byte[] document : documents) {
            // No renumbering here, so the separator is never looked at.
            parts.add(new Part(document, ""));
        }
        return merge(parts, new ArrayList<>());
    }

    /**
     * The documents as one, with every page's footer counter rewritten to its place in the merged
     * document.
     *
     * @throws IllegalArgumentException if nothing was handed in — merging no documents is a caller
     *                                  bug, and an empty PDF is not a useful answer to it
     */
    public byte[] mergeAndRenumber(List<byte[]> documents, PageNumbering numbering) {
        Objects.requireNonNull(numbering, "numbering");
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("Merging needs at least one document");
        }
        List<Part> parts = new ArrayList<>(documents.size());
        for (byte[] document : documents) {
            parts.add(new Part(document, numbering.separator()));
        }
        return mergeParts(parts, numbering);
    }

    /**
     * The same, for parts that were <b>not</b> all numbered alike — the mixed-language case.
     *
     * <p>Each {@link Part} says how its own counter read; {@code numbering} says how the merged
     * document should read, which is one language for the whole file. That is a real decision and
     * the caller's to make: a document assembled from an English half and a Thai half has no
     * intrinsic language, so its counter is printed in the one the caller asked the merge in.
     */
    public byte[] mergeParts(List<Part> parts, PageNumbering numbering) {
        Objects.requireNonNull(numbering, "numbering");
        List<PartPages> merged = new ArrayList<>();
        byte[] document = merge(parts, merged);
        return renumber(document, merged, numbering);
    }

    private byte[] merge(List<Part> parts, List<PartPages> merged) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("Merging needs at least one document");
        }
        List<PdfReader> readers = new ArrayList<>(parts.size());
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document();
            // SMART copy, not PdfCopy: both halves of a statement merge embed the same Noto faces,
            // and the plain copier would carry one set per part.
            PdfSmartCopy copy = new PdfSmartCopy(document, out);
            document.open();
            for (Part part : parts) {
                PdfReader reader = new PdfReader(part.document());
                readers.add(reader);
                int pages = reader.getNumberOfPages();
                merged.add(new PartPages(pages, part.separator()));
                for (int page = 1; page <= pages; page++) {
                    PdfImportedPage imported = copy.getImportedPage(reader, page);
                    copy.addPage(imported);
                }
                copy.freeReader(reader);
            }
            document.close();
            return out.toByteArray();
        }
        catch (Exception ex) {
            throw new IllegalStateException("Merging " + parts.size() + " PDFs failed", ex);
        }
        finally {
            readers.forEach(PdfReader::close);
        }
    }

    /**
     * Rewrites each page's counter from "page of part" to "page of whole".
     *
     * <p>{@code parts} is what makes this exact rather than a pattern match: it says which part a
     * merged page came from, how long that part was and how it spelled its counter — therefore
     * exactly what that page's old counter said.
     */
    private byte[] renumber(byte[] merged, List<PartPages> parts, PageNumbering numbering) {
        PdfReader reader;
        try {
            reader = new PdfReader(merged);
        }
        catch (Exception ex) {
            throw new IllegalStateException("The merged PDF could not be read back", ex);
        }
        try {
            int total = reader.getNumberOfPages();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfStamper stamper = new PdfStamper(reader, out);

            int page = 0;
            for (PartPages part : parts) {
                for (int inPart = 1; inPart <= part.pages(); inPart++) {
                    page++;
                    // The OLD string in that part's own spelling, the new one in the merged
                    // document's -- the two differ whenever the parts were not all one language.
                    String was = inPart + part.separator() + part.pages();
                    String now = numbering.text(page, total);
                    if (was.equals(now)) {
                        // A single-part merge, or a part that happens to sit where it already
                        // claimed to be: nothing to correct, and no reason to paint over anything.
                        continue;
                    }
                    replace(stamper, reader, page, was, now, numbering);
                }
            }
            stamper.close();
            return out.toByteArray();
        }
        catch (Exception ex) {
            throw new IllegalStateException("Renumbering the merged PDF failed", ex);
        }
        finally {
            reader.close();
        }
    }

    private void replace(PdfStamper stamper, PdfReader reader, int page, String was, String now,
                         PageNumbering numbering) throws Exception {
        TextBox box = locate(reader, page, was);
        if (box == null) {
            // Not fatal: the merged pages are all there and correct, only their counter still reads
            // for the part. Stamping a guessed position would be worse than saying so.
            log.warn("Page {} carries no counter reading \"{}\"; left as it was", page, was);
            return;
        }
        // WHERE the old counter is comes from the parse, and HOW FAR IT RUNS needs one correction:
        // the parser's end point stops at the last glyph's ORIGIN, not past its advance, so a
        // "1 of 2" that the font measures at 20.9pt parses as 16.3 and a cover cut to that leaves
        // the old last digit showing beside the new one.
        //
        // The correction is one digit's advance, and that is exact enough on purpose: a page
        // counter always ENDS IN A DIGIT, and a digit's advance is near-identical across these
        // faces. Measuring the old string itself is not an option -- in a mixed-language merge it
        // was drawn in a face this one does not have, and asking Noto Sans for the width of
        // "1 จาก 2" answers with the Thai glyphs missing, which is how a Thai "2" survived a cover
        // sized that way.
        float digit = numbering.font().getWidthPoint("0", numbering.fontSize());
        float oldRight = box.right() + digit;
        float nowWidth = numbering.font().getWidthPoint(now, numbering.fontSize());
        float centre = (box.left() + oldRight) / 2;
        float left = Math.min(box.left(), centre - nowWidth / 2) - COVER_PADDING;
        float right = Math.max(oldRight, centre + nowWidth / 2) + COVER_PADDING;
        float ascent = numbering.font().getFontDescriptor(BaseFont.BBOXURY, numbering.fontSize());
        float descent = numbering.font().getFontDescriptor(BaseFont.BBOXLLY, numbering.fontSize());

        PdfContentByte canvas = stamper.getOverContent(page);
        canvas.saveState();
        canvas.setColorFill(numbering.background());
        canvas.rectangle(left, box.baseline() + descent - COVER_PADDING,
                right - left, ascent - descent + 2 * COVER_PADDING);
        canvas.fill();

        canvas.beginText();
        canvas.setColorFill(numbering.color());
        canvas.setFontAndSize(numbering.font(), numbering.fontSize());
        // Centred on the OLD text's centre and sitting on its baseline, so a counter that grew a
        // digit ("9 of 9" -> "19 of 24") stays centred where the narrow one was rather than
        // drifting right. The template centres it; this keeps it centred.
        canvas.showTextAligned(PdfContentByte.ALIGN_CENTER, now, centre, box.baseline(), 0);
        canvas.endText();
        canvas.restoreState();
        log.debug("Page {}: counter \"{}\" -> \"{}\" at x={} baseline={}", page, was, now, centre,
                box.baseline());
    }

    /** Where on the page {@code text} is drawn, or {@code null} if it is not. */
    private TextBox locate(PdfReader reader, int page, String text) throws Exception {
        TextLocator locator = new TextLocator(text);
        // The extractor drives the assembler; the string it returns is of no interest, the
        // positions the assembler collected on the way are.
        new PdfTextExtractor(reader, locator).getTextFromPage(page);
        return locator.found();
    }

    /**
     * Where a drawn string runs, in the page's own coordinates (origin bottom-left, points). No
     * height: the parser's ascent is not worth trusting, so the cover's height comes from the
     * stamping font's descriptor. {@code right} is the last glyph's <b>origin</b>, not the end of
     * its advance — see the correction in {@link #replace}.
     */
    private record TextBox(float left, float right, float baseline) {

        TextBox union(TextBox other) {
            return new TextBox(Math.min(left, other.left), Math.max(right, other.right), baseline);
        }
    }

    /**
     * A {@link TextAssembler} that does not assemble anything — it watches the text go past and
     * remembers where the wanted string was drawn.
     *
     * <p>It has to accumulate across items: a counter reading "1 of 2" reaches this as three
     * {@link Word}s, so the locator collects the pieces of the line and keeps the union of their
     * boxes once they spell the string being looked for.
     */
    private static final class TextLocator implements TextAssembler {

        private final String wanted;
        private final StringBuilder line = new StringBuilder();
        private TextBox lineBox;
        private float lineBaseline = Float.NaN;
        private TextBox found;

        private TextLocator(String wanted) {
            this.wanted = wanted;
        }

        private TextBox found() {
            return found;
        }

        @Override
        public void process(ParsedText text, String contextName) {
            // Its WORDS, never ParsedText.getText(). That accessor decodes the raw PdfString with
            // the font's declared encoding name, and a Flying Saucer Identity-H subset declares one
            // the JVM has never heard of -- "IDENTITY_H2" -- so it throws instead of returning the
            // text. getAsPartialWords() goes through the font's CMap, which is the path the
            // engine's own assembler takes, and the Words carry the positions this needs anyway.
            for (Word word : text.getAsPartialWords()) {
                accept(word);
            }
        }

        @Override
        public void process(Word word, String contextName) {
            accept(word);
        }

        @Override
        public void process(FinalText text, String contextName) {
            // Already-assembled text carries no position; the pieces above are what this needs.
        }

        private void accept(ParsedTextImpl item) {
            if (found != null || item.getText() == null || item.getText().isEmpty()) {
                return;
            }
            // Vector is (x, y, 1) in user space; index 1 is y.
            float baseline = item.getBaseline().get(1);
            if (Float.isNaN(lineBaseline) || Math.abs(baseline - lineBaseline) > 0.5f) {
                // A new line: the string being looked for is one line's worth, so start over.
                line.setLength(0);
                lineBox = null;
                lineBaseline = baseline;
            }
            TextBox box = boxOf(item, baseline);
            lineBox = lineBox == null ? box : lineBox.union(box);
            line.append(item.getText());
            if (line.toString().contains(wanted)) {
                found = lineBox;
            }
        }

        private static TextBox boxOf(ParsedTextImpl item, float baseline) {
            // Index 0 of the (x, y, 1) vector. Start and end can be either way round for a
            // right-to-left run, so take them as a pair rather than by name.
            float start = item.getStartPoint().get(0);
            float end = item.getEndPoint().get(0);
            return new TextBox(Math.min(start, end), Math.max(start, end), baseline);
        }

        @Override
        public void renderText(FinalText text) {
        }

        @Override
        public void renderText(ParsedTextImpl text) {
        }

        @Override
        public FinalText endParsingContext(String containingElementName) {
            return new FinalText("");
        }

        @Override
        public String getWordId() {
            return "";
        }

        @Override
        public void setPage(int page) {
        }

        @Override
        public void reset() {
            line.setLength(0);
            lineBox = null;
            lineBaseline = Float.NaN;
        }
    }
}
