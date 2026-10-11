package com.creed.report.markdown;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableBody;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.springframework.stereotype.Component;

/**
 * Reads the summary table out of a review document with flexmark-java:
 *
 * <pre>
 * # 1. Manager Summary
 *
 * | Item | Result |
 * |---|---|
 * | Verdict | 🟡 Approve with comments |
 * | Risk | Medium |
 * </pre>
 *
 * Parsed as a real GFM table, not with a regex over lines — so escaped pipes ({@code \|}), emphasis
 * ({@code **Medium**}), inline code and links in a cell come out as the text a reader sees, and a
 * table-looking line inside a code fence is not mistaken for one.
 *
 * <p>Which table: the first one under a heading containing "Manager Summary" (the numbering is
 * ignored); failing that, the first table anywhere with a {@code Verdict} row. A table's first
 * column is the item, its second the result; the header row ({@code Item | Result}) is skipped.
 */
@Component
public class ReviewSummaryParser {

    private static final Pattern SUMMARY_HEADING = Pattern.compile("(?i)\\bmanager\\s+summary\\b");

    /** Thread-safe once built — one per application. */
    private final Parser parser;

    public ReviewSummaryParser() {
        MutableDataSet options = new MutableDataSet().set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        this.parser = Parser.builder(options).build();
    }

    /** @throws ReviewSummaryNotFoundException when no summary table is found */
    public ReviewSummary parse(String markdown) {
        Node document = parser.parse(markdown == null ? "" : markdown);

        List<ReviewSummary> tables = new ArrayList<>();
        String heading = null;
        boolean underSummary = false;
        // Top-level blocks only: a table is a top-level block unless it sits in a list or quote,
        // and walking the whole tree would also pick up tables nested inside those.
        for (Node block = document.getFirstChild(); block != null; block = block.getNext()) {
            if (block instanceof Heading h) {
                heading = text(h).strip();
                underSummary = SUMMARY_HEADING.matcher(heading).find();
            } else if (block instanceof TableBlock table) {
                ReviewSummary summary = new ReviewSummary(heading, rows(table));
                if (underSummary) return summary;
                tables.add(summary);
            }
        }
        return tables.stream().filter(t -> t.get("Verdict").isPresent()).findFirst()
                .orElseThrow(() -> new ReviewSummaryNotFoundException(
                        "no summary table: no table under a 'Manager Summary' heading, and none with a Verdict row"));
    }

    /** The {@code Verdict} cell of the summary table. */
    public Verdict verdict(String markdown) {
        ReviewSummary summary = parse(markdown);
        return summary.verdict().orElseThrow(() -> new ReviewSummaryNotFoundException(
                "the summary table" + (summary.heading() == null ? "" : " under '" + summary.heading() + "'")
                        + " has no Verdict row"));
    }

    public Optional<String> item(String markdown, String item) {
        return parse(markdown).get(item);
    }

    private static Map<String, String> rows(TableBlock table) {
        Map<String, String> items = new LinkedHashMap<>();
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            if (!(section instanceof TableBody)) continue; // TableHead (Item | Result), TableSeparator
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) continue;
                List<String> cells = new ArrayList<>();
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (cell instanceof TableCell) cells.add(text(cell).strip());
                }
                if (cells.size() >= 2 && !cells.getFirst().isEmpty()) items.putIfAbsent(cells.get(0), cells.get(1));
            }
        }
        return items;
    }

    /**
     * Plain text of a node — its {@code Text} leaves, unescaped ({@code \\|} → {@code |}, entities
     * decoded): emphasis markers, link targets and code ticks are syntax, not text, so they drop out.
     * Not flexmark's {@code TextCollectingVisitor}: in 0.64.8 it returned
     * {@code see notes](https://…) | ok} for a link followed by an escaped pipe in a table cell.
     */
    private static String text(Node node) {
        StringBuilder out = new StringBuilder();
        for (Node n : node.getDescendants()) {
            if (n instanceof Text t) out.append(t.getChars().unescape());
            else if (n instanceof SoftLineBreak || n instanceof HardLineBreak) out.append(' ');
        }
        return out.toString();
    }
}
