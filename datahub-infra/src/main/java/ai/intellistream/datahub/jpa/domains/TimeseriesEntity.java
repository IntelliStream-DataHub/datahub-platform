// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import ai.intellistream.datahub.timeseries.enums.TableEngine;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;


@Entity
@DiscriminatorValue("2")
@Getter
@Setter
public class TimeseriesEntity extends NodeEntity{


    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "value_type_id")
    private TimeseriesValueType valueType;

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
                ", unit='" + unit + '\'' +
                ", unitExternalId='" + unitExternalId + '\'' +
                ", tableEngine=" + tableEngine +
                '}';
    }
}
