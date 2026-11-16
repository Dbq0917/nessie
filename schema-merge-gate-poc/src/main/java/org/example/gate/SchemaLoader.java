package org.example.gate;

import org.apache.iceberg.Schema;

/** Loads a historical schema by id from a table metadata location (S3/ADLS/GCS/local). */
@FunctionalInterface
public interface SchemaLoader {

  Schema loadSchema(String metadataLocation, int schemaId);
}
