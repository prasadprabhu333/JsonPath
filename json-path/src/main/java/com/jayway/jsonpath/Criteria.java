/*
 * Copyright 2011 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jayway.jsonpath;

import com.jayway.jsonpath.internal.Path;
import com.jayway.jsonpath.internal.PathCompiler;
import com.jayway.jsonpath.internal.Utils;
import com.jayway.jsonpath.internal.token.PredicateContextImpl;
import com.jayway.jsonpath.spi.json.JsonProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

import static com.jayway.jsonpath.internal.Utils.join;
import static com.jayway.jsonpath.internal.Utils.notNull;


/**
 *
 */
@SuppressWarnings("unchecked")
public class Criteria implements Predicate {

    private static final Logger logger = LoggerFactory.getLogger(Criteria.class);

    private static final String CRITERIA_WRAPPER_CHAR = "¦";

    private static final String[] OPERATORS = {
            CriteriaType.GTE.toString(),
            CriteriaType.LTE.toString(),
            CriteriaType.EQ.toString(),
            CriteriaType.NE.toString(),
            CriteriaType.LT.toString(),
            CriteriaType.GT.toString(),
            CriteriaType.REGEX.toString(),
            CriteriaType.NIN.toString(),
            CriteriaType.IN.toString(),
            CriteriaType.CONTAINS.toString(),
            CriteriaType.ALL.toString(),
            CriteriaType.SIZE.toString(),
            CriteriaType.EXISTS.toString(),
            CriteriaType.TYPE.toString(),
            CriteriaType.MATCHES.toString(),
            CriteriaType.NOT_EMPTY.toString(),

    };
    private static final char BS = '\\';

    private Object left;
    private CriteriaType criteriaType;
    private Object right;

    private final List<Criteria> criteriaChain;

    private static enum CriteriaType {
        EQ {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                boolean res = (0 == safeCompare(expected, model, ctx));
                if (logger.isDebugEnabled()) logger.debug("[{}] {} [{}] => {}", model, name(), expected, res);
                return res;
            }

            @Override
            public String toString() {
                return "==";
            }
        },
        NE {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                boolean res = (0 != safeCompare(expected, model, ctx));
                if (logger.isDebugEnabled()) logger.debug("[{}] {} [{}] => {}", model, name(), expected, res);
                return res;
            }

            @Override
            public String toString() {
                return "!=";
            }
        },
        GT {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                if ((expected == null) ^ (model == null)) {
                    return false;
                }
                boolean res = (0 > safeCompare(expected, model));
                if (logger.isDebugEnabled()) logger.debug("[{}] {} [{}] => {}", model, name(), expected, res);
                return res;
            }

            @Override
            public String toString() {
                return ">";
            }
        },
        GTE {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                if ((expected == null) ^ (model == null)) {
                    return false;
                }
                boolean res = (0 >= safeCompare(expected, model));
                if (logger.isDebugEnabled()) logger.debug("[{}] {} [{}] => {}", model, name(), expected, res);
                return res;
            }

            @Override
            public String toString() {
                return ">=";
            }
        },
        LT {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                if ((expected == null) ^ (model == null)) {
                    return false;
                }
                boolean res = (0 < safeCompare(expected, model));
                if (logger.isDebugEnabled()) logger.debug("[{}] {} [{}] => {}", model, name(), expected, res);
                return res;
            }

            @Override
            public String toString() {
                return "<";
            }
        },
        LTE {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                if ((expected == null) ^ (model == null)) {
                    return false;
                }
                boolean res = (0 <= safeCompare(expected, model));
                if (logger.isDebugEnabled()) logger.debug("[{}] {} [{}] => {}", model, name(), expected, res);
                return res;
            }

            @Override
            public String toString() {
                return "<=";
            }
        },
        REGEX {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                boolean res = false;
                Pattern pattern;
                Object target;

                if (model instanceof Pattern) {
                    pattern = (Pattern) model;
                    target = expected;
                } else {
                    pattern = (Pattern) expected;
                    target = model;
                }

                if (target != null) {
                    res = pattern.matcher(target.toString()).matches();
                }
                if (logger.isDebugEnabled())
                    logger.debug("[{}] {} [{}] => {}", model == null ? "null" : model.toString(), name(), expected == null ? "null" : expected.toString(), res);
                return res;
            }

            @Override
            public String toString() {
                return "=~";
            }
        },
        IN {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                boolean res = false;
                Collection exps = (Collection) expected;
                for (Object exp : exps) {
                    if (0 == safeCompare(exp, model)) {
                        res = true;
                        break;
                    }
                }
                if (logger.isDebugEnabled()) logger.debug("[{}] {} [{}] => {}", model, name(), join(", ", exps), res);
                return res;
            }

            @Override
            public String toString() {
                return CRITERIA_WRAPPER_CHAR + name() + CRITERIA_WRAPPER_CHAR;
            }
        },
        NIN {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                Collection nexps = (Collection) expected;
                boolean res = !nexps.contains(model);
                if (logger.isDebugEnabled()) logger.debug("[{}] {} [{}] => {}", model, name(), join(", ", nexps), res);
                return res;
            }

            @Override
            public String toString() {
                return CRITERIA_WRAPPER_CHAR + name() + CRITERIA_WRAPPER_CHAR;
            }
        },
        CONTAINS {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                boolean res = false;
                if (ctx.configuration().jsonProvider().isArray(model)) {
                    for (Object o : ctx.configuration().jsonProvider().toIterable(model)) {
                        if (expected.equals(o)) {
                            res = true;
                            break;
                        }
                    }
                } else if (model instanceof String) {
                    if (isNullish(expected) || !(expected instanceof String)) {
                        res = false;
                    } else {
                        res = ((String) model).contains((String) expected);
                    }
                }
                if (logger.isDebugEnabled()) logger.debug("[{}] {} [{}] => {}", model, name(), expected, res);
                return res;
            }

            @Override
            public String toString() {
                return CRITERIA_WRAPPER_CHAR + name() + CRITERIA_WRAPPER_CHAR;
            }
        },
        ALL {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                boolean res = true;
                Collection exps = (Collection) expected;
                if (ctx.configuration().jsonProvider().isArray(model)) {
                    for (Object exp : exps) {
                        boolean found = false;
                        for (Object check : ctx.configuration().jsonProvider().toIterable(model)) {
                            if (0 == safeCompare(exp, check)) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            res = false;
                            break;
                        }
                    }
                    if (logger.isDebugEnabled())
                        logger.debug("[{}] {} [{}] => {}", join(", ", ctx.configuration().jsonProvider().toIterable(model)), name(), join(", ", exps), res);
                } else {
                    res = false;
                    if (logger.isDebugEnabled()) logger.debug("[{}] {} [{}] => {}", "<NOT AN ARRAY>", name(), join(", ", exps), res);
                }
                return res;
            }

            @Override
            public String toString() {
                return CRITERIA_WRAPPER_CHAR + name() + CRITERIA_WRAPPER_CHAR;
            }
        },
        SIZE {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                int size = (Integer) expected;
                boolean res;
                if (ctx.configuration().jsonProvider().isArray(model)) {
                    int length = ctx.configuration().jsonProvider().length(model);
                    res = (length == size);
                    if (logger.isDebugEnabled()) logger.debug("Array with size {} {} {} => {}", length, name(), size, res);
                } else if (model instanceof String) {
                    int length = ((String) model).length();
                    res = length == size;
                    if (logger.isDebugEnabled()) logger.debug("String with length {} {} {} => {}", length, name(), size, res);
                } else {
                    res = false;
                    if (logger.isDebugEnabled())
                        logger.debug("{} {} {} => {}", model == null ? "null" : model.getClass().getName(), name(), size, res);
                }
                return res;
            }

            @Override
            public String toString() {
                return CRITERIA_WRAPPER_CHAR + name() + CRITERIA_WRAPPER_CHAR;
            }
        },
        EXISTS {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                //This must be handled outside
                throw new UnsupportedOperationException();
            }

            @Override
            public String toString() {
                return CRITERIA_WRAPPER_CHAR + name() + CRITERIA_WRAPPER_CHAR;
            }
        },
        TYPE {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                final Class<?> expType = (Class<?>) expected;
                final Class<?> actType = model == null ? null : model.getClass();

                return actType != null && expType.isAssignableFrom(actType);
            }

            @Override
            public String toString() {
                return CRITERIA_WRAPPER_CHAR + name() + CRITERIA_WRAPPER_CHAR;
            }
        },
        MATCHES {
            @Override
            boolean eval(Object expected, final Object model, final PredicateContext ctx) {
                PredicateContextImpl pci = (PredicateContextImpl) ctx;
                Predicate exp = (Predicate) expected;
                return exp.apply(new PredicateContextImpl(model, ctx.root(), ctx.configuration(), pci.documentPathCache()));
            }

            @Override
            public String toString() {
                return CRITERIA_WRAPPER_CHAR + name() + CRITERIA_WRAPPER_CHAR;
            }
        },
        NOT_EMPTY {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                boolean res = false;
                if (model != null) {
                    if (ctx.configuration().jsonProvider().isArray(model)) {
                        int len = ctx.configuration().jsonProvider().length(model);
                        res = (0 != len);
                        if (logger.isDebugEnabled()) logger.debug("array length = {} {} => {}", len, name(), res);
                    } else if (model instanceof String) {
                        int len = ((String) model).length();
                        res = (0 != len);
                        if (logger.isDebugEnabled()) logger.debug("string length = {} {} => {}", len, name(), res);
                    }
                }
                return res;
            }

            @Override
            public String toString() {
                return CRITERIA_WRAPPER_CHAR + name() + CRITERIA_WRAPPER_CHAR;
            }
        },
        NOOP {
            @Override
            boolean eval(Object expected, Object model, PredicateContext ctx) {
                return true;
            }

            @Override
            public String toString() {
                return CRITERIA_WRAPPER_CHAR + name() + CRITERIA_WRAPPER_CHAR;
            }
        };

        abstract boolean eval(Object expected, Object model, PredicateContext ctx);

        public static CriteriaType parse(String str) {
            for (CriteriaType criteriaType : values()) {
                if (criteriaType.toString().equals(str)) {
                    return criteriaType;
                }
            }
            throw new UnsupportedOperationException("CriteriaType " + str + " can not be parsed");
        }
    }

    private Criteria(List<Criteria> criteriaChain, Object left) {
        this.left = left;
        this.criteriaChain = criteriaChain;
        this.criteriaChain.add(this);
    }

    private Criteria(Object left) {
        this(new LinkedList<Criteria>(), left);
    }

    private Criteria(Object left, CriteriaType criteriaType, Object right) {
        this(new LinkedList<Criteria>(), left);
        this.criteriaType = criteriaType;
        this.right = right;
    }


    @Override
    public boolean apply(PredicateContext ctx) {
        for (Criteria criteria : criteriaChain) {
            if (!criteria.eval(ctx)) {
                return false;
            }
        }
        return true;
    }

    private Object evaluateIfPath(Object target, PredicateContext ctx) {
        Object res = target;
        if (res instanceof Path) {
            Path leftPath = (Path) target;

            if (ctx instanceof PredicateContextImpl) {
                //This will use cache for document ($) queries
                PredicateContextImpl ctxi = (PredicateContextImpl) ctx;
                res = ctxi.evaluate(leftPath);
            } else {
                Object doc = leftPath.isRootPath() ? ctx.root() : ctx.item();
                res = leftPath.evaluate(doc, ctx.root(), ctx.configuration()).getValue();
            }
        }
        return res == null ? null : ctx.configuration().jsonProvider().unwrap(res);
    }

    private boolean eval(PredicateContext ctx) {
        if (CriteriaType.EXISTS == criteriaType) {
            boolean exists = ((Boolean) right);
            try {
                Configuration c = Configuration.builder().jsonProvider(ctx.configuration().jsonProvider()).options(Option.REQUIRE_PROPERTIES).build();
                Object value = ((Path) left).evaluate(ctx.item(), ctx.root(), c).getValue();
                if (exists) {
                    return (value != null);
                } else {
                    return (value == null);
                }
            } catch (PathNotFoundException e) {
                return !exists;
            }
        } else {
            try {
                Object leftVal = evaluateIfPath(left, ctx);
                Object rightVal = evaluateIfPath(right, ctx);

                return criteriaType.eval(rightVal, leftVal, ctx);
            } catch (ValueCompareException e) {
                return false;
            } catch (PathNotFoundException e) {
                return false;
            }
        }
    }


    /**
     * Static factory method to create a Criteria using the provided key
     *
     * @param key filed name
     * @return the new criteria
     */
    public static Criteria where(Path key) {
        return new Criteria(key);
    }

    /**
     * Static factory method to create a Criteria using the provided key
     *
     * @param key filed name
     * @return the new criteria
     */

    public static Criteria where(String key) {
        if (!key.startsWith("$") && !key.startsWith("@")) {
            key = "@." + key;
        }
        return where(PathCompiler.compile(key));
    }

    /**
     * Static factory method to create a Criteria using the provided key
     *
     * @param key ads new filed to criteria
     * @return the criteria builder
     */
    public Criteria and(String key) {
        if (!key.startsWith("$") && !key.startsWith("@")) {
            key = "@." + key;
        }
        return new Criteria(this.criteriaChain, PathCompiler.compile(key));
    }

    /**
     * Creates a criterion using equality
     *
     * @param o
     * @return the criteria
     */
    public Criteria is(Object o) {
        this.criteriaType = CriteriaType.EQ;
        this.right = o;
        return this;
    }

    /**
     * Creates a criterion using equality
     *
     * @param o
     * @return the criteria
     */
    public Criteria eq(Object o) {
        return is(o);
    }

    /**
     * Creates a criterion using the <b>!=</b> operator
     *
     * @param o
     * @return the criteria
     */
    public Criteria ne(Object o) {
        this.criteriaType = CriteriaType.NE;
        this.right = o;
        return this;
    }

    /**
     * Creates a criterion using the <b>&lt;</b> operator
     *
     * @param o
     * @return the criteria
     */
    public Criteria lt(Object o) {
        this.criteriaType = CriteriaType.LT;
        this.right = o;
        return this;
    }

    /**
     * Creates a criterion using the <b>&lt;=</b> operator
     *
     * @param o
     * @return the criteria
     */
    public Criteria lte(Object o) {
        this.criteriaType = CriteriaType.LTE;
        this.right = o;
        return this;
    }

    /**
     * Creates a criterion using the <b>&gt;</b> operator
     *
     * @param o
     * @return the criteria
     */
    public Criteria gt(Object o) {
        this.criteriaType = CriteriaType.GT;
        this.right = o;
        return this;
    }

    /**
     * Creates a criterion using the <b>&gt;=</b> operator
     *
     * @param o
     * @return the criteria
     */
    public Criteria gte(Object o) {
        this.criteriaType = CriteriaType.GTE;
        this.right = o;
        return this;
    }

    /**
     * Creates a criterion using a Regex
     *
     * @param pattern
     * @return the criteria
     */
    public Criteria regex(Pattern pattern) {
        notNull(pattern, "pattern can not be null");
        this.criteriaType = CriteriaType.REGEX;
        this.right = pattern;
        return this;
    }

    /**
     * The <code>in</code> operator is analogous to the SQL IN modifier, allowing you
     * to specify an array of possible matches.
     *
     * @param o the values to match against
     * @return the criteria
     */
    public Criteria in(Object... o) {
        return in(Arrays.asList(o));
    }

    /**
     * The <code>in</code> operator is analogous to the SQL IN modifier, allowing you
     * to specify an array of possible matches.
     *
     * @param c the collection containing the values to match against
     * @return the criteria
     */
    public Criteria in(Collection<?> c) {
        notNull(c, "collection can not be null");
        this.criteriaType = CriteriaType.IN;
        this.right = c;
        return this;
    }

    /**
     * The <code>contains</code> operator asserts that the provided object is contained
     * in the result. The object that should contain the input can be either an object or a String.
     *
     * @param o that should exists in given collection or
     * @return the criteria
     */
    public Criteria contains(Object o) {
        this.criteriaType = CriteriaType.CONTAINS;
        this.right = o;
        return this;
    }

    /**
     * The <code>nin</code> operator is similar to $in except that it selects objects for
     * which the specified field does not have any value in the specified array.
     *
     * @param o the values to match against
     * @return the criteria
     */
    public Criteria nin(Object... o) {
        return nin(Arrays.asList(o));
    }

    /**
     * The <code>nin</code> operator is similar to $in except that it selects objects for
     * which the specified field does not have any value in the specified array.
     *
     * @param c the values to match against
     * @return the criteria
     */
    public Criteria nin(Collection<?> c) {
        notNull(c, "collection can not be null");
        this.criteriaType = CriteriaType.NIN;
        this.right = c;
        return this;
    }

    /**
     * The <code>all</code> operator is similar to $in, but instead of matching any value
     * in the specified array all values in the array must be matched.
     *
     * @param o
     * @return the criteria
     */
    public Criteria all(Object... o) {
        return all(Arrays.asList(o));
    }

    /**
     * The <code>all</code> operator is similar to $in, but instead of matching any value
     * in the specified array all values in the array must be matched.
     *
     * @param c
     * @return the criteria
     */
    public Criteria all(Collection<?> c) {
        notNull(c, "collection can not be null");
        this.criteriaType = CriteriaType.ALL;
        this.right = c;
        return this;
    }

    /**
     * The <code>size</code> operator matches:
     * <p/>
     * <ol>
     * <li>array with the specified number of elements.</li>
     * <li>string with given length.</li>
     * </ol>
     *
     * @param size
     * @return the criteria
     */
    public Criteria size(int size) {
        this.criteriaType = CriteriaType.SIZE;
        this.right = size;
        return this;
    }


    /**
     * Check for existence (or lack thereof) of a field.
     *
     * @param b
     * @return the criteria
     */
    public Criteria exists(boolean b) {
        this.criteriaType = CriteriaType.EXISTS;
        this.right = b;
        return this;
    }

    /**
     * The $type operator matches values based on their Java type.
     *
     * @param t
     * @return the criteria
     */
    public Criteria type(Class<?> t) {
        notNull(t, "type can not be null");
        this.criteriaType = CriteriaType.TYPE;
        this.right = t;
        return this;
    }

    /**
     * The <code>notEmpty</code> operator checks that an array or String is not empty.
     *
     * @return the criteria
     */
    public Criteria notEmpty() {
        this.criteriaType = CriteriaType.NOT_EMPTY;
        this.right = null;
        return this;
    }

    /**
     * The <code>matches</code> operator checks that an object matches the given predicate.
     *
     * @param p
     * @return the criteria
     */
    public Criteria matches(Predicate p) {
        this.criteriaType = CriteriaType.MATCHES;
        this.right = p;
        return this;
    }

    private static boolean isPath(String string) {
        return (string != null
                && (string.startsWith("$") || string.startsWith("@") || string.startsWith("!@")));
    }

    private static boolean isString(String string) {
        return (string != null && !string.isEmpty() && string.charAt(0) == '\'' && string.charAt(string.length() - 1) == '\'');
    }

    private static boolean isJson(String string) {
        if (string == null || string.length() <= 1)
            return false;
        char c0 = string.charAt(0);
        char c1 = string.charAt(string.length() - 1);
        return (c0 == '[' && c1 == ']') || (c0 == '{' && c1 == '}');
    }

    private static boolean isPattern(String string) {
        return (string != null
                && !string.isEmpty()
                && string.charAt(0) == '/'
                && (string.charAt(string.length() - 1) == '/' || (string.charAt(string.length() - 2) == '/' && string.charAt(string.length() - 1) == 'i'))
        );
    }

    private static Pattern compilePattern(String string) {
        int lastIndex = string.lastIndexOf('/');
        boolean ignoreCase = string.endsWith("i");
        String regex = string.substring(1, lastIndex);

        int flags = ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
        return Pattern.compile(regex, flags);
    }

    /**
     * Parse the provided criteria
     *
     * @param criteria
     * @return a criteria
     */
    public static Criteria parse(String criteria) {
        int operatorIndex = -1;
        String left = "";
        CriteriaType operator = null;
        String right = "";

        //can not iterate values() because the need to be checked in order eg '>=' before '>'
        for (int y = 0; y < OPERATORS.length; y++) {
            operatorIndex = criteria.indexOf(OPERATORS[y]);
            if (operatorIndex != -1) {
                operator = CriteriaType.parse(OPERATORS[y]);
                break;
            }
        }
        if (operator != null) {
            left = criteria.substring(0, operatorIndex).trim();
            right = criteria.substring(operatorIndex + operator.toString().length()).trim();
        } else {
            left = criteria.trim();
        }

        return Criteria.create(left, operator, right);
    }

    /**
     * Wrapper for JSON to be parsed as a String.
     */
    private static class JsonValue {
        final String value;
        volatile Object jsonValue;

        JsonValue(String value) {
            this.value = value;
        }

        Object parsed(PredicateContext ctx) {
            if (jsonValue == null) {
                JsonProvider provider = ctx.configuration().jsonProvider();
                jsonValue = provider.parse(value);
            }
            return jsonValue;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * Creates a new criteria
     *
     * @param left     path to evaluate in criteria
     * @param operator operator
     * @param right    expected value
     * @return a new Criteria
     */
    private static Criteria create(String left, CriteriaType operator, String right) {
        Object leftPrepared = left;
        Object rightPrepared = right;
        Path leftPath = null;
        Path rightPath = null;
        boolean existsCheck = true;

        if (isPath(left)) {
            if (left.charAt(0) == '!') {
                existsCheck = false;
                left = left.substring(1);
            }
            leftPath = PathCompiler.compile(left);
            if (!leftPath.isDefinite()) {
                throw new InvalidPathException("the predicate path: " + left + " is not definite");
            }
            leftPrepared = leftPath;
        } else if (isString(left)) {
            leftPrepared = left.substring(1, left.length() - 1);
        } else if (isPattern(left)) {
            leftPrepared = compilePattern(left);
        }

        if (isPath(right)) {
            if (right.charAt(0) == '!') {
                throw new InvalidPathException("Invalid negation! Can only be used for existence check e.g [?(!@.foo)]");
            }
            rightPath = PathCompiler.compile(right);
            if (!rightPath.isDefinite()) {
                throw new InvalidPathException("the predicate path: " + right + " is not definite");
            }
            rightPrepared = rightPath;
        } else if (isString(right)) {
            rightPrepared = right.substring(1, right.length() - 1);
        } else if(Utils.isNumeric(right)){
            rightPrepared = new BigDecimal(right);
        } else if (isJson(right)) {
            rightPrepared = new JsonValue(right);
        } else if (isPattern(right)) {
            rightPrepared = compilePattern(right);
        }

        if (leftPath != null && (operator == null)) {
            return Criteria.where(leftPath).exists(existsCheck);
        } else {
            return new Criteria(leftPrepared, operator, rightPrepared);
        }
    }

    private static int safeCompare(Object left, Object right) throws ValueCompareException {
        return safeCompare(left, right, null);
    }

    private static String unescape(String s) {
        if (s.indexOf(BS) == -1)
            return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == BS) {
                char c2 = s.charAt(++i);
                switch (c2) {
                    case 'b':
                        c2 = '\b';
                        break;
                    case 'f':
                        c2 = '\f';
                        break;
                    case 'n':
                        c2 = '\n';
                        break;
                    case 'r':
                        c2 = '\r';
                        break;
                    case 't':
                        c2 = '\t';
                        break;
                    case 'u':
                        try {
                            String hex = s.substring(i + 1, i + 5);
                            c2 = (char) Integer.parseInt(hex, 16);
                            i += 4;
                        } catch (Exception e) {
                            throw new ValueCompareException("\\u parse failed", e);
                        }
                        break;
                }
                sb.append(c2);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int safeCompare(Object left, Object right, PredicateContext ctx) throws ValueCompareException {

        if (left == right) {
            return 0;
        }

        boolean leftNullish = isNullish(left);
        boolean rightNullish = isNullish(right);

        if (leftNullish && !rightNullish) {
            return -1;
        } else if (!leftNullish && rightNullish) {
            return 1;
        } else if (leftNullish && rightNullish) {
            return 0;
        } else if (left instanceof String && right instanceof String) {
            String expected = unescape((String) left);
            return expected.compareTo((String) right);
        } else if (left instanceof Number && right instanceof Number) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
        } else if (left instanceof Number && right instanceof BigDecimal) {
            return new BigDecimal(left.toString()).compareTo((BigDecimal)right);
        } else if (left instanceof String && right instanceof Number) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
        } else if (left instanceof String && right instanceof Boolean) {
            Boolean e = Boolean.valueOf((String) left);
            Boolean a = (Boolean) right;
            return e.compareTo(a);
        } else if (left instanceof Boolean && right instanceof Boolean) {
            Boolean e = (Boolean) left;
            Boolean a = (Boolean) right;
            return e.compareTo(a);
        } else if (left instanceof JsonValue) {
            notNull(ctx, "ctx");
            JsonValue json = (JsonValue) left;
            return right.equals(json.parsed(ctx)) ? 0 : -1;
        } else {
            throw new ValueCompareException(left, right);
        }
    }

    private static boolean isNullish(Object o) {
        return (o == null || ((o instanceof String) && ("null".equals(o))));
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        Iterator<Criteria> i = criteriaChain.iterator();
        while (i.hasNext()) {
            Criteria c = i.next();

            if (CriteriaType.NOT_EMPTY == c.criteriaType) {
                sb.append(c.left.toString())
                        .append(" ")
                        .append(c.criteriaType.toString());

            } else if (CriteriaType.EXISTS == c.criteriaType) {
                Boolean exists = (Boolean)c.right;
                if(exists.booleanValue()){
                    sb.append(c.left.toString());
                } else {
                    sb.append("!").append(c.left.toString());
                }
            } else if (CriteriaType.TYPE == c.criteriaType) {
                Class tp = (Class) c.right;
                sb.append(c.left.toString())
                        .append(" ")
                        .append(c.criteriaType.toString())
                        .append(" ")
                        .append(tp.getCanonicalName());
            }  else {
                sb.append(c.left.toString())
                        .append(" ")
                        .append(c.criteriaType.toString())
                        .append(" ")
                        .append(wrapString(c.right));
            }

            if (i.hasNext()) {
                sb.append(" && ");
            }
        }
        return sb.toString();
    }

    private static String wrapString(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof String) {
            String s = o.toString();
            return "'" + s + "'";
//            if(Utils.isNumeric(s)){
//                return s;
//            } else {
//                return "'" + s + "'";
//            }
        }
        if (o instanceof Collection) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");

            Iterator i = ((Collection) o).iterator();
            while (i.hasNext()) {
                sb.append(wrapString(i.next()));
                if (i.hasNext()) {
                    sb.append(",");
                }
            }
            sb.append("]");
            return sb.toString();
        }

        return o.toString();
    }
}
