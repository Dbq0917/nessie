package org.example.gate;

/**
 * Compatibility of a write produced against an older schema when read through a newer schema.
 */
public enum SchemaCompatibility {

  /** Source already writes against the target schema. */
  UP_TO_DATE,

  /** Schema drifted, but every written field id still resolves safely under the new schema. */
  COMPATIBLE,

  /** Schema drifted and reading the old data through the new schema would misalign or fail. */
  INCOMPATIBLE
}
