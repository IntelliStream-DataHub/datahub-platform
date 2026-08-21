// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.RelatedNode;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.datafilters.DataSetFilter;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import ai.intellistream.datahub.models.datafilters.TimeseriesFilter;
import ai.intellistream.datahub.models.events.EventFilter;
import ai.intellistream.datahub.timeseries.Timeseries;
import com.fasterxml.jackson.annotation.JsonIgnore;
import tools.jackson.databind.annotation.JsonSerialize;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds id-shaped fields that would go onto the wire as JSON numbers.
 *
 * <p>Node ids are 64-bit xxHash values, well past what a JavaScript {@code Number} can hold exactly,
 * so the API serializes every id as a string. Three fields were missed —
 * {@code DataSetModel.connectedDataSets}, {@code EdgeProxy.start}/{@code end} and
 * {@code LeanEvent.dataSetId} — each a place a browser client could silently corrupt an id. This
 * finds the next one.
 *
 * <p>"Id-shaped" is deliberately name-based: a {@code Long} called {@code id}, {@code *Id} or
 * {@code *Ids}. That over-matches nothing in practice and under-matches only fields that are ids
 * without saying so, which is a naming problem worth having flagged anyway.
 */
final class IdSerializationCheck {

    private IdSerializationCheck() {}

    /** The DTOs that make up the node-family wire contract. */
    static final List<Class<?>> WIRE_TYPES = List.of(
            Resource.class, Timeseries.class, DataSetModel.class, Policy.class, Function.class,
            EventModel.class, EdgeProxy.class, RelatedNode.class, IdCollection.class,
            ResourceFilter.class, TimeseriesFilter.class, DataSetFilter.class, EventFilter.class);

    /** Fully-qualified names of id-shaped fields that lack string serialization. */
    static List<String> rawNumericIdFields(Class<?> type) {
        List<String> offenders = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.isAnnotationPresent(JsonIgnore.class)) continue;
                if (!looksLikeAnId(c, f.getName())) continue;
                if (!carriesLongs(f)) continue;
                if (f.isAnnotationPresent(JsonSerialize.class)) continue;
                offenders.add(c.getSimpleName() + "." + f.getName());
            }
        }
        return offenders;
    }

    /**
     * Fields holding a node id whose name does not end in "Id". Keyed by declaring class so a
     * common word like "start" is only treated as an id where it actually is one.
     */
    private static final Map<Class<?>, Set<String>> ID_FIELDS_NOT_NAMED_ID = Map.of(
            EdgeProxy.class, Set.of("start", "end"));

    private static boolean looksLikeAnId(Class<?> declaring, String name) {
        if (name.equals("id") || name.endsWith("Id") || name.endsWith("Ids")) return true;
        return ID_FIELDS_NOT_NAMED_ID.getOrDefault(declaring, Set.of()).contains(name);
    }

    /** True for {@code Long}/{@code long} and for collections of {@code Long}. */
    private static boolean carriesLongs(Field f) {
        if (f.getType() == Long.class || f.getType() == long.class) return true;
        if (!Iterable.class.isAssignableFrom(f.getType())) return false;
        if (!(f.getGenericType() instanceof ParameterizedType pt)) return false;
        Type[] args = pt.getActualTypeArguments();
        return args.length == 1 && args[0] == Long.class;
    }
}
