package com.creed.report.controller;

import com.creed.report.model.Commit;
import com.creed.report.model.DiffFile;
import com.creed.report.model.DiffRow;
import com.creed.report.service.AssetService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static com.creed.report.controller.ReportController.FILE_TS;

@Controller
public class DiffController {
    private final TemplateEngine templateEngine;
    private final AssetService assetService;

    public DiffController(TemplateEngine templateEngine, AssetService assetService) {
        this.templateEngine = templateEngine;
        this.assetService = assetService;
    }

    @GetMapping({"/", "/commit"})
    public String commit(Model model) {
        model.addAttribute("commit", sampleCommit());
        return "commit";
    }

    @GetMapping(value = "/export/commit", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> exportCommit() {
        LocalDateTime now = LocalDateTime.now();

        Context ctx = new Context();
        ctx.setVariable("commit", sampleCommit());
        ctx.setVariable("bootstrapCss", assetService.bootstrapCss());
        ctx.setVariable("bootstrapIconsCss", assetService.bootstrapIconsCss());
        ctx.setVariable("styleCss", assetService.styleCss());
        ctx.setVariable("bootstrapJs", assetService.bootstrapJs());

        String html = templateEngine.process("commit-export", ctx);
        byte[] body = html.getBytes(StandardCharsets.UTF_8);

        String filename = "creed-commit-" + now.format(FILE_TS) + ".html";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/html; charset=UTF-8"));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(body.length);

        return ResponseEntity.ok().headers(headers).body(body);
    }

    /** Builds the github-linguist commit e415a13 shown in the reference screenshot. */
    private Commit sampleCommit() {
        Commit c = new Commit();
        c.setShortSha("e415a13");
        c.setTitle("When testing if a blob is indexable, check size first");
        c.setDescription("""
                Otherwise, charlock_holmes will allocate another large binary
                buffer for testing the encoding, which is a problem if the binary
                blob is many hundreds of MB large. It'll just fail and crash ruby.""");
        c.setAuthor("Scott J. Goldman");
        c.setDate("Sep 1, 2012");
        c.setBranch("master");
        c.setPullRequest("github-linguist/linguist#247");
        c.setTag("v4.8.8");
        c.setVersion("2.10.5");
        c.setParentSha("6ec907a");
        c.setAdditions(11);
        c.setDeletions(3);
        c.setFiles(List.of(gemspec(), blobHelper(), testBlob()));
        return c;
    }

    private DiffFile gemspec() {
        List<DiffRow> rows = List.of(
                DiffRow.hunk("@@ -12,6 +12,7 @@ Gem::Specification.new do |s|"),
                DiffRow.context("12", "12", "  s.add_dependency 'escape_utils',    '~> 0.2.3'"),
                DiffRow.context("13", "13", "  s.add_dependency 'mime-types',      '~> 1.19'"),
                DiffRow.context("14", "14", "  s.add_dependency 'pygments.rb',     '>= 0.2.13'"),
                DiffRow.add("15", "  s.add_development_dependency 'mocha'"),
                DiffRow.context("15", "16", "  s.add_development_dependency 'json'"),
                DiffRow.context("16", "17", "  s.add_development_dependency 'rake'"),
                DiffRow.context("17", "18", "  s.add_development_dependency 'yajl-ruby'"),
                DiffRow.expandDown()
        );
        return new DiffFile("github-linguist.gemspec", 1, 0, rows);
    }

    private DiffFile blobHelper() {
        List<DiffRow> rows = List.of(
                DiffRow.hunk("@@ -250,7 +250,9 @@ def generated?"),
                DiffRow.context("250", "250", "    #"),
                DiffRow.context("251", "251", "    # Return true or false"),
                DiffRow.context("252", "252", "    def indexable?"),
                DiffRow.replace("253", "      if binary?", "253", "      if size > 100 * 1024"),
                DiffRow.add("254", "        false"),
                DiffRow.add("255", "      elsif binary?"),
                DiffRow.context("254", "256", "        false"),
                DiffRow.context("255", "257", "      elsif extname == '.txt'"),
                DiffRow.context("256", "258", "        true"),

                DiffRow.hunk("@@ -260,8 +262,6 @@ def indexable?"),
                DiffRow.context("260", "262", "        false"),
                DiffRow.context("261", "263", "      elsif generated?"),
                DiffRow.context("262", "264", "        false"),
                DiffRow.del("263", "      elsif size > 100 * 1024"),
                DiffRow.del("264", "        false"),
                DiffRow.context("265", "265", "      else"),
                DiffRow.context("266", "266", "        true"),
                DiffRow.context("267", "267", "      end"),
                DiffRow.expandDown()
        );
        return new DiffFile("lib/linguist/blob_helper.rb", 3, 3, rows);
    }

    private DiffFile testBlob() {
        List<DiffRow> rows = List.of(
                DiffRow.hunk("@@ -2,6 +2,7 @@"),
                DiffRow.context("2", "2", "require 'linguist/samples'"),
                DiffRow.context("3", "3", ""),
                DiffRow.context("4", "4", "require 'test/unit'"),
                DiffRow.add("5", "require 'mocha'"),
                DiffRow.context("5", "6", "require 'mime/types'"),
                DiffRow.context("6", "7", "require 'pygments'"),
                DiffRow.context("7", "8", ""),

                DiffRow.hunk("@@ -261,6 +262,12 @@ def test_indexable"),
                DiffRow.context("261", "262", "    assert !blob(\"Text/dump.sql\").indexable?"),
                DiffRow.context("262", "263", "    assert !blob(\"Binary/github.po\").indexable?"),
                DiffRow.context("263", "264", "    assert !blob(\"Binary/linguist.gem\").indexable?"),
                DiffRow.add("265", ""),
                DiffRow.add("266", "    # large binary blobs should fail on size check first, not call"),
                DiffRow.add("267", "    # into charlock_holmes and alloc big buffers for testing encoding"),
                DiffRow.add("268", "    b = blob(\"Binary/octocat.ai\")"),
                DiffRow.add("269", "    b.expects(:binary?).never"),
                DiffRow.add("270", "    assert !b.indexable?"),
                DiffRow.context("264", "271", "  end"),
                DiffRow.context("265", "272", ""),
                DiffRow.context("266", "273", "  def test_language"),
                DiffRow.expandDown()
        );
        return new DiffFile("test/test_blob.rb", 7, 0, rows);
    }
}
