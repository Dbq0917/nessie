package org.example.gate;

/** Gate operating mode. SHADOW only records decisions; ENFORCING blocks incompatible merges. */
public enum GateMode {
  SHADOW,
  ENFORCING
}
