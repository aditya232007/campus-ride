package com.example

import com.example.location.CampusLandmarkZone
import com.example.location.CampusLandmarkZone.Companion.CartDirection
import com.example.location.CampusLandmarkZone.Companion.StopVisualState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ExampleUnitTest {

    @Before
    fun setup() {
        CampusLandmarkZone.clearRollingHistory()
    }

    @Test
    fun testExactCoordinates_MatchAuthoritativeGoogleMaps() {
        // Stop 1: MAIN GATE
        assertEquals(25.2531616, CampusLandmarkZone.GATE.latitude, 0.0000001)
        assertEquals(87.0370730, CampusLandmarkZone.GATE.longitude, 0.0000001)
        assertEquals("Main Gate", CampusLandmarkZone.GATE.displayName)
        assertTrue(CampusLandmarkZone.GATE.fullAddress.contains("IIIT BHAGALPUR Main Gate"))

        // Stop 2: TRUNKUT
        assertEquals(25.2577186, CampusLandmarkZone.TRUNKUT.latitude, 0.0000001)
        assertEquals(87.0381730, CampusLandmarkZone.TRUNKUT.longitude, 0.0000001)
        assertEquals("Trunkut", CampusLandmarkZone.TRUNKUT.displayName)
        assertTrue(CampusLandmarkZone.TRUNKUT.fullAddress.contains("Trunkut"))

        // Stop 3: COMPUTER CENTRE
        assertEquals(25.2590500, CampusLandmarkZone.COMPUTER_CENTRE.latitude, 0.0000001)
        assertEquals(87.0394730, CampusLandmarkZone.COMPUTER_CENTRE.longitude, 0.0000001)
        assertEquals("Computer Centre", CampusLandmarkZone.COMPUTER_CENTRE.displayName)
        assertTrue(CampusLandmarkZone.COMPUTER_CENTRE.fullAddress.contains("Computer Centre"))

        // Stop 4: BOYS HOSTEL
        assertEquals(25.2577810, CampusLandmarkZone.HOSTEL.latitude, 0.0000001)
        assertEquals(87.0418910, CampusLandmarkZone.HOSTEL.longitude, 0.0000001)
        assertEquals("Hostel", CampusLandmarkZone.HOSTEL.displayName)
        assertTrue(CampusLandmarkZone.HOSTEL.fullAddress.contains("Boys Hostel"))
    }

    @Test
    fun testDriverAtBoysHostel_IdentifiedAsAtHostel() {
        // Exact Boys Hostel Google Maps Coordinates
        val result = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577810,
            longitude = 87.0418910,
            bearing = 0f,
            speedKmH = 0,
            accuracy = 8.0f
        )
        assertNotNull(result)
        assertEquals(CampusLandmarkZone.HOSTEL, result?.primaryLandmark)
        assertEquals(true, result?.isAtLandmark)
        assertEquals("At Hostel", result?.driverDetailedLocation)
        assertEquals("At Hostel", result?.studentPrimaryText)
        assertEquals(3.0f, result?.routeProgressFloat ?: 0f, 0.01f)

        val hostelStop = result?.timelineStops?.find { it.landmark == CampusLandmarkZone.HOSTEL }
        assertNotNull(hostelStop)
        assertEquals(StopVisualState.AT_STOP, hostelStop?.visualState)
        assertEquals(true, hostelStop?.isAtThisStop)

        val gateStop = result?.timelineStops?.find { it.landmark == CampusLandmarkZone.GATE }
        assertEquals(StopVisualState.COMPLETED, gateStop?.visualState)
    }

    @Test
    fun testStationaryAtHostel_NoFluctuationUnderGpsJitter() {
        // Step 1: Initial location at Hostel
        val r1 = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577810,
            longitude = 87.0418910,
            speedKmH = 0,
            accuracy = 10f,
            timestamp = 1000L
        )
        assertEquals("At Hostel", r1?.driverDetailedLocation)

        // Jitter 1: +4 meters noise
        val r2 = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2578170, // ~4m north
            longitude = 87.0418910,
            speedKmH = 0,
            accuracy = 12f,
            timestamp = 3000L
        )
        assertEquals("At Hostel", r2?.driverDetailedLocation)
        assertEquals(3.0f, r2?.routeProgressFloat ?: 0f, 0.01f)

        // Jitter 2: -6 meters noise
        val r3 = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577270, // ~6m south
            longitude = 87.0418910,
            speedKmH = 0,
            accuracy = 15f,
            timestamp = 5000L
        )
        assertEquals("At Hostel", r3?.driverDetailedLocation)

        // Jitter 3: +3 meters noise
        val r4 = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2578080,
            longitude = 87.0419200,
            speedKmH = 0,
            accuracy = 14f,
            timestamp = 7000L
        )
        assertEquals("At Hostel", r4?.driverDetailedLocation)

        // Jitter 4: -5 meters noise
        val r5 = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577360,
            longitude = 87.0418600,
            speedKmH = 0,
            accuracy = 18f,
            timestamp = 9000L
        )
        assertEquals("At Hostel", r5?.driverDetailedLocation)
    }

    @Test
    fun testGpsAccuracyFilter_SuppressesLowQualityGpsReading() {
        // Established valid location
        val valid = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577810,
            longitude = 87.0418910,
            accuracy = 8.0f,
            timestamp = 1000L
        )
        assertEquals("At Hostel", valid?.driverDetailedLocation)

        // Low accuracy reading (e.g. 45m accuracy)
        val poorAccuracy = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2590500, // Jumped to Computer Centre falsely due to bad GPS fix
            longitude = 87.0394730,
            accuracy = 45.0f, // Poor accuracy > 30m
            timestamp = 3000L
        )
        // Should ignore the jump and retain the confirmed stable status
        assertEquals("At Hostel", poorAccuracy?.driverDetailedLocation)
    }

    @Test
    fun testHysteresisLocksAtHostelUntilMeaningfulExitDistance() {
        // At Hostel
        CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577810,
            longitude = 87.0418910,
            speedKmH = 0,
            timestamp = 1000L
        )

        // Move 70m away from Hostel center (inside exit radius 90m)
        // 25.2577810, 87.0411960 (~70m away)
        val insideExitRadius = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577810,
            longitude = 87.0411960,
            speedKmH = 0,
            timestamp = 3000L
        )
        assertEquals("At Hostel", insideExitRadius?.driverDetailedLocation)

        // Now move > 100m away with confirmed speed
        CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.258300,
            longitude = 87.040800,
            speedKmH = 12,
            timestamp = 5000L
        )
        CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.258500,
            longitude = 87.040400,
            speedKmH = 12,
            timestamp = 7000L
        )
        val movingAway = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.258800,
            longitude = 87.040000,
            speedKmH = 12,
            timestamp = 9000L
        )
        assertEquals("Between Hostel & Computer Centre", movingAway?.driverDetailedLocation)
    }

    @Test
    fun testDriverAtComputerCentre_IdentifiedAsAtComputerCentre() {
        // Exact Computer Centre Google Maps Coordinates
        val result = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2590500,
            longitude = 87.0394730,
            bearing = 180f,
            speedKmH = 0,
            accuracy = 8.0f
        )
        assertNotNull(result)
        assertEquals(CampusLandmarkZone.COMPUTER_CENTRE, result?.primaryLandmark)
        assertEquals(true, result?.isAtLandmark)
        assertEquals("At Computer Centre", result?.driverDetailedLocation)
        assertEquals(2.0f, result?.routeProgressFloat ?: 0f, 0.01f)
    }

    @Test
    fun testDriverAtTrunkut_IdentifiedAsAtTrunkut() {
        // Exact Trunkut Google Maps Coordinates
        val result = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577186,
            longitude = 87.0381730,
            bearing = 180f,
            speedKmH = 0,
            accuracy = 8.0f
        )
        assertNotNull(result)
        assertEquals(CampusLandmarkZone.TRUNKUT, result?.primaryLandmark)
        assertEquals(true, result?.isAtLandmark)
        assertEquals("At Trunkut", result?.driverDetailedLocation)
        assertEquals(1.0f, result?.routeProgressFloat ?: 0f, 0.01f)
    }

    @Test
    fun testDriverAtMainGate_IdentifiedAsAtMainGate() {
        // Exact Main Gate Google Maps Coordinates
        val result = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2531616,
            longitude = 87.0370730,
            bearing = 180f,
            speedKmH = 0,
            accuracy = 8.0f
        )
        assertNotNull(result)
        assertEquals(CampusLandmarkZone.GATE, result?.primaryLandmark)
        assertEquals(true, result?.isAtLandmark)
        assertEquals(true, result?.isAtGate)
        assertEquals("At Main Gate", result?.driverDetailedLocation)
        assertEquals(0.0f, result?.routeProgressFloat ?: 0f, 0.01f)
    }
}
