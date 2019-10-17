package tech.libeufin.sandbox

import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.BeforeClass
import org.junit.Test
import org.junit.Before

class DbTest {

    @Before
    fun setUp() {
        dbCreateTables()
    }

    /**
     * This function creates a EBICS subscriber _first_, and
     * subsequently tries to insert a mock bianry value into
     * the keys columns of the subscriber.
     */
    @Test
    fun storeBinary() {
        transaction {
            // join table
            val subscriber = createSubscriber()

            val key = EbicsPublicKey.new {
                pub = "BINARYVALUE".toByteArray()
                state = KeyStates.NEW
            }
            subscriber.authorizationKey = key
        }
    }

    @Test
    fun nestedQuery() {

        /***
         *  Some query like the following is needed:
         *
         *  val result = EbicsSubscriber.find {
         *    EbicsSubscribers.userId.userId eq "u1"
         *  }.first()
         */
    }
}