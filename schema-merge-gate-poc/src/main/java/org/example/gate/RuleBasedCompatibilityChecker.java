package org.example.gate;

import java.util.ArrayList;
import java.util.List;
import org.apache.iceberg.Schema;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.types.Types.NestedField;

/**
 * Default rule set.
 *
 * <p>Rules, evaluated on field ids (never on column position/name order):
 *
 * <ul>
 *   <li>ids only present in the target (add column / add optional nested field): compatible.
 *   <li>ids present in both with the same type, or a safe widening: compatible.
 *   <li>ids dropped on the target, ids reused with a different type/name, narrowing, or a type
 *       family change: incompatible. These are exactly the cases that silently scramble rows when
 *       an engine projects by position.
 * </ul>
 */
public class RuleBasedCompatibilityChecker implements SchemaCompatibilityChecker {

  private final SchemaLoader schemaLoader;
  private final List<String> reasons = new ArrayList<>();

  public RuleBasedCompatibilityChecker(SchemaLoader schemaLoader) {
    this.schemaLoader = schemaLoader;
  }

  @Override
  public SchemaCompatibility check(
      int writeBaseSchemaId, int targetSchemaId, String metadataLocation) {
    reasons.clear();
    if (writeBaseSchemaId == targetSchemaId) {
      return SchemaCompatibility.UP_TO_DATE;
    }

    Schema writeBase = schemaLoader.loadSchema(metadataLocation, writeBaseSchemaId);
    Schema target = schemaLoader.loadSchema(metadataLocation, targetSchemaId);

    boolean incompatible = false;
    for (NestedField writtenField : writeBase.columns()) {
      NestedField targetField = target.findField(writtenField.fieldId());
      if (targetField == null) {
        incompatible = true;
        reasons.add(
            "field id " + writtenField.fieldId() + " (" + writtenField.name()
                + ") was removed on target");
        continue;
      }
      if (!isTypeSafe(writtenField.type(), targetField.type())) {
        incompatible = true;
        reasons.add(
            "field id " + writtenField.fieldId() + " (" + writtenField.name() + ") type changed "
                + writtenField.type() + " -> " + targetField.type());
      }
      if (!targetField.name().equals(writtenField.name())) {
        // id reuse with a different name is the classic silent column-swap source.
        incompatible = true;
        reasons.add(
            "field id " + writtenField.fieldId() + " renamed/reused: " + writtenField.name()
                + " -> " + targetField.name());
      }
    }
    return incompatible ? SchemaCompatibility.INCOMPATIBLE : SchemaCompatibility.COMPATIBLE;
  }

  @Override
  public List<String> lastReasons() {
    return List.copyOf(reasons);
  }

  private static boolean isTypeSafe(Type written, Type target) {
    if (written.equals(target)) {
      return true;
    }
    // Primitive widenings allowed by Iceberg/Parquet readers.
    switch (written.typeId()) {
      case INTEGER:
        return target.typeId() == Type.TypeID.LONG;
      case FLOAT:
        return target.typeId() == Type.TypeID.DOUBLE;
      case DECIMAL:
        return target.typeId() == Type.TypeID.DECIMAL && decimalWidens(written, target);
      default:
        return false;
    }
  }

  private static boolean decimalWidens(Type written, Type target) {
    if (!(written instanceof Types.DecimalType)
        || !(target instanceof Types.DecimalType)) {
      return false;
    }
    Types.DecimalType from = (Types.DecimalType) written;
    Types.DecimalType to = (Types.DecimalType) target;
    return to.scale() == from.scale() && to.precision() >= from.precision();
  }
}
