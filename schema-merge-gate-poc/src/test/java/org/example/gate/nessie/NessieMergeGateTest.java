package org.example.gate.nessie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.example.gate.GateConfig;
import org.example.gate.GateMode;
import org.example.gate.SchemaCompatibility;
import org.example.gate.SchemaCompatibilityChecker;
import org.example.gate.SemanticMergeGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.projectnessie.client.api.GetContentBuilder;
import org.projectnessie.client.api.GetDiffBuilder;
import org.projectnessie.client.api.MergeReferenceBuilder;
import org.projectnessie.client.api.NessieApiV2;
import org.projectnessie.model.ContentKey;
import org.projectnessie.model.DiffResponse;
import org.projectnessie.model.IcebergTable;
import org.projectnessie.model.ImmutableDiffResponse;
import org.projectnessie.model.ImmutableMergeResponse;
import org.projectnessie.model.MergeResponse;

class NessieMergeGateTest {

  private static final ContentKey KEY = ContentKey.of("warehouse", "db", "shared_events");

  private NessieApiV2 api;
  private MergeReferenceBuilder dryRunMerge;
  private MergeReferenceBuilder realMerge;
  private GetDiffBuilder diffBuilder;
  private GetContentBuilder contentBuilder;
  private SchemaCompatibilityChecker checker;
  private NessieMergeGate gate;

  private GateConfig gateConfig;

  @BeforeEach
  void setUp() {
    api = mock(NessieApiV2.class);
    dryRunMerge = mock(MergeReferenceBuilder.class);
    realMerge = mock(MergeReferenceBuilder.class);
    diffBuilder = mock(GetDiffBuilder.class);
    contentBuilder = mock(GetContentBuilder.class);
    when(api.mergeRefIntoBranch()).thenReturn(dryRunMerge, realMerge);
    when(dryRunMerge.fromRefName(any())).thenReturn(dryRunMerge);
    when(dryRunMerge.branchName(any())).thenReturn(dryRunMerge);
    when(dryRunMerge.dryRun(any(Boolean.class))).thenReturn(dryRunMerge);
    when(dryRunMerge.returnConflictAsResult(any(Boolean.class))).thenReturn(dryRunMerge);
    when(dryRunMerge.defaultMergeMode(any())).thenReturn(dryRunMerge);
    when(realMerge.fromRefName(any())).thenReturn(realMerge);
    when(realMerge.branchName(any())).thenReturn(realMerge);
    when(realMerge.defaultMergeMode(any())).thenReturn(realMerge);
    when(api.getDiff()).thenReturn(diffBuilder);
    when(diffBuilder.fromRefName(any())).thenReturn(diffBuilder);
    when(diffBuilder.toRefName(any())).thenReturn(diffBuilder);
    when(diffBuilder.pageToken(any())).thenReturn(diffBuilder);
    when(api.getContent()).thenReturn(contentBuilder);
    when(contentBuilder.refName(any())).thenReturn(contentBuilder);
    when(contentBuilder.keys(any())).thenReturn(contentBuilder);

    gateConfig =
        GateConfig.builder()
            .addWatchedKeys(KEY.toString())
            .mode(GateMode.ENFORCING)
            .build();
    checker = mock(SchemaCompatibilityChecker.class);
    when(checker.lastReasons()).thenReturn(List.of("field id 2 type changed STRING -> DOUBLE"));
    gate =
        new NessieMergeGate(api, gateConfig, new SemanticMergeGate(gateConfig, checker));
  }

  @Test
  void blocksStaleReadThenWriteBackAndSkipsRealMerge() throws Exception {
    MergeResponse dryRunResponse = mergeResponse(true, "base123");
    when(dryRunMerge.merge()).thenReturn(dryRunResponse);

    when(diffBuilder.get())
        .thenReturn(diffWith(KEY, table(1, 21L)))
        .thenReturn(diffWith(KEY, table(2, 19L)));
    when(contentBuilder.get())
        .thenReturn(Map.of(KEY, table(1, 10L))) // base
        .thenReturn(Map.of(KEY, table(1, 21L))) // source: stale v1 write-back
        .thenReturn(Map.of(KEY, table(2, 19L))); // target: evolved to v2
    when(checker.check(eq(1), eq(2), any())).thenReturn(SchemaCompatibility.INCOMPATIBLE);

    assertThatThrownBy(() -> gate.merge("feature-b", "main"))
        .isInstanceOf(SchemaConflictException.class)
        .hasMessageContaining("Schema semantic conflict")
        .hasMessageContaining(KEY.toString());

    verify(realMerge, never()).merge();
  }

  @Test
  void allowsMergeWhenTargetSchemaDidNotDrift() throws Exception {
    MergeResponse dryRunResponse = mergeResponse(true, "base123");
    when(dryRunMerge.merge()).thenReturn(dryRunResponse);

    when(diffBuilder.get())
        .thenReturn(diffWith(KEY, table(1, 21L)))
        .thenReturn(diffWith(KEY, table(1, 10L)));
    when(contentBuilder.get())
        .thenReturn(Map.of(KEY, table(1, 10L)))
        .thenReturn(Map.of(KEY, table(1, 21L)))
        .thenReturn(Map.of(KEY, table(1, 10L)));

    MergeResponse applied = mergeResponse(true, "base123");
    when(realMerge.merge()).thenReturn(applied);

    assertThat(gate.merge("feature-b", "main")).isSameAs(applied);
    verify(realMerge).merge();
    verify(checker, never()).check(any(Integer.class), any(Integer.class), any());
  }

  @Test
  void returnsDryRunFailureWithoutSemanticEvaluation() throws Exception {
    MergeResponse keyConflict = mergeResponse(false, null);
    when(dryRunMerge.merge()).thenReturn(keyConflict);

    assertThat(gate.merge("feature-b", "main")).isSameAs(keyConflict);
    verify(diffBuilder, never()).get();
    verify(realMerge, never()).merge();
  }

  private static DiffResponse diffWith(ContentKey key, IcebergTable to) {
    return ImmutableDiffResponse.builder()
        .addDiffs(DiffResponse.DiffEntry.diffEntry(key, null, to))
        .build();
  }

  private static MergeResponse mergeResponse(boolean successful, String commonAncestor) {
    return ImmutableMergeResponse.builder()
        .wasSuccessful(successful)
        .targetBranch("main")
        .effectiveTargetHash("head456")
        .commonAncestor(commonAncestor)
        .build();
  }

  private static IcebergTable table(int schemaId, long snapshotId) {
    return IcebergTable.builder()
        .metadataLocation("s3://bucket/metadata/v" + schemaId + ".metadata.json")
        .snapshotId(snapshotId)
        .schemaId(schemaId)
        .specId(0)
        .sortOrderId(0)
        .build();
  }
}
