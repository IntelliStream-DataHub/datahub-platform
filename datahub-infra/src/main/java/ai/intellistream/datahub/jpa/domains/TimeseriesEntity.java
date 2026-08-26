// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import ai.intellistream.datahub.timeseries.enums.TableEngine;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;
import java.util.TreeSet;


@Entity
@DiscriminatorValue("2")
@Getter
@Setter
public class TimeseriesEntity extends NodeEntity{


    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "value_type_id")
    private TimeseriesValueType valueType;

    /**
     * EAGER because the DTO is serialized after the transaction closes, so a LAZY collection would
     * throw {@code LazyInitializationException} at JSON time. EAGER alone cannot be join-fetched
     * without multiplying the parent rows, so Hibernate issues one SELECT per time series — an
     * N+1 that scales with the page: a 1000-row read of {@code /resources/filter}, which spans
     * every node type and so can return a page that is mostly time series, costs 1000 extra
     * round trips.
     *
     * <p>{@code @BatchSize} is what removes it: Hibernate collects the pending ids and loads them
     * in {@code IN (…)} batches, turning those 1000 queries into 10. Chosen over
     * {@code hibernate.default_batch_fetch_size} because it travels with the entity and so holds
     * for the api, both consumers, cleanup and analysis without each repeating the setting. (The
     * {@code batch_size: 1000} already in application.yml is JDBC <em>write</em> batching and does
     * nothing for reads.)
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @BatchSize(size = 100)
    @CollectionTable(name = "timeseries_security_categories", joinColumns = @JoinColumn(name = "timeseries_id"))
    @Column(name = "security_categories_integer")
    private Set<Integer> securityCategories = new TreeSet<>();

    private String unit;

    private String unitExternalId;

    @Enumerated(EnumType.ORDINAL)
    private TableEngine tableEngine;

    public void setValueType(@NotNull TimeseriesValueType valueType){
        this.valueType = valueType;
    }

    public void setValueType(@NotNull String text){
        int valueTypeId = TimeseriesValueType.getValueTypeId(text);
        this.valueType = new TimeseriesValueType(valueTypeId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TimeseriesEntity )) return false;
        return id != null && id.equals(((TimeseriesEntity) o).getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "TimeseriesEntity{" + "id=" + id +
                ", valueType=" + valueType +
                ", securityCategories=" + securityCategories +
                ", unit='" + unit + '\'' +
                ", unitExternalId='" + unitExternalId + '\'' +
                ", tableEngine=" + tableEngine +
                '}';
    }
}
