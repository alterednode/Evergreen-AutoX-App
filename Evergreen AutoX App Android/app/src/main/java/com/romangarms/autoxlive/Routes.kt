package com.romangarms.autoxlive

import kotlinx.serialization.Serializable

@Serializable data object LiveRoute
@Serializable data object FriendsRoute
@Serializable data object EventsRoute
@Serializable data object BoardsRoute
@Serializable data object SetupRoute

// Driver and compare pages name drivers by car number rather than finish
// position, so a refresh that reorders the results leaves them on the same
// people without a navigation (iOS follows positions by hand in AppModel.show).
@Serializable data class DriverRoute(val number: String)
@Serializable data class CompareRoute(val a: String, val b: String)
@Serializable data class LeaderboardRoute(val courseId: Int)
@Serializable data class LeaderboardDriverRoute(val courseId: Int, val position: Int)
@Serializable data object AccelerationRoute
@Serializable data class AccelerationEntryRoute(val id: Int)
