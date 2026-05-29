package cs.trade.scheduler.core.backend.schema

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [SchemaHasher] sensitivity (DESIGN.md 22.9). All payloads pin the same `@SerialName("Email")`
 * so the hash differences below come purely from STRUCTURE (fields / types / optionality /
 * order), not from the serial name — that's what schema-drift detection keys on.
 */
class SchemaHasherTest {

    @Serializable @SerialName("Email") private data class EmailBase(val userId: Long, val template: String)
    @Serializable @SerialName("Email") private data class EmailReordered(val template: String, val userId: Long)
    @Serializable @SerialName("Email") private data class EmailAddedField(val userId: Long, val template: String, val from: String)
    @Serializable @SerialName("Email") private data class EmailRenamedField(val userId: Long, val tmpl: String)
    @Serializable @SerialName("Email") private data class EmailRetypedField(val userId: String, val template: String)
    @Serializable @SerialName("Email") private data class EmailOptionalField(val userId: Long, val template: String = "default")
    @Serializable @SerialName("Email") private data class EmailNullableField(val userId: Long, val template: String?)

    private fun hashOf(serializer: KSerializer<*>): String = SchemaHasher.hash(serializer.descriptor)

    @Test
    fun `same schema hashes identically`() {
        assertEquals(hashOf(EmailBase.serializer()), hashOf(EmailBase.serializer()))
    }

    @Test
    fun `field reorder does NOT change the hash`() {
        assertEquals(
            hashOf(EmailBase.serializer()),
            hashOf(EmailReordered.serializer()),
            "reordering fields is wire-compatible (kotlinx is name-based) — must not raise a false alert",
        )
    }

    @Test
    fun `adding a field changes the hash`() {
        assertNotEquals(hashOf(EmailBase.serializer()), hashOf(EmailAddedField.serializer()))
    }

    @Test
    fun `renaming a field changes the hash`() {
        assertNotEquals(hashOf(EmailBase.serializer()), hashOf(EmailRenamedField.serializer()))
    }

    @Test
    fun `changing a field type changes the hash`() {
        assertNotEquals(hashOf(EmailBase.serializer()), hashOf(EmailRetypedField.serializer()))
    }

    @Test
    fun `making a field optional changes the hash`() {
        assertNotEquals(hashOf(EmailBase.serializer()), hashOf(EmailOptionalField.serializer()))
    }

    @Test
    fun `making a field nullable changes the hash`() {
        assertNotEquals(hashOf(EmailBase.serializer()), hashOf(EmailNullableField.serializer()))
    }

    @Test
    fun `hash is 64-char lowercase hex`() {
        val h = hashOf(EmailBase.serializer())
        assertEquals(64, h.length)
        assertTrue(h.all { it in "0123456789abcdef" }, h)
    }
}
