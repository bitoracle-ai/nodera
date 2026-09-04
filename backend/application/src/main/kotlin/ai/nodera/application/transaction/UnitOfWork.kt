package ai.nodera.application.transaction

/**
 * The transaction boundary, which is the **use case** (`skills/backend-kotlin.md` § Transactions).
 *
 * A call made inside an open transaction joins it rather than starting a second one, so nested use
 * cases commit as one unit and their audit rows cannot outlive the mutations they describe.
 */
public interface UnitOfWork {
    public suspend fun <T> inTransaction(block: suspend () -> T): T
}
