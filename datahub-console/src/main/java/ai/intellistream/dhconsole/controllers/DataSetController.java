// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.DataSetRetreiver;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.PolicyType;
import ai.intellistream.dhconsole.api.DatahubApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping(value = {"/datasets"})
@Slf4j
public class DataSetController {

    private final DatahubApi datahubApi;

    public DataSetController(DatahubApi datahubApi){
        this.datahubApi = datahubApi;
    }

    @RequestMapping(value = {"", "/", "/index"}, method = RequestMethod.GET)
    public String index(Model model, Locale locale){
        DataSetRetreiver retreiver = new DataSetRetreiver();
        retreiver.setLimit(100);
        DataWrapper<DataSetModel> datasets = datahubApi.listDataSets(retreiver);

        model.addAttribute("datasets", datasets.getItems());

        return "datasets/index";
    }

    @RequestMapping(value = {"/policies"}, method = RequestMethod.GET)
    public String policies(Model model, Locale locale){
        var features = datahubApi.getTenantFeatures();
        model.addAttribute("featureDisabled", !features.isPolicyFeatureEnabled());
        if (!features.isPolicyFeatureEnabled()) {
            return "datasets/policies";
        }

        DataWrapper<Policy> policies = datahubApi.getPolicies();
        model.addAttribute("policies", policies.getItems());

        // The left menu splits the policies by scope, and each data set policy names the data set it
        // is attached to, so the page needs a id -> name lookup as well as the policies themselves.
        DataSetRetreiver policyDataSets = new DataSetRetreiver();
        policyDataSets.setLimit(100);
        var dataSets = datahubApi.listDataSets(policyDataSets).getItems();
        model.addAttribute("datasets", dataSets);
        model.addAttribute("dataSetNames", dataSets.stream()
                .filter(d -> d.getId() != null)
                .collect(Collectors.toMap(DataSetModel::getId, DataSetModel::getName, (a, b) -> a)));

        // Whether the tenant has configured a naming policy of its own.
        //
        // When it has not, there is nothing to list, and yet a naming policy IS being enforced: the
        // API falls back to NamingPolicy.shippedDefault(). A page that showed an empty list would
        // say "no rules" while every write is being checked, so the template renders the default as
        // a row instead, and opening it creates a real policy node seeded with those values.
        boolean hasGlobalNamingPolicy = policies.getItems().stream()
                .anyMatch(p -> p.getDataSetId() == null
                        && p.getType() == PolicyType.NAMING_CONVENTION);
        model.addAttribute("hasGlobalNamingPolicy", hasGlobalNamingPolicy);
        return "datasets/policies";
    }

    /**
     * The policy findings queue: external ids that were allowed through but do not follow the
     * naming policy in force.
     *
     * <p>Only the shell and the two filter dropdowns are rendered here. The findings themselves are
     * fetched by the browser straight from datahub-api, because they are grouped and paged client
     * side and there is no reason to relay a potentially very large list through this app.
     */
    @RequestMapping(value = {"/findings"}, method = RequestMethod.GET)
    public String findings(Model model, Locale locale){
        var features = datahubApi.getTenantFeatures();
        model.addAttribute("featureDisabled", !features.isPolicyFeatureEnabled());
        if (!features.isPolicyFeatureEnabled()) {
            return "datasets/findings";
        }

        model.addAttribute("policies", datahubApi.getPolicies().getItems());

        DataSetRetreiver retreiver = new DataSetRetreiver();
        retreiver.setLimit(100);
        model.addAttribute("datasets", datahubApi.listDataSets(retreiver).getItems());

        return "datasets/findings";
    }

    @RequestMapping(value = {"/timeseries"}, method = RequestMethod.GET)
    public String timeseries(Model model, Locale locale){
        DataSetRetreiver retreiver = new DataSetRetreiver();
        retreiver.setLimit(100);
        DataWrapper<DataSetModel> datasets = datahubApi.listDataSets(retreiver);

        model.addAttribute("datasets", datasets.getItems());

        return "datasets/timeseries";
    }
}
