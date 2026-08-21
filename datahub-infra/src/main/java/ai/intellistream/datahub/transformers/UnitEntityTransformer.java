// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.jpa.domains.Unit;
import ai.intellistream.datahub.models.unit.UnitConversion;
import ai.intellistream.datahub.models.unit.UnitModel;

import java.util.Collection;
import java.util.stream.Collectors;

public class UnitEntityTransformer {

    public static Collection<UnitModel> toUnit(Collection<Unit> units){
        return units.stream().map(UnitEntityTransformer::toUnit).collect(Collectors.toSet());
    }

    public static UnitModel toUnit(Unit unit){
        var unitModel = new UnitModel();
        unitModel.setId(unit.getId());
        unitModel.setName(unit.getName());
        unitModel.setDescription(unit.getDescription());
        unitModel.setAliasNames(unit.getAliasNames());
        unitModel.setExternalId(unit.getExternalId());
        unitModel.setLongName(unit.getLongName());
        unitModel.setSymbol(unit.getSymbol());
        unitModel.setQuantity(unit.getQuantity());
        unitModel.setSource(unit.getSource());
        unitModel.setSourceReference(unit.getSourceReference());
        if(unit.getConversionMultiplier() != null || unit.getConversionOffset() != null){
            var uc = new UnitConversion();
            uc.setMultiplier(unit.getConversionMultiplier());
            uc.setOffset(unit.getConversionOffset());
            unitModel.setConversion(uc);
        }
        return unitModel;
    }
}
