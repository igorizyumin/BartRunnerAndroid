package `in`.izyum.bart.networktasks

import org.junit.Assert.assertEquals
import org.junit.Test

class ElevatorStatusClientTest {
    @Test
    fun parsesElevatorDescriptionFromBsaCdata() {
        val json = """
            {
              "root": {
                "bsa": [
                  {
                    "description": {
                      "#cdata-section": "There is 1 elevator out of service at this time."
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        assertEquals(
            "There is 1 elevator out of service at this time.",
            ElevatorStatusClient.parseDescription(json),
        )
    }
}
