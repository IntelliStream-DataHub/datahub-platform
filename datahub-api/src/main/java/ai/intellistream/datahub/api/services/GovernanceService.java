// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.jpa.domains.GovernanceTemplate;
import ai.intellistream.datahub.jpa.domains.GovernanceTemplateMetadata;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.models.GovernanceTemplateDTO;
import ai.intellistream.datahub.repositories.governance.GovernanceTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceService {

    private final GovernanceTemplateRepository governanceTemplateRepository;

    /*
     *1. List all governance templates
     */
    @Transactional(readOnly = true)
    public DataWrapper<GovernanceTemplateDTO> listTemplates() {
        List<GovernanceTemplateDTO> templates = governanceTemplateRepository.findAll()
                .stream()
                .map(this::toDto)
                .toList();

        DataWrapper<GovernanceTemplateDTO> wrapper = new DataWrapper<>();
        wrapper.getItems().addAll(templates);
        return wrapper;
    }

    /*
     * 2. Get template by ID
     */
    @Transactional(readOnly = true)
    public DataWrapper<GovernanceTemplateDTO> getTemplateById(Long id) {
        GovernanceTemplate template = governanceTemplateRepository.findById(id)
                .orElseThrow(() -> new ObjectNotFoundException("Governance template not found: " + id));

        DataWrapper<GovernanceTemplateDTO> wrapper = new DataWrapper<>();
        wrapper.getItems().add(toDto(template));
        return wrapper;
    }

    /*
     *  Convert Domain → DTO
     */
    private GovernanceTemplateDTO toDto(GovernanceTemplate template) {
        Map<String, String> metadata = template.getMetadata();

        return new GovernanceTemplateDTO(
                template.getId(),
                template.getName(),
                template.getDescription(),
                template.getExternalId(),
                metadata
        );
    }
}
