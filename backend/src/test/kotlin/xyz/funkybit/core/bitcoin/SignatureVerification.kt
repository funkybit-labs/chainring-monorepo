package xyz.funkybit.core.bitcoin

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.utils.bitcoin.BitcoinSignatureVerification

class SignatureVerification {

    @Test
    fun `test P2SH signature verification`() {
        val address = "2MuBP8G3ZaKYCvrhicoM7v5hmhLcebPFsdi"
        val messageToSign = "[funkybit] Please sign this message to verify your ownership of this wallet address. This action will not cost any gas fees.\n" +
            "Address: 2MuBP8G3ZaKYCvrhicoM7v5hmhLcebPFsdi, Timestamp: 2024-08-21T14:14:13.095Z"
        val signature = "JDQ7fNjw2JOQUeTzlMCLhesFfS+AHMkbwQAX7cbUNjQLUQSO2YuX62KwLHrhjfQMO0EjBJ5BAqCb/OfW9CBCTsg="
        assertTrue(
            BitcoinSignatureVerification.verifyMessage(
                BitcoinAddress.canonicalize(address),
                signature,
                messageToSign,
            ),
        )
    }

    @Test
    fun `test P2PKH signature verification`() {
        // address representation of public key hash
        // mainnet: "1EgGNqPsJLXHgnHHgUQEP63vU4DJKEkpiq"
        // testnet: "muCDftUr7MxYTtkuQ3NcD1GFL3p18jEQU8"

        // supply testnet address so that verification succeeds, later signature should be updated due to message change
        val address = "muCDftUr7MxYTtkuQ3NcD1GFL3p18jEQU8"
        val messageToSign = "[funkybit] Please sign this message to verify your ownership of this wallet address. This action will not cost any gas fees.\n" +
            "Address: 1EgGNqPsJLXHgnHHgUQEP63vU4DJKEkpiq, Timestamp: 2024-08-21T14:20:17.358Z"
        val signature = "IH8bsp/iwsft8DvqD6I6aq0rt2dr2spQAl4Y7udt19N1Wl+v5djary8UxXs+rAErvvY/niYxURJJAd2rukydNCg="
        assertTrue(
            BitcoinSignatureVerification.verifyMessage(
                BitcoinAddress.canonicalize(address),
                signature,
                messageToSign,
            ),
        )
    }

    @Test
    fun `test segwit 65-bytes signature verification`() {
        val address = BitcoinAddress.canonicalize("bcrt1qmasw66mddrrkwdumd24lece7hslyy303xwk8nv")
        val messageToSign = "[funkybit] Please sign this message to verify your ownership of this wallet address. This action will not cost any gas fees.\nAddress: bcrt1qmasw66mddrrkwdumd24lece7hslyy303xwk8nv, Timestamp: 2024-08-19T20:22:52.523Z"
        val signature = "IBfCHqORRWn4cMSzC4+Vt8DDmFLf64hkW0DDfZxDa0hOX9esApXWZvOECDVmG2adny8Z3NebIhmC6zhN3HTTTdc="

        assertTrue(
            BitcoinSignatureVerification.verifyMessage(
                address,
                signature,
                messageToSign,
            ),
        )
    }

    @Test
    fun `test segwit signature verification`() {
        val address = "bc1qw5p0htg7n4cfezyck7pkygk89nrx5yhttwcgg0"
        val messageToSign = "[funkybit] Please sign this message to verify your ownership of this wallet address. This action will not cost any gas fees.\nAddress: bc1qw5p0htg7n4cfezyck7pkygk89nrx5yhttwcgg0, Timestamp: 2024-08-19T20:22:52.523Z"
        val signature = "AkcwRAIgIcIApbLuQeAikBSVgnSAArxQiCmntysG0jd6f9aAO3gCIEohertG+SIpwJS4EGDkBaSyUktN2E958II7JMFUmNf0ASEDwd8wX2HIQflk3mxf3fQpTMeiqoyMcJ6EEcmFYzOvvZ0="
        assertTrue(
            BitcoinSignatureVerification.verifyMessage(
                BitcoinAddress.canonicalize(address),
                signature,
                messageToSign,
            ),
        )
    }

    @Test
    fun `test taproot signature verification`() {
        val address = "bc1pyu06hqa927kff4v2kgdct7vew69w6wn7yphtz4emqj0qyrjgfrusvy6fz9"
        val messageToSign = "[funkybit] Please sign this message to verify your ownership of this wallet address. This action will not cost any gas fees.\nAddress: bc1pyu06hqa927kff4v2kgdct7vew69w6wn7yphtz4emqj0qyrjgfrusvy6fz9, Timestamp: 2024-08-19T20:22:52.523Z"
        val signature = "AUDbsQk0Q1p2T0sgxzOE9wnrZOJw08q14/9tLixaxjjKPSlX1jAs7vJ625hSdqEfkJMo1K8OLHG2TsdgTSEOaipM"
        assertTrue(
            BitcoinSignatureVerification.verifyMessage(
                BitcoinAddress.canonicalize(address),
                signature,
                messageToSign,
            ),
        )
    }
}
