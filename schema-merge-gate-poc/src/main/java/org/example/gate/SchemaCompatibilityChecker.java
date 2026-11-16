package org.example.gate;

import java.util.List;

/**
 * Field-level compatibility decision between the schema a branch wrote against and the schema
 * currently present on the merge target.
 *
 * <p>Implementations work on Iceberg table metadata only (field id, type), never on data files.
 */
public interface SchemaCompatibilityChecker {

  /**
   * @param writeBaseSchemaId schema id the source branch read before writing
   * @param targetSchemaId current schema id on the target branch
   * @param metadataLocation pointer allowing the implementation to load both schemas
   */
  SchemaCompatibility check(
      int writeBaseSchemaId, int targetSchemaId, String metadataLocation);

  /** Human-readable reasons for the last {@link SchemaCompatibility#INCOMPATIBLE} result. */
  List<String> lastReasons();
}
