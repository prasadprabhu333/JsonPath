package com.jayway.jsonpath.internal.token;

import com.jayway.jsonpath.Predicate;

import java.util.Collection;
import java.util.List;

import static java.util.Collections.singletonList;

public class PathTokenFactory {

    public static RootPathToken createRootPathToken(char token) {
        return new RootPathToken(token);
    }

    public static PathToken createSinglePropertyPathToken(String property) {
        return new PropertyPathToken(singletonList(property));
    }

    public static PathToken createPropertyPathToken(List<String> properties) {
        return new PropertyPathToken(properties);
    }

    public static PathToken createSliceArrayPathToken(final ArraySliceOperation arraySliceOperation) {
        return new ArrayPathToken(arraySliceOperation);
    }

    public static PathToken createIndexArrayPathToken(final ArrayIndexOperation arrayIndexOperation) {
        return new ArrayPathToken(arrayIndexOperation);
    }

    public static PathToken createWildCardPathToken() {
        return new WildcardPathToken();
    }

    public static PathToken crateScanToken() {
        return new ScanPathToken();
    }

    public static PathToken createPredicatePathToken(Collection<Predicate> predicates) {
        return new PredicatePathToken(predicates);
    }

    public static PathToken createPredicatePathToken(Predicate predicate) {
        return new PredicatePathToken(predicate);
    }
}
