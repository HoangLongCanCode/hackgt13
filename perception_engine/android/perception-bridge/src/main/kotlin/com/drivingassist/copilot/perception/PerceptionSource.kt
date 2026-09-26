package com.drivingassist.copilot.perception

import kotlinx.coroutines.flow.Flow

/**
 * Plan §34 hardware abstraction for perception: where [PerceptionMessage]s come from.
 *
 * - `com.drivingassist.copilot.bridge.PerceptionBridge`: the laptop GPU server (realtime; the only source the app uses).
 * - [ReplayPerceptionSource]: recorded / synthetic messages (tests, offline demos) into `WorldModel.run`.
 * - Later: an on-device backend (models on the tablet / glasses) implements the same interface.
 *
 * [messages] is meant for ONE collector (normally `WorldModel.run`).
 */
interface PerceptionSource : AutoCloseable {
    val messages: Flow<PerceptionMessage>

    override fun close()
}
