package org.example.gate;

import java.util.Optional;
import org.immutables.value.Value;

/** Fingerprints of one table pointer at merge base, source head and target head. */
@Value.Immutable
public interface ThreeWayView {

  String key();

  Optional<TableFingerprint> base();

  Optional<TableFingerprint> source();

  Optional<TableFingerprint> target();

  static ImmutableThreeWayView.Builder builder() {
    return ImmutableThreeWayView.builder();
  }
}
