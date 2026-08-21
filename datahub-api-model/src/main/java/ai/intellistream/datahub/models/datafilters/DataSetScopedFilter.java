// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.datafilters;

import ai.intellistream.datahub.json.SingleOrList;
import ai.intellistream.datahub.models.IdCollection;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * {@link NodeFilter} for the node types that <em>live in</em> a data set. Data sets themselves do
 * not, which is why {@code dataSetId} sits here rather than on the base — a data set filter with a
 * {@code dataSetId} field would be asking which data set a data set belongs to.
 */
@EqualsAndHashCode(callSuper = true)
@Data
public abstract class DataSetScopedFilter extends NodeFilter {

    /**
     * Restrict to nodes in these data sets, each given by id or externalId. A data set stands in
     * for everything beneath it in the {@code BELONGS_TO} hierarchy, so naming a parent covers its
     * children — the same expansion the data set ACL applies. An externalId naming no data set
     * contributes nothing.
     *
     * <p><b>Null and empty differ here, unlike every other list on {@link NodeFilter}.</b> Null (or
     * an absent field) is "no data set restriction"; an explicit {@code []} is "narrow to no data
     * sets" and matches nothing. The two are opposite answers, so the null must survive — datahub-api
     * coerces null collections to empty for most fields, and without the setter below a null would
     * silently match nothing at all.
     *
     * <p><b>{@code dataSetId} was once a scalar {@code Long} on {@code TimeseriesFilter}</b>, so the
     * name is being reused rather than coined. Nothing silently changes meaning for a caller who
     * still sends the old shape: {@code "dataSetId": 43} is a bare number where an
     * {@link IdCollection} object is expected, so it fails to deserialize and the request is
     * rejected. Send {@code {"id": "43"}} — or an externalId — instead.
     */
    @SingleOrList
    @JsonSetter(nulls = Nulls.SET)
    @Schema(description = """
            Restrict to nodes in these data sets, each given by id or externalId. A data set stands \
            in for everything beneath it in the BELONGS_TO hierarchy, so naming a parent covers its \
            children. Omit the field (or send null) for no data set restriction; an explicit empty \
            list matches nothing.""",
            example = "[{\"id\": \"43\"}, {\"externalId\": \"data_set_sap\"}]")
    private List<IdCollection> dataSetId;
}
