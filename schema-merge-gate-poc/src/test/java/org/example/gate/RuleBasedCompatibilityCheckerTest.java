package org.example.gate;

import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.apache.iceberg.types.Types.NestedField.required;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.Schema;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

class RuleBasedCompatibilityCheckerTest {

  private static final String LOCATION = "s3://bucket/t/metadata.json";

  private SchemaLoader loaderWith(Map<Integer, Schema> schemas) {
    return (metadataLocation, schemaId) -> schemas.get(schemaId);
  }

  @Test
  void sameSchemaIdIsUpToDateWithoutLoading() {
    RuleBasedCompatibilityChecker checker =
        new RuleBasedCompatibilityChecker((location, id) -> {
          throw new AssertionError("must not load metadata when schema ids match");
        });
    assertThat(checker.check(3, 3, LOCATION)).isEqualTo(SchemaCompatibility.UP_TO_DATE);
  }

  @Test
  void addedOptionalColumnIsCompatible() {
    Schema v1 =
        new Schema(
            required(1, "id", Types.LongType.get()),
            optional(2, "amount", Types.DoubleType.get()));
    Schema v2 =
        new Schema(
            required(1, "id", Types.LongType.get()),
            optional(2, "amount", Types.DoubleType.get()),
            optional(3, "zone", Types.StringType.get()));

    Map<Integer, Schema> schemas = new HashMap<>();
    schemas.put(1, v1);
    schemas.put(2, v2);

    RuleBasedCompatibilityChecker checker =
        new RuleBasedCompatibilityChecker(loaderWith(schemas));
    assertThat(checker.check(1, 2, LOCATION)).isEqualTo(SchemaCompatibility.COMPATIBLE);
  }

  @Test
  void intToLongWideningIsCompatible() {
    Schema v1 = new Schema(required(1, "id", Types.IntegerType.get()));
    Schema v2 = new Schema(required(1, "id", Types.LongType.get()));

    Map<Integer, Schema> schemas = new HashMap<>();
    schemas.put(1, v1);
    schemas.put(2, v2);

    RuleBasedCompatibilityChecker checker =
        new RuleBasedCompatibilityChecker(loaderWith(schemas));
    assertThat(checker.check(1, 2, LOCATION)).isEqualTo(SchemaCompatibility.COMPATIBLE);
  }

  @Test
  void droppedColumnIsIncompatible() {
    Schema v1 =
        new Schema(
            required(1, "id", Types.LongType.get()),
            optional(2, "amount", Types.DoubleType.get()));
    Schema v2 = new Schema(required(1, "id", Types.LongType.get()));

    Map<Integer, Schema> schemas = new HashMap<>();
    schemas.put(1, v1);
    schemas.put(2, v2);

    RuleBasedCompatibilityChecker checker =
        new RuleBasedCompatibilityChecker(loaderWith(schemas));
    assertThat(checker.check(1, 2, LOCATION)).isEqualTo(SchemaCompatibility.INCOMPATIBLE);
    assertThat(checker.lastReasons()).anyMatch(reason -> reason.contains("field id 2"));
  }

  @Test
  void fieldIdReusedWithNewNameIsIncompatible() {
    // Classic silent column swap: id 2 used to be "amount", now carries "zone".
    Schema v1 =
        new Schema(
            required(1, "id", Types.LongType.get()),
            optional(2, "amount", Types.DoubleType.get()));
    Schema v2 =
        new Schema(
            required(1, "id", Types.LongType.get()),
            optional(2, "zone", Types.StringType.get()));

    Map<Integer, Schema> schemas = new HashMap<>();
    schemas.put(1, v1);
    schemas.put(2, v2);

    RuleBasedCompatibilityChecker checker =
        new RuleBasedCompatibilityChecker(loaderWith(schemas));
    assertThat(checker.check(1, 2, LOCATION)).isEqualTo(SchemaCompatibility.INCOMPATIBLE);
    assertThat(String.join("|", checker.lastReasons())).contains("renamed/reused");
  }

  @Test
  void narrowingLongToIntIsIncompatible() {
    Schema v1 = new Schema(required(1, "id", Types.LongType.get()));
    Schema v2 = new Schema(required(1, "id", Types.IntegerType.get()));

    Map<Integer, Schema> schemas = new HashMap<>();
    schemas.put(1, v1);
    schemas.put(2, v2);

    RuleBasedCompatibilityChecker checker =
        new RuleBasedCompatibilityChecker(loaderWith(schemas));
    assertThat(checker.check(1, 2, LOCATION)).isEqualTo(SchemaCompatibility.INCOMPATIBLE);
  }
}
