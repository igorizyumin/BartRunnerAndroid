package com.dougkeen.bart.backend

import com.dougkeen.bart.model.ScheduleInformation
import com.dougkeen.bart.model.Station

/** Supplies cached static GTFS schedule data to schedule projections. */
interface StaticScheduleSource {
    fun getSchedule(origin: Station, destination: Station): ScheduleInformation
}
