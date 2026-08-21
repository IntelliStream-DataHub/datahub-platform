// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

@Data
public class DataSort {

    private List<String> property;
    private String order;

    @Pattern(regexp = "^[a-zA-Z]+$")
    private String nulls;

    public void setNulls(String txt){
        if(txt != null){
            this.nulls = txt.toLowerCase();
        }
    }

}
