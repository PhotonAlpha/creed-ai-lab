package com.creed.metrics.command.web;

import com.creed.metrics.annotation.CommandMapping;
import com.creed.metrics.command.Command;
import com.creed.metrics.command.Validator;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces a {@link CommandMapping} method: the annotated method body never runs. Command and validator
 * beans are looked up on first call and cached per method; their names were already checked at startup by
 * {@link CommandMappingPostProcessor}.
 */
public class CommandMappingInterceptor implements MethodInterceptor {

    private final BeanFactory beanFactory;
    private final Map<Method, CommandInvoker> invokers = new ConcurrentHashMap<>();

    public CommandMappingInterceptor(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object invoke(MethodInvocation invocation) {
        Class<?> targetClass = invocation.getThis() == null ? null : AopUtils.getTargetClass(invocation.getThis());
        Method method = AopUtils.getMostSpecificMethod(invocation.getMethod(), targetClass);
        return invokers.computeIfAbsent(method, this::invoker).invoke(invocation.getArguments());
    }

    private CommandInvoker invoker(Method method) {
        // Merged lookup, so a @CommandMapping inherited from an interface or superclass method is found too.
        CommandMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, CommandMapping.class);
        Command<?, ?> command = beanFactory.getBean(mapping.command(), Command.class);
        Validator<?> validator = StringUtils.hasText(mapping.validator())
                ? beanFactory.getBean(mapping.validator(), Validator.class)
                : null;
        return new CommandInvoker(method, mapping, command, validator);
    }
}
