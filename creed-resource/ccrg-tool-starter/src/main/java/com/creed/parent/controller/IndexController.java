/**
 * Copyright the original author or authors.
 *
 * @author Ethen Cao
 */
package com.creed.parent.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class IndexController {
    // @RequestMapping({"/{path:[^\\\\.]*}"})
    @RequestMapping({"/index", "/system/*"})
    public String index() {
        return "forward:/";
    }
}
