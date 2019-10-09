package tech.libeufin.sandbox

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
    val ebicsInfo: CustomerEbicsInfo
)

data class CustomerEbicsInfo(
    val userId: String
)

/**
 * Wrapper type around initialization letters
 * for RSA keys.
 */
data class IniHiaLetters(
    val ini: IniLetter,
    val hia: HiaLetter
)

/**
 * Request for INI letter.
 */
data class IniLetter(

    val userId: String,
    val customerId: String,
    val name: String,
    val date: String,
    val time: String,
    val recipient: String,
    val public_exponent_length: Int,
    val public_exponent: String,
    val public_modulus_length: Int,
    val public_modulus: String,
    val hash: String
)

/**
 * Request for HIA letter.
 */
data class HiaLetter(
    val userId: String,
    val customerId: String,
    val name: String,
    val date: String,
    val time: String,
    val recipient: String,
    val ia_exponent_length: Int,
    val ia_exponent: String,
    val ia_modulus_length: Int,
    val ia_modulus: String,
    val ia_hash: String,
    val enc_exponent_length: Int,
    val enc_exponent: String,
    val enc_modulus_length: Int,
    val enc_modulus: String,
    val enc_hash: String
)
