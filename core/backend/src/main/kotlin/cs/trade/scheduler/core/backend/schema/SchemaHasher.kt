package cs.trade.scheduler.core.backend.schema

import kotlinx.serialization.descriptors.SerialDescriptor
import java.security.MessageDigest

/**
 * Stable structural fingerprint of a payload type's serialization schema (DESIGN.md 22.9).
 *
 * The hash is derived from the kotlinx-serialization [SerialDescriptor] — the type's serial
 * name, kind, nullability, and, recursively, every element's name / type / optionality. It
 * is **deterministic** (same schema → same hash across JVMs and runs) and sensitive to
 * exactly the changes that break payload compatibility:
 *
 *  - add / remove a field        → different hash
 *  - rename a field              → different hash
 *  - change a field's type       → different hash (the element descriptor differs)
 *  - make a field optional        → different hash (a default appears)
 *  - make a field nullable        → different hash
 *
 * It is deliberately INsensitive to field **declaration order** — kotlinx serialization is
 * name-based for classes, so reordering fields is wire-compatible and must not raise a false
 * drift alert. Elements are therefore sorted by name before hashing.
 *
 * Self-referential schemas (a tree node whose field is a list of itself) are handled by a
 * recursion-stack guard that emits a back-reference instead of recursing forever.
 */
public object SchemaHasher {

    public fun hash(descriptor: SerialDescriptor): String {
        val canonical = StringBuilder()
        appendRepr(descriptor, canonical, HashSet())
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().encodeToByteArray())
        // Lowercase hex without String.format (avoids the signed-byte sign-extension trap).
        return digest.joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    }

    private fun appendRepr(d: SerialDescriptor, sb: StringBuilder, stack: MutableSet<String>) {
        // Recursion guard: a type already on the current path is a cycle (e.g. a self-
        // referential tree). Emit a back-reference rather than recursing forever.
        if (!stack.add(d.serialName)) {
            sb.append('@').append(d.serialName)
            return
        }
        sb.append(d.serialName).append('/').append(d.kind.toString())
        if (d.isNullable) sb.append('?')
        sb.append('(')
        // Sort by element name so a field reorder (compat-safe) yields the same hash.
        for (i in (0 until d.elementsCount).sortedBy { d.getElementName(it) }) {
            sb.append(d.getElementName(i))
            if (d.isElementOptional(i)) sb.append('?')   // element has a default value
            sb.append('=')
            appendRepr(d.getElementDescriptor(i), sb, stack)
            sb.append(',')
        }
        sb.append(')')
        stack.remove(d.serialName)
    }
}
