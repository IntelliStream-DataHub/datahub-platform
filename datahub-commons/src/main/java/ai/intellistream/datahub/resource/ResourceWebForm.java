// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.resource;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ResourceWebForm extends ResourceForm {

    private Long relationFrom;

    private List<String> relationTypes;

}
