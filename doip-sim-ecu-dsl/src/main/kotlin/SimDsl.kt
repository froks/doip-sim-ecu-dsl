import doip.library.message.UdsMessage
import helper.decodeHex
import helper.toHexString
import java.util.*
import kotlin.IllegalArgumentException
import kotlin.time.Duration

typealias RequestResponseData = ResponseData<RequestMatcher>
typealias RequestResponseHandler = RequestResponseData.() -> Unit
typealias InterceptorResponseData = ResponseData<InterceptorData>
typealias InterceptorResponseHandler = InterceptorResponseData.(request: RequestMessage) -> Boolean
typealias EcuDataHandler = EcuData.() -> Unit
typealias GatewayDataHandler = GatewayData.() -> Unit
typealias CreateEcuFunc = (name: String, receiver: EcuDataHandler) -> Unit
typealias CreateGatewayFunc = (name: String, receiver: GatewayDataHandler) -> Unit

@Suppress("unused")
object NrcError {
    // Common Response Codes
    const val GeneralReject: Byte = 0x10
    const val ServiceNotSupported: Byte = 0x11
    const val SubFunctionNotSupported: Byte = 0x12
    const val IncorrectMessageLengthOrInvalidFormat: Byte = 0x13
    const val ResponseTooLong: Byte = 0x04

    const val BusyRepeatRequest: Byte = 0x21
    const val ConditionsNotCorrect: Byte = 0x22

    const val RequestSequenceError: Byte = 0x24
    const val NoResponseFromSubNetComponent: Byte = 0x25
    const val FailurePreventsExecutionOfRequestedAction: Byte = 0x26

    const val RequestOutOfRange: Byte = 0x31

    const val SecurityAccessDenied: Byte = 0x33

    const val InvalidKey: Byte = 0x35
    const val ExceededNumberOfAttempts: Byte = 0x36
    const val RequiredTimeDelayNotExpired: Byte = 0x37

    const val UploadDownloadNotAccepted: Byte = 0x70
    const val TransferDataSuspended: Byte = 0x71
    const val GeneralProgrammingFailure: Byte = 0x72
    const val WrongBlockSequenceCounter: Byte = 0x73

    const val RequestCorrectlyReceivedButResponseIsPending: Byte = 0x78

    const val SubFunctionNotSupportedInActiveSession: Byte = 0x7E
    const val ServiceNotSupportedInActiveSession: Byte = 0x7F

    // Condition driven
    const val VoltageTooHigh: Byte = 0x92.toByte()
    const val VoltageTooLow: Byte = 0x93.toByte()
}

open class RequestMessage(udsMessage: UdsMessage, val isBusy: Boolean) :
    UdsMessage(
        udsMessage.sourceAdrress,
        udsMessage.targetAddress,
        udsMessage.targetAddressType,
        udsMessage.message)

/**
 * Define the response to be sent after the function returns
 */
open class ResponseData<out T : DataStorage>(
    /**
     * The object that called this response handler (e.g. [RequestMatcher] or [InterceptorData])
     */
    val caller: T,
    /**
     * The request as received for the ecu
     */
    val request: UdsMessage,
    /**
     * Represents the simulated ecu, allows you to modify data on it
     */
    val ecu: SimEcu
) {
    /**
     * The request as a byte-array for easier access
     */
    val message: ByteArray
        get() = request.message

    /**
     * The response that's scheduled to be sent after the response handler
     * finishes
     */
    val response
        get() = _response

    /**
     * Continue matching with other request handlers
     */
    val continueMatching
        get() = _continueMatching

    private var _response: ByteArray = ByteArray(0)
    private var _continueMatching: Boolean = false

    /**
     * See [SimEcu.addOrReplaceTimer]
     */
    fun addOrReplaceEcuTimer(name: String, delay: Duration, handler: TimerTask.() -> Unit) {
        ecu.addOrReplaceTimer(name, delay, handler)
    }

    /**
     * See [SimEcu.cancelTimer]
     */
    fun cancelEcuTimer(name: String) {
        ecu.cancelTimer(name)
    }

    /**
     * See [SimEcu.addInterceptor]
     */
    fun addEcuInterceptor(
        name: String = UUID.randomUUID().toString(),
        duration: Duration = Duration.INFINITE,
        alsoCallWhenEcuIsBusy: Boolean = false,
        interceptor: InterceptorResponseHandler
    ) =
        ecu.addInterceptor(name, duration, alsoCallWhenEcuIsBusy, interceptor)

    /**
     * See [SimEcu.removeInterceptor]
     */
    fun removeEcuInterceptor(name: String) =
        ecu.removeInterceptor(name)

    fun respond(responseHex: ByteArray) {
        _response = responseHex
    }

    fun respond(responseHex: String) =
        respond(responseHex.decodeHex())

    /**
     * Acknowledge a request with the given payload. The first nrOfRequestBytes
     * bytes (SID + 0x40, ...) are automatically prefixed.
     *
     * nrOfRequestBytes is the total number of bytes (including SID + 0x40)
     */
    fun ack(payload: ByteArray = ByteArray(0), nrOfRequestBytes: Int) =
        respond(byteArrayOf((message[0] + 0x40.toByte()).toByte(), *message.copyOfRange(1, nrOfRequestBytes)) + payload)

    /**
     * Acknowledge a request with the given payload. The first nrOfRequestBytes
     * bytes (SID + 0x40, ...) are automatically prefixed
     *
     * payload must be a hex-string.
     * nrOfRequestBytes is the total number of bytes (including SID + 0x40)
     */
    fun ack(payload: String, nrOfRequestBytes: Int) =
        ack(payload.decodeHex(), nrOfRequestBytes)

    /**
     * Acknowledge a request with the given payload.
     *
     * The first n bytes are automatically prefixed, depending on which service
     * is responded to (see [RequestsData.ackBytesLengthMap])
     */
    fun ack(payload: String) =
        ack(payload, ecu.ackBytesMap[message[0]] ?: 2)

    /**
     * Acknowledge a request with the given payload.
     *
     * The first n bytes are automatically prefixed, depending on which service
     * is responded to (see [RequestsData.ackBytesLengthMap])
     */
    fun ack(payload: ByteArray = ByteArray(0)) =
        ack(payload, ecu.ackBytesMap[message[0]] ?: 2)

    /**
     * Send a negative response code (NRC) in response to the request
     */
    fun nrc(code: Byte = NrcError.GeneralReject) =
        respond(byteArrayOf(0x7F, message[0], code))

    /**
     * Don't send any responses/acknowledgement, and continue matching
     */
    fun continueMatching(continueMatching: Boolean = true) {
        _continueMatching = continueMatching
    }
}

/**
 * Defines a matcher for an incoming request and the lambdas to be executed when it matches
 */
class RequestMatcher(
    val name: String?,
    val requestBytes: ByteArray?,
    val requestRegex: Regex?,
    val responseHandler: RequestResponseHandler
) : DataStorage() {
    init {
        if (requestBytes == null && requestRegex == null) {
            throw IllegalArgumentException("requestBytes or requestRegex must be not null")
        } else if (requestBytes != null && requestRegex != null) {
            throw IllegalArgumentException("Only requestBytes or requestRegex must be set")
        }
    }

    /**
     * Reset persistent storage for this request
     */
    fun reset() {
        clearStoredProperties()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("{ ")
        if (!name.isNullOrEmpty()) {
            sb.append("$name; ")
        }
        if (requestBytes != null) {
            sb.append("Bytes: ${requestBytes.toHexString(limit = 10)}")
        } else if (requestRegex != null) {
            sb.append("Pattern: ${requestRegex.pattern}")
        }
        sb.append(" }")
        return sb.toString()
    }
}

fun List<RequestMatcher>.findByName(name: String) =
    this.firstOrNull { it.name == name }

fun MutableList<RequestMatcher>.removeByName(name: String): RequestMatcher? {
    val index = this.indexOfFirst { it.name == name }
    if (index >= 0) {
        return this.removeAt(index)
    }
    return null
}

data class ResetHandler(val name: String?, val handler: (SimEcu) -> Unit)

open class RequestsData(
    val name: String,
    /**
     * Return a nrc when no request could be matched
     */
    var nrcOnNoMatch: Boolean = true,
    requests: List<RequestMatcher> = emptyList(),
    resetHandler: List<ResetHandler> = emptyList(),
    /**
     * Maximum length of data converted into a hex-string for incoming requests
     */
    var requestRegexMatchBytes: Int = 10,

    /**
     * Map of Request SID to number of ack response byte count
     */
    var ackBytesLengthMap: Map<Byte, Int> = mapOf(),
) {
    /**
     * List of all defined requests in the order they were defined
     */
    val requests: MutableList<RequestMatcher> = mutableListOf(*requests.toTypedArray())

    /**
     * List of defined reset handlers
     */
    val resetHandler: MutableList<ResetHandler> = mutableListOf(*resetHandler.toTypedArray())


    private fun regexifyRequestHex(requestHex: String) =
        Regex(
            pattern = requestHex
                .replace(" ", "")
                .uppercase()
                .replace("[]", ".*"),
//            option = RegexOption.IGNORE_CASE
        )

    /**
     * Define request-matcher & response-handler for a gateway or ecu by using an
     * exact matching byte-array
     */
    fun request(request: ByteArray, name: String? = null, response: RequestResponseHandler = {}): RequestMatcher {
        val req = RequestMatcher(name = name, requestBytes = request, requestRegex = null, responseHandler = response)
        requests.add(req)
        return req
    }

    /**
     * Define request-matcher & response-handler for a gateway or ecu by using a
     * regular expression. Incoming request are normalized into a string
     * by converting the incoming data into uppercase hexadecimal
     * without any spaces. The regular expression defined here is then
     * used to match against this string
     *
     * Note: Take the maximal string length [requestRegexMatchBytes] into
     * account
     */
    fun request(reqRegex: Regex, name: String? = null, response: RequestResponseHandler = {}): RequestMatcher {
        val req = RequestMatcher(name = name, requestBytes = null, requestRegex = reqRegex, responseHandler = response)
        requests.add(req)
        return req
    }

    /**
     * Define request/response pair for a gateway or ecu by using a best guess
     * for the type of string that's used.
     *
     * A static request is only hexadecimal digits and whitespaces, and will be converted
     * into a call to the [request]-Method that takes an ByteArray
     *
     * A dynamic match is detected when the string includes "[", "." or "|". The string
     * is automatically converted into a regular expression by replacing all "[]" with ".*",
     * turning it into uppercase, and removing all spaces.
     */
    fun request(reqHex: String, name: String? = null, response: RequestResponseHandler = {}) {
        if (isRegex(reqHex)) {
            request(regexifyRequestHex(reqHex), name, response)
        } else {
            request(reqHex.decodeHex(), name, response)
        }
    }

    fun onReset(name: String? = null, handler: (SimEcu) -> Unit) {
        resetHandler.add(ResetHandler(name, handler))
    }

    private fun isRegex(value: String) =
        value.contains("[") || value.contains(".") || value.contains("|")
}

/**
 * Define the data associated with the ecu
 */
open class EcuData(
    name: String,
    var physicalAddress: Int = 0,
    var functionalAddress: Int = 0,
    nrcOnNoMatch: Boolean = true,
    requests: List<RequestMatcher> = emptyList(),
    resetHandler: List<ResetHandler> = emptyList(),
    requestRegexMatchBytes: Int = 10,
    ackBytesLengthMap: Map<Byte, Int> = mapOf(),
) : RequestsData(
    name = name,
    nrcOnNoMatch = nrcOnNoMatch,
    requests = requests,
    resetHandler = resetHandler,
    requestRegexMatchBytes = requestRegexMatchBytes,
    ackBytesLengthMap = ackBytesLengthMap,
)

val gateways: MutableList<GatewayData> = mutableListOf()
val gatewayInstances: MutableList<SimGateway> = mutableListOf()

/**
 * Defines a DoIP-Gateway and the ECUs behind it
 */
fun gateway(name: String, receiver: GatewayDataHandler) {
    val gatewayData = GatewayData(name)
    receiver.invoke(gatewayData)
    gateways.add(gatewayData)
}

fun reset() {
    gatewayInstances.forEach { it.reset() }
}

fun stop() {
    gatewayInstances.forEach { it.stop() }
    gatewayInstances.clear()
}

fun start() {
    gatewayInstances.addAll(gateways.map { SimGateway(it) })

    gatewayInstances.forEach { it.start() }
}
