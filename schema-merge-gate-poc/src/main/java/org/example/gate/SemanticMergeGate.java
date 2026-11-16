package org.example.gate;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure decision logic, independent from Nessie client wiring so it can be unit tested with plain
 * fingerprints. The rule set implements the decision matrix in
 * {@code design/schema-aware-merge-gate.md}, section 5.2.
 */
public class SemanticMergeGate {

  private final GateConfig config;
  private final SchemaCompatibilityChecker compatibilityChecker;

  public SemanticMergeGate(GateConfig config, SchemaCompatibilityChecker compatibilityChecker) {
    this.config = config;
    this.compatibilityChecker = compatibilityChecker;
  }

  public Verdict evaluate(List<ThreeWayView> views) {
    List<Verdict.Violation> violations = new ArrayList<>();

    for (ThreeWayView view : views) {
      if (!isWatched(view.key())) {
        continue;
      }

      if (view.target().isEmpty() || view.source().isEmpty() || view.base().isEmpty()) {
        // CREATE on one side or table removal: Nessie's own key-level conflict detection
        // (NORMAL behavior / expected content) handles these.
        continue;
      }

      TableFingerprint base = view.base().get();
      TableFingerprint source = view.source().get();
      TableFingerprint target = view.target().get();

      if (target.schemaId() == base.schemaId()) {
        // No schema drift on target: no semantic conflict is possible.
        continue;
      }

      if (!sourceWroteData(base, source)) {
        // Pure read (or only non-data changes): nothing stale can land on target.
        continue;
      }

      if (source.schemaId() == target.schemaId()) {
        // Branch rebased / re-resolved the schema before writing.
        continue;
      }

      SchemaCompatibility compatibility =
          compatibilityChecker.check(
              source.schemaId(), target.schemaId(), target.metadataLocation());

      if (compatibility == SchemaCompatibility.UP_TO_DATE) {
        continue;
      }
      if (compatibility == SchemaCompatibility.COMPATIBLE
          && config.allowCompatibleStaleWrites()) {
        // Audited fast path, e.g. target only added an optional column.
        continue;
      }

      String reason =
          "stale schema write: source schemaId=" + source.schemaId()
              + ", target schemaId=" + target.schemaId()
              + ", base schemaId=" + base.schemaId()
              + "; " + String.join("; ", compatibilityChecker.lastReasons())
              + ". Rebase the branch onto target and rewrite the affected snapshots.";
      violations.add(Verdict.Violation.of(view.key(), reason));
    }

    return Verdict.builder()
        .allowed(violations.isEmpty() || config.mode() == GateMode.SHADOW)
        .violations(violations)
        .build();
  }

  private boolean isWatched(String key) {
    return config.watchedKeys().isEmpty() || config.watchedKeys().contains(key);
  }

  private static boolean sourceWroteData(TableFingerprint base, TableFingerprint source) {
    // A pointer bump alone can be a schema-only change; a changed snapshot id means data files
    // were committed, i.e. the "read then write back" pattern.
    return source.snapshotId() != base.snapshotId();
  }
}
