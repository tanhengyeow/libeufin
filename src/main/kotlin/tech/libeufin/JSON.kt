package tech.libeufin

/**
 * Error message.
 */
data class SandboxError(
    val message: String
)


/**
 * Request for POST /admin/customers
 */
data class CustomerRequest(
    val name: String
)

data class CustomerResponse(
    val id: Int
)

/**
 * Response for GET /admin/customers/:id
 */
data class CustomerInfo(
    val name: String,
    val customerEbicsInfo: CustomerEbicsInfo
)

data class CustomerEbicsInfo(
    val userId: Int
)

/**
 * Request for INI / HIA letter(s).
 */
data class IniHiaLetters(

    val userId: String,
    val customerId: String,
    val name: String,
    val date: String,
    val time: String,
    val recipient: String,
    val exponent: String,
    val modulus: String,
    val hash: String,
    val INI: IniVersion,
    val HIA: HiaVersion
)

/**
 * INI specific version numbers
 */
data class IniVersion(
    // Signature key
    val es_version: String
)

/**
 * INI specific version numbers
 */
data class HiaVersion(

    // Identification and authentication key
    val ia_version: String,
    // Encryption key
    val enc_version: String
)