package com.creed.resource.veracode.api;

import com.creed.resource.veracode.alias.ApplicationAliasService;
import com.creed.resource.veracode.domain.ApplicationAlias;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Application aliases ({@link ApplicationAliasService}), the report-templates page's second tab. */
@RestController
@RequestMapping("/api/veracode/application-aliases")
@RequiredArgsConstructor
public class ApplicationAliasController {

    private final ApplicationAliasService aliases;

    @GetMapping
    List<ApplicationAlias> list() {
        return aliases.all();
    }

    /** The whole table, as the page saves it. */
    @PutMapping
    List<ApplicationAlias> replace(@RequestBody List<ApplicationAlias> body) {
        return aliases.replace(body);
    }
}
