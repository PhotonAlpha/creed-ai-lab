package com.creed.metrics.command.web;

import com.creed.metrics.AccountCommandController;
import com.creed.metrics.annotation.CommandMapping;
import com.creed.metrics.command.AccountCommand;
import com.creed.metrics.config.CommandMappingAutoConfiguration;
import com.creed.metrics.model.request.GenericRequest;
import com.creed.metrics.model.response.DefaultResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plain web context instead of @SpringBootTest: this module ships the OTel starter and logback config,
 * and none of that is needed to prove the proxy is mapped and dispatches. Importing the auto-configuration
 * directly also shows it needs nothing from Boot's own AOP auto-configuration.
 */
class CommandControllerTest {

    @Configuration
    @EnableWebMvc
    @Import({CommandMappingAutoConfiguration.class, AccountCommandController.class})
    // Scanned, not @Import-ed: @CommandMapping refers to beans by their scanned names ("accountCommand").
    // The scan covers command.web too, so this test's own nested beans are excluded.
    @ComponentScan(basePackageClasses = AccountCommand.class, excludeFilters = @ComponentScan.Filter(
            type = FilterType.REGEX, pattern = ".*\\.CommandControllerTest\\$.*"))
    static class Config {
    }

    @Configuration
    @EnableWebMvc
    @Import({CommandMappingAutoConfiguration.class, AccountCommandController.class})
    static class MissingCommandConfig {
    }

    @RestController
    static class FinalMethodController {
        @PostMapping("/final")
        @CommandMapping(command = "accountCommand")
        final DefaultResponse finalMethod(@RequestBody GenericRequest<?, ?> request) {
            return null;
        }
    }

    @Configuration
    @EnableWebMvc
    @Import({CommandMappingAutoConfiguration.class, FinalMethodController.class})
    @ComponentScan(basePackageClasses = AccountCommand.class, excludeFilters = @ComponentScan.Filter(
            type = FilterType.REGEX, pattern = ".*\\.CommandControllerTest\\$.*"))
    static class FinalMethodConfig {
    }

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = start(Config.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private static AnnotationConfigWebApplicationContext start(Class<?> config) {
        var ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.register(config);
        ctx.refresh();
        return ctx;
    }

    @Test
    void controllerIsWrappedInCglibProxy() {
        assertThat(AopUtils.isCglibProxy(context.getBean(AccountCommandController.class))).isTrue();
    }

    @Test
    void unknownCommandBeanFailsStartupNotFirstRequest() {
        assertThatThrownBy(() -> start(MissingCommandConfig.class))
                .rootCause().hasMessageEndingWith("#accountList: @CommandMapping refers to 'accountCommand', "
                        + "but there is no Command bean with that name");
    }

    @Test
    void finalMethodFailsStartupInsteadOfSilentlyReturningNull() {
        assertThatThrownBy(() -> start(FinalMethodConfig.class))
                .rootCause().hasMessageEndingWith("#finalMethod: a @CommandMapping method must not be private, "
                        + "final or static, or the proxy cannot intercept it");
    }

    @Test
    void validRequestRunsCommand() throws Exception {
        mvc.perform(post("/account/list").contentType(MediaType.APPLICATION_JSON).content("""
                        {"serviceRequestHeader":{"requesterContext":{"channelId":"MB","requestUserId":"u1"}}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceResponseHeader.requestContext.channelId").value("MB"))
                .andExpect(jsonPath("$.serviceResponseHeader.responseContext.statusCode").value("0000"))
                .andExpect(jsonPath("$.serviceResponseBody.profile.customerInfo.userId").value("u1"))
                .andExpect(jsonPath("$.serviceResponseBody.accounts[0].accountId").value("hsadkjjhdsa"));
    }

    @Test
    void rejectedRequestIs400AndCommandDoesNotRun() throws Exception {
        mvc.perform(post("/account/list").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(CommandValidationException.class));
    }
}
