package tech.libeufin.sandbox

import junit.framework.TestCase.assertFalse
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import org.junit.Before
import tech.libeufin.sandbox.db.*

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
                modulus = "BINARYVALUE".toByteArray()
                exponent = "BINARYVALUE".toByteArray()
                state = KeyStates.NEW
            }
            subscriber.authenticationKey = key
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

         transaction {
            createSubscriber()

            val tmp = EbicsUser.find { EbicsUsers.userId eq "u1" }.firstOrNull()
            if (tmp == null) {
                logger.error("No such user found in database.")
                return@transaction
            }
            println("Found user with id: ${tmp.id.value}")

            val found = EbicsSubscriber.find {
                EbicsSubscribers.userId eq EntityID(tmp.id.value, EbicsUsers)
            }

            assertFalse(found.empty())
        }
    }
}