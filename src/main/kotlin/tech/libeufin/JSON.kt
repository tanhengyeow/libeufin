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
    val ebicsInfo: CustomerEbicsInfo
)

data class CustomerEbicsInfo(
    val userId: Int
)

/**
 * Wrapper type around initialization letters
 * for RSA keys.
 */
data class IniHiaLetters(
    val INI: IniLetter,
    val HIA: HiaLetter
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
    val exp_length: Int,
    val exponent: String,
    val mod_length: Int,
    val modulus: String,
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
    val ia_exp_length: Int,
    val ia_exponent: String,
    val ia_mod_length: Int,
    val ia_modulus: String,
    val ia_hash: String,
    val enc_exp_length: Int,
    val enc_exponent: String,
    val enc_mod_length: Int,
    val enc_modulus: String,
    val enc_hash: String
)
