package com.creed.metrics.command.web;

import com.creed.metrics.annotation.CommandMapping;
import com.creed.metrics.command.Command;
import com.creed.metrics.command.Validator;
import com.creed.metrics.model.request.GenericRequest;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

/**
 * One {@link CommandMapping} method: validate (if a validator is named), then execute the command.
 * <p>
 * Command and validator are generic in their request type, and only their resolved type arguments say what
 * they accept. Checking the request against those up front turns a mismatch into a clear message instead
 * of a ClassCastException from inside a bridge method.
 */
final class CommandInvoker {

    private final Method method;
    private final CommandMapping mapping;
    private final Command<?, ?> command;
    private final Validator<?> validator;
    private final Class<?> commandRequestType;
    private final Class<?> validatorRequestType;

    CommandInvoker(Method method, CommandMapping mapping, Command<?, ?> command, Validator<?> validator) {
        this.method = method;
        this.mapping = mapping;
        this.command = command;
        this.validator = validator;
        this.commandRequestType = requestType(command.getClass(), Command.class);
        this.validatorRequestType = validator == null ? null : requestType(validator.getClass(), Validator.class);
    }

    Object invoke(Object[] args) {
        // Without a GenericRequest parameter (e.g. a bodyless GET) the command still gets a non-null request.
        GenericRequest<?, ?> request = requestArgument(args);

        if (validator != null) {
            checkAccepts(validatorRequestType, request, mapping.validator());
            if (!validate(validator, request)) {
                throw new CommandValidationException(mapping.validator());
            }
        }

        checkAccepts(commandRequestType, request, mapping.command());
        Object result = execute(command, request);
        if (result != null && !ClassUtils.isAssignableValue(method.getReturnType(), result)) {
            throw new IllegalStateException("Command '" + mapping.command() + "' returned "
                    + result.getClass().getName() + ", but " + method + " declares " + method.getReturnType().getName());
        }
        return result;
    }

    // MVC has already resolved the arguments (@RequestBody etc.); the command only needs the envelope.
    private static GenericRequest<?, ?> requestArgument(Object[] args) {
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof GenericRequest<?, ?> request) {
                    return request;
                }
            }
        }
        return new GenericRequest<>();
    }

    // Safe after checkAccepts: the request is an instance of the type the bean was declared with.
    @SuppressWarnings("unchecked")
    private static <Q extends GenericRequest<?, ?>> boolean validate(Validator<Q> validator, GenericRequest<?, ?> request) {
        return validator.validate((Q) request);
    }

    @SuppressWarnings("unchecked")
    private static <Q extends GenericRequest<?, ?>> Object execute(Command<Q, ?> command, GenericRequest<?, ?> request) {
        return command.execute((Q) request);
    }

    private static Class<?> requestType(Class<?> beanClass, Class<?> contract) {
        return ResolvableType.forClass(ClassUtils.getUserClass(beanClass)).as(contract).getGeneric(0).toClass();
    }

    private static void checkAccepts(Class<?> accepted, GenericRequest<?, ?> request, String beanName) {
        if (!accepted.isInstance(request)) {
            throw new IllegalStateException("Bean '" + beanName + "' accepts " + accepted.getName()
                    + " but the request is " + request.getClass().getName());
        }
    }

    @Override
    public String toString() {
        return method.getName() + "=" + mapping.command()
                + (validator == null ? "" : " (validator " + mapping.validator() + ")");
    }
}
