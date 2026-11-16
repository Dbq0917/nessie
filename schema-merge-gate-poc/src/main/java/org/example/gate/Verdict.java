package org.example.gate;

import java.util.List;
import org.immutables.value.Value;

/** Decision for one merge request, covering every watched key. */
@Value.Immutable
public interface Verdict {

  boolean allowed();

  List<Violation> violations();

  static ImmutableVerdict.Builder builder() {
    return ImmutableVerdict.builder();
  }

  @Value.Immutable
  interface Violation {
    @Value.Parameter(order = 1)
    String key();

    @Value.Parameter(order = 2)
    String reason();

    static Violation of(String key, String reason) {
      return ImmutableViolation.of(key, reason);
    }
  }
}
