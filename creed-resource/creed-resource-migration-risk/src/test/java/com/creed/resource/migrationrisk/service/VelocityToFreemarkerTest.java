package com.creed.resource.migrationrisk.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityToFreemarkerTest {

    @Test
    void referencesDirectivesAndNesting() {
        assertThat(VelocityToFreemarker.convert(List.of(
                "<h1>Invoice $invoice.number</h1>",
                "<p>$!invoice.customer / ${invoice.total}</p>",
                "#if($invoice.lines.size() > 0 && $invoice.paid)",
                "  #foreach($line in $invoice.lines)",
                "    <tr><td>$line.name</td></tr>",
                "  #end",
                "#elseif($invoice.draft)",
                "  #set($label = \"draft\")",
                "#else",
                "  ## nothing to show",
                "#end"))).containsExactly(
                "<h1>Invoice ${invoice.number}</h1>",
                "<p>${invoice.customer!} / ${invoice.total}</p>",
                "<#if invoice.lines.size() > 0 && invoice.paid>",
                "  <#list invoice.lines as line>",
                "    <tr><td>${line.name}</td></tr>",
                "  </#list>",
                "<#elseif invoice.draft>",
                "  <#assign label = \"draft\">",
                "<#else>",
                "  <#-- nothing to show -->",
                "</#if>");
    }
}
