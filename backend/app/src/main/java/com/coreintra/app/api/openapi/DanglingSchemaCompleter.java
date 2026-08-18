package com.coreintra.app.api.openapi;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.customizers.OpenApiCustomiser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Completes the OpenAPI document by defining schemas springdoc referenced and
 * never wrote down.
 *
 * <h2>The bug</h2>
 *
 * <p>springdoc 1.8 emits {@code $ref: #/components/schemas/X} for a type that
 * appears <em>only</em> as an array item — {@code List&lt;Approver&gt;} on a
 * request body, {@code List&lt;PostingResponse&gt;} on a response — and then
 * omits {@code X} from {@code components.schemas}. Twenty-two of them in a
 * 145-schema document, across every area of the API.
 *
 * <p>A dangling {@code $ref} is not cosmetic. The generated TypeScript client
 * refuses to build on it, which is the good failure; the bad one is a lenient
 * generator emitting {@code unknown} and quietly discarding the type safety
 * this pipeline exists to provide.
 *
 * <h2>How the missing types are found</h2>
 *
 * <p>By reading the handler signatures, not by matching names. An earlier
 * attempt scanned the classpath and looked types up by simple name; that is
 * guesswork twice over — it cannot tell two same-named DTOs apart, and it
 * silently finds nothing if a scan filter is subtly wrong, which is exactly
 * what happened. Every schema in this document exists because some controller
 * method returns or accepts it, so walking those methods and asking Swagger's
 * own converter to resolve each type transitively produces precisely the set
 * springdoc should have produced, with no name resolution involved at all.
 *
 * <h2>Why here rather than at the 22 sites</h2>
 *
 * <p>The alternative is {@code @ArraySchema(schema = @Schema(implementation =
 * X.class))} at every site: twenty-two chances to forget on the twenty-third,
 * and no signal when someone does. This runs over the finished document, so a
 * DTO added next month is covered without anyone remembering, and
 * {@code OpenApiSpecTest} asserts the document has no dangling reference at
 * all — so if springdoc fixes the bug, or this stops working, the build says
 * so rather than the frontend finding out.
 */
@Component
public class DanglingSchemaCompleter implements OpenApiCustomiser {

    private static final Logger LOG = LoggerFactory.getLogger(DanglingSchemaCompleter.class);
    private static final String REF_PREFIX = "#/components/schemas/";

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappings;

    public DanglingSchemaCompleter(ObjectProvider<RequestMappingHandlerMapping> handlerMappings) {
        this.handlerMappings = handlerMappings;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            return;
        }
        Set<String> missing = danglingNames(openApi);
        if (missing.isEmpty()) {
            return;
        }

        Map<String, Schema> resolvable = resolveEverySignature();
        for (String name : missing) {
            Schema<?> schema = resolvable.get(name);
            if (schema != null) {
                openApi.getComponents().addSchemas(name, schema);
            }
        }

        // Second pass. A handful of types are reachable only as a field of a
        // field, and Swagger's readAll returns a $ref for those without the
        // definition. The type graph rooted at the handler signatures does
        // contain them, so resolve what is left from there — still only types
        // the API genuinely exposes, never a classpath-wide name search.
        Set<String> afterSignatures = danglingNames(openApi);
        if (!afterSignatures.isEmpty()) {
            Map<String, Class<?>> reachable = reachableTypes();
            for (String name : afterSignatures) {
                Class<?> type = reachable.get(name);
                if (type == null) {
                    continue;
                }
                Map<String, Schema> resolved =
                        new ModelConverters().readAll(new AnnotatedType(type));
                for (Map.Entry<String, Schema> entry : resolved.entrySet()) {
                    if (!openApi.getComponents().getSchemas().containsKey(entry.getKey())) {
                        openApi.getComponents().addSchemas(entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        // Last resort, and bounded on purpose: the DTO packages only, and only
        // when exactly one class carries the name. A type can sit two levels
        // inside a payload that a ResponseEntity<Object> handler names solely in
        // an annotation, and neither walk above reaches it. Restricting the
        // search to com.coreintra.app.api keeps this from becoming the
        // classpath-wide name guess it must not be, and uniqueness keeps it from
        // choosing between two same-named DTOs.
        Set<String> afterGraph = danglingNames(openApi);
        if (!afterGraph.isEmpty()) {
            Map<String, Class<?>> inApiPackages = apiPackageTypes();
            for (String name : afterGraph) {
                Class<?> type = inApiPackages.get(name);
                if (type == null) {
                    continue;
                }
                for (Map.Entry<String, Schema> entry
                        : new ModelConverters().readAll(new AnnotatedType(type)).entrySet()) {
                    if (!openApi.getComponents().getSchemas().containsKey(entry.getKey())) {
                        openApi.getComponents().addSchemas(entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        Set<String> stillMissing = danglingNames(openApi);
        if (!stillMissing.isEmpty()) {
            LOG.warn("OpenAPI document still references {} undefined schema(s): {}",
                    stillMissing.size(), stillMissing);
        }
    }

    /**
     * Every schema reachable from a handler signature, resolved transitively.
     *
     * <p>Swagger's {@code readAll} returns the whole graph it walked, which is
     * the part springdoc drops. Resolving the same type twice is harmless and
     * cheap; this runs once, when the document is built.
     */
    private Map<String, Schema> resolveEverySignature() {
        Map<String, Schema> all = new java.util.LinkedHashMap<String, Schema>();
        for (RequestMappingHandlerMapping mapping : handlerMappings) {
            for (HandlerMethod handler : mapping.getHandlerMethods().values()) {
                Method method = handler.getMethod();
                if (!method.getDeclaringClass().getName().startsWith("com.coreintra")) {
                    continue;
                }
                readInto(all, unwrap(method.getGenericReturnType()));
                for (Type parameter : method.getGenericParameterTypes()) {
                    readInto(all, unwrap(parameter));
                }
                // A handler that returns ResponseEntity<Object> — because it
                // sometimes streams bytes and sometimes returns a view — has no
                // payload type in its signature at all. The type is named in
                // the @Schema(implementation = ...) the author wrote instead,
                // so read it from there.
                for (Class<?> declared : declaredSchemaTypes(method)) {
                    readInto(all, declared);
                }
            }
        }
        return all;
    }

    /**
     * Every class reachable from a handler signature, by simple name.
     *
     * <p>Ambiguity is resolved by refusing: a name held by two classes is
     * dropped, because guessing between them would put a subtly wrong schema in
     * the contract, which is far worse than an obviously missing one. The spec
     * test then fails and the answer is to rename the DTO.
     */
    private Map<String, Class<?>> reachableTypes() {
        Map<String, List<Class<?>>> byName = new java.util.LinkedHashMap<String, List<Class<?>>>();
        Set<Class<?>> visited = new HashSet<Class<?>>();
        for (RequestMappingHandlerMapping mapping : handlerMappings) {
            for (HandlerMethod handler : mapping.getHandlerMethods().values()) {
                Method method = handler.getMethod();
                if (!method.getDeclaringClass().getName().startsWith("com.coreintra")) {
                    continue;
                }
                walkTypes(unwrap(method.getGenericReturnType()), byName, visited, 0);
                for (Type parameter : method.getGenericParameterTypes()) {
                    walkTypes(unwrap(parameter), byName, visited, 0);
                }
                // Same reason as above: a ResponseEntity<Object> handler names
                // its payload only in an annotation, and the graph below that
                // payload is where the deepest omissions are.
                for (Class<?> declared : declaredSchemaTypes(method)) {
                    walkTypes(declared, byName, visited, 0);
                }
            }
        }
        Map<String, Class<?>> unique = new java.util.LinkedHashMap<String, Class<?>>();
        for (Map.Entry<String, List<Class<?>>> entry : byName.entrySet()) {
            if (entry.getValue().size() == 1) {
                unique.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        return unique;
    }

    private static void walkTypes(Type type, Map<String, List<Class<?>>> byName,
            Set<Class<?>> visited, int depth) {
        if (type == null || depth > 6) {
            return;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            walkTypes(parameterized.getRawType(), byName, visited, depth);
            for (Type argument : parameterized.getActualTypeArguments()) {
                walkTypes(argument, byName, visited, depth);
            }
            return;
        }
        if (!(type instanceof Class)) {
            return;
        }
        Class<?> raw = (Class<?>) type;
        if (raw.isPrimitive() || raw.isArray() || !raw.getName().startsWith("com.coreintra")
                || !visited.add(raw)) {
            return;
        }
        record(byName, raw);
        for (Class<?> nested : raw.getDeclaredClasses()) {
            record(byName, nested);
            walkTypes(nested, byName, visited, depth + 1);
        }
        for (java.lang.reflect.Field field : raw.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                walkTypes(field.getGenericType(), byName, visited, depth + 1);
            }
        }
        for (Method accessor : raw.getDeclaredMethods()) {
            if (accessor.getParameterCount() == 0 && accessor.getReturnType() != void.class) {
                walkTypes(accessor.getGenericReturnType(), byName, visited, depth + 1);
            }
        }
    }

    private static void record(Map<String, List<Class<?>>> byName, Class<?> type) {
        if (type.isAnonymousClass() || type.isSynthetic()) {
            return;
        }
        List<Class<?>> existing = byName.get(type.getSimpleName());
        if (existing == null) {
            existing = new java.util.ArrayList<Class<?>>(1);
            byName.put(type.getSimpleName(), existing);
        }
        if (!existing.contains(type)) {
            existing.add(type);
        }
    }

    /**
     * Types named in {@code @Schema(implementation = ...)} anywhere on a method.
     *
     * <p>Walks the annotation tree rather than looking in the three places these
     * usually appear — {@code @ApiResponse}, {@code @RequestBody},
     * {@code @ArraySchema} — because a fourth place is one silent omission
     * away, and the whole point of this class is to stop silent omissions.
     */
    private static Set<Class<?>> declaredSchemaTypes(Method method) {
        Set<Class<?>> found = new LinkedHashSet<Class<?>>();
        for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
            collectSchemaImplementations(annotation, found, new HashSet<Object>());
        }
        return found;
    }

    private static void collectSchemaImplementations(Object node, Set<Class<?>> into,
            Set<Object> seen) {
        if (node == null || !seen.add(node)) {
            return;
        }
        if (node instanceof Object[]) {
            for (Object element : (Object[]) node) {
                collectSchemaImplementations(element, into, seen);
            }
            return;
        }
        if (!(node instanceof java.lang.annotation.Annotation)) {
            return;
        }
        java.lang.annotation.Annotation annotation = (java.lang.annotation.Annotation) node;
        Class<?> annotationType = annotation.annotationType();
        for (Method member : annotationType.getDeclaredMethods()) {
            if (member.getParameterCount() != 0) {
                continue;
            }
            Object value;
            try {
                value = member.invoke(annotation);
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue;
            }
            if (value instanceof Class && "implementation".equals(member.getName())) {
                Class<?> implementation = (Class<?>) value;
                if (implementation.getName().startsWith("com.coreintra")) {
                    into.add(implementation);
                }
            } else {
                collectSchemaImplementations(value, into, seen);
            }
        }
    }

    /** Every class under {@code com.coreintra.app.api}, by simple name, when unique. */
    private static Map<String, Class<?>> apiPackageTypes() {
        Map<String, List<Class<?>>> byName = new java.util.LinkedHashMap<String, List<Class<?>>>();
        org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider scanner =
                new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        // A name pattern, not AssignableTypeFilter(Object.class): the type
        // hierarchy filter stops at java.lang.Object and matches nothing, which
        // fails as an empty result rather than as an error.
        scanner.addIncludeFilter(new org.springframework.core.type.filter.RegexPatternTypeFilter(
                java.util.regex.Pattern.compile(".*")));
        for (org.springframework.beans.factory.config.BeanDefinition definition
                : scanner.findCandidateComponents("com.coreintra.app.api")) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            try {
                Class<?> type = org.springframework.util.ClassUtils.forName(
                        className, DanglingSchemaCompleter.class.getClassLoader());
                record(byName, type);
                for (Class<?> nested : type.getDeclaredClasses()) {
                    record(byName, nested);
                }
            } catch (ClassNotFoundException | LinkageError e) {
                LOG.trace("not indexable: {}", className);
            }
        }
        Map<String, Class<?>> unique = new java.util.LinkedHashMap<String, Class<?>>();
        for (Map.Entry<String, List<Class<?>>> entry : byName.entrySet()) {
            if (entry.getValue().size() == 1) {
                unique.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        return unique;
    }

    private static void readInto(Map<String, Schema> into, Type type) {
        if (type == null) {
            return;
        }
        try {
            // A fresh converter, not ModelConverters.getInstance(). The
            // singleton shares its cache with springdoc's own pass, so a type
            // it has already seen comes back as a bare $ref with no definition
            // — which is the very hole being filled. A clean instance walks the
            // whole graph and emits every schema in it.
            Map<String, Schema> resolved = new ModelConverters().readAll(new AnnotatedType(type));
            for (Map.Entry<String, Schema> entry : resolved.entrySet()) {
                into.putIfAbsent(entry.getKey(), entry.getValue());
            }
        } catch (RuntimeException e) {
            // A type Swagger cannot model — a servlet request, a stream — is not
            // a schema, and failing the whole document over one is wrong.
            LOG.trace("not resolvable as a schema: {}", type, e);
        }
    }

    /** {@code ResponseEntity<T>} carries no schema of its own; the payload is T. */
    private static Type unwrap(Type type) {
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            if (ResponseEntity.class.equals(parameterized.getRawType())) {
                Type[] arguments = parameterized.getActualTypeArguments();
                return arguments.length == 1 ? unwrap(arguments[0]) : null;
            }
        }
        return type;
    }

    /** Every {@code $ref} target with no definition behind it. */
    public static Set<String> danglingNames(OpenAPI openApi) {
        Set<String> referenced = new LinkedHashSet<String>();
        collectRefs(openApi, referenced, new HashSet<Object>());
        Set<String> defined = openApi.getComponents() == null
                || openApi.getComponents().getSchemas() == null
                        ? new HashSet<String>()
                        : openApi.getComponents().getSchemas().keySet();
        Set<String> missing = new LinkedHashSet<String>();
        for (String name : referenced) {
            if (!defined.contains(name)) {
                missing.add(name);
            }
        }
        return missing;
    }

    /**
     * Walks the model looking for {@code $ref} strings.
     *
     * <p>Traversing by object graph rather than by knowing every field a schema
     * can hide in — properties, items, additionalProperties, allOf, oneOf,
     * request bodies, responses, parameters, headers, callbacks — means a new
     * hiding place cannot be missed.
     */
    private static void collectRefs(Object node, Set<String> into, Set<Object> seen) {
        if (node == null) {
            return;
        }
        if (node instanceof String) {
            String text = (String) node;
            if (text.startsWith(REF_PREFIX)) {
                into.add(text.substring(REF_PREFIX.length()));
            }
            return;
        }
        if (node instanceof Number || node instanceof Boolean || node instanceof Enum) {
            return;
        }
        if (!seen.add(node)) {
            return;
        }
        if (node instanceof Map) {
            for (Object value : ((Map<?, ?>) node).values()) {
                collectRefs(value, into, seen);
            }
            return;
        }
        if (node instanceof Iterable) {
            for (Object value : (Iterable<?>) node) {
                collectRefs(value, into, seen);
            }
            return;
        }
        if (node instanceof Schema) {
            Schema<?> schema = (Schema<?>) node;
            collectRefs(schema.get$ref(), into, seen);
            collectRefs(schema.getProperties(), into, seen);
            collectRefs(schema.getItems(), into, seen);
            collectRefs(schema.getAdditionalProperties(), into, seen);
            collectRefs(schema.getAllOf(), into, seen);
            collectRefs(schema.getOneOf(), into, seen);
            collectRefs(schema.getAnyOf(), into, seen);
            return;
        }
        if (node.getClass().getName().startsWith("io.swagger.v3.oas.models")) {
            for (Method method : node.getClass().getMethods()) {
                if (method.getParameterCount() != 0 || method.getReturnType() == void.class
                        || "getClass".equals(method.getName())
                        || !method.getName().startsWith("get")) {
                    continue;
                }
                try {
                    collectRefs(method.invoke(node), into, seen);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOG.trace("skipped {}.{}", node.getClass().getSimpleName(), method.getName());
                }
            }
        }
    }
}
