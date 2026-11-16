package org.example.gate.nessie;

import org.example.gate.Verdict;

/**
 * Thrown when a merge would land data written against a stale, incompatible schema. Mapped to an
 * HTTP 409 with a structured conflict body by the serving layer (CI service / REST gateway).
 */
public class SchemaConflictException extends RuntimeException {

  private final transient Verdict verdict;

  public SchemaConflictException(Verdict verdict) {
    super(format(verdict));
    this.verdict = verdict;
  }

  public Verdict verdict() {
    return verdict;
  }

  private static String format(Verdict verdict) {
    StringBuilder builder =
        new StringBuilder("Schema semantic conflict, merge blocked for keys:");
    verdict.violations()
        .forEach(violation ->
            builder.append("\n - ").append(violation.key()).append(": ").append(violation.reason()));
    return builder.toString();
  }
}
