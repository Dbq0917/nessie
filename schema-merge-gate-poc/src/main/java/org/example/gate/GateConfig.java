package org.example.gate;

import java.util.List;
import org.immutables.value.Value;

/** Static configuration of the schema-aware merge gate. */
@Value.Immutable
public interface GateConfig {

  /** Content keys of shared tables that participate in semantic checks. Empty means all keys. */
  List<String> watchedKeys();

  /** When true, compatible-but-stale writes (e.g. add-column) are allowed and only audited. */
  @Value.Default
  default boolean allowCompatibleStaleWrites() {
    return true;
  }

  @Value.Default
  default GateMode mode() {
    return GateMode.SHADOW;
  }

  /** CommitMeta property marking an approved schema change, value carries the ticket id. */
  @Value.Default
  default String approvalProperty() {
    return "schema-change.approved";
  }

  /** CommitMeta property a writer uses to record the schema id its data was written against. */
  @Value.Default
  default String writeBaseSchemaIdProperty() {
    return "write-base-schema-id";
  }

  static ImmutableGateConfig.Builder builder() {
    return ImmutableGateConfig.builder();
  }
}
