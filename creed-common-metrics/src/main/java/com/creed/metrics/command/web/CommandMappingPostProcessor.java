package com.creed.metrics.command.web;

import com.creed.metrics.annotation.CommandMapping;
import com.creed.metrics.command.Command;
import com.creed.metrics.command.Validator;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.aop.framework.autoproxy.AbstractBeanFactoryAwareAdvisingPostProcessor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Proxies every bean that has {@link CommandMapping} methods with {@link CommandMappingInterceptor} — the same
 * mechanism as {@code @Async} / {@code @Validated}, so no {@code @EnableXxx}, no aspectjweaver, and no
 * dependency on the application's global auto-proxy settings.
 * <p>
 * {@code checkInherited = true} also matches a {@code @CommandMapping} declared on an interface or superclass
 * method, which an {@code @Aspect} {@code @annotation(...)} pointcut would miss.
 */
public class CommandMappingPostProcessor extends AbstractBeanFactoryAwareAdvisingPostProcessor {

    private BeanFactory beanFactory;

    public CommandMappingPostProcessor() {
        // CGLIB: the proxy must stay the controller class so Spring MVC still finds its mappings.
        setProxyTargetClass(true);
        setBeforeExistingAdvisors(true);
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        super.setBeanFactory(beanFactory);
        this.beanFactory = beanFactory;
        this.advisor = new DefaultPointcutAdvisor(
                new AnnotationMatchingPointcut(null, CommandMapping.class, true),
                new CommandMappingInterceptor(beanFactory));
    }

    // Fail at startup, not on the first request: a mapping that names a missing bean, or a method the CGLIB
    // subclass cannot override — that one would skip the interceptor and run its placeholder body instead.
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (isEligible(bean, beanName)) {
            ReflectionUtils.doWithMethods(ClassUtils.getUserClass(bean), method -> {
                CommandMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, CommandMapping.class);
                if (mapping != null) {
                    requireOverridable(method, beanName);
                    requireBean(mapping.command(), Command.class, beanName, method.getName());
                    if (StringUtils.hasText(mapping.validator())) {
                        requireBean(mapping.validator(), Validator.class, beanName, method.getName());
                    }
                }
            });
        }
        return super.postProcessAfterInitialization(bean, beanName);
    }

    private static void requireOverridable(Method method, String beanName) {
        int modifiers = method.getModifiers();
        if (Modifier.isPrivate(modifiers) || Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers)) {
            throw new IllegalStateException(beanName + "#" + method.getName()
                    + ": a @CommandMapping method must not be private, final or static, or the proxy cannot intercept it");
        }
    }

    private void requireBean(String name, Class<?> type, String beanName, String methodName) {
        if (!beanFactory.containsBean(name) || !beanFactory.isTypeMatch(name, type)) {
            throw new IllegalStateException(beanName + "#" + methodName + ": @CommandMapping refers to '"
                    + name + "', but there is no " + type.getSimpleName() + " bean with that name");
        }
    }
}
