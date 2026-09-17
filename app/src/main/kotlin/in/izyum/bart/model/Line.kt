package `in`.izyum.bart.model

/** Stable app identities for the BART lines described by static GTFS. */
enum class Line(
    val transferLine1: Line? = null,
    val transferLine2: Line? = null,
    private val label: String? = null
) {
    RED,
    ORANGE,
    YELLOW,
    BLUE,
    GREEN,
    YELLOW_ORANGE_SCHEDULED_TRANSFER(YELLOW, ORANGE),
    PURPLE;

    fun getDisplayName(): String = label ?: name

    fun requiresTransfer(): Boolean = transferLine1 != null
}
