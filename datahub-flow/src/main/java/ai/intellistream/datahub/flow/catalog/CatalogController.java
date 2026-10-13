// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.catalog;

import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The processors a flow may use, as a flow definition, the console's editor and an agent read them.
 * Every {@link Processor} bean in the service is listed, by name, each with its properties as a
 * JSON Schema ({@link ProcessorSchema}).
 */
@RestController
@RequestMapping("/catalog")
public class CatalogController {

    private final List<Processor> processors;

    public CatalogController(List<Processor> processors) {
        this.processors = processors;
    }

    @GetMapping("/processors")
    public List<Map<String, Object>> processors() {
        return processors.stream()
                .map(Processor::describe)
                .sorted(Comparator.comparing(ProcessorDescriptor::category)
                        .thenComparing(ProcessorDescriptor::displayName))
                .map(ProcessorSchema::of)
                .toList();
    }
}
