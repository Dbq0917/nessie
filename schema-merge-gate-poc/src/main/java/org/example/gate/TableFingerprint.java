package org.example.gate;

import org.immutables.value.Value;

/**
 * Schema-relevant fingerprint of a table pointer at one point in history.
 *
 * <p>For Iceberg these fields map directly to {@code IcebergTable} content stored in Nessie; no
 * data files need to be opened.
 */
@Value.Immutable
public interface TableFingerprint {

  /** Nessie content key, e.g. "warehouse.db.events". */
  String key();

  /** Iceberg current schema id referenced by the table pointer. */
  int schemaId();

  /** Iceberg current snapshot id; changes on every data write. */
  long snapshotId();

  /** Location of the table metadata JSON, used for deep compatibility checks. */
  String metadataLocation();

  static ImmutableTableFingerprint.Builder builder() {
    return ImmutableTableFingerprint.builder();
  }
}
