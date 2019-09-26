package tech.libeufin

/**
 * Error message.
 */
data class SandboxError (
    val message: String
)


/**
 * Request for POST /admin/customers
 */
data class CustomerRequest (
    val name: String
)

data class CustomerResponse (
    val id: Int
)

/**
 * Response for GET /admin/customers/:id
 */
data class CustomerInfo (
    val customerEbicsInfo: CustomerEbicsInfo
)

data class CustomerEbicsInfo (
    val userId: Int
)