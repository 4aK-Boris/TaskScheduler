package cs.trade.scheduler.core.backend.ktor.validation

/**
 * Marker for module-scoped validation classes. Concrete validators live in
 * `<module>.api.validations/` and declare individual `val validateXxx = ...` rules.
 *
 * MVP note: Konform integration deferred — for now this is just a marker so that DSL signatures
 * can refer to `BaseValidation` without import noise.
 */
public interface BaseValidation
