/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.security.authz.permission;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.Operations;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.xpack.core.security.authz.privilege.ApplicationPrivilege;
import org.elasticsearch.xpack.core.security.support.Automatons;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A permission that is based on privileges for application (non elasticsearch) capabilities
 */
public final class ApplicationPermission {

    public static final ApplicationPermission NONE = new ApplicationPermission(Collections.emptyList());

    private final Logger logger;
    private final List<PermissionEntry> permissions;

    /**
     * @param privilegesAndResources A list of (privilege, resources). Each element in the {@link List} is a {@link Tuple} containing
     *                               a single {@link ApplicationPrivilege} and the {@link Set} of resources to which that privilege is
     *                               applied. The resources are treated as a wildcard {@link Automatons#pattern}.
     */
    ApplicationPermission(List<Tuple<ApplicationPrivilege, Set<String>>> privilegesAndResources) {
        this.logger = LogManager.getLogger(getClass());
        Map<ApplicationPrivilege, PermissionEntry> permissionsByPrivilege = new HashMap<>();
        privilegesAndResources.forEach(tup -> permissionsByPrivilege.compute(tup.v1(), (appPriv, existing) -> {
            final Set<String> resourceNames = tup.v2();
            final Automaton patterns = Automatons.patterns(resourceNames);
            if (existing == null) {
                return new PermissionEntry(appPriv, resourceNames, patterns);
            } else {
                return new PermissionEntry(appPriv, Sets.union(existing.resourceNames, resourceNames),
                    Automatons.unionAndMinimize(Arrays.asList(existing.resourceAutomaton, patterns)));
            }
        }));
        this.permissions = Collections.unmodifiableList(new ArrayList<>(permissionsByPrivilege.values()));
    }

    /**
     * Determines whether this permission grants the specified privilege on the given resource.
     * <p>
     * An {@link ApplicationPermission} consists of a sequence of permission entries, where each entry contains a single
     * {@link ApplicationPrivilege} and one or more resource patterns.
     * </p>
     * <p>
     * This method returns {@code true} if, one or more of those entries meet the following criteria
     * </p>
     * <ul>
     * <li>The entry's application, when interpreted as an {@link Automaton} {@link Automatons#pattern(String) pattern} matches the
     * application given in the argument (interpreted as a raw string)
     * </li>
     * <li>The {@link ApplicationPrivilege#getAutomaton automaton that defines the entry's actions} entirely covers the
     * automaton given in the argument (that is, the argument is a subset of the entry's automaton)
     * </li>
     * <li>The entry's resources, when interpreted as an {@link Automaton} {@link Automatons#patterns(String...)} set of patterns} entirely
     * covers the resource given in the argument (also interpreted as an {@link Automaton} {@link Automatons#pattern(String) pattern}.
     * </li>
     * </ul>
     */
    public boolean grants(ApplicationPrivilege other, String resource) {
        Automaton resourceAutomaton = Automatons.patterns(resource);
        final boolean matched = permissions.stream().anyMatch(e -> e.grants(other, resourceAutomaton));
        logger.trace("Permission [{}] {} grant [{} , {}]", this, matched ? "does" : "does not", other, resource);
        return matched;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{privileges=" + permissions + "}";
    }

    public Set<String> getApplicationNames() {
        return permissions.stream()
            .map(e -> e.privilege.getApplication())
            .collect(Collectors.toSet());
    }

    public Set<ApplicationPrivilege> getPrivileges(String application) {
        return permissions.stream()
            .filter(e -> application.equals(e.privilege.getApplication()))
            .map(e -> e.privilege)
            .collect(Collectors.toSet());
    }

    /**
     * Returns a set of resource patterns that are permitted for the provided privilege.
     * The returned set may include patterns that overlap (e.g. "object/*" and "object/1") and may
     * also include patterns that are defined again a more permissive privilege.
     * e.g. If a permission grants
     * <ul>
     *     <li>"my-app", "read", [ "user/*" ]</li>
     *     <li>"my-app", "all", [ "user/kimchy", "config/*" ]</li>
     * </ul>
     * Then <code>getResourcePatterns( myAppRead )</code> would return <code>"user/*", "user/kimchy", "config/*"</code>.
     */
    public Set<String> getResourcePatterns(ApplicationPrivilege privilege) {
        return permissions.stream()
            .filter(e -> e.matchesPrivilege(privilege))
            .map(e -> e.resourceNames)
            .flatMap(Set::stream)
            .collect(Collectors.toSet());
    }

    private static class PermissionEntry {
        private final ApplicationPrivilege privilege;
        private final Predicate<String> application;
        private final Set<String> resourceNames;
        private final Automaton resourceAutomaton;

        private PermissionEntry(ApplicationPrivilege privilege, Set<String> resourceNames, Automaton resourceAutomaton) {
            this.privilege = privilege;
            this.application = Automatons.predicate(privilege.getApplication());
            this.resourceNames = resourceNames;
            this.resourceAutomaton = resourceAutomaton;
        }

        private boolean grants(ApplicationPrivilege other, Automaton resource) {
            return matchesPrivilege(other) && Operations.subsetOf(resource, this.resourceAutomaton);
        }

        private boolean matchesPrivilege(ApplicationPrivilege other) {
            if (this.privilege.equals(other)) {
                return true;
            }
            if (this.application.test(other.getApplication()) == false) {
                return false;
            }
            if (Operations.isTotal(privilege.getAutomaton())) {
                return true;
            }
            return Operations.isEmpty(privilege.getAutomaton()) == false
                && Operations.isEmpty(other.getAutomaton()) == false
                && Operations.subsetOf(other.getAutomaton(), privilege.getAutomaton());
        }

        @Override
        public String toString() {
            return privilege.toString() + ":" + resourceNames;
        }
    }
}
