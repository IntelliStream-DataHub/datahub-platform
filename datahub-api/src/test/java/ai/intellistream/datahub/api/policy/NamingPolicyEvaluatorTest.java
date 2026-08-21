// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.policy;

import ai.intellistream.datahub.models.policy.NamingPolicy;
import ai.intellistream.datahub.models.policy.NamingPreset;
import ai.intellistream.datahub.models.policy.PolicyDecision;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import ai.intellistream.datahub.models.policy.PolicyMode;
import ai.intellistream.datahub.repositories.policy.NearDuplicateRepository;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The naming policy's two rules, and the batch semantics around them.
 *
 * <p>Everything here runs against a stubbed {@link NearDuplicateRepository}, so what is under test
 * is the decision logic rather than the SQL. The folded-lookup SQL is covered by
 * {@code NamingPolicyIT}, which needs a real PostgreSQL to exercise the functional index.
 */
class NamingPolicyEvaluatorTest {

    private final NearDuplicateRepository nearDuplicateRepository = mock(NearDuplicateRepository.class);
    private final NamingPolicyEvaluator evaluator = new NamingPolicyEvaluator(nearDuplicateRepository);

    private void noExistingNearDuplicates() {
        when(nearDuplicateRepository.findExistingByFoldedValue(anyCollection(), anyCollection()))
                .thenReturn(Map.of());
    }

    private void existingNearDuplicate(String folded, String storedExternalId) {
        when(nearDuplicateRepository.findExistingByFoldedValue(anyCollection(), anyCollection()))
                .thenReturn(Map.of(folded, storedExternalId));
    }

    private static PolicyContext contextOf(NamingPolicy policy) {
        return new PolicyContext("tenant", "sub-abc", dataSetId -> policy);
    }

    // --- the shipped default -------------------------------------------------------------------

    @Test
    void shippedDefaultAcceptsIndustrialTags() {
        noExistingNearDuplicates();

        List<PolicyFinding> findings = evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, "COM-99-PT-1034", null, null),
                        PolicyCandidate.forCreate(1, "=K1-M3+B02", null, null)),
                contextOf(NamingPolicy.shippedDefault()));

        assertThat(findings).isEmpty();
    }

    @Test
    void shippedDefaultStillAcceptsExistingSnakeCaseIds() {
        // The default constrains how many segments an id has, not which convention writes them, so
        // an ordinary snake_case id clears it untouched.
        noExistingNearDuplicates();

        assertThat(evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, "valve_pressure_sensors", null, null)),
                contextOf(NamingPolicy.shippedDefault()))).isEmpty();
    }

    // --- presets -------------------------------------------------------------------------------

    @Test
    void snakeCasePresetRejectsATagAndSuggestsAConformingAlternative() {
        noExistingNearDuplicates();

        List<PolicyFinding> findings = evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, "COM-99-PT-1034", null, null)),
                contextOf(policy(NamingPreset.SNAKE_CASE, null, PolicyMode.REJECT, PolicyMode.REJECT)));

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().decision()).isEqualTo(PolicyDecision.NOT_OK);
        assertThat(findings.getFirst().suggestion()).isEqualTo("com_99_pt_1034");
    }

    @Test
    void warnModeAllowsTheWriteAndStillReportsIt() {
        noExistingNearDuplicates();

        List<PolicyFinding> findings = evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, "COM-99-PT-1034", null, null)),
                contextOf(policy(NamingPreset.SNAKE_CASE, null, PolicyMode.WARN, PolicyMode.REJECT)));

        assertThat(findings).singleElement()
                .extracting(PolicyFinding::decision).isEqualTo(PolicyDecision.WARNING);
    }

    @Test
    void patternPresetAppliesTheSuppliedRegexAndStillSuggests() {
        noExistingNearDuplicates();
        NamingPolicy policy = policy(NamingPreset.PATTERN, Pattern.compile("[A-Z]{2}-\\d{4}"),
                PolicyMode.REJECT, PolicyMode.REJECT);

        assertThat(evaluator.evaluate(List.of(PolicyCandidate.forCreate(0, "PT-1034", null, null)), contextOf(policy)))
                .isEmpty();

        List<PolicyFinding> rejected = evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, "pt-1034", null, null)), contextOf(policy));

        assertThat(rejected).hasSize(1);
        // A regex cannot be inverted, but candidates can be proposed and then tested against it —
        // so the pattern preset gets a real suggestion rather than the shrug it used to give. The
        // value is only offered because it was verified to match.
        assertThat(rejected.getFirst().suggestion()).isEqualTo("PT-1034");
        assertThat(policy.matchesPreset(rejected.getFirst().suggestion())).isTrue();
    }

    @Test
    void theSuggestionIsDerivedFromTheEntitysName() {
        // The evaluator hands the name to the suggester, so a rejection offers something meaningful
        // rather than a mangling of the id that was already wrong.
        noExistingNearDuplicates();

        List<PolicyFinding> findings = evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, "VPS!!", "Valve pressure sensors", null)),
                contextOf(policy(NamingPreset.SNAKE_CASE, null, PolicyMode.REJECT, PolicyMode.REJECT)));

        assertThat(findings).singleElement()
                .extracting(PolicyFinding::suggestion).isEqualTo("valve_pressure_sensors");
    }

    @Test
    void aSuggestionIsNeverAValueAnEarlierItemInTheBatchAlreadyClaimed() {
        // Two items with the same name: the first gets the derived id, the second must not be told
        // to use an id its own batch-mate is about to take.
        noExistingNearDuplicates();
        NamingPolicy snake = policy(NamingPreset.SNAKE_CASE, null, PolicyMode.WARN, PolicyMode.WARN);

        List<PolicyFinding> findings = evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, "pump_a_01", "Pump A 01", null),
                        PolicyCandidate.forCreate(1, "PUMP-A-02", "Pump A 01", null)),
                contextOf(snake));

        assertThat(findings).isNotEmpty();
        assertThat(findings).allSatisfy(f ->
                assertThat(f.suggestion()).isNotEqualTo("pump_a_01"));
    }

    // --- the near-duplicate guard --------------------------------------------------------------

    @Test
    void nearDuplicateOfAStoredIdIsReportedByDefault() {
        // The anomaly the old snake_case rule prevented. The shipped default records it rather than
        // refusing it: which of the two ids is right is the steward's call, not the platform's.
        existingNearDuplicate("pump_a_01", "pump_a_01");

        List<PolicyFinding> findings = evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, "pump-a-01", null, null)),
                contextOf(NamingPolicy.shippedDefault()));

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().decision()).isEqualTo(PolicyDecision.WARNING);
        assertThat(findings.getFirst().message()).contains("pump_a_01");
    }

    @Test
    void nearDuplicateGuardCanBeDowngradedToWarn() {
        // The escape hatch for a facility that genuinely maintains both as distinct tags. Meaningful
        // because the finding is persisted: "allowed and in the steward's queue", not "forgotten".
        existingNearDuplicate("pump_a_01", "pump_a_01");

        List<PolicyFinding> findings = evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, "pump-a-01", null, null)),
                contextOf(policy(NamingPreset.VERBATIM_TAG, null, PolicyMode.REJECT, PolicyMode.WARN)));

        assertThat(findings).singleElement()
                .extracting(PolicyFinding::decision).isEqualTo(PolicyDecision.WARNING);
    }

    @Test
    void twoItemsInOneBatchThatCollideWithEachOtherAreCaught() {
        // Neither exists in the database yet, so comparing only against stored data would let both
        // through — which makes the guard bypassable by submitting the pair together.
        noExistingNearDuplicates();

        List<PolicyFinding> findings = evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, "PUMP-A-01", null, null),
                        PolicyCandidate.forCreate(1, "pump_a_01", null, null)),
                contextOf(NamingPolicy.shippedDefault()));

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().index()).isEqualTo(1);
        assertThat(findings.getFirst().externalId()).isEqualTo("pump_a_01");
    }

    @Test
    void anEntityIsNotItsOwnNearDuplicateOnUpdate() {
        // The updated node's id is excluded from the lookup, or editing a resource's description
        // would report it as colliding with itself.
        noExistingNearDuplicates();

        evaluator.evaluate(
                List.of(PolicyCandidate.forUpdate(0, "pump-a-02", null, null, 41L, "pump-a-01")),
                contextOf(NamingPolicy.shippedDefault()));

        var excluded = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        org.mockito.Mockito.verify(nearDuplicateRepository)
                .findExistingByFoldedValue(anyCollection(), excluded.capture());
        assertThat(excluded.getValue()).containsExactly(41L);
    }

    // --- update semantics ----------------------------------------------------------------------

    @Test
    void anUnchangedExternalIdIsNotRevalidated() {
        // Otherwise tightening a policy makes every pre-existing resource unupdatable, and a steward
        // fixing a description gets a naming error on a field they did not touch.
        List<PolicyFinding> findings = evaluator.evaluate(
                List.of(PolicyCandidate.forUpdate(0, "COM-99-PT-1034", null, null, 7L, "COM-99-PT-1034")),
                contextOf(policy(NamingPreset.SNAKE_CASE, null, PolicyMode.REJECT, PolicyMode.REJECT)));

        assertThat(findings).isEmpty();
        // Not even the lookup runs — an untouched id costs nothing.
        org.mockito.Mockito.verifyNoInteractions(nearDuplicateRepository);
    }

    @Test
    void aChangedExternalIdIsRevalidated() {
        noExistingNearDuplicates();

        assertThat(evaluator.evaluate(
                List.of(PolicyCandidate.forUpdate(0, "COM-99-PT-1034", null, null, 7L, "com_99_pt_1034")),
                contextOf(policy(NamingPreset.SNAKE_CASE, null, PolicyMode.REJECT, PolicyMode.REJECT))))
                .hasSize(1);
    }

    // --- severity ------------------------------------------------------------------------------

    @Test
    void whenBothRulesFireTheMoreSevereWins() {
        // A caller told "warning" about something that is actually going to be rejected has been
        // misled about what happened to their write.
        existingNearDuplicate("pump_a_01", "pump_a_01");

        List<PolicyFinding> findings = evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, "pump-a-01", null, null)),
                contextOf(policy(NamingPreset.SNAKE_CASE, null, PolicyMode.WARN, PolicyMode.REJECT)));

        assertThat(findings).singleElement()
                .extracting(PolicyFinding::decision).isEqualTo(PolicyDecision.NOT_OK);
    }

    // --- worked examples, against the shipped default ------------------------------------------

    /**
     * Twenty ids the shipped default accepts: qualified, and not near-duplicates of anything stored.
     *
     * <p>Qualified means at least {@link NamingPolicy#MIN_QUALIFIED_SEGMENTS} separator-delimited
     * segments. ISA-5.1 loop tags, IEC 81346 reference designations and ordinary snake_case ids all
     * clear it, which is the point: the rule is about how much an id says, not which convention it
     * is written in. These are also stored verbatim, so a regression here means a plant tag came
     * back different from how it was sent.
     */
    private static List<String> acceptedByTheDefault() {
        return List.of(
                "COM-99-PT-1034",
                "=K1-M3+B02",
                "TIC-101.PV",
                "LT-5001:PV",
                "21-PT-1034",
                "HX-201.TEMP",
                "pump_a_01",
                "=G01+H02-K5",
                "AI-3021.OUT",
                "TT-7788:VALUE",
                "compressor_discharge_temp",
                "PDT-115.SP",
                "WELL-A-12:THP",
                "valve_pressure_sensor",
                "FIC-2001-PV",
                "ESDV-1200-CMD",
                "MOV-44A-POS",
                "XV-0042-STATUS",
                "3K-401A-VIB",
                "P-101-DISCH-TEMP");
    }

    /**
     * Twenty under-qualified ids: fewer than {@link NamingPolicy#MIN_QUALIFIED_SEGMENTS} segments.
     *
     * <p>All are legal under the charset floor and none collides with anything stored, so the
     * qualified_tag rule is the only thing that fires and the decision is entirely its doing.
     *
     * <p>Covers one segment ({@code pump}, {@code PT1034}) and two ({@code pump-1234},
     * {@code P-101}), across every separator, so the count is what decides rather than the
     * punctuation used. Several of these are real tags that are simply short — which is why this
     * rule warns rather than rejects.
     */
    private static List<String> underQualified() {
        return List.of(
                "pump-1234",
                "P-101",
                "MOV-44A",
                "XV-0042",
                "ESDV-1200",
                "3K-401A",
                "FIC_2001",
                "e101_temp",
                "pump",
                "valve",
                "TIC.101",
                "LT:5001",
                "HX+201",
                "AI=3021",
                "compressor_1",
                "tank2",
                "PT1034",
                "separator",
                "flow-meter",
                "TT.7788");
    }

    /**
     * Twenty {@code (candidate, already stored)} pairs that fold to the same value.
     *
     * <p>Every candidate is deliberately qualified, so the near-duplicate guard is the only rule
     * that fires and NOT_OK is unambiguously its verdict rather than a severity contest with the
     * preset. Each pair differs only in case or in one of the folded separators
     * ({@code - . : + =}), which is what "near duplicate" means here.
     */
    private static Stream<Arguments> nearDuplicatePairs() {
        return Stream.of(
                Arguments.of("pump-a-01", "pump_a_01"),
                Arguments.of("PUMP.A.01", "pump_a_01"),
                Arguments.of("Pump:A:01", "pump_a_01"),
                Arguments.of("pump+a+01", "pump_a_01"),
                Arguments.of("pump=a=01", "pump_a_01"),
                Arguments.of("COM.99.PT.1034", "COM-99-PT-1034"),
                Arguments.of("com-99-pt-1034", "COM-99-PT-1034"),
                Arguments.of("TIC_101_PV", "TIC-101.PV"),
                Arguments.of("lt-5001-pv", "LT-5001:PV"),
                Arguments.of("hx.201.temp", "HX-201.TEMP"),
                Arguments.of("ai-3021-out", "AI-3021.OUT"),
                Arguments.of("tt.7788.value", "TT-7788:VALUE"),
                Arguments.of("pdt_115_sp", "PDT-115.SP"),
                Arguments.of("well.a.12.thp", "WELL-A-12:THP"),
                Arguments.of("fic.2001.pv", "FIC-2001-PV"),
                Arguments.of("esdv_1200_cmd", "ESDV-1200-CMD"),
                Arguments.of("mov.44a.pos", "MOV-44A-POS"),
                Arguments.of("xv-0042-status", "XV-0042-STATUS"),
                Arguments.of("3k.401a.vib", "3K-401A-VIB"),
                Arguments.of("p.101.disch.temp", "P-101-DISCH-TEMP"));
    }

    @ParameterizedTest(name = "[{index}] OK: {0}")
    @MethodSource("acceptedByTheDefault")
    void aQualifiedTagIsOkUnderTheDefault(String externalId) {
        noExistingNearDuplicates();

        // No finding at all is how OK is expressed: the evaluator reports exceptions, not approvals.
        assertThat(evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, externalId, null, null)),
                contextOf(NamingPolicy.shippedDefault())))
                .isEmpty();
    }

    @ParameterizedTest(name = "[{index}] WARNING: {0} is under-qualified")
    @MethodSource("underQualified")
    void anUnderQualifiedTagWarnsUnderTheDefault(String externalId) {
        // shippedDefault() carries mode = WARN for the preset, so the write goes through and the
        // finding lands in the steward's queue rather than refusing the data.
        noExistingNearDuplicates();

        assertThat(evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, externalId, null, null)),
                contextOf(NamingPolicy.shippedDefault())))
                .singleElement()
                .extracting(PolicyFinding::decision).isEqualTo(PolicyDecision.WARNING);
    }

    @ParameterizedTest(name = "[{index}] WARNING: {0} near-duplicates existing {1}")
    @MethodSource("nearDuplicatePairs")
    void aNearDuplicateWarnsUnderTheDefault(String externalId, String existing) {
        // shippedDefault() carries nearDuplicateMode = WARN, so the write goes through and the
        // collision is recorded. Nothing in the shipped policy refuses a write.
        existingNearDuplicate(ExternalIds.fold(externalId), existing);

        assertThat(evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, externalId, null, null)),
                contextOf(NamingPolicy.shippedDefault())))
                .singleElement()
                .extracting(PolicyFinding::decision).isEqualTo(PolicyDecision.WARNING);
    }

    @ParameterizedTest(name = "[{index}] NOT_OK: {0} near-duplicates existing {1}")
    @MethodSource("nearDuplicatePairs")
    void aNearDuplicateIsRejectedWhenTheGuardIsSetToReject(String externalId, String existing) {
        // What a deployment opts into when it wants the collision to block rather than be queued.
        existingNearDuplicate(ExternalIds.fold(externalId), existing);

        assertThat(evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, externalId, null, null)),
                contextOf(policy(NamingPreset.QUALIFIED_TAG, null, PolicyMode.WARN, PolicyMode.REJECT))))
                .singleElement()
                .extracting(PolicyFinding::decision).isEqualTo(PolicyDecision.NOT_OK);
    }

    // --- performance ---------------------------------------------------------------------------

    @Test
    void aThousandItemBatchDoesOneResolutionAndOneLookup() {
        // The kind of thing that silently regresses into a per-item query, so assert it explicitly.
        noExistingNearDuplicates();

        List<PolicyCandidate> batch = new ArrayList<>(1000);
        for (int i = 0; i < 1000; i++) {
            batch.add(PolicyCandidate.forCreate(i, "asset_" + i, null, 12L));
        }

        AtomicInteger resolutions = new AtomicInteger();
        PolicyContext context = new PolicyContext("tenant", "sub", dataSetId -> {
            resolutions.incrementAndGet();
            return NamingPolicy.shippedDefault();
        });

        evaluator.evaluate(batch, context);

        assertThat(resolutions).hasValue(1);
        assertThat(context.resolutionCount()).isEqualTo(1);
        org.mockito.Mockito.verify(nearDuplicateRepository, org.mockito.Mockito.times(1))
                .findExistingByFoldedValue(anyCollection(), anyCollection());
    }

    @Test
    void aBatchSpanningTwoDataSetsResolvesOncePerDataSet() {
        noExistingNearDuplicates();

        AtomicInteger resolutions = new AtomicInteger();
        PolicyContext context = new PolicyContext("tenant", "sub", dataSetId -> {
            resolutions.incrementAndGet();
            return NamingPolicy.shippedDefault();
        });

        evaluator.evaluate(List.of(
                PolicyCandidate.forCreate(0, "asset_a", null, 1L),
                PolicyCandidate.forCreate(1, "asset_b", null, 1L),
                PolicyCandidate.forCreate(2, "asset_c", null, 2L)), context);

        assertThat(resolutions).hasValue(2);
    }

    @Test
    void aTimeseriesIsGovernedLikeAnyOtherNode() {
        // Timeseries share the node table and its external_id_hash unique index, so the same
        // convention has to govern them — otherwise a resource creation is checked against existing
        // timeseries ids while creating a timeseries is not, and the guard has a hole shaped like a
        // whole entity type. The evaluator is entity-agnostic; this pins the intent.
        existingNearDuplicate("engine_temp", "engine_temp");

        List<PolicyFinding> findings = evaluator.evaluate(
                List.of(PolicyCandidate.forCreate(0, "engine-temp", "Engine temperature", 12L)),
                contextOf(NamingPolicy.shippedDefault()));

        assertThat(findings).singleElement()
                .extracting(PolicyFinding::decision).isEqualTo(PolicyDecision.WARNING);
    }

    @Test
    void anEmptyBatchTouchesNothing() {
        assertThat(evaluator.evaluate(List.of(), contextOf(NamingPolicy.shippedDefault()))).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(nearDuplicateRepository);
    }

    private static NamingPolicy policy(NamingPreset preset, Pattern pattern,
                                       PolicyMode mode, PolicyMode nearDuplicateMode) {
        return new NamingPolicy(7L, "naming_house_rule", preset, pattern, mode, nearDuplicateMode);
    }
}
