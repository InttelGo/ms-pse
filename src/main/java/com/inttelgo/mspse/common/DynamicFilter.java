package com.inttelgo.mspse.common;

import com.inttelgo.mspse.domain.EstadoTransaccion;
import com.inttelgo.mspse.domain.EstadoUrl;
import com.inttelgo.mspse.domain.GenerateUrl;
import com.inttelgo.mspse.domain.Transaccion;
import com.inttelgo.mspse.exception.BadRequestException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class DynamicFilter {

    private static final Pattern DATE_ONLY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private static final Map<Class<?>, Map<String, String>> COLUMNS = new HashMap<>();
    private static final Map<Class<?>, Set<String>> ASSOCIATIONS = new HashMap<>();
    static {
        Map<String, String> audit = Map.of(
                "create_at", "createAt",
                "create_by", "createBy",
                "update_at", "updateAt",
                "update_by", "updateBy",
                "delete_at", "deleteAt");
        register(EstadoTransaccion.class, with(audit, Map.of(
                "id", "id",
                "status", "status")), Set.of());
        register(EstadoUrl.class, with(audit, Map.of(
                "id", "id",
                "status", "status")), Set.of());
        register(GenerateUrl.class, with(audit, Map.of(
                "id", "id",
                "url", "url",
                "concept", "concept",
                "value", "value",
                "expiry_at", "expiryAt",
                "status_id", "status")), Set.of("status"));
        register(Transaccion.class, Map.ofEntries(
                Map.entry("id", "id"),
                Map.entry("number_ticket", "numberTicket"),
                Map.entry("code_trazability", "codeTrazability"),
                Map.entry("transaction_value", "transactionValue"),
                Map.entry("discount", "discount"),
                Map.entry("iva_value", "ivaValue"),
                Map.entry("bank_code", "bankCode"),
                Map.entry("bank_name", "bankName"),
                Map.entry("type_document", "typeDocument"),
                Map.entry("identification", "identification"),
                Map.entry("full_name", "fullName"),
                Map.entry("email", "email"),
                Map.entry("phone", "phone"),
                Map.entry("create_at", "createAt"),
                Map.entry("create_by", "createBy"),
                Map.entry("response_at", "responseAt"),
                Map.entry("update_at", "updateAt"),
                Map.entry("first_reference", "firstReference"),
                Map.entry("second_reference", "secondReference"),
                Map.entry("thirth_reference", "thirthReference"),
                Map.entry("entitycode", "entitycode"),
                Map.entry("financial_institutioncode", "financialInstitutionCode"),
                Map.entry("service_code", "serviceCode"),
                Map.entry("bank_process_date", "bankProcessDate"),
                Map.entry("trasnsaction_cycle", "trasnsactionCycle"),
                Map.entry("payment_description", "paymentDescription"),
                Map.entry("address", "address"),
                Map.entry("urlgenerated", "urlGenerated"),
                Map.entry("status_id", "status"),
                Map.entry("build_id", "buildId"),
                Map.entry("collection_build_id", "collectionBuildId")
        ), Set.of("status", "urlGenerated"));
    }

    private static Map<String, String> with(Map<String, String> base, Map<String, String> extra) {
        Map<String, String> merged = new HashMap<>(base);
        merged.putAll(extra);
        return Map.copyOf(merged);
    }

    private DynamicFilter() {
    }

    private static void register(Class<?> type, Map<String, String> columns, Set<String> associations) {
        COLUMNS.put(type, columns);
        ASSOCIATIONS.put(type, associations);
    }

    public static String sortAttribute(Class<?> type, String column) {
        if (column == null || column.isBlank()) {
            return null;
        }
        String mapped = attribute(type, column.trim());
        if (mapped.contains(".") || isAssociation(type, mapped)) {
            throw new BadRequestException("Columna de ordenamiento no permitida: " + column);
        }
        return mapped;
    }

    public static Predicate build(CriteriaBuilder builder, CriteriaQuery<?> query, Root<?> root, List<Filter> filters) {
        return combine(builder, query, root, filters, new HashMap<>());
    }

    private static Predicate combine(
            CriteriaBuilder builder,
            CriteriaQuery<?> query,
            Root<?> root,
            List<Filter> filters,
            Map<String, Join<?, ?>> joins) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        Predicate accumulated = null;
        for (Filter filter : filters) {
            Predicate next = one(builder, query, root, filter, joins);
            if (next == null) {
                continue;
            }
            if (accumulated == null) {
                accumulated = next;
                continue;
            }
            String logic = filter.logic();
            accumulated = logic != null && logic.equalsIgnoreCase("OR")
                    ? builder.or(accumulated, next)
                    : builder.and(accumulated, next);
        }
        return accumulated;
    }

    private static Predicate one(
            CriteriaBuilder builder,
            CriteriaQuery<?> query,
            Root<?> root,
            Filter filter,
            Map<String, Join<?, ?>> joins) {
        if (filter.filters() != null && !filter.filters().isEmpty()) {
            return combine(builder, query, root, filter.filters(), joins);
        }
        if (filter.column() == null || filter.column().isBlank()) {
            return null;
        }
        Expression<?> expression = resolve(builder, root, joins, filter.column());
        return compare(builder, expression, filter.operator(), filter.value());
    }

    private static Expression<?> resolve(
            CriteriaBuilder builder,
            Root<?> root,
            Map<String, Join<?, ?>> joins,
            String column) {
        String name = attribute(root.getJavaType(), column);
        if (name.contains(".")) {
            String[] parts = name.split("\\.", 2);
            Join<?, ?> join = joins.computeIfAbsent(parts[0], key -> root.join(key, JoinType.LEFT));
            return join.get(leaf(parts[1]));
        }
        if (isAssociation(root.getJavaType(), name)) {
            Join<?, ?> join = joins.computeIfAbsent(name, key -> root.join(key, JoinType.LEFT));
            return join.get("id");
        }
        try {
            return root.get(name);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Columna no permitida: " + column);
        }
    }

    private static boolean isAssociation(Class<?> type, String name) {
        return ASSOCIATIONS.getOrDefault(type, Set.of()).contains(name);
    }

    private static String attribute(Class<?> type, String column) {
        String key = column.trim();
        if (key.contains(".")) {
            String[] parts = key.split("\\.", 2);
            return attribute(type, parts[0]) + "." + leaf(parts[1]);
        }
        Map<String, String> columns = COLUMNS.get(type);
        if (columns == null) {
            throw new BadRequestException("Columna no permitida: " + column);
        }
        String mapped = columns.get(key.toLowerCase(Locale.ROOT));
        if (mapped == null) {
            throw new BadRequestException("Columna no permitida: " + column);
        }
        return mapped;
    }

    private static String leaf(String column) {
        return switch (column.toLowerCase(Locale.ROOT)) {
            case "status" -> "status";
            case "concept" -> "concept";
            case "value" -> "value";
            case "id" -> "id";
            default -> throw new BadRequestException("Columna no permitida: " + column);
        };
    }

    private static Predicate compare(CriteriaBuilder builder, Expression<?> expression, String operator, Object value) {
        String op = operator == null || operator.isBlank() ? "contains" : operator;
        if ("in".equals(op) || "notIn".equals(op)) {
            return inPredicate(builder, expression, op, value);
        }
        if ("day".equalsIgnoreCase(op) || "month".equalsIgnoreCase(op) || "year".equalsIgnoreCase(op)) {
            if (value == null) {
                throw new BadRequestException("El operador " + op + " requiere un valor");
            }
            Expression<Integer> fn = builder.function(op.toUpperCase(Locale.ROOT), Integer.class, expression);
            return builder.equal(fn, Integer.valueOf(String.valueOf(value)));
        }
        if (value == null) {
            return switch (op) {
                case "equals" -> builder.isNull(expression);
                case "notEquals" -> builder.isNotNull(expression);
                default -> throw new BadRequestException("El operador " + op + " requiere un valor");
            };
        }
        if (isTextual(op)) {
            Expression<String> lowered = builder.lower(expression.as(String.class));
            Predicate like = builder.like(lowered, pattern(op, String.valueOf(value)), '\\');
            return "notContains".equals(op) ? builder.not(like) : like;
        }
        if (dateOnly(op, expression.getJavaType(), value)) {
            Expression<java.sql.Date> date = builder.function("DATE", java.sql.Date.class, expression);
            java.sql.Date sqlDate = java.sql.Date.valueOf(String.valueOf(value));
            return "notEquals".equals(op) ? builder.notEqual(date, sqlDate) : builder.equal(date, sqlDate);
        }
        Object coerced = coerce(expression.getJavaType(), value);
        return switch (op) {
            case "equals" -> builder.equal(expression, coerced);
            case "notEquals" -> builder.notEqual(expression, coerced);
            case "greaterThan" -> greater(builder, expression, coerced);
            case "greaterThanOrEqual" -> greaterEqual(builder, expression, coerced);
            case "lessThan" -> less(builder, expression, coerced);
            case "lessThanOrEqual" -> lessEqual(builder, expression, coerced);
            default -> throw new BadRequestException("Operador no soportado: " + op);
        };
    }

    private static boolean isTextual(String operator) {
        return operator.equals("contains")
                || operator.equals("notContains")
                || operator.equals("like")
                || operator.equals("startsWith")
                || operator.equals("endsWith");
    }

    private static boolean dateOnly(String operator, Class<?> type, Object value) {
        return type == Instant.class
                && ("equals".equals(operator) || "notEquals".equals(operator))
                && value instanceof String text
                && DATE_ONLY.matcher(text).matches();
    }

    private static Predicate inPredicate(CriteriaBuilder builder, Expression<?> expression, String operator, Object value) {
        List<Object> values = asList(value).stream()
                .map(item -> coerce(expression.getJavaType(), item))
                .toList();
        if (values.isEmpty()) {
            return "notIn".equals(operator) ? builder.conjunction() : builder.disjunction();
        }
        CriteriaBuilder.In<Object> in = builder.in(expression);
        values.forEach(in::value);
        return "notIn".equals(operator) ? builder.not(in) : in;
    }

    private static List<Object> asList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value instanceof Map<?, ?> map) {
            return new ArrayList<>(map.values());
        }
        return List.of(value);
    }

    private static Object coerce(Class<?> type, Object value) {
        if (value == null || type == null) {
            return value;
        }
        if (type.isEnum()) {
            return enumValue(type, String.valueOf(value));
        }
        if (type == Boolean.class || type == boolean.class) {
            return booleanValue(value);
        }
        if (type.isInstance(value) && type != Instant.class && type != LocalDate.class) {
            return value;
        }
        String text = String.valueOf(value);
        if (type == String.class) {
            return text;
        }
        if (type == java.math.BigDecimal.class) {
            if (value instanceof java.math.BigDecimal decimal) {
                return decimal;
            }
            if (value instanceof Number number) {
                return java.math.BigDecimal.valueOf(number.doubleValue());
            }
            try {
                return new java.math.BigDecimal(text);
            } catch (NumberFormatException exception) {
                throw new BadRequestException("Valor numérico inválido: " + value);
            }
        }
        if (type == Long.class || type == long.class) {
            return parseLong(value);
        }
        if (type == Integer.class || type == int.class) {
            return parseLong(value).intValue();
        }
        if (type == LocalDate.class) {
            return LocalDate.parse(text);
        }
        if (type == Instant.class) {
            if (DATE_ONLY.matcher(text).matches()) {
                return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            return Instant.parse(text);
        }
        return value;
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value).trim();
        return text.equalsIgnoreCase("true") || text.equals("1");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String text) {
        try {
            return Enum.valueOf((Class<? extends Enum>) type, text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Valor no permitido: " + text);
        }
    }

    private static Long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new BadRequestException("Valor numérico inválido: " + value);
        }
    }

    private static String pattern(String operator, String value) {
        String escaped = value.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return switch (operator) {
            case "startsWith" -> escaped + "%";
            case "endsWith" -> "%" + escaped;
            default -> "%" + escaped + "%";
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate greater(CriteriaBuilder builder, Expression<?> expression, Object value) {
        return builder.greaterThan((Expression) expression, (Comparable) value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate greaterEqual(CriteriaBuilder builder, Expression<?> expression, Object value) {
        return builder.greaterThanOrEqualTo((Expression) expression, (Comparable) value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate less(CriteriaBuilder builder, Expression<?> expression, Object value) {
        return builder.lessThan((Expression) expression, (Comparable) value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate lessEqual(CriteriaBuilder builder, Expression<?> expression, Object value) {
        return builder.lessThanOrEqualTo((Expression) expression, (Comparable) value);
    }
}
