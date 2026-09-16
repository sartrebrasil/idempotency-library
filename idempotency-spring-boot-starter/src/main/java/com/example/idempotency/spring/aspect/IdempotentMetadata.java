package com.example.idempotency.spring.aspect;

import com.example.idempotency.annotation.Idempotent;
import com.example.idempotency.annotation.IdempotentKey;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds {@code @Idempotent} and {@code @IdempotentKey} for a call.
 *
 * <p>Java does not inherit method annotations, so a guard declared on an
 * interface (or an abstract base class) is invisible on the implementation
 * method. Every lookup here therefore walks the method's whole hierarchy —
 * implementation first, then the interfaces and superclasses it overrides —
 * which is what makes annotating the contract work.
 *
 * <p>Results are cached per resolved method; the maps are bounded by the number
 * of guarded methods in the application.
 */
public class IdempotentMetadata {

    private final Map<Method, Optional<Idempotent>> annotations = new ConcurrentHashMap<>();
    private final Map<Method, IdempotentKey[]> keyParameters = new ConcurrentHashMap<>();

    /** The implementation method behind a possibly interface-declared call. */
    public Method specificMethod(Method method, Class<?> targetClass) {
        return targetClass == null ? method : AopUtils.getMostSpecificMethod(method, targetClass);
    }

    public Class<?> targetClassOf(Object target) {
        return target == null ? null : AopProxyUtils.ultimateTargetClass(target);
    }

    /** The annotation in effect, looked up across the method hierarchy. */
    public Optional<Idempotent> findIdempotent(Method method, Class<?> targetClass) {
        Method specific = specificMethod(method, targetClass);
        return annotations.computeIfAbsent(specific,
                resolved -> Optional.ofNullable(firstAnnotationInHierarchy(resolved, targetClass)));
    }

    /**
     * {@code @IdempotentKey} per parameter position, taken from the first method
     * in the hierarchy that declares any — parameter annotations have no
     * equivalent of {@code SearchStrategy.TYPE_HIERARCHY}.
     */
    public IdempotentKey[] keyParameters(Method method, Class<?> targetClass) {
        Method specific = specificMethod(method, targetClass);
        return keyParameters.computeIfAbsent(specific, resolved -> {
            for (Method candidate : hierarchy(resolved, targetClass)) {
                IdempotentKey[] declared = parameterAnnotations(candidate);
                if (declared != null) {
                    return declared;
                }
            }
            return new IdempotentKey[resolved.getParameterCount()];
        });
    }

    private Idempotent firstAnnotationInHierarchy(Method specific, Class<?> targetClass) {
        for (Method candidate : hierarchy(specific, targetClass)) {
            Idempotent annotation = candidate.getAnnotation(Idempotent.class);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    private IdempotentKey[] parameterAnnotations(Method method) {
        Parameter[] parameters = method.getParameters();
        IdempotentKey[] found = new IdempotentKey[parameters.length];
        boolean any = false;
        for (int i = 0; i < parameters.length; i++) {
            found[i] = parameters[i].getAnnotation(IdempotentKey.class);
            any |= found[i] != null;
        }
        return any ? found : null;
    }

    /** The method itself, then the same signature on interfaces and superclasses. */
    private List<Method> hierarchy(Method specific, Class<?> targetClass) {
        List<Method> methods = new ArrayList<>();
        methods.add(specific);
        Class<?> type = targetClass != null ? targetClass : specific.getDeclaringClass();

        for (Class<?> interfaceType : ClassUtils.getAllInterfacesForClass(type)) {
            addIfDeclared(methods, interfaceType, specific);
        }
        for (Class<?> superType = type.getSuperclass(); superType != null && superType != Object.class;
                superType = superType.getSuperclass()) {
            addIfDeclared(methods, superType, specific);
        }
        return methods;
    }

    private void addIfDeclared(List<Method> methods, Class<?> type, Method specific) {
        try {
            Method declared = type.getDeclaredMethod(specific.getName(), specific.getParameterTypes());
            if (!methods.contains(declared)) {
                methods.add(declared);
            }
        } catch (NoSuchMethodException ignored) {
            // This type does not take part in the signature.
        }
    }
}
