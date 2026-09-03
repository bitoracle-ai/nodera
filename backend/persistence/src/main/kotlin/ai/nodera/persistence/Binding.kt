package ai.nodera.persistence

import java.sql.PreparedStatement
import java.sql.Types
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/** Binds in declaration order, so a statement's placeholders never have to be counted by hand. */
internal class Binding(
    private val statement: PreparedStatement,
) {
    private var index = 0

    fun uuid(value: Uuid?) {
        index += 1
        if (value == null) {
            statement.setNull(index, Types.OTHER)
        } else {
            statement.setObject(index, value.toJavaUuid())
        }
    }

    fun text(value: String?) {
        index += 1
        statement.setString(index, value)
    }

    fun int(value: Int) {
        index += 1
        statement.setInt(index, value)
    }

    /** A `text[]` parameter. Cast to the column's own type in the statement, never here. */
    fun textArray(values: List<String>) {
        index += 1
        statement.setArray(index, statement.connection.createArrayOf("text", values.toTypedArray()))
    }
}

/** Every enum this schema stores is the lowercased Kotlin name; tests insert every value to prove it. */
internal val Enum<*>.label: String get() = name.lowercase()
