package org.example.gate.nessie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.example.gate.GateConfig;
import org.example.gate.SemanticMergeGate;
import org.example.gate.TableFingerprint;
import org.example.gate.ThreeWayView;
import org.example.gate.Verdict;
import org.projectnessie.client.api.NessieApiV2;
import org.projectnessie.error.NessieConflictException;
import org.projectnessie.error.NessieNotFoundException;
import org.projectnessie.model.Content;
import org.projectnessie.model.ContentKey;
import org.projectnessie.model.DiffResponse;
import org.projectnessie.model.IcebergTable;
import org.projectnessie.model.MergeBehavior;
import org.projectnessie.model.MergeResponse;

/**
 * Nessie-backed entry point of the merge gate.
 *
 * <p>All interaction uses the public Nessie client API; the Nessie server itself is unchanged.
 *
 * <ol>
 *   <li>dry-run merge to obtain the merge-base hash and let Nessie perform key-level checks,
 *   <li>collect base/source/target fingerprints for watched keys via the diff API,
 *   <li>run the semantic gate,
 *   <li>perform the real merge with {@code NORMAL} behavior when the verdict allows it.
 * </ol>
 */
public class NessieMergeGate {

  private final NessieApiV2 api;
  private final GateConfig config;
  private final SemanticMergeGate semanticGate;

  public NessieMergeGate(NessieApiV2 api, GateConfig config, SemanticMergeGate semanticGate) {
    this.api = api;
    this.config = config;
    this.semanticGate = semanticGate;
  }

  public MergeResponse merge(String sourceRef, String targetBranch)
      throws NessieNotFoundException, NessieConflictException {

    MergeResponse dryRun =
        api.mergeRefIntoBranch()
            .fromRefName(sourceRef)
            .branchName(targetBranch)
            .dryRun(true)
            .returnConflictAsResult(true)
            .defaultMergeMode(MergeBehavior.NORMAL)
            .merge();

    if (!dryRun.wasSuccessful()) {
      // Ordinary key-level conflict (same key changed on both sides). Nessie already rejected it;
      // surface the standard response so the caller can rebase.
      return dryRun;
    }

    String mergeBaseHash = dryRun.getCommonAncestor();
    List<ThreeWayView> views = collectViews(sourceRef, targetBranch, mergeBaseHash);
    Verdict verdict = semanticGate.evaluate(views);

    if (!verdict.allowed()) {
      throw new SchemaConflictException(verdict);
    }

    return api.mergeRefIntoBranch()
        .fromRefName(sourceRef)
        .branchName(targetBranch)
        .defaultMergeMode(MergeBehavior.NORMAL)
        .merge();
  }

  private List<ThreeWayView> collectViews(
      String sourceRef, String targetBranch, String mergeBaseHash)
      throws NessieNotFoundException {

    Map<ContentKey, Content> baseToSource = diffContents(mergeBaseHash, sourceRef);
    Map<ContentKey, Content> baseToTarget = diffContents(mergeBaseHash, targetBranch);

    Set<ContentKey> candidateKeys = new HashSet<>(baseToSource.keySet());
    candidateKeys.retainAll(baseToTarget.keySet());

    List<ContentKey> watched = candidateKeys.stream()
        .filter(this::isWatchedKey)
        .toList();
    if (watched.isEmpty()) {
      return List.of();
    }

    Map<ContentKey, Content> baseContents = contentsAt(mergeBaseHash, watched);
    Map<ContentKey, Content> sourceContents = contentsAt(sourceRef, watched);
    Map<ContentKey, Content> targetContents = contentsAt(targetBranch, watched);

    List<ThreeWayView> views = new ArrayList<>(watched.size());
    for (ContentKey key : watched) {
      Optional<TableFingerprint> base = fingerprint(key, baseContents.get(key));
      Optional<TableFingerprint> source = fingerprint(key, sourceContents.get(key));
      Optional<TableFingerprint> target = fingerprint(key, targetContents.get(key));
      if (base.isEmpty() || source.isEmpty() || target.isEmpty()) {
        continue;
      }
      views.add(
          ThreeWayView.builder()
              .key(key.toString())
              .base(base)
              .source(source)
              .target(target)
              .build());
    }
    return views;
  }

  private Map<ContentKey, Content> diffContents(String fromRef, String toRef)
      throws NessieNotFoundException {
    Map<ContentKey, Content> result = new HashMap<>();
    String pageToken = null;
    do {
      DiffResponse response =
          api.getDiff()
              .fromRefName(fromRef)
              .toRefName(toRef)
              .pageToken(pageToken)
              .get();
      for (DiffResponse.DiffEntry entry : response.getDiffs()) {
        result.put(entry.getKey(), entry.getTo());
      }
      pageToken = response.isHasMore() ? response.getToken() : null;
    } while (pageToken != null);
    return result;
  }

  private Map<ContentKey, Content> contentsAt(String ref, List<ContentKey> keys)
      throws NessieNotFoundException {
    return api.getContent().refName(ref).keys(keys).get();
  }

  private boolean isWatchedKey(ContentKey key) {
    return config.watchedKeys().isEmpty()
        || config.watchedKeys().contains(key.toString());
  }

  private static Optional<TableFingerprint> fingerprint(ContentKey key, Content content) {
    if (!(content instanceof IcebergTable table)) {
      return Optional.empty();
    }
    return Optional.of(
        TableFingerprint.builder()
            .key(key.toString())
            .schemaId(table.getSchemaId())
            .snapshotId(table.getSnapshotId())
            .metadataLocation(table.getMetadataLocation())
            .build());
  }
}
