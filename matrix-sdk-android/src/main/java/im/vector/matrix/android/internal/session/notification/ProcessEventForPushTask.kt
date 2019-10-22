/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.session.notification

import im.vector.matrix.android.api.pushrules.rest.PushRule
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.room.RoomService
import im.vector.matrix.android.internal.di.UserId
import im.vector.matrix.android.internal.session.pushers.DefaultConditionResolver
import im.vector.matrix.android.internal.session.sync.model.RoomsSyncResponse
import im.vector.matrix.android.internal.task.Task
import timber.log.Timber
import javax.inject.Inject

internal interface ProcessEventForPushTask : Task<ProcessEventForPushTask.Params, Unit> {
    data class Params(
            val syncResponse: RoomsSyncResponse,
            val rules: List<PushRule>
    )
}

internal class DefaultProcessEventForPushTask @Inject constructor(
        private val defaultPushRuleService: DefaultPushRuleService,
        private val roomService: RoomService,
        @UserId private val userId: String
) : ProcessEventForPushTask {

    override suspend fun execute(params: ProcessEventForPushTask.Params) {
        // Handle left rooms
        params.syncResponse.leave.keys.forEach {
            defaultPushRuleService.dispatchRoomLeft(it)
        }
        // Handle joined rooms
        params.syncResponse.join.keys.forEach {
            defaultPushRuleService.dispatchRoomJoined(it)
        }
        val newJoinEvents = params.syncResponse.join
                .mapNotNull { (key, value) ->
                    value.timeline?.events?.map { it.copy(roomId = key) }
                }
                .flatten()
        val inviteEvents = params.syncResponse.invite
                .mapNotNull { (key, value) ->
                    value.inviteState?.events?.map { it.copy(roomId = key) }
                }
                .flatten()
        val allEvents = (newJoinEvents + inviteEvents).filter { event ->
            when (event.type) {
                EventType.MESSAGE,
                EventType.REDACTION,
                EventType.ENCRYPTED,
                EventType.STATE_ROOM_MEMBER -> true
                else                        -> false
            }
        }.filter {
            it.senderId != userId
        }
        Timber.v("[PushRules] Found ${allEvents.size} out of ${(newJoinEvents + inviteEvents).size}" +
                " to check for push rules with ${params.rules.size} rules")
        allEvents.forEach { event ->
            fulfilledBingRule(event, params.rules)?.let {
                Timber.v("[PushRules] Rule $it match for event ${event.eventId}")
                defaultPushRuleService.dispatchBing(event, it)
            }
        }

        val allRedactedEvents = params.syncResponse.join
                .asSequence()
                .mapNotNull { (_, value) -> value.timeline?.events }
                .flatten()
                .filter { it.type == EventType.REDACTION }
                .mapNotNull { it.redacts }
                .toList()

        Timber.v("[PushRules] Found ${allRedactedEvents.size} redacted events")

        allRedactedEvents.forEach { redactedEventId ->
            defaultPushRuleService.dispatchRedactedEventId(redactedEventId)
        }

        defaultPushRuleService.dispatchFinish()
    }

    private fun fulfilledBingRule(event: Event, rules: List<PushRule>): PushRule? {
        // TODO This should be injected
        val conditionResolver = DefaultConditionResolver(event, roomService, userId)
        return rules.firstOrNull { rule ->
            // All conditions must hold true for an event in order to apply the action for the event.
            rule.enabled && rule.conditions?.all {
                it.asExecutableCondition()?.isSatisfied(conditionResolver) ?: false
            } ?: false
        }
    }
}
