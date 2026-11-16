package org.example.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticMergeGateTest {

  private static final String KEY = "warehouse.db.shared_events";

  private final RecordingChecker checker = new RecordingChecker();

  private SemanticMergeGate gate(GateMode mode, boolean allowCompatible) {
    return new SemanticMergeGate(
        GateConfig.builder()
            .addWatchedKeys(KEY)
            .mode(mode)
            .allowCompatibleStaleWrites(allowCompatible)
            .build(),
        checker);
  }

  private static TableFingerprint fp(int schemaId, long snapshotId) {
    return TableFingerprint.builder()
        .key(KEY)
        .schemaId(schemaId)
        .snapshotId(snapshotId)
        .metadataLocation("s3://bucket/metadata/v" + schemaId + ".metadata.json")
        .build();
  }

  private static ThreeWayView view(
      TableFingerprint base, TableFingerprint source, TableFingerprint target) {
    return ThreeWayView.builder()
        .key(KEY)
        .base(base)
        .source(source)
        .target(target)
        .build();
  }

  @Test
  void noSchemaDriftOnTargetIsAlwaysAllowed() {
    Verdict verdict =
        gate(GateMode.ENFORCING, true)
            .evaluate(
                List.of(
                    view(
                        fp(1, 10L),
                        fp(1, 17L), // source wrote new data
                        fp(1, 10L)))); // target schema unchanged
    assertThat(verdict.allowed()).isTrue();
    assertThat(verdict.violations()).isEmpty();
  }

  @Test
  void pureReadAgainstEvolvedTargetIsAllowed() {
    Verdict verdict =
        gate(GateMode.ENFORCING, true)
            .evaluate(
                List.of(
                    view(
                        fp(1, 10L),
                        fp(1, 10L), // identical pointer: pure read, no write back
                        fp(2, 10L))));
    assertThat(verdict.allowed()).isTrue();
    assertThat(verdict.violations()).isEmpty();
  }

  @Test
  void writeAfterRebaseOnNewSchemaIsAllowed() {
    Verdict verdict =
        gate(GateMode.ENFORCING, true)
            .evaluate(
                List.of(
                    view(
                        fp(1, 10L),
                        fp(2, 21L), // source already resolved schema v2 before writing
                        fp(2, 19L))));
    assertThat(verdict.allowed()).isTrue();
  }

  @Test
  void staleWriteWithCompatibleEvolutionIsAllowedAndAudited() {
    checker.next = SchemaCompatibility.COMPATIBLE;
    Verdict verdict =
        gate(GateMode.ENFORCING, true)
            .evaluate(
                List.of(
                    view(
                        fp(1, 10L),
                        fp(1, 21L), // still on v1, target only added an optional column
                        fp(2, 19L))));
    assertThat(verdict.allowed()).isTrue();
    assertThat(verdict.violations()).isEmpty();
  }

  @Test
  void staleReadThenWriteBackWithIncompatibleEvolutionIsBlocked() {
    checker.next = SchemaCompatibility.INCOMPATIBLE;
    checker.reasons = List.of("field id 3 (amount) type changed STRING -> DOUBLE");

    Verdict verdict =
        gate(GateMode.ENFORCING, true)
            .evaluate(
                List.of(
                    view(
                        fp(1, 10L),
                        fp(1, 21L), // branch B cached v1, then wrote data files back
                        fp(2, 19L)))); // target evolved to v2

    assertThat(verdict.allowed()).isFalse();
    assertThat(verdict.violations()).hasSize(1);
    assertThat(verdict.violations().get(0).key()).isEqualTo(KEY);
    assertThat(verdict.violations().get(0).reason())
        .contains("stale schema write")
        .contains("source schemaId=1")
        .contains("target schemaId=2")
        .contains("Rebase");
  }

  @Test
  void shadowModeNeverBlocksButRecordsViolations() {
    checker.next = SchemaCompatibility.INCOMPATIBLE;
    Verdict verdict =
        gate(GateMode.SHADOW, true)
            .evaluate(
                List.of(
                    view(
                        fp(1, 10L),
                        fp(1, 21L),
                        fp(2, 19L))));
    assertThat(verdict.allowed()).isTrue();
    assertThat(verdict.violations()).hasSize(1);
  }

  @Test
  void nonWatchedKeysAreIgnored() {
    checker.next = SchemaCompatibility.INCOMPATIBLE;
    Verdict verdict =
        gate(GateMode.ENFORCING, true)
            .evaluate(
                List.of(
                    ThreeWayView.builder()
                        .key("other.team.private_table")
                        .base(fp(1, 10L))
                        .source(fp(1, 21L))
                        .target(fp(2, 19L))
                        .build()));
    assertThat(verdict.allowed()).isTrue();
    assertThat(verdict.violations()).isEmpty();
  }

  private static final class RecordingChecker implements SchemaCompatibilityChecker {
    SchemaCompatibility next = SchemaCompatibility.UP_TO_DATE;
    List<String> reasons = List.of();

    @Override
    public SchemaCompatibility check(
        int writeBaseSchemaId, int targetSchemaId, String metadataLocation) {
      return next;
    }

    @Override
    public List<String> lastReasons() {
      return reasons;
    }
  }
}
