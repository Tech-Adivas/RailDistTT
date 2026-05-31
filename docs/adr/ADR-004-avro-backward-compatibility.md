# ADR-004: Avro Schema BACKWARD Compatibility Rule

## Status
Accepted

## Context
Producers and consumers are deployed independently. A schema change that breaks existing consumers causes runtime errors in production. We need a compatibility policy enforced in CI.

## Decision
All Avro schemas registered in the Schema Registry must be **BACKWARD compatible**:
- New fields must have a default value.
- Fields may not be removed (mark deprecated with a `doc` annotation instead; remove in a future major version after all consumers have migrated).
- Field types may not change in an incompatible way.

The Schema Registry subject naming strategy is `TopicRecordNameStrategy` (one subject per event type per topic). CI enforces compatibility via a schema compatibility check step before any deployment.

## Consequences
**Positive:**
- Consumers running old schema versions can safely consume events produced with new schema versions.
- Schema evolution is controlled and auditable.
- CI gate prevents accidental breaking changes.

**Negative:**
- Schema evolution is more constrained — breaking changes require a multi-step migration.
- Fields cannot be removed immediately; they accumulate until a deliberate major-version migration.

## Alternatives Considered
- **FULL compatibility**: Stricter (both forward and backward), harder to evolve.
- **NONE**: No compatibility guarantee — unsafe for independent deployments.
- **JSON Schema**: Less compact on the wire, no native schema registry evolution support.
