package tech.libeufin.sandbox

import org.junit.Test
import junit.framework.TestCase.assertTrue
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import tech.libeufin.sandbox.db.EbicsBankPrivateKey
import tech.libeufin.sandbox.db.dbCreateTables

class GeneratePrivateKeyTest {

    @Before
    fun setUp() {
        dbCreateTables()
    }

    @Test
    fun loadOrGeneratePrivateKey() {

        getOrMakePrivateKey()

        assertTrue(
            transaction {
                EbicsBankPrivateKey.findById(1)
            } != null
        )
    }
}